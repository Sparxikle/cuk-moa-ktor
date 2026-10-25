package kr.ac.catholic.cukmoa.server.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit
import kr.ac.catholic.cukmoa.model.Announcement
import kr.ac.catholic.cukmoa.model.AnnouncementQuery
import kr.ac.catholic.cukmoa.model.BoardCount
import kr.ac.catholic.cukmoa.model.ScrapeRun

fun nowUtc(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString() // 2026-10-25T05:46:14Z

/** One announcement produced by the collector (before it goes into the DB). */
data class ScrapedItem(
    val source: String,
    val board: String,
    val major: String?,
    val category: String?,
    val articleNo: String,
    val title: String,
    val date: String,
    val author: String,
    val views: Long?,
    val link: String,
    val image: String?,
    val pinned: Boolean,
)

/**
 * Collects announcements in one SQLite file. WAL mode lets the collector write while the API reads.
 * SQLite connections are cheap, so a new one is opened per request.
 */
class Database(private val path: String) {
    private val url = "jdbc:sqlite:$path"

    init {
        File(path).absoluteFile.parentFile?.mkdirs()
        connect().use { c ->
            c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            migrate(c)
        }
    }

    private fun connect(): Connection = DriverManager.getConnection(url).apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    fun <T> use(block: (Connection) -> T): T = connect().use(block)

    /** Remembers how many migrations were applied via user_version. Added files are applied on the next start. */
    private fun migrate(c: Connection) {
        val applied = c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.getInt(1) } }
        val files = listOf("db/0001_init.sql")
        files.drop(applied).forEachIndexed { i, name ->
            val sql = javaClass.classLoader.getResource(name)!!.readText()
            c.autoCommit = false
            splitSql(sql).forEach { stmt -> c.createStatement().use { it.execute(stmt) } }
            c.createStatement().use { it.execute("PRAGMA user_version = ${applied + i + 1}") }
            c.commit()
            c.autoCommit = true
        }
    }

    // ---- Collector side ----

    fun knownArticles(source: String, major: String?, board: String): Set<String> = use { c ->
        c.prepareStatement("SELECT article_no FROM announcements WHERE source = ? AND major = ? AND board = ?").use { st ->
            st.setString(1, source); st.setString(2, major.orEmpty()); st.setString(3, board)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /** Inserts new posts and updates title, view count and pinned status of existing ones. first_seen is set only on first insert. */
    fun upsert(items: List<ScrapedItem>) = use { c ->
        val now = nowUtc()
        c.autoCommit = false
        c.prepareStatement(
            """
            INSERT INTO announcements
                (source, board, major, category, article_no, title, date, author, views, link, image, pinned, first_seen, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source, major, board, article_no) DO UPDATE SET
                category = excluded.category, title = excluded.title, date = excluded.date,
                author = excluded.author, views = excluded.views, link = excluded.link,
                image = excluded.image, pinned = excluded.pinned, updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { st ->
            for (it in items) {
                st.setString(1, it.source); st.setString(2, it.board); st.setString(3, it.major.orEmpty())
                st.setString(4, it.category); st.setString(5, it.articleNo); st.setString(6, it.title)
                st.setString(7, it.date); st.setString(8, it.author)
                if (it.views != null) st.setLong(9, it.views) else st.setNull(9, java.sql.Types.INTEGER)
                st.setString(10, it.link); st.setString(11, it.image); st.setInt(12, if (it.pinned) 1 else 0)
                st.setString(13, now); st.setString(14, now)
                st.addBatch()
            }
            st.executeBatch()
        }
        c.commit()
    }

    fun recordRun(boardKey: String, result: Result<Int>) = use { c ->
        val now = nowUtc()
        result.fold(
            onSuccess = { n ->
                c.prepareStatement(
                    """INSERT INTO scrape_runs (board_key, last_ok, last_error, item_count) VALUES (?, ?, NULL, ?)
                       ON CONFLICT (board_key) DO UPDATE SET last_ok = excluded.last_ok, last_error = NULL, item_count = excluded.item_count""",
                ).use { st -> st.setString(1, boardKey); st.setString(2, now); st.setInt(3, n); st.executeUpdate() }
            },
            onFailure = { e ->
                c.prepareStatement(
                    """INSERT INTO scrape_runs (board_key, last_error) VALUES (?, ?)
                       ON CONFLICT (board_key) DO UPDATE SET last_error = excluded.last_error""",
                ).use { st -> st.setString(1, boardKey); st.setString(2, "$now: ${e.message}"); st.executeUpdate() }
            },
        )
    }

    // ---- API side ----

    fun list(q: AnnouncementQuery): Pair<List<Announcement>, Long> = use { c ->
        val where = Where()
        where.inList("source", q.source)
        where.inList("major", q.major)
        where.inList("category", q.category)
        where.inList("board", q.board)
        q.q?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.forEach { term ->
            // Trigram FTS only matches terms of 3+ characters. Shorter words are found with LIKE.
            if (term.codePointCount(0, term.length) >= 3) {
                where.add("id IN (SELECT rowid FROM announcements_fts WHERE announcements_fts MATCH ?)", "\"${term.replace("\"", "\"\"")}\"")
            } else {
                val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                where.add("title LIKE ? ESCAPE '\\'", "%$escaped%")
            }
        }
        q.since?.let { where.add("first_seen > ?", it) }

        val total = c.prepareStatement("SELECT COUNT(*) FROM announcements${where.sql}").use { st ->
            where.bind(st); st.executeQuery().use { it.getLong(1) }
        }
        val order = if (q.pinnedFirst) "pinned DESC, date DESC, id DESC" else "date DESC, id DESC"
        val items = c.prepareStatement("SELECT $COLUMNS FROM announcements${where.sql} ORDER BY $order LIMIT ? OFFSET ?").use { st ->
            val n = where.bind(st)
            st.setInt(n + 1, q.limit); st.setInt(n + 2, q.offset)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toAnnouncement()) } }
        }
        items to total
    }

    fun get(id: Long): Announcement? = use { c ->
        c.prepareStatement("SELECT $COLUMNS FROM announcements WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { if (it.next()) it.toAnnouncement() else null }
        }
    }

    fun boardCounts(): List<BoardCount> = use { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                """SELECT source, NULLIF(major, '') AS major, board, category, COUNT(*) FROM announcements
                   GROUP BY source, major, board, category ORDER BY source, major, board, category""",
            ).use { rs ->
                buildList { while (rs.next()) add(BoardCount(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5))) }
            }
        }
    }

    fun scrapeRuns(): List<ScrapeRun> = use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT board_key, last_ok, last_error, item_count FROM scrape_runs ORDER BY board_key").use { rs ->
                buildList {
                    while (rs.next()) add(ScrapeRun(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4).takeUnless { rs.wasNull() }))
                }
            }
        }
    }

    fun lastScrape(): String? = scrapeRuns().mapNotNull { it.lastOk }.maxOrNull()

    private companion object {
        const val COLUMNS = "id, source, board, NULLIF(major, '') AS major, category, title, date, author, views, link, image, pinned, first_seen, updated_at"

        fun ResultSet.toAnnouncement() = Announcement(
            id = getLong("id"),
            source = getString("source"),
            board = getString("board"),
            major = getString("major"),
            category = getString("category"),
            title = getString("title"),
            date = getString("date"),
            author = getString("author"),
            views = getLong("views").takeUnless { wasNull() },
            link = getString("link"),
            image = getString("image"),
            pinned = getInt("pinned") != 0,
            firstSeen = getString("first_seen"),
            updatedAt = getString("updated_at"),
        )

        /** Don't split on ; inside a trigger body (BEGIN … END). */
        fun splitSql(sql: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var inTrigger = false
            sql.lineSequence().map { it.substringBefore("--").trimEnd() }.filter { it.isNotBlank() }.forEach { line ->
                cur.appendLine(line)
                val upper = line.trim().uppercase()
                if (upper.startsWith("CREATE TRIGGER")) inTrigger = true
                if ((inTrigger && upper == "END;") || (!inTrigger && line.trimEnd().endsWith(";"))) {
                    out += cur.toString().trim().removeSuffix(";")
                    cur.clear()
                    inTrigger = false
                }
            }
            return out
        }
    }
}

/** Builds a WHERE clause along with bind values. User input only ever goes in through ?. */
private class Where {
    private val clauses = mutableListOf<String>()
    private val values = mutableListOf<String>()

    fun add(clause: String, vararg v: String) { clauses += clause; values += v }

    fun inList(column: String, vs: List<String>) {
        if (vs.isEmpty()) return
        add("$column IN (${vs.joinToString { "?" }})", *vs.toTypedArray())
    }

    val sql: String get() = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")

    /** Returns the last bound position. */
    fun bind(st: java.sql.PreparedStatement): Int {
        values.forEachIndexed { i, v -> st.setString(i + 1, v) }
        return values.size
    }
}
