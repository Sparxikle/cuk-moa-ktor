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

/** School-wide boards, then every department board in the site registry (sites/department-sites.json). */
fun boards(registry: kr.ac.catholic.cukmoa.server.SiteRegistry): List<Board> = buildList {
    add(Board(AnnouncementSource.LMS, null, "notice", "notice", "https://e-cyber.catholic.ac.kr/ilos/community/notice_list.acl", "https://e-cyber.catholic.ac.kr", Kind.Lms, 500))
    add(Board(AnnouncementSource.MAIN, null, "notice", null, "$WWW/ko/campuslife/notice.do", WWW, Kind.MainNotice, 500))
    add(Board(AnnouncementSource.EVENT, null, "notice_event", "event", "$WWW/ko/campuslife/notice_event.do", WWW, Kind.Event, 500))
    add(Board(AnnouncementSource.OUTSIDE, null, "notice_outside", "external", "$WWW/ko/campuslife/notice_outside.do", WWW, Kind.Outside, 500))
    registry.boards.forEach { b ->
        add(Board(AnnouncementSource.DEPT, b.code, b.board, b.category, b.url, b.origin, Kind.Dept, 50))
    }
}

class Scraper(
    private val db: Database,
    private val registry: kr.ac.catholic.cukmoa.server.SiteRegistry,
    private val requestDelay: Duration = Duration.ofMillis(300),
) {
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
        for (b in boards(registry)) {
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
        if (mode == Mode.Full) for (r in registry.roadmaps) {
            val result = runCatching { scrapeRoadmap(r) }
            result.onSuccess { log.info("roadmap {} entries={}", r.key, it) }.onFailure { log.warn("roadmap {} failed: {}", r.key, it.message) }
            db.recordRun("roadmap/${r.key}", result)
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

    /**
     * Collects and merges professor profiles from every professor page in the registry. Pages in a layout the parser
     * doesn't read (labs, honorary lists …) are skipped and logged. Nothing read at all → the existing list is kept.
     */
    private suspend fun scrapeFaculty(): Int {
        val lists = registry.facultyPages.flatMap { (code, urls) ->
            urls.mapNotNull { url ->
                runCatching {
                    delay(requestDelay.toMillis())
                    val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", USER_AGENT).GET().build()
                    val res = withContext(Dispatchers.IO) { http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)) }
                    check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()}" }
                    // Regular CMS faculty pages first, otherwise card lists (융복합 주임·겸임·초빙교수)
                    val position = if ("adjunct" in url || "visiting" in url) "겸임·초빙교수" else "주임교수"
                    // Pages without a 소속학과 field: the majors on that site
                    parseFaculty(res.body(), code, url).ifEmpty { parseCardFaculty(res.body(), code, url, position) }.map { m ->
                        if (m.departments.isNotEmpty()) m else m.copy(departments = registry.facultyDepartments(code))
                    }
                }.onFailure { log.warn("faculty page failed {}: {}", url, it.message) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
                    .also { if (it == null) log.info("faculty page skipped (no profiles read) {}", url) }
            }
        }
        check(lists.isNotEmpty()) { "교수 목록을 하나도 읽지 못함" }
        val merged = mergeCardFaculty(mergeFaculty(lists))
        db.replaceFaculty(merged)
        return merged.size
    }

    private suspend fun scrapeRoadmap(r: kr.ac.catholic.cukmoa.server.SiteRoadmap): Int {
        delay(requestDelay.toMillis())
        val url = r.url
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", USER_AGENT).GET().build()
        val res = withContext(Dispatchers.IO) { http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)) }
        check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()}" }
        val roadmap = withManualRoadmap(
            // 융복합전공 pages: the "교과과정" tab's tables are in the page itself
            if ("/curriculum-info/" in url) parseConvergenceCurriculum(res.body(), r.key, r.department, url)
            else parseMajorRoadmap(res.body(), r.key, r.department, url),
        )
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

/**
 * Departments that publish their roadmap only as an image use hand-transcribed data (resources/roadmaps/{major}.json).
 * If the department switches to a table (entries appear), the department page takes over automatically.
 */
fun withManualRoadmap(parsed: kr.ac.catholic.cukmoa.model.MajorRoadmap): kr.ac.catholic.cukmoa.model.MajorRoadmap {
    if (parsed.entries.isNotEmpty()) return parsed
    val text = Scraper::class.java.classLoader.getResource("roadmaps/${parsed.major}.json")?.readText() ?: return parsed
    val manual = kr.ac.catholic.cukmoa.model.ApiJson.decodeFromString(kr.ac.catholic.cukmoa.model.MajorRoadmap.serializer(), text)
    return parsed.copy(legend = manual.legend, entries = manual.entries, note = manual.note)
}

/**
 * A card-list professor (no faculty number) who also has a regular profile in their home department — e.g. 김유중,
 * 주임교수 of the 융복합전공 — becomes one person: the regular profile, with the card page's board code added.
 */
fun mergeCardFaculty(members: List<kr.ac.catholic.cukmoa.model.FacultyMember>): List<kr.ac.catholic.cukmoa.model.FacultyMember> {
    val (cards, regular) = members.partition { it.id.startsWith("card:") }
    val extra = mutableMapOf<String, MutableSet<String>>()
    val left = cards.filter { card ->
        val home = regular.firstOrNull { r -> r.name == card.name && (card.departments.isEmpty() || card.departments.any { it in r.departments }) }
        if (home != null) extra.getOrPut(home.id) { mutableSetOf() }.addAll(card.majors)
        home == null
    }
    return regular.map { r -> extra[r.id]?.let { r.copy(majors = (r.majors + it).distinct()) } ?: r } + left
}
