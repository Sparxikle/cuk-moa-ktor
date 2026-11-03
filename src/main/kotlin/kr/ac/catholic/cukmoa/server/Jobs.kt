package kr.ac.catholic.cukmoa.server

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.ac.catholic.cukmoa.model.DataSource
import kr.ac.catholic.cukmoa.model.JobInfo
import kr.ac.catholic.cukmoa.model.JobList
import kr.ac.catholic.cukmoa.server.db.Database
import org.slf4j.LoggerFactory

// Job details for a department's related careers (GET /api/v1/jobs).
// Names: 한국대학교육협의회 department data (Department.careers). Details: CareerNet job encyclopedia
//   https://www.career.go.kr/cnet/front/openapi/jobs.json?apiKey=…&pageIndex=…  (10 jobs a page, 552 jobs as of 2026-11)
// The name search (searchJobNm) returns loosely related jobs and misses most names, so the server downloads the whole
// encyclopedia (about 56 requests) every 30 days into the database and matches names locally (CareerNetMatcher).
// Without CAREERNET_API_KEY the endpoint returns the names alone.

private const val CAREERNET = "https://www.career.go.kr/cnet/front/openapi/jobs.json"
private const val CAREERNET_JOB_PAGE = "https://www.career.go.kr/cloud/w/job/view?seq="
private val REFRESH_AFTER: Duration = Duration.ofDays(30)

/** One CareerNet job (jobs.json item) */
data class CareerNetJob(
    val code: String,
    val name: String,
    val work: String? = null,
    val wage: String? = null,
    val aptitude: String? = null,
    val related: String? = null,
)

private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** A jobs.json item, or null without a code and name */
fun parseCareerNetItem(o: JsonObject): CareerNetJob? {
    val code = o.s("job_cd") ?: return null
    val name = o.s("job_nm") ?: return null
    return CareerNetJob(code, name, o.s("work"), o.s("wage"), o.s("aptit_name"), o.s("rel_job_nm"))
}

/** One jobs.json page: total job count and the page's raw items. null when the body is not a jobs.json page. */
fun parseCareerNetPage(body: String): Pair<Int, List<JsonObject>>? {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val jobs = root["jobs"] as? JsonArray ?: return null
    return (root["count"]?.jsonPrimitive?.intOrNull ?: jobs.size) to jobs.map { it.jsonObject }
}

/**
 * Matches department career names (대교협 wording) to CareerNet jobs. Conservative on purpose: a wrong description
 * is worse than none.
 * 1. Same name, ignoring spaces, middle dots and a parenthesized part ("상담전문가(심리상담사)" is also tried as
 *    "상담전문가" and "심리상담사"). A CareerNet name "A 및 B" or "Aㆍ B" also answers to A.
 * 2. Otherwise one name contains the other and the shorter has at least 4 letters: the longest such CareerNet name
 *    ("게임일러스트레이터" → "일러스트레이터"). Shorter overlaps ("수술실간호사" → "간호사") are left unmatched.
 */
class CareerNetMatcher(jobs: List<CareerNetJob>) {
    private val index: Map<String, CareerNetJob> = buildMap {
        for (j in jobs) {
            val keys = variants(j.name) + listOf(" 및 ", "ㆍ").mapNotNull { sep -> j.name.split(sep).takeIf { it.size > 1 }?.first()?.let(::key) }
            keys.filter { it.isNotEmpty() }.forEach { putIfAbsent(it, j) }
        }
    }

    fun match(name: String): CareerNetJob? {
        variants(name).firstNotNullOfOrNull { index[it] }?.let { return it }
        val wanted = key(name)
        return index.entries
            .filter { (k, _) -> minOf(k.length, wanted.length) >= MIN_OVERLAP && (k in wanted || wanted in k) }
            .maxByOrNull { it.key.length }?.value
    }

    private companion object {
        const val MIN_OVERLAP = 4
        fun key(s: String) = s.filterNot { it.isWhitespace() || it == '·' || it == 'ㆍ' }

        fun variants(name: String): List<String> {
            val m = Regex("""^(.*?)\((.*?)\)(.*)$""").find(name)
            val all = if (m == null) listOf(name) else listOf(name, m.groupValues[1] + m.groupValues[3], m.groupValues[2])
            return all.map(::key).filter { it.isNotEmpty() }.distinct()
        }
    }
}

/** Department career name + matched CareerNet job → what the app shows */
fun jobInfo(name: String, job: CareerNetJob?): JobInfo =
    if (job == null) JobInfo(name)
    else JobInfo(
        name = name,
        summary = job.work,
        wage = job.wage,
        aptitude = job.aptitude,
        related = job.related,
        url = CAREERNET_JOB_PAGE + job.code,
        matched = job.name.takeIf { it.filterNot(Char::isWhitespace) != name.filterNot(Char::isWhitespace) },
    )

class JobService(private val db: Database, private val departments: DepartmentService, private val apiKey: String?) {
    private val log = LoggerFactory.getLogger(JobService::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val refreshing = AtomicBoolean(false)

    @Volatile private var matcher: CareerNetMatcher? = null

    /** Never waits for CareerNet: with no stored encyclopedia yet, the names come back alone until the download finishes. */
    fun forDepartment(departmentId: String): JobList {
        val dept = departments.current().items.firstOrNull { it.id == departmentId }
            ?: throw BadRequest("학과를 찾을 수 없어요: $departmentId")
        refreshInBackgroundIfStale()
        val m = loaded()
        return JobList(
            source = DataSource(
                label = if (m != null) "한국대학교육협의회 학과 정보 · 커리어넷 직업백과" else "한국대학교육협의회 학과 정보",
                asOf = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString(),
                isSample = false,
                url = if (m != null) "https://www.career.go.kr" else null,
            ),
            department = dept.name,
            items = dept.careers.map { n -> jobInfo(n, m?.match(n)) },
        )
    }

    /** The stored encyclopedia, read from the database once */
    private fun loaded(): CareerNetMatcher? {
        matcher?.let { return it }
        val (rows, _) = db.careerNetJobs()
        if (rows.isEmpty()) return null
        val jobs = rows.mapNotNull { r -> runCatching { parseCareerNetItem(Json.parseToJsonElement(r).jsonObject) }.getOrNull() }
        return CareerNetMatcher(jobs).also { matcher = it }
    }

    /** At startup and on requests: downloads the encyclopedia again when it is missing or older than 30 days. */
    fun refreshInBackgroundIfStale() {
        if (apiKey == null) return
        val (_, oldest) = db.careerNetJobs()
        val stale = oldest == null || Duration.between(Instant.parse(oldest), Instant.now()) > REFRESH_AFTER
        if (!stale || !refreshing.compareAndSet(false, true)) return
        thread(name = "careernet", isDaemon = true) {
            try {
                val items = download()
                db.replaceCareerNetJobs(items)
                matcher = null
                log.info("CareerNet: stored {} jobs", items.size)
            } catch (e: Exception) {
                log.warn("CareerNet download failed: {}", e.message)
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** Every page of jobs.json (6 at a time). Fails as a whole if a page fails, so a partial list never replaces a full one. */
    private fun download(): Map<String, String> {
        val (total, first) = parseCareerNetPage(page(1)) ?: error("first page is not jobs.json")
        val pages = (total + first.size.coerceAtLeast(1) - 1) / first.size.coerceAtLeast(1)
        val rest = (2..pages).chunked(6).flatMap { chunk ->
            chunk.map { i -> CompletableFuture.supplyAsync { parseCareerNetPage(page(i))?.second ?: error("page $i is not jobs.json") } }
                .map { it.join() }.flatten()
        }
        val all = (first + rest).associateBy { it.s("job_cd").orEmpty() }.filterKeys { it.isNotEmpty() }
        check(all.size >= total * 9 / 10) { "only ${all.size} of $total jobs" }
        return all.mapValues { it.value.toString() }
    }

    private fun page(i: Int): String {
        val url = "$CAREERNET?apiKey=${URLEncoder.encode(apiKey, Charsets.UTF_8)}&pageIndex=$i"
        val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "HTTP ${res.statusCode()} on page $i" }
        return res.body()
    }
}
