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
    fun `major roadmaps map merged table cells to semesters and tracks`() {
        fun road(m: String) = kr.ac.catholic.cukmoa.server.scrape.parseMajorRoadmap(
            fixture("roadmap_$m.html"), m, m, "https://$m.catholic.ac.kr/$m/course/roadmap.do",
        )
        val ai = road("ai")
        assertEquals(mapOf("졸" to "졸업요건과목", "추" to "추천전공과목"), ai.legend)
        fun at(r: kr.ac.catholic.cukmoa.model.MajorRoadmap, name: String) = r.entries.first { it.name == name }
        // Line breaks (<br>) are joined, and marks (졸·추) are replaced with their meanings
        assertEquals(kr.ac.catholic.cukmoa.model.SemesterKey.S1_1, at(ai, "컴퓨터와프로그래밍1").semester)
        assertEquals(listOf("추천전공과목"), at(ai, "컴퓨터와프로그래밍1").marks)
        // Courses after cells emptied by rowspan still land in their own semester
        assertEquals(kr.ac.catholic.cukmoa.model.SemesterKey.S3_1, at(ai, "알고리즘설계").semester)
        assertEquals(kr.ac.catholic.cukmoa.model.SemesterKey.S3_1, at(ai, "자료구조").semester)
        assertEquals("기초역량 과정", at(ai, "자료구조").track)
        assertTrue(ai.entries.any { it.track == "핵심과정" && it.name == "머신러닝" && "졸업요건과목" in it.marks })

        val csie = road("csie")
        assertEquals(46, csie.entries.size)
        assertEquals(kr.ac.catholic.cukmoa.model.SemesterKey.S3_1, at(csie, "운영체제").semester)
        assertEquals(setOf("기초 역량 과정", "핵심과정"), csie.entries.map { it.track }.toSet())

        assertEquals(3, road("mtc").entries.map { it.track }.distinct().size)
        assertEquals(3, road("datascience").legend.size)

        // 정보통신전자공학부 has only an image, no table
        val ice = road("ice")
        assertTrue(ice.entries.isEmpty())
        assertEquals("https://ice.catholic.ac.kr/_res/cuk/ice/img/content/33.PNG", ice.imageUrl)

        // Departments with only an image are filled from transcribed data (the image address is kept)
        val iceFilled = kr.ac.catholic.cukmoa.server.scrape.withManualRoadmap(ice)
        assertEquals(76, iceFilled.entries.size)
        assertEquals(ice.imageUrl, iceFilled.imageUrl)
        assertTrue(iceFilled.entries.all { it.semester == null && it.year in 1..4 })
        assertEquals(setOf("정보통신 분야", "반도체시스템 분야"), iceFilled.entries.filter { it.yearLevel >= 3 }.map { it.track }.toSet())
        assertTrue(iceFilled.note!!.contains("옮겨 적은"))
        // Departments with a table are left untouched
        assertEquals(ai, kr.ac.catholic.cukmoa.server.scrape.withManualRoadmap(ai))
    }

    @Test
    fun `dotenv lines`() {
        val f = kotlin.io.path.createTempFile().toFile().apply { writeText("# comment\nDATA_GO_API_KEY=\"abc=def\"\n\nPORT = 9000\n"); deleteOnExit() }
        assertEquals(mapOf("DATA_GO_API_KEY" to "abc=def", "PORT" to "9000"), loadDotEnv(f.path))
    }
}
