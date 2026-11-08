package kr.ac.catholic.cukmoa.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kr.ac.catholic.cukmoa.model.AnnouncementPage
import kr.ac.catholic.cukmoa.model.ApiJson
import kr.ac.catholic.cukmoa.model.Catalog
import kr.ac.catholic.cukmoa.model.Course
import kr.ac.catholic.cukmoa.model.ErrorBody
import kr.ac.catholic.cukmoa.model.Health
import kr.ac.catholic.cukmoa.server.db.Database
import kr.ac.catholic.cukmoa.server.db.ScrapedItem

// Reads responses with the same shared models as the app — mismatched fields break here.
class ApiTest {
    private fun item(no: String, title: String, major: String? = "csie", pinned: Boolean = false, date: String = "2026-10-01") = ScrapedItem(
        source = if (major != null) "dept" else "main", board = "notice", major = major, category = "notice",
        articleNo = no, title = title, date = date, author = "학과", views = 10, link = "https://x/?articleNo=$no",
        image = null, pinned = pinned,
    )

    private fun withApi(block: suspend ApplicationTestBuilder.(Database) -> Unit) {
        val file = File.createTempFile("announcements", ".db").apply { delete(); deleteOnExit() }
        val db = Database(file.path)
        db.upsert(
            listOf(
                item("1", "2026-2학기 졸업시험 일시 및 장소 안내", pinned = true, date = "2026-09-01"),
                item("2", "SK쉴더스 채용연계형 교육과정 설명회", date = "2026-10-10"),
                item("3", "AI 해커톤 공모전 참가자 모집", date = "2026-10-20"),
                item("4", "학교 장학 공지", major = null, date = "2026-10-21"),
            ),
        )
        testApplication {
            application { module(db, loadCatalog(null), DepartmentService(apiKey = null, registry = SiteRegistry.parse(File("sites/department-sites.json").readText()))) }
            block(db)
        }
    }

    private fun ApplicationTestBuilder.json() = createClient { install(ContentNegotiation) { json(ApiJson) } }

    @Test
    fun `health and catalog use the shared contract`() = withApi {
        val client = json()
        val health = client.get("/health").body<Health>()
        assertEquals("ok", health.status)
        assertEquals("sample", health.dataMode)
        val catalog = client.get("/api/v1/catalog").body<Catalog>()
        assertTrue(catalog.courses.isNotEmpty())
        val course = client.get("/api/v1/courses/${catalog.courses[0].id}").body<Course>()
        assertEquals(catalog.courses[0], course)
    }

    @Test
    fun `errors have code and message`() = withApi {
        val client = json()
        suspend fun code(path: String, status: HttpStatusCode): String {
            val res: HttpResponse = client.get(path)
            assertEquals(status, res.status, path)
            return res.body<ErrorBody>().error.code
        }
        assertEquals("COURSE_NOT_FOUND", code("/api/v1/courses/not-a-course", HttpStatusCode.NotFound))
        assertEquals("INVALID_REQUEST", code("/api/v1/courses/bad%20id", HttpStatusCode.BadRequest))
        assertEquals("NOT_FOUND", code("/api/v1/missing", HttpStatusCode.NotFound))
        assertEquals("ANNOUNCEMENT_NOT_FOUND", code("/api/v1/announcements/99999", HttpStatusCode.NotFound))
        assertEquals("INVALID_REQUEST", code("/api/v1/announcements?since=yesterday", HttpStatusCode.BadRequest))
    }

    @Test
    fun `announcements filter, search and pin order`() = withApi {
        val client = json()
        suspend fun page(q: String) = client.get("/api/v1/announcements$q").body<AnnouncementPage>()

        val all = page("")
        assertEquals(4, all.total)
        assertTrue(all.items.first().pinned, "pinned first")
        assertEquals("2026-10-21", page("?pinnedFirst=false").items.first().date)

        assertEquals(3, page("?source=dept&major=csie").total)
        // The department filter applies only to department posts: my department + school notices
        assertEquals(4, page("?source=dept,main&major=csie").total)
        assertEquals(1, page("?source=dept,main&major=ai").total)
        assertEquals(1, page("?source=main").total)
        assertEquals(4, page("?source=dept,main").total)
        assertEquals(1, page("?q=공모전").total) // 3+ characters: FTS
        assertEquals(1, page("?q=채용").total) // 2 characters: LIKE
        assertEquals(0, page("?q=없는단어").total)
        assertEquals(0, page("?since=2099-01-01").total)
        assertEquals(2, page("?limit=2").items.size)
        assertEquals(0, page("?source=dept&major=ai')%20OR%201=1--").total) // Input is only ever bound as a parameter
    }

    @Test
    fun `departments fall back to the bundled snapshot without an API key`() = withApi {
        val list = json().get("/api/v1/departments").body<kr.ac.catholic.cukmoa.model.DepartmentList>()
        assertTrue(list.items.size > 50)
        val csie = list.items.first { it.name == "컴퓨터정보공학부" }
        assertEquals("csie", csie.board)
        assertEquals("https://csie.catholic.ac.kr", csie.site)
        assertTrue(csie.graduationUrl!!.endsWith("academic.do"))
        assertTrue(list.items.count { it.board != null } > 40) // boards collected for most majors
    }

    @Test
    fun `professors filter by major`() = withApi { db ->
        fun member(id: String, name: String, majors: List<String>, role: String? = null) = kr.ac.catholic.cukmoa.model.FacultyMember(
            id = id, name = name, position = "교수", role = role, majors = majors, departments = emptyList(), profileUrl = "https://x/?persNo=$id",
        )
        db.replaceFaculty(listOf(member("1", "나교수", listOf("ai", "datascience")), member("2", "가교수", listOf("csie")), member("3", "다교수", listOf("ai"), "학과장")))
        val client = json()
        suspend fun names(q: String) = client.get("/api/v1/professors$q").body<kr.ac.catholic.cukmoa.model.FacultyList>().items.map { it.name }
        assertEquals(listOf("다교수", "가교수", "나교수"), names("")) // Department chairs first, then by name
        assertEquals(listOf("다교수", "나교수"), names("?major=ai"))
        assertEquals(listOf("가교수", "나교수"), names("?major=csie,datascience"))
    }

    @Test
    fun `calendar filters by campus`() = withApi { db ->
        db.replaceCalendar(
            2026,
            listOf(
                kr.ac.catholic.cukmoa.server.scrape.CalendarRow("중간고사", "2026-10-20", "2026-10-26", listOf("성심")),
                kr.ac.catholic.cukmoa.server.scrape.CalendarRow("의대 일정", "2026-10-21", null, listOf("성의")),
            ),
        )
        val client = json()
        val cal = client.get("/api/v1/calendar").body<kr.ac.catholic.cukmoa.model.CalendarResponse>()
        assertEquals(listOf("중간고사"), cal.events.map { it.title })
        assertEquals(kr.ac.catholic.cukmoa.model.EventKind.Exam, cal.events.single().kind)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/calendar?campus=x").status)
    }

    @Test
    fun `imported classes are served for the latest semester and filter`() = withApi { db ->
        // Shape of the school's 개설과목 export (fields trimmed); staff fields like updtIp must not be stored
        fun row(no: String, cls: String, name: String, dept: String, grade: String?, prof: String) =
            """{"openYyyy":"2026","openShtm":"20","sbjtNo":"$no","clssNo":"$cls","sbjtKorNm":"$name","sustNm":"$dept",
                "pobtFgNm":"제1전공선택","pobtGrade":${grade?.let { "\"$it\"" } ?: "null"},"orgLangLtYnChk":null,
                "respProfNm":"$prof","corsTemp2Nm":"진로개발","tlsnAplyRcnt":"51","pntCnt":"4/3","updtIp":"10.0.0.1"}"""
        val export = """{"catholicResult":{},"DS_CURR_OPSB010":[
            ${row("02922", "10", "컴퓨터와프로그래밍2", "AI의공학과", null, "김주완, 정진모")},
            ${row("02922", "20", "컴퓨터와프로그래밍2", "AI의공학과", null, "남형수")},
            ${row("06615", "05", "회계학현장실습Ⅴ", "회계학과", "4", "안성희")}]}"""
        val parsed = parseOpenClasses(export)
        assertEquals(listOf("김주완", "정진모"), parsed[0].professors)
        assertEquals(4 to 3, parsed[0].hours to parsed[0].credits)
        assertEquals("2", parsed[0].semester)
        db.replaceClasses(parsed)
        db.replaceClasses(parsed) // Re-importing the same semester replaces, not duplicates

        val client = json()
        suspend fun get(query: String) = client.get("/api/v1/classes$query").body<kr.ac.catholic.cukmoa.model.OpenClassList>()
        val all = get("")
        assertEquals(2026 to "2", all.year to all.semester)
        assertEquals(3, all.items.size)
        assertEquals(listOf("06615"), get("?department=회계학과").items.map { it.subjectNo })
        assertEquals(listOf("06615"), get("?grade=4").items.map { it.subjectNo })
        assertEquals(listOf("20"), get("?q=남형수").items.map { it.classNo })
        assertEquals(2, get("?q=프로그래밍").items.size)
        assertEquals(0, get("?year=2025").items.size)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/classes?grade=x").status)
        assertTrue(db.use { c -> c.createStatement().executeQuery("SELECT data FROM open_classes").use { rs -> rs.next() && "10.0.0.1" !in rs.getString(1) } })
    }

    @Test
    fun `upsert keeps first_seen and does not duplicate`() = withApi { db ->
        val before = db.list(kr.ac.catholic.cukmoa.model.AnnouncementQuery(q = "공모전")).first.single()
        db.upsert(listOf(item("3", "AI 해커톤 공모전 참가자 모집 (마감 연장)", date = "2026-10-20")))
        val after = db.list(kr.ac.catholic.cukmoa.model.AnnouncementQuery(q = "해커톤")).first.single()
        assertEquals(before.id, after.id)
        assertEquals(before.firstSeen, after.firstSeen)
        assertEquals("AI 해커톤 공모전 참가자 모집 (마감 연장)", after.title) // The FTS triggers update too
        assertEquals(4, db.list(kr.ac.catholic.cukmoa.model.AnnouncementQuery()).second)
    }
}
