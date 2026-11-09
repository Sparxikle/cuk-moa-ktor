package kr.ac.catholic.cukmoa.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kr.ac.catholic.cukmoa.model.FacultyMember
import kr.ac.catholic.cukmoa.server.scrape.mergeCardFaculty
import kr.ac.catholic.cukmoa.server.scrape.parseCardFaculty
import kr.ac.catholic.cukmoa.server.scrape.parseConvergenceCurriculum
import kr.ac.catholic.cukmoa.server.scrape.RawRow
import kr.ac.catholic.cukmoa.server.scrape.articleNo
import kr.ac.catholic.cukmoa.server.scrape.isoDate
import kr.ac.catholic.cukmoa.server.scrape.makeLink
import kr.ac.catholic.cukmoa.server.scrape.parseDept
import kr.ac.catholic.cukmoa.server.scrape.parseEvents
import kr.ac.catholic.cukmoa.server.scrape.parseLms
import kr.ac.catholic.cukmoa.server.scrape.parseMainNotice
import kr.ac.catholic.cukmoa.server.scrape.parseOutside

// Checks selectors against real board HTML (saved 2026-10-25). If the school site changes, this breaks first.
class ParsersTest {
    private fun fixture(name: String) = javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    private fun assertWellFormed(rows: List<RawRow>, minRows: Int) {
        assertTrue(rows.size >= minRows, "expected at least $minRows rows, got ${rows.size}")
        rows.forEach {
            assertTrue(it.title.isNotBlank(), "blank title")
            assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(it.date), "bad date ${it.date}")
            assertTrue(it.href.isNotBlank(), "blank href for ${it.title}")
        }
    }

    @Test
    fun `department board table`() {
        val rows = parseDept(fixture("dept_csie_notice.html"))
        assertWellFormed(rows, 10)
        assertTrue(rows.any { it.pinned }, "csie notice has pinned posts")
        assertTrue(rows.all { it.views != null })
    }

    @Test
    fun `main site notice keeps its category`() {
        val rows = parseMainNotice(fixture("main_notice.html"))
        assertWellFormed(rows, 10)
        assertTrue(rows.all { !it.category.isNullOrBlank() })
    }

    @Test
    fun `events gallery and outside board`() {
        assertWellFormed(parseEvents(fixture("events.html")), 1)
        assertWellFormed(parseOutside(fixture("outside.html")), 10)
    }

    @Test
    fun `lms dates without year get the current year`() {
        val rows = parseLms(fixture("lms.html"), 2026)
        assertWellFormed(rows, 10)
        assertTrue(rows.all { it.href.startsWith("/") })
    }

    @Test
    fun `helpers`() {
        assertEquals("2026-10-16", isoDate("2026.10.16"))
        assertEquals("276825", articleNo("https://x/notice.do?mode=view&articleNo=276825&article.offset=10"))
        assertEquals("5866978", articleNo("https://x/notice_view_form.acl?ARTL_NUM=5866978&start=1"))
        assertEquals("https://a/b.do?mode=view", makeLink("https://a/b.do", "https://a", "?mode=view"))
        assertEquals("https://a/img.png", makeLink("https://a/b.do", "https://a", "/img.png"))
    }

    @Test
    fun `융복합전공 curriculum tab becomes a roadmap`() {
        val r = parseConvergenceCurriculum(fixture("convergence_global.html"), "major_convergence/글로벌인문경영융복합전공", "글로벌인문경영융복합전공", "https://major-convergence.catholic.ac.kr/major_convergence/curriculum-info/global.do")
        println(r.entries.joinToString("\n"))
        assertTrue(r.entries.size >= 10)
        assertTrue(r.entries.all { it.name.isNotBlank() && "(고유)" !in it.name })
        assertTrue(r.entries.any { "고유과목" in it.marks })
    }

    @Test
    fun `융복합전공 professor cards`() {
        val url = "https://major-convergence.catholic.ac.kr/major_convergence/undergraduate-info/faculty.do"
        val main = parseCardFaculty(fixture("convergence_faculty.html"), "major_convergence", url, "주임교수")
        val adjunct = parseCardFaculty(fixture("convergence_adjunct.html"), "major_convergence", url.replace("faculty", "adjunct"), "겸임·초빙교수")
        println((main + adjunct).joinToString("\n"))
        assertTrue(main.isNotEmpty() && adjunct.isNotEmpty())
        assertTrue(main.any { it.name == "김유중" })
        assertTrue((main + adjunct).all { it.majors == listOf("major_convergence") && it.id.startsWith("card:") })
    }

    @Test
    fun `card professors with a home profile merge into it`() {
        val home = FacultyMember(id = "123", name = "김유중", position = "교수", majors = listOf("csie"), departments = listOf("컴퓨터정보공학부"), profileUrl = "")
        val card = FacultyMember(id = "card:x:김유중", name = "김유중", position = "주임교수", majors = listOf("major_convergence"), departments = listOf("컴퓨터정보공학부"), profileUrl = "")
        val other = FacultyMember(id = "card:x:홍길동", name = "홍길동", position = "주임교수", majors = listOf("major_convergence"), departments = emptyList(), profileUrl = "")
        val merged = mergeCardFaculty(listOf(home, card, other))
        assertEquals(listOf("123", "card:x:홍길동"), merged.map { it.id })
        assertEquals(listOf("csie", "major_convergence"), merged[0].majors)
    }
}
