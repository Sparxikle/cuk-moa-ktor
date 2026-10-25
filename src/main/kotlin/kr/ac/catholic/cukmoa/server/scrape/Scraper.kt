package kr.ac.catholic.cukmoa.server.scrape

import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.ac.catholic.cukmoa.model.AnnouncementSource
import kr.ac.catholic.cukmoa.server.db.Database
import kr.ac.catholic.cukmoa.server.db.ScrapedItem
import org.slf4j.LoggerFactory

// Pages through each board from the newest post, collecting only this year's posts. Hitting an unpinned post from last year stops that board.

enum class Mode {
    /** Re-reads this year's posts to the end (refreshes view counts and pinned status). At startup and once a day. */
    Full,

    /** Stops when a whole page is already known. Every 30 minutes. */
    Incremental,
}

internal enum class Kind { Lms, MainNotice, Event, Outside, Dept }

class Board internal constructor(
    val source: String,
    val major: String?,
    val board: String,
    /** Fixed category. null means it's read from the list (school notices). */
    val category: String?,
    val url: String,
    val origin: String,
    private val kind: Kind,
    val maxPages: Int,
) {
    val key: String get() = listOfNotNull(source, major, board).joinToString("/")

    internal fun parse(html: String, year: Int): List<RawRow> = when (kind) {
        Kind.Lms -> parseLms(html, year)
        Kind.MainNotice -> parseMainNotice(html)
        Kind.Event -> parseEvents(html)
        Kind.Outside -> parseOutside(html)
        Kind.Dept -> parseDept(html)
    }

    internal fun request(page: Int): HttpRequest {
        val b = HttpRequest.newBuilder().timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT)
        return if (kind == Kind.Lms) {
            val form = mapOf("SCH_KEY" to "T", "SCH_TARGET" to "", "SCH_VALUE" to "", "start" to "${1 + page * 20}", "encoding" to "utf-8")
            b.uri(URI(url))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }))
                .build()
        } else {
            b.uri(URI("$url?mode=list&articleLimit=10&article.offset=${page * 10}")).GET().build()
        }
    }
}

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
private const val WWW = "https://www.catholic.ac.kr"

/** AI, Electronics & Information Convergence department boards: department → (board URL name → category). Categories: notice, career, news, external, graduate, recruitment */
val DEPT_BOARDS: Map<String, Map<String, String>> = linkedMapOf(
    "csie" to linkedMapOf("notice" to "notice", "career" to "career", "news" to "news"),
    "mtc" to linkedMapOf("notice" to "notice", "news" to "news", "notice1" to "external"),
    "ice" to linkedMapOf("notice" to "notice", "notice01" to "career", "ice-news" to "news"), // ice-news is currently empty
    "ai" to linkedMapOf("notice" to "notice", "contest" to "external", "graduatenotice" to "graduate"),
    "datascience" to linkedMapOf("notice" to "notice", "recruitment" to "recruitment"), // Use career if these are job postings
)

/** Department board code → faculty page name (teacher.do only for 정보통신전자공학부) */
val FACULTY_PAGES: Map<String, String> = linkedMapOf(
    "csie" to "faculty", "mtc" to "faculty", "ice" to "teacher", "ai" to "faculty", "datascience" to "faculty",
)

fun boards(): List<Board> = buildList {
    add(Board(AnnouncementSource.LMS, null, "notice", "notice", "https://e-cyber.catholic.ac.kr/ilos/community/notice_list.acl", "https://e-cyber.catholic.ac.kr", Kind.Lms, 500))
    add(Board(AnnouncementSource.MAIN, null, "notice", null, "$WWW/ko/campuslife/notice.do", WWW, Kind.MainNotice, 500))
    add(Board(AnnouncementSource.EVENT, null, "notice_event", "event", "$WWW/ko/campuslife/notice_event.do", WWW, Kind.Event, 500))
    add(Board(AnnouncementSource.OUTSIDE, null, "notice_outside", "external", "$WWW/ko/campuslife/notice_outside.do", WWW, Kind.Outside, 500))
    DEPT_BOARDS.forEach { (major, boards) ->
        val origin = "https://$major.catholic.ac.kr"
        boards.forEach { (board, category) ->
            add(Board(AnnouncementSource.DEPT, major, board, category, "$origin/$major/community/$board.do", origin, Kind.Dept, 50))
        }
    }
}

class Scraper(private val db: Database, private val requestDelay: Duration = Duration.ofMillis(300)) {
    private val log = LoggerFactory.getLogger(Scraper::class.java)
    private val http = HttpClient.newBuilder()
        .cookieHandler(CookieManager())
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Year according to the school site (so the January 1 boundary matches Korea time). */
    private fun currentYear() = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).year

    suspend fun runAll(mode: Mode) {
        val year = currentYear()
        log.info("scrape run started mode={} year={}", mode, year)
        var total = 0
        for (b in boards()) {
            val result = runCatching {
                val known = if (mode == Mode.Incremental) db.knownArticles(b.source, b.major, b.board) else emptySet()
                val items = scrapeBoard(b, year, known)
                db.upsert(items)
                items.size
            }
            result.onSuccess { total += it; log.info("scraped {} items={}", b.key, it) }
                .onFailure { log.warn("scrape failed {}: {}", b.key, it.message) }
            db.recordRun(b.key, result)
        }
        // Professor profiles rarely change, so they're read only during full collection.
        if (mode == Mode.Full) {
            val result = runCatching { scrapeFaculty() }
            result.onSuccess { log.info("faculty members={}", it) }.onFailure { log.warn("faculty failed: {}", it.message) }
            db.recordRun("faculty", result)
        }
        // Official department roadmaps also only during full collection. Recorded per department, so one failure doesn't stop the rest from updating.
        if (mode == Mode.Full) for ((major, department) in kr.ac.catholic.cukmoa.model.Majors) {
            val result = runCatching { scrapeRoadmap(major, department) }
            result.onSuccess { log.info("roadmap {} entries={}", major, it) }.onFailure { log.warn("roadmap {} failed: {}", major, it.message) }
            db.recordRun("roadmap/$major", result)
        }
        // The academic calendar rarely changes, so it's read only during full collection. In January–February the previous academic year is needed too.
        if (mode == Mode.Full) for (academicYear in listOf(year - 1, year)) {
            val result = runCatching { scrapeCalendar(academicYear) }
            result.onSuccess { log.info("calendar {} events={}", academicYear, it) }
                .onFailure { log.warn("calendar {} failed: {}", academicYear, it.message) }
            db.recordRun("calendar/$academicYear", result)
        }
        log.info("scrape run finished mode={} total={}", mode, total)
    }

    /** Collects and merges professor profiles per department. If any department fails, the existing list is kept. */
    private suspend fun scrapeFaculty(): Int {
        val lists = FACULTY_PAGES.map { (major, path) ->
            delay(requestDelay.toMillis())
            val url = "https://$major.catholic.ac.kr/$major/faculty/$path.do"
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", USER_AGENT).GET().build()
            val res = withContext(Dispatchers.IO) { http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)) }
            check(res.statusCode() in 200..299) { "$major: HTTP ${res.statusCode()}" }
            parseFaculty(res.body(), major, url).also { check(it.isNotEmpty()) { "$major: 교수 목록이 비어 있음" } }
        }
        val merged = mergeFaculty(lists)
        db.replaceFaculty(merged)
        return merged.size
    }

    private suspend fun scrapeRoadmap(major: String, department: String): Int {
        delay(requestDelay.toMillis())
        val url = "https://$major.catholic.ac.kr/$major/course/roadmap.do"
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).GET().build()
        val res = withContext(Dispatchers.IO) { http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)) }
        check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()}" }
        val roadmap = parseMajorRoadmap(res.body(), major, department, url)
        // Neither a table nor an image means the page structure changed → don't delete the existing roadmap
        check(roadmap.entries.isNotEmpty() || roadmap.imageUrl != null) { "로드맵 표·그림을 찾지 못함" }
        db.replaceRoadmap(roadmap)
        return roadmap.entries.size
    }

    private suspend fun scrapeCalendar(academicYear: Int): Int {
        delay(requestDelay.toMillis())
        val req = HttpRequest.newBuilder(URI("$WWW/ko/support/calendar2024_list.do?mode=list&siteId=ko&selectedYear=$academicYear"))
            .timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).GET().build()
        val res = withContext(Dispatchers.IO) { http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)) }
        check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()}" }
        val rows = parseCalendar(res.body(), academicYear)
        // Don't wipe existing events with an empty result (not published yet, or the page structure changed)
        if (rows.isNotEmpty()) db.replaceCalendar(academicYear, rows)
        return rows.size
    }

    private suspend fun fetch(b: Board, page: Int): String {
        delay(requestDelay.toMillis())
        val res = withContext(Dispatchers.IO) { http.send(b.request(page), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)) }
        check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()}" }
        return res.body()
    }

    suspend fun scrapeBoard(b: Board, year: Int, known: Set<String>): List<ScrapedItem> {
        val results = mutableListOf<ScrapedItem>()
        val seen = mutableSetOf<String>()
        pages@ for (page in 0 until b.maxPages) {
            val rows = b.parse(fetch(b, page), year)
            if (rows.isEmpty()) break

            var newOnPage = 0
            var unpinned = 0
            var unpinnedKnown = 0
            for (row in rows) {
                val link = makeLink(b.url, b.origin, row.href)
                val no = articleNo(link)
                if (!seen.add(no)) continue // Pinned posts reappear on every page
                newOnPage++

                val rowYear = row.date.take(4).toIntOrNull() ?: 0
                if (rowYear < year && !row.pinned) break@pages
                if (!row.pinned) {
                    unpinned++
                    if (no in known) unpinnedKnown++
                }
                if (rowYear == year) results += ScrapedItem(
                    source = b.source,
                    board = b.board,
                    major = b.major,
                    category = b.category ?: row.category,
                    articleNo = no,
                    title = row.title,
                    date = row.date,
                    author = row.author,
                    views = row.views,
                    link = link,
                    image = row.image?.let { makeLink(b.url, b.origin, it) },
                    pinned = row.pinned,
                )
            }
            // If the board ignores offset, the same page keeps coming back
            if (newOnPage == 0) break
            // If this whole page is known, later pages are already stored too
            if (known.isNotEmpty() && unpinned > 0 && unpinnedKnown == unpinned) break
        }
        return results
    }
}
