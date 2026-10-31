package kr.ac.catholic.cukmoa.server

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.ac.catholic.cukmoa.model.DataSource
import kr.ac.catholic.cukmoa.model.JobInfo
import kr.ac.catholic.cukmoa.model.JobList
import kr.ac.catholic.cukmoa.server.db.Database
import org.slf4j.LoggerFactory

// Job details for a department's related careers (GET /api/v1/jobs).
// Names: 한국대학교육협의회 department data (Department.careers). Details: CareerNet job encyclopedia
//   https://www.career.go.kr/cnet/front/openapi/jobs.json?apiKey=…&searchJobNm=…
// Each job is looked up once and cached in the database for 30 days (misses too), so a department costs
// at most one round of API calls. Without CAREERNET_API_KEY the endpoint returns the names alone.

private const val CAREERNET = "https://www.career.go.kr/cnet/front/openapi/jobs.json"
private val CACHE_FOR: Duration = Duration.ofDays(30)

private fun key(s: String) = s.filterNot { it.isWhitespace() || it == '·' }

/**
 * CareerNet jobs.json search result → details for [name]. The exact job name wins; otherwise the first result
 * whose name contains it (or is contained in it). null when nothing fits.
 */
fun parseCareerNetJob(name: String, body: String): JobInfo? {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val jobs = (root["jobs"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
    fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val wanted = key(name)
    val hit = jobs.firstOrNull { key(it.s("job_nm").orEmpty()) == wanted }
        ?: jobs.firstOrNull { val n = key(it.s("job_nm").orEmpty()); n.isNotEmpty() && (wanted in n || n in wanted) }
        ?: return null
    return JobInfo(
        name = name,
        summary = hit.s("work"),
        wage = hit.s("wage"),
        aptitude = hit.s("aptit_name"),
        related = hit.s("rel_job_nm"),
        url = hit.s("job_cd")?.let { "https://www.career.go.kr/cnet/front/base/job/jobView.do?SEQ=$it" },
    )
}

class JobService(private val db: Database, private val departments: DepartmentService, private val apiKey: String?) {
    private val log = LoggerFactory.getLogger(JobService::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun forDepartment(departmentId: String): JobList {
        val dept = departments.current().items.firstOrNull { it.id == departmentId }
            ?: throw BadRequest("학과를 찾을 수 없어요: $departmentId")
        val names = dept.careers
        val cached = db.jobInfo(names)
        val stale = names.filter { n -> cached[n]?.let { (_, at) -> Duration.between(Instant.parse(at), Instant.now()) > CACHE_FOR } ?: true }
        if (apiKey != null && stale.isNotEmpty()) {
            val fetched = fetch(stale)
            if (fetched.isNotEmpty()) db.saveJobInfo(fetched)
        }
        val now = db.jobInfo(names)
        return JobList(
            source = DataSource(
                label = if (apiKey != null) "한국대학교육협의회 학과 정보 · 커리어넷 직업백과" else "한국대학교육협의회 학과 정보",
                asOf = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString(),
                isSample = false,
                url = if (apiKey != null) "https://www.career.go.kr" else null,
            ),
            department = dept.name,
            items = names.map { n -> now[n]?.first ?: JobInfo(n) },
        )
    }

    /** Looks the names up in parallel (8 at a time). Failed lookups aren't cached, so they're retried next time. */
    private fun fetch(names: List<String>): List<JobInfo> = names.chunked(8).flatMap { chunk ->
        chunk.map { name ->
            val url = "$CAREERNET?apiKey=${URLEncoder.encode(apiKey, Charsets.UTF_8)}&searchJobNm=${URLEncoder.encode(name, Charsets.UTF_8)}"
            http.sendAsync(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString())
                .thenApply { res -> if (res.statusCode() == 200) parseCareerNetJob(name, res.body()) ?: JobInfo(name) else null }
                .exceptionally { e -> log.warn("CareerNet lookup failed for {}: {}", name, e.message); null }
        }.let { futures -> CompletableFuture.allOf(*futures.toTypedArray()).join(); futures.mapNotNull { it.join() } }
    }
}
