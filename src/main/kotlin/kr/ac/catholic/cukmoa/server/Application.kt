package kr.ac.catholic.cukmoa.server

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kr.ac.catholic.cukmoa.logic.parseCatalog
import kr.ac.catholic.cukmoa.model.AnnouncementMeta
import kr.ac.catholic.cukmoa.model.AnnouncementPage
import kr.ac.catholic.cukmoa.model.AnnouncementQuery
import kr.ac.catholic.cukmoa.model.AnnouncementSource
import kr.ac.catholic.cukmoa.model.AnnouncementsHealth
import kr.ac.catholic.cukmoa.model.ApiJson
import kr.ac.catholic.cukmoa.model.Catalog
import kr.ac.catholic.cukmoa.model.ErrorBody
import kr.ac.catholic.cukmoa.model.ErrorCodes
import kr.ac.catholic.cukmoa.model.ErrorDetail
import kr.ac.catholic.cukmoa.model.Health
import kr.ac.catholic.cukmoa.model.Majors
import kr.ac.catholic.cukmoa.server.db.Database
import kr.ac.catholic.cukmoa.server.scrape.Mode
import kr.ac.catholic.cukmoa.server.scrape.Scraper

/** Thrown when a request is malformed → 400 INVALID_REQUEST */
class BadRequest(message: String) : Exception(message)

private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String, message: String) =
    respond(status, ErrorBody(ErrorDetail(code, message)))

private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 100

/** HTTP config and routes. Tests call the same function inside testApplication. */
fun Application.module(db: Database, catalog: Catalog) {
    install(ContentNegotiation) { json(ApiJson) }
    install(Compression)
    install(CallLogging)
    // The (native) app has no CORS restrictions. Only GET is opened, for viewing from the web.
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
    }
    install(StatusPages) {
        exception<BadRequest> { call, e -> call.error(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, e.message ?: "요청 형식을 확인해주세요.") }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("unhandled", e)
            call.error(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL_ERROR, "서버에서 오류가 발생했습니다.")
        }
    }

    val courseId = Regex("^[a-zA-Z0-9_-]{1,100}$")

    routing {
        get("/health") {
            call.respond(
                Health(
                    status = "ok",
                    service = "cuk-moa-api",
                    dataMode = if (catalog.source.isSample) "sample" else "live",
                    announcements = AnnouncementsHealth(db.lastScrape()),
                ),
            )
        }

        get("/api/v1/catalog") { call.respond(catalog) }

        get("/api/v1/courses/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (!courseId.matches(id)) throw BadRequest("과목 ID는 영문·숫자·하이픈·밑줄, 최대 100자입니다.")
            val course = catalog.courses.firstOrNull { it.id == id }
                ?: return@get call.error(HttpStatusCode.NotFound, ErrorCodes.COURSE_NOT_FOUND, "과목을 찾을 수 없습니다.")
            call.respond(course)
        }

        get("/api/v1/announcements") {
            val p = call.request.queryParameters
            fun values(name: String) = p[name]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            fun int(name: String, default: Int) = p[name]?.let { it.toIntOrNull() ?: throw BadRequest("$name 값이 숫자가 아닙니다.") } ?: default
            val query = AnnouncementQuery(
                source = values("source"),
                major = values("major"),
                category = values("category"),
                board = values("board"),
                q = p["q"]?.takeIf { it.isNotBlank() },
                since = p["since"]?.let { parseSince(it) ?: throw BadRequest("since는 YYYY-MM-DD 또는 ISO 8601 시각입니다.") },
                pinnedFirst = p["pinnedFirst"]?.toBooleanStrictOrNull() ?: true,
                limit = int("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
                offset = int("offset", 0).coerceAtLeast(0),
            )
            val (items, total) = db.list(query)
            call.respond(AnnouncementPage(items, total, query.limit, query.offset))
        }

        get("/api/v1/announcements/meta") {
            val runs = db.scrapeRuns()
            call.respond(
                AnnouncementMeta(
                    sources = listOf(AnnouncementSource.LMS, AnnouncementSource.MAIN, AnnouncementSource.EVENT, AnnouncementSource.OUTSIDE, AnnouncementSource.DEPT),
                    majors = Majors,
                    boards = db.boardCounts(),
                    lastScrape = runs.mapNotNull { it.lastOk }.maxOrNull(),
                    scrapeRuns = runs,
                ),
            )
        }

        get("/api/v1/announcements/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequest("공지 ID는 숫자입니다.")
            val item = db.get(id) ?: return@get call.error(HttpStatusCode.NotFound, ErrorCodes.ANNOUNCEMENT_NOT_FOUND, "공지를 찾을 수 없습니다.")
            call.respond(item)
        }

        // Unknown paths answer with the same error format too
        route("{...}") {
            handle { call.error(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "요청한 경로가 없습니다.") }
        }
    }
}

/** "2026-10-25" or an ISO time → storage format (UTC, seconds, Z). */
internal fun parseSince(s: String): String? = runCatching {
    java.time.OffsetDateTime.parse(s).toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
}.recoverCatching {
    java.time.LocalDate.parse(s).toString() + "T00:00:00Z"
}.getOrNull()

private fun env(name: String, default: String) = System.getenv(name) ?: default

fun loadCatalog(path: String?): Catalog {
    val text = path?.let { File(it).readText() }
        ?: Database::class.java.classLoader.getResource("sample-catalog.json")!!.readText()
    return parseCatalog(text) // Don't start with a catalog whose references are broken
}

fun main(args: Array<String>) {
    val db = Database(env("DATABASE_PATH", "data/announcements.db"))
    val catalog = loadCatalog(System.getenv("CATALOG_PATH"))
    val scraper = Scraper(db)

    // `--once [--incremental]`: collect once and exit (for manual refresh and checks)
    if ("--once" in args) {
        runBlocking { scraper.runAll(if ("--incremental" in args) Mode.Incremental else Mode.Full) }
        return
    }

    val port = env("PORT", "8080").toInt()
    val interval = env("SCRAPE_INTERVAL_MIN", "30").toInt().minutes
    val scrapeEnabled = env("SCRAPE_ENABLED", "true") != "false"

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(db, catalog)
        if (scrapeEnabled) launch {
            // Full collection at startup, then new posts only every interval, and a full collection again once a day.
            var lastFull: TimeSource.Monotonic.ValueTimeMark? = null
            while (true) {
                val mode = if (lastFull == null || lastFull.elapsedNow() >= 24.hours) Mode.Full else Mode.Incremental
                scraper.runAll(mode)
                if (mode == Mode.Full) lastFull = TimeSource.Monotonic.markNow()
                delay(interval)
            }
        }
    }.start(wait = true)
}
