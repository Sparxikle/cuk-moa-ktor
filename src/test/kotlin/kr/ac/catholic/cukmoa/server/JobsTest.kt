package kr.ac.catholic.cukmoa.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.ac.catholic.cukmoa.model.ApiJson
import kr.ac.catholic.cukmoa.model.JobList
import kr.ac.catholic.cukmoa.server.db.Database

class JobsTest {
    // Shape of a real CareerNet jobs.json page (2026-11): job_cd is a number, 10 items a page
    private val page = """{"count":552,"pageSize":10,"pageIndex":1,"jobs":[
        {"job_nm":"데이터베이스관리자","work":"데이터를 체계적으로 저장하고 관리합니다.","wage":"3천만원↑","aptit_name":"IT관련전문직","rel_job_nm":"데이터분석가","job_cd":341,"seq":800},
        {"job_nm":"일러스트레이터","work":"그림을 그립니다.","job_cd":12},
        {"job_nm":"컴퓨터하드웨어 기술자 및 연구원","work":"하드웨어를 만듭니다.","job_cd":20},
        {"job_nm":"상담전문가","work":"상담합니다.","job_cd":30},
        {"job_nm":"간호사","work":"환자를 돌봅니다.","job_cd":40}]}"""

    private val matcher = CareerNetMatcher(parseCareerNetPage(page)!!.second.mapNotNull(::parseCareerNetItem))

    @Test
    fun `careernet page parses with numeric codes`() {
        val (count, items) = parseCareerNetPage(page)!!
        assertEquals(552, count)
        assertEquals("341", parseCareerNetItem(items[0])!!.code)
        assertNull(parseCareerNetPage("not json"))
    }

    @Test
    fun `department career names match careernet jobs conservatively`() {
        fun m(n: String) = matcher.match(n)?.name
        assertEquals("데이터베이스관리자", m("데이터베이스 관리자"))
        assertEquals("상담전문가", m("상담전문가(심리상담사)"))
        assertEquals("컴퓨터하드웨어 기술자 및 연구원", m("컴퓨터하드웨어기술자"))
        assertEquals("일러스트레이터", m("게임일러스트레이터"))
        // Overlap shorter than 4 letters: no guess
        assertNull(m("수술실간호사"))
        assertNull(m("게임PD"))
    }

    @Test
    fun `job info links to the careernet page and names a broader match`() {
        val exact = jobInfo("데이터베이스 관리자", matcher.match("데이터베이스 관리자"))
        assertEquals("데이터를 체계적으로 저장하고 관리합니다.", exact.summary)
        assertEquals("3천만원↑", exact.wage)
        assertEquals("https://www.career.go.kr/cloud/w/job/view?seq=341", exact.url)
        assertNull(exact.matched)
        assertEquals("일러스트레이터", jobInfo("게임일러스트레이터", matcher.match("게임일러스트레이터")).matched)
        assertNull(jobInfo("게임PD", null).summary)
    }

    @Test
    fun `jobs endpoint uses the stored encyclopedia`() = testApplication {
        val db = Database(File.createTempFile("jobs", ".db").apply { delete(); deleteOnExit() }.path)
        db.replaceCareerNetJobs(parseCareerNetPage(page)!!.second.associate { it["job_cd"].toString() to it.toString() })
        val departments = DepartmentService(apiKey = null)
        application { module(db, loadCatalog(null), departments, jobs = JobService(db, departments, apiKey = null)) }
        val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
        val dept = departments.current().items.first { "간호사" in it.careers }
        val list = client.get("/api/v1/jobs?department=${dept.id}").body<JobList>()
        assertEquals(dept.careers, list.items.map { it.name })
        assertEquals("환자를 돌봅니다.", list.items.first { it.name == "간호사" }.summary)
    }

    @Test
    fun `jobs endpoint lists a department's careers, names only without a key`() = testApplication {
        val db = Database(File.createTempFile("jobs", ".db").apply { delete(); deleteOnExit() }.path)
        val departments = DepartmentService(apiKey = null)
        application { module(db, loadCatalog(null), departments) }
        val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
        val dept = departments.current().items.first { it.careers.isNotEmpty() }
        val list = client.get("/api/v1/jobs?department=${dept.id}").body<JobList>()
        assertEquals(dept.careers, list.items.map { it.name })
        assertTrue(list.items.all { it.summary == null })
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/jobs").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/jobs?department=없는/학과").status)
    }
}
