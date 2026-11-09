package kr.ac.catholic.cukmoa.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kr.ac.catholic.cukmoa.model.AiCollegeDepartments

class SiteRegistryTest {
    // The registry the server ships with (edited with tools/department-sites.py)
    private val registry = SiteRegistry.parse(File("sites/department-sites.json").readText())

    @Test
    fun `site codes come from the CMS path`() {
        assertEquals("csie", siteCode("https://csie.catholic.ac.kr/csie/community/notice.do"))
        assertEquals("ble", siteCode("https://ble.catholic.ac.kr/BLE/index.do"))
        assertEquals("major_convergence", siteCode("https://major-convergence.catholic.ac.kr/major_convergence/curriculum-info/global.do"))
        assertEquals("songsin", siteCode("https://songsin.catholic.ac.kr/ko/academics/faculty.do"))
        assertEquals(null, siteCode("https://example.com/a/b.do"))
        assertEquals("recruitment", boardCategory("recruitment"))
        assertEquals("career", boardCategory("notice01-job"))
        assertEquals("notice", boardCategory("notice"))
    }

    @Test
    fun `every major is in the registry and the AI college keeps its codes`() {
        assertEquals(62, registry.entries.size)
        assertEquals(mapOf("컴퓨터정보공학부" to "csie", "미디어기술콘텐츠학과" to "mtc", "데이터사이언스학과" to "datascience", "정보통신전자공학부" to "ice", "인공지능학과" to "ai"),
            registry.codeByDepartment.filterKeys { it in AiCollegeDepartments })
    }

    @Test
    fun `boards are unique, skip non-board pages and use the right site`() {
        assertTrue(registry.boards.size > 100)
        assertEquals(registry.boards.size, registry.boards.map { it.code to it.board }.toSet().size)
        assertEquals(registry.boards.size, registry.boards.map { it.url }.toSet().size)
        assertTrue(registry.boards.none { "faq" in it.board.lowercase() || "gallery" in it.board.lowercase() })
        assertTrue(registry.boards.all { it.url.startsWith("https://") && it.url.endsWith(".do") })
        assertTrue(registry.boards.any { it.code == "csie" && it.board == "notice" })
    }

    @Test
    fun `majors sharing one site get separate roadmap keys`() {
        val keys = registry.roadmaps.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(registry.roadmaps.first { it.department == "컴퓨터정보공학부" }.key == "csie")
        val shared = registry.departmentsByCode.filterValues { it.size > 1 }
        assertTrue(shared.isNotEmpty())
        registry.roadmaps.filter { it.code in shared }.forEach { assertTrue(it.key.startsWith("${it.code}/"), it.key) }
    }

    @Test
    fun `every 융복합전공 shares the convergence site for notices, professors and roadmaps`() {
        val convergence = registry.entries.map { it.department }.filter { "융복합전공" in it }
        assertTrue(convergence.size >= 12)
        convergence.forEach { assertEquals("major_convergence", registry.codeByDepartment[it], it) }
        assertEquals(convergence.toSet(), registry.departmentsByCode["major_convergence"]!!.toSet())
        assertTrue(registry.facultyPages["major_convergence"]!!.any { it.endsWith("undergraduate-info/faculty.do") })
        assertEquals(listOf("융복합전공"), registry.facultyDepartments("major_convergence"))
        assertEquals("major_convergence/금융공학융복합전공", registry.roadmaps.first { it.department == "금융공학융복합전공" }.key)
    }
}
