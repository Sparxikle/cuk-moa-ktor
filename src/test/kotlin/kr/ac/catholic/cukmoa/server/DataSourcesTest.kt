package kr.ac.catholic.cukmoa.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kr.ac.catholic.cukmoa.model.AI_COLLEGE
import kr.ac.catholic.cukmoa.server.scrape.parseCalendar

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
    fun `departments drop closed majors and admission units, group AI departments`() {
        val rows = Json.parseToJsonElement(fixture("departments_api.json")).jsonObject["data"]!!.jsonArray.map { it.jsonObject }
        val list = buildDepartments(rows)
        assertEquals(58, list.size)
        assertTrue(list.none { it.name.endsWith("계열") || it.name == "기타모집단위" })
        val ai = list.filter { it.college == AI_COLLEGE }
        assertEquals(setOf("csie", "mtc", "datascience", "ice", "ai"), ai.mapNotNull { it.board }.toSet())
        assertEquals(AI_COLLEGE, list.first().college) // 성심 · AI departments first
        assertEquals("의과대학", list.first { it.name == "의학과" }.college)
        assertEquals("성의", list.first { it.name == "의학과" }.campus)
        assertNull(list.first { it.name == "철학과" }.board)
    }

    @Test
    fun `dotenv lines`() {
        val f = kotlin.io.path.createTempFile().toFile().apply { writeText("# comment\nDATA_GO_API_KEY=\"abc=def\"\n\nPORT = 9000\n"); deleteOnExit() }
        assertEquals(mapOf("DATA_GO_API_KEY" to "abc=def", "PORT" to "9000"), loadDotEnv(f.path))
    }
}
