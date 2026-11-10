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
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.ac.catholic.cukmoa.model.TimetableRequest
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import kotlin.time.Duration.Companion.minutes
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
import kr.ac.catholic.cukmoa.model.CalendarResponse
import kr.ac.catholic.cukmoa.model.Catalog
import kr.ac.catholic.cukmoa.model.DataSource
import kr.ac.catholic.cukmoa.model.DepartmentList
import kr.ac.catholic.cukmoa.model.FacultyList
import kr.ac.catholic.cukmoa.model.MajorRoadmapList
import kr.ac.catholic.cukmoa.model.ErrorBody
import kr.ac.catholic.cukmoa.model.ErrorCodes
import kr.ac.catholic.cukmoa.model.ErrorDetail
import kr.ac.catholic.cukmoa.model.Health
import kr.ac.catholic.cukmoa.model.OpenClassList
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
fun Application.module(
    db: Database,
    catalog: Catalog,
    departments: DepartmentService,
    timetables: TimetableService = TimetableService(db, catalog, ai = null),
    jobs: JobService = JobService(db, departments, apiKey = null),
    sites: SiteRegistry = SiteRegistry(emptyList()),
) {
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
        exception<ServiceUnavailable> { call, e -> call.error(HttpStatusCode.ServiceUnavailable, ErrorCodes.AI_UNAVAILABLE, e.message ?: "잠시 뒤에 다시 해 주세요.") }
        exception<TooManyRequests> { call, e -> call.error(HttpStatusCode.TooManyRequests, ErrorCodes.RATE_LIMITED, e.message ?: "잠시 뒤에 다시 해 주세요.") }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ -> call.error(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, "요청 형식을 확인해주세요.") }
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
                    majors = sites.majorNames,
                    boards = db.boardCounts(),
                    lastScrape = runs.mapNotNull { it.lastOk }.maxOrNull(),
                    scrapeRuns = runs,
                ),
            )
        }

        get("/api/v1/departments") { call.respond(departments.current()) }

        get("/api/v1/roadmaps") {
            val majors = call.request.queryParameters["major"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            call.respond(
                MajorRoadmapList(
                    source = DataSource(
                        label = "AI전자정보융합대학 학과 홈페이지 전공로드맵",
                        asOf = db.scrapeRuns().filter { it.boardKey.startsWith("roadmap/") }.mapNotNull { it.lastOk }.maxOrNull()?.take(10)
                            ?: java.time.LocalDate.now().toString(),
                        isSample = false,
                    ),
                    items = db.roadmaps(majors),
                ),
            )
        }

        get("/api/v1/professors") {
            val majors = call.request.queryParameters["major"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            call.respond(
                FacultyList(
                    source = DataSource(
                        label = "AI전자정보융합대학 학과 홈페이지 교수 소개",
                        asOf = db.scrapeRuns().firstOrNull { it.boardKey == "faculty" }?.lastOk?.take(10) ?: java.time.LocalDate.now().toString(),
                        isSample = false,
                    ),
                    items = db.faculty(majors),
                ),
            )
        }

        get("/api/v1/calendar") {
            val campus = call.request.queryParameters["campus"] ?: "성심"
            if (campus !in setOf("성심", "성의", "성신")) throw BadRequest("campus는 성심·성의·성신 중 하나입니다.")
            call.respond(
                CalendarResponse(
                    source = DataSource(
                        label = "가톨릭대학교 학사일정",
                        asOf = db.scrapeRuns().filter { it.boardKey.startsWith("calendar/") }.mapNotNull { it.lastOk }.maxOrNull()?.take(10)
                            ?: java.time.LocalDate.now().toString(),
                        isSample = false,
                        url = "https://www.catholic.ac.kr/ko/support/calendar2024_list.do",
                    ),
                    campus = campus,
                    events = db.calendar(campus),
                ),
            )
        }

        get("/api/v1/classes") {
            val p = call.request.queryParameters
            fun values(name: String) = p[name]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val latest = db.latestClassTerm()
            val year = p["year"]?.let { it.toIntOrNull() ?: throw BadRequest("year 값이 숫자가 아닙니다.") } ?: latest?.first
            val semester = p["semester"] ?: latest?.second
            val grade = p["grade"]?.let { it.toIntOrNull() ?: throw BadRequest("grade 값이 숫자가 아닙니다.") }
            call.respond(
                OpenClassList(
                    source = DataSource(
                        label = "가톨릭대학교 개설과목 (학기마다 직접 반영)",
                        asOf = latest?.third?.take(10) ?: java.time.LocalDate.now().toString(),
                        isSample = false,
                    ),
                    year = year ?: 0,
                    semester = semester.orEmpty(),
                    items = if (year == null || semester == null) emptyList()
                    else db.classes(year, semester, values("department"), values("category"), grade, p["q"]?.takeIf { it.isNotBlank() }),
                ),
            )
        }

        get("/api/v1/jobs") {
            val department = call.request.queryParameters["department"]?.takeIf { it.isNotBlank() }
                ?: throw BadRequest("department(예: 성심/경영학과)가 필요해요.")
            call.respond(withContext(Dispatchers.IO) { jobs.forDepartment(department) })
        }

        post("/api/v1/timetables") {
            val req = call.receive<TimetableRequest>()
            // remoteAddress is the raw IP; remoteHost does a reverse DNS lookup that hangs for VPN (Tailscale) clients
            val ip = call.request.origin.remoteAddress
            val res = withContext(Dispatchers.IO) { timetables.suggest(req, ip) }
            call.respond(res)
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

// Config: environment variables → .env in the server folder → defaults. Secrets (API keys) only via .env or environment variables.
private val dotEnv by lazy { loadDotEnv() }
private fun env(name: String): String? = System.getenv(name) ?: dotEnv[name]
private fun env(name: String, default: String) = env(name) ?: default

fun loadCatalog(path: String?): Catalog {
    val text = path?.let { File(it).readText() }
        ?: Database::class.java.classLoader.getResource("sample-catalog.json")!!.readText()
    return parseCatalog(text) // Don't start with a catalog whose references are broken
}

fun main(args: Array<String>) {
    val db = Database(env("DATABASE_PATH", "data/announcements.db"))
    val catalog = loadCatalog(System.getenv("CATALOG_PATH"))
    val sites = SiteRegistry.load(env("SITES_PATH", "sites/department-sites.json"))
    val scraper = Scraper(db, sites)
    val departments = DepartmentService(env("DATA_GO_API_KEY"), sites)

    // `--export-departments <file>`: fetch the department list from the API and save it as the snapshot in the app repo
    args.indexOf("--export-departments").takeIf { it >= 0 }?.let { i ->
        val list = departments.fetch()
        File(args[i + 1]).writeText(kotlinx.serialization.json.Json(ApiJson) { prettyPrint = true }.encodeToString(DepartmentList.serializer(), list) + "\n")
        println("wrote ${list.items.size} departments to ${args[i + 1]}")
        return
    }

    // `--import-classes <file>`: load the school's 개설과목 export (classes.json) and replace that semester's classes
    args.indexOf("--import-classes").takeIf { it >= 0 }?.let { i ->
        val items = parseOpenClasses(File(args[i + 1]).readText())
        check(items.isNotEmpty()) { "개설과목이 없습니다: ${args[i + 1]}" }
        db.replaceClasses(items)
        items.groupBy { "${it.year}-${it.semester}" }.forEach { (term, list) -> println("imported ${list.size} classes for $term") }
        return
    }

    // `--once [--incremental]`: collect once and exit (for manual refresh and checks)
    if ("--once" in args) {
        runBlocking { scraper.runAll(if ("--incremental" in args) Mode.Incremental else Mode.Full) }
        return
    }

    val port = env("PORT", "8080").toInt()
    val interval = env("SCRAPE_INTERVAL_MIN", "30").toInt().minutes
    val scrapeEnabled = env("SCRAPE_ENABLED", "true") != "false"

    embeddedServer(Netty, port = port, host = env("HOST", "0.0.0.0")) {
        // The AWS SDK reads the Bedrock API key only from the process environment or this system property, not from .env
        if (System.getenv("AWS_BEARER_TOKEN_BEDROCK") == null) env("AWS_BEARER_TOKEN_BEDROCK")?.takeIf { it.isNotBlank() }?.let { System.setProperty("aws.bearerTokenBedrock", it) }
        val bedrock = env("BEDROCK_MODEL_ID")?.takeIf { it.isNotBlank() }?.let { BedrockTimetableAi(it, env("AWS_REGION", "ap-northeast-2")) }
        if (bedrock == null) environment.log.info("BEDROCK_MODEL_ID not set: AI timetable maker answers 503")
        val careerNetKey = env("CAREERNET_API_KEY")?.takeIf { it.isNotBlank() }
        if (careerNetKey == null) environment.log.info("CAREERNET_API_KEY not set: /api/v1/jobs returns job names only")
        val jobs = JobService(db, departments, careerNetKey).also { it.refreshInBackgroundIfStale() }
        module(db, catalog, departments, TimetableService(db, catalog, bedrock), jobs, sites)
        if (scrapeEnabled) launch {
            // New posts only every interval, and a full collection (every page, professors, roadmaps, calendar) once a day.
            // The last full collection is read from the database, so a restart doesn't start another one;
            // `--fetch` forces one at startup.
            var lastFull: java.time.Instant? = if ("--fetch" in args) null else lastFullScrape(db)
            while (true) {
                val due = lastFull == null || java.time.Duration.between(lastFull, java.time.Instant.now()) >= java.time.Duration.ofHours(24)
                val mode = if (due) Mode.Full else Mode.Incremental
                scraper.runAll(mode)
                if (mode == Mode.Full) lastFull = java.time.Instant.now()
                delay(interval)
            }
        }
    }.start(wait = true)
}

/** When the last full collection finished: professors and the calendar are only read in full collections. */
fun lastFullScrape(db: Database): java.time.Instant? =
    db.scrapeRuns().filter { it.boardKey == "faculty" || it.boardKey.startsWith("calendar/") }
        .mapNotNull { r -> r.lastOk?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } }.maxOrNull()
