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
    // Shape of CareerNet's jobs.json (from its Open API documentation)
    private val sample = """{"count":2,"pageSize":10,"pageIndex":1,"jobs":[
        {"job_nm":"데이터베이스관리자","work":"데이터를 체계적으로 저장하고 관리한다.","wage":"4,500만원","aptit_name":"탐구형","rel_job_nm":"데이터분석가","job_cd":"341"},
        {"job_nm":"웹개발자","work":"웹사이트를 만든다.","job_cd":"12"}]}"""

    @Test
    fun `careernet result picks the matching job`() {
        val j = parseCareerNetJob("데이터베이스 관리자", sample)!!
        assertEquals("데이터베이스 관리자", j.name)
        assertEquals("데이터를 체계적으로 저장하고 관리한다.", j.summary)
        assertEquals("4,500만원", j.wage)
        assertEquals("https://www.career.go.kr/cnet/front/base/job/jobView.do?SEQ=341", j.url)
        assertNull(parseCareerNetJob("간호사", sample))
        assertNull(parseCareerNetJob("간호사", "not json"))
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
