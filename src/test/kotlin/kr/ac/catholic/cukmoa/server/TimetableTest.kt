package kr.ac.catholic.cukmoa.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kr.ac.catholic.cukmoa.logic.newStudent
import kr.ac.catholic.cukmoa.logic.timetableProblems
import kr.ac.catholic.cukmoa.model.ApiJson
import kr.ac.catholic.cukmoa.model.ClassTime
import kr.ac.catholic.cukmoa.model.ErrorBody
import kr.ac.catholic.cukmoa.model.OpenClass
import kr.ac.catholic.cukmoa.model.TimetablePrefs
import kr.ac.catholic.cukmoa.model.TimetableRequest
import kr.ac.catholic.cukmoa.model.TimetableResponse
import kr.ac.catholic.cukmoa.server.db.Database

class TimetableTest {
    private val catalog = loadCatalog(null)

    // Credits are large so two classes land inside the school's 15–19 range
    private fun k(
        no: String, name: String, credits: Int, vararg times: ClassTime,
        dept: String = "컴퓨터정보공학부", cls: String = "01", category: String = "제1전공선택",
    ) = OpenClass(
        year = 2026, semester = "2", subjectNo = no, classNo = cls, name = name, department = dept,
        category = category, credits = credits, times = times.toList(),
    )

    private val classes = listOf(
        k("A", "웹프로그래밍", 8, ClassTime("월", 1, 3, "N101")),
        k("B", "컴퓨터구조", 8, ClassTime("월", 2, 3, "N102")), // clashes with A
        k("C", "운영체제", 8, ClassTime("화", 4, 6, "N103")),
        k("D", "데이터베이스", 8, ClassTime("수", 4, 6, "N104")),
        k("E", "회계원리", 8, ClassTime("목", 1, 3), dept = "회계학과"), // other department → not a candidate
    )

    private val student = newStudent(3).copy(departments = listOf("성심/컴퓨터정보공학부"))
    private val prefs = TimetablePrefs(minCredits = 15, maxCredits = 19)

    /** Returns the scripted answers in order and records what it was asked. */
    private class FakeAi(vararg answers: String) : TimetableAi {
        val left = ArrayDeque(answers.toList())
        val asked = mutableListOf<List<AiTurn>>()
        override fun generate(system: String, turns: List<AiTurn>): String { asked += turns.toList(); return left.removeFirst() }
    }

    private fun db() = Database(File.createTempFile("timetable", ".db").apply { delete(); deleteOnExit() }.path).apply { replaceClasses(classes) }

    private fun plans(vararg plans: String) = """{"plans":[${plans.joinToString(",")}]}"""
    private fun plan(title: String, vararg items: String) =
        """{"title":"$title","summary":"s","items":[${items.joinToString(",") { """{"subjectNo":"$it","classNo":"01","reason":"r"}""" }}]}"""

    @Test
    fun `class times parse from the school export`() {
        assertEquals(
            listOf(ClassTime("화", 5, 6, "M306"), ClassTime("목", 10, 10, "B338"), ClassTime("토", 1, 3, "K246")),
            parseClassTimes("화5~6(M306), 목10(B338), 토1~3(K246)"),
        )
        assertEquals(emptyList(), parseClassTimes(null))
        assertEquals(listOf(ClassTime("월", 2, 2)), parseClassTimes("월2, 이상한값"))
    }

    @Test
    fun `problems catch clashes, credits, free days and unknown classes`() {
        val p = kr.ac.catholic.cukmoa.model.TimetablePlan("t", items = listOf("A", "B").map { kr.ac.catholic.cukmoa.model.TimetableItem(it, "01") })
        assertTrue(timetableProblems(p, classes, prefs, student, catalog).any { "겹침" in it })
        val wide = kr.ac.catholic.cukmoa.model.TimetablePlan("t", items = listOf("C", "D", "Z").map { kr.ac.catholic.cukmoa.model.TimetableItem(it, "01") })
        val problems = timetableProblems(wide, classes, prefs.copy(freeDays = listOf("화"), minCredits = 17), student, catalog)
        assertTrue(problems.any { "없는 분반" in it })
        assertTrue(problems.any { "공강" in it })
        assertTrue(problems.any { "학점" in it }) // C + D = 16 < 17
    }

    @Test
    fun `invalid suggestions are sent back and only valid timetables are returned`() {
        val ai = FakeAi(
            plans(plan("엉망", "Z"), plan("좋음1", "A", "C")),
            plans(plan("좋음2", "C", "D"), plan("좋음1 중복", "C", "A")),
            plans(),
        )
        val service = TimetableService(db(), catalog, ai)
        val res = service.suggest(TimetableRequest(student, prefs), "1.1.1.1")
        assertEquals(listOf("좋음1", "좋음2"), res.plans.map { it.title }.sorted())
        assertEquals(2026 to "2", res.year to res.semester)
        // The second request carried the problem of the clashing plan
        assertTrue(ai.asked[1].last().text.contains("엉망"))
        assertEquals(3, ai.asked.size) // stops after 3 AI calls
        // Candidates: own major only (E from 회계학과 is left out); times are in the prompt
        val prompt = ai.asked[0].first().text
        assertTrue("웹프로그래밍" in prompt && "월1~3(N101)" in prompt && "회계원리" !in prompt)
    }

    @Test
    fun `a clashing draft is repaired with another class the AI picked`() {
        val service = TimetableService(db(), catalog, FakeAi())
        val req = TimetableRequest(student, prefs)
        val cands = service.candidates(req, classes)
        val clashing = kr.ac.catholic.cukmoa.server.AiPlans.serializer().let { ApiJson.decodeFromString(it, plans(plan("겹침", "A", "B"), plan("다른", "D"))) }.plans
        val fixed = service.repair(clashing[0], clashing, cands, prefs)
        assertEquals(listOf("A", "D"), fixed.items.map { it.subjectNo }) // B clashes with A; D comes from the other draft
        assertEquals(emptyList(), timetableProblems(fixed, classes, prefs, student, catalog))
    }

    @Test
    fun `candidates keep to the student's majors and list the 1st major first`() {
        val extra = classes + listOf(
            k("F", "시창2", 3, ClassTime("화", 1, 2), dept = "음악과", category = "전공기초"), // other department's 전공기초
            k("G", "딥러닝", 3, ClassTime("목", 4, 6), dept = "인공지능학과"),
            k("H", "음악으로듣는문화와사회", 3, ClassTime("목", 7, 8), dept = "음악과", category = "중핵교양필수"), // liberal arts: open to all
        )
        val service = TimetableService(db(), catalog, FakeAi())
        val single = service.candidates(TimetableRequest(student, prefs), extra).map { it.subjectNo }
        assertTrue("F" !in single && "G" !in single && "H" in single)
        val double = student.copy(departments = listOf("성심/인공지능학과", "성심/컴퓨터정보공학부"))
        val both = service.candidates(TimetableRequest(double, prefs), extra).map { it.subjectNo }
        assertEquals("G", both.first()) // 1st major (인공지능학과) first
        assertTrue("A" in both && "F" !in both)
    }

    @Test
    fun `limits protect the free quota`() {
        var t = Instant.parse("2026-10-27T01:00:00Z")
        val limiter = TimetableLimiter(perMinute = 2, perDay = 3, perIpHour = 2, now = { t })
        limiter.aiCall(); limiter.aiCall()
        assertFailsWith<TooManyRequests> { limiter.aiCall() } // per minute
        t = t.plusSeconds(61)
        limiter.aiCall()
        t = t.plusSeconds(61)
        assertFailsWith<TooManyRequests> { limiter.aiCall() } // per day
        t = Instant.parse("2026-10-27T15:01:00Z") // 00:01 KST next day
        limiter.aiCall()
        limiter.requestFrom("ip"); limiter.requestFrom("ip")
        assertFailsWith<TooManyRequests> { limiter.requestFrom("ip") }
        limiter.requestFrom("other")
    }

    @Test
    fun `endpoint answers 503 without AI and 400 on bad prefs`() = testApplication {
        val db = db()
        application { module(db, catalog, DepartmentService(apiKey = null)) }
        val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
        suspend fun post(p: TimetablePrefs) = client.post("/api/v1/timetables") {
            contentType(ContentType.Application.Json)
            setBody(TimetableRequest(student, p))
        }
        val off = post(prefs)
        assertEquals(HttpStatusCode.ServiceUnavailable, off.status)
        assertEquals("AI_UNAVAILABLE", off.body<ErrorBody>().error.code)
        assertEquals(HttpStatusCode.BadRequest, post(prefs.copy(minCredits = 20, maxCredits = 10)).status)
        assertEquals(HttpStatusCode.BadRequest, post(prefs.copy(freeDays = listOf("일요일"))).status)
        // School rule: 15–19, up to 21 only with good grades last semester
        assertEquals(HttpStatusCode.BadRequest, post(prefs.copy(minCredits = 12)).status)
        assertEquals(HttpStatusCode.BadRequest, post(prefs.copy(maxCredits = 21)).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, post(prefs.copy(maxCredits = 21, highGpa = true)).status) // valid, AI off
    }

    @Test
    fun `endpoint returns checked timetables`() = testApplication {
        val db = db()
        val ai = FakeAi(plans(plan("하나", "A", "C"), plan("둘", "C", "D"), plan("셋", "A", "D")))
        application { module(db, catalog, DepartmentService(apiKey = null), TimetableService(db, catalog, ai)) }
        val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
        val res = client.post("/api/v1/timetables") {
            contentType(ContentType.Application.Json)
            setBody(TimetableRequest(student, prefs))
        }.body<TimetableResponse>()
        assertEquals(3, res.plans.size)
        assertEquals(1, ai.asked.size) // all valid → one AI call
    }

    @Test
    fun `gemini request and response shapes`() {
        val body = geminiRequest("sys", listOf(AiTurn("user", "hi"), AiTurn("model", "{}"), AiTurn("user", "fix")))
        assertEquals("sys", body["systemInstruction"].toString().substringAfter("\"text\":\"").substringBefore("\""))
        assertTrue(body["contents"].toString().contains("\"role\":\"model\""))
        assertTrue(body["generationConfig"].toString().contains("application/json"))
        val sample = """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"plans\":"},{"text":"[]}"}]}}],"usageMetadata":{"totalTokenCount":10}}"""
        assertEquals("""{"plans":[]}""", geminiText(sample))
        assertFailsWith<ServiceUnavailable> { geminiText("""{"promptFeedback":{"blockReason":"SAFETY"}}""") }
    }
}
