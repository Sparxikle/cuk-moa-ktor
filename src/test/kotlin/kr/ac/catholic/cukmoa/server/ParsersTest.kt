package kr.ac.catholic.cukmoa.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
}
