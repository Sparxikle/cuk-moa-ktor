package kr.ac.catholic.cukmoa.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.ac.catholic.cukmoa.model.AI_COLLEGE
import kr.ac.catholic.cukmoa.server.scrape.mergeFaculty
import kr.ac.catholic.cukmoa.server.scrape.parseCalendar
import kr.ac.catholic.cukmoa.server.scrape.parseFaculty

// Checks the processing rules against academic calendar HTML (saved 2026-10-25) and a department API response (saved the same day).
class DataSourcesTest {
    private fun fixture(name: String) = javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `calendar rows have dates, ranges and campuses`() {
        val rows = parseCalendar(fixture("calendar_2026.html"), 2026)
        assertEquals(62, rows.size) // All 62 events on the page (March to February of the next year)
        assertTrue(rows.any { it.title == "시무미사" && it.start == "2027-01-04" }) // "2027년 1월" group
        val first = rows.first()
        assertEquals("2026-03-03", first.start)
        assertTrue("성심" in first.campuses)
        // Range within the same month: "10 (화) ~ 13 (금)"
        assertTrue(rows.any { it.start == "2026-03-10" && it.end == "2026-03-13" })
        // Range into another month / the next year: "~ 01.12(화)"
        assertTrue(rows.any { it.end == "2027-01-12" })
        rows.forEach { r -> r.end?.let { assertTrue(it > r.start, "${r.title}: ${r.start}~$it") } }
    }

    @Test
    fun `departments drop closed majors and admission units, carry subjects`() {
        val (year, rows) = parseUnivMajorResponse(fixture("univ_major_api.json"))
        assertEquals("2025", year)
        val list = buildDepartments(rows)
        assertTrue(list.none { it.name.endsWith("계열") || it.name.endsWith("대학") || it.name.startsWith("기타") })
        val ai = list.filter { it.college == AI_COLLEGE }
        assertEquals(setOf("csie", "mtc", "datascience", "ice", "ai"), ai.mapNotNull { it.board }.toSet())
        assertEquals(AI_COLLEGE, list.first().college) // 성심 · AI departments first
        assertEquals("성의", list.first { it.name == "의학과" }.campus)
        assertEquals("의과대학", list.first { it.name == "의학과" }.college)

        val csie = list.first { it.name == "컴퓨터정보공학부" }
        assertTrue("운영체제" in csie.subjects && "자료구조" in csie.subjects)
        assertTrue(csie.subjects.none { it.isBlank() || it != it.trim() })
        assertTrue(csie.careers.isNotEmpty())
        assertEquals(36, csie.quota)
        // Unify middle-dot notation (・ → ·)
        assertTrue(list.any { it.name == "공간디자인·소비자학과" })
        assertTrue(list.count { it.subjects.isNotEmpty() } >= list.size - 5, "most departments have subjects")
    }

    @Test
    fun `data go kr error responses are reported`() {
        val err = """{"header":{"resultCode":"30","resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}}"""
        val e = kotlin.runCatching { parseUnivMajorResponse(err) }.exceptionOrNull()
        assertTrue(e?.message?.contains("SERVICE_KEY") == true)
    }

    @Test
    fun `faculty pages parse and merge professors listed by two departments`() {
        val ai = parseFaculty(fixture("faculty_ai.html"), "ai", "https://ai.catholic.ac.kr/ai/faculty/faculty.do")
        val csie = parseFaculty(fixture("faculty_csie.html"), "csie", "https://csie.catholic.ac.kr/csie/faculty/faculty.do")
        assertEquals(6, ai.size)
        assertEquals(6, csie.size)
        val kim = csie.first { it.name == "김유중" }
        assertEquals("조교수", kim.position)
        assertEquals("컴퓨터정보공학부 학과장", kim.role)
        assertEquals("다솔관 D416", kim.office)
        assertEquals(listOf("컴퓨터정보공학부"), kim.departments)
        assertEquals("https://csie.catholic.ac.kr/csie/faculty/faculty.do?mode=view&persNo=212210346", kim.profileUrl)
        assertEquals(listOf("데이터베이스 시스템", "빅데이터 분석", "정보 검색"), csie.first { it.name == "황병연" }.keywords)
        assertTrue(ai.all { it.position.isNotEmpty() && it.id.all(Char::isDigit) })

        // A professor listed in two departments is merged into one
        val merged = mergeFaculty(listOf(ai, ai.map { it.copy(majors = listOf("datascience"), departments = listOf("데이터사이언스학과")) }, csie))
        assertEquals(12, merged.size)
        assertTrue(merged.filter { it.id in ai.map { a -> a.id } }.all { it.majors == listOf("ai", "datascience") })
        // One department's chair doesn't appear as chair in another department
        assertTrue(merged.mapNotNull { it.role }.all { "학과" in it || "학부" in it })
    }

    @Test
    fun `dotenv lines`() {
        val f = kotlin.io.path.createTempFile().toFile().apply { writeText("# comment\nDATA_GO_API_KEY=\"abc=def\"\n\nPORT = 9000\n"); deleteOnExit() }
        assertEquals(mapOf("DATA_GO_API_KEY" to "abc=def", "PORT" to "9000"), loadDotEnv(f.path))
    }
}
