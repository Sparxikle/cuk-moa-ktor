package kr.ac.catholic.cukmoa.server

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.ac.catholic.cukmoa.model.AI_COLLEGE
import kr.ac.catholic.cukmoa.model.ApiJson
import kr.ac.catholic.cukmoa.model.DataSource
import kr.ac.catholic.cukmoa.model.Department
import kr.ac.catholic.cukmoa.model.DepartmentBoards
import kr.ac.catholic.cukmoa.model.DepartmentList
import org.slf4j.LoggerFactory

// School department list. Fetched from the Public Data Portal "University Department Information" (api.odcloud.kr) and cleaned up.
// The API key stays on the server only (environment variable DATA_GO_API_KEY or .env). Never put it in the app.

private const val API = "https://api.odcloud.kr/api/15014632/v1/uddi:6939f45b-1283-4462-b394-820c26e1445d"
const val SCHOOL_NAME = "가톨릭대학교"

private val CAMPUS = mapOf("본교" to "성심", "제2캠퍼스" to "성의", "제3캠퍼스" to "성신")
private val CAMPUS_ORDER = listOf("성심", "성의", "성신")

/** Names that are admission or administrative units, not departments */
private fun isAdmissionUnit(name: String) =
    name.endsWith("계열") || name in setOf("기타모집단위", "교양과정", "교직과", "가톨릭대학교학부대학", "창업대학", "융합전공학부")

/**
 * API rows → department list shown in the app.
 * - Closed departments and admission units are dropped.
 * - College info is used as is if present; otherwise AI departments with collected announcements go under the College of AI, Electronics & Information Convergence,
 *   names containing 융복합전공 go under "융복합전공", and the rest are grouped by the standard field (공학계열, etc.).
 */
fun buildDepartments(rows: List<JsonObject>): List<Department> {
    fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    return rows
        .filter { it.s("학과상태") != "폐지" && !isAdmissionUnit(it.s("학부_과(전공)명")) }
        .map { r ->
            val name = r.s("학부_과(전공)명")
            val campus = CAMPUS[r.s("본분교명")] ?: r.s("본분교명")
            val college = r.s("단과대학명").takeIf { it.isNotEmpty() && it != "단과대구분없음" }
                ?: when {
                    name in DepartmentBoards -> AI_COLLEGE
                    "융복합전공" in name -> "융복합전공"
                    else -> r.s("표준분류대계열").ifEmpty { "기타" }
                }
            Department(id = "$campus/$name", name = name, college = college, campus = campus, board = DepartmentBoards[name])
        }
        .distinctBy { it.id }
        // 성심 → 성의 → 성신, the College of AI, Electronics & Information Convergence first, then by group and department name
        .sortedWith(
            compareBy<Department>(
                { CAMPUS_ORDER.indexOf(it.campus).let { i -> if (i < 0) 99 else i } },
                { if (it.college == AI_COLLEGE) 0 else 1 },
                { it.college },
                { it.name },
            ),
        )
}

/** Server .env file (KEY=VALUE lines) → map. Environment variables win when set. */
fun loadDotEnv(path: String = ".env"): Map<String, String> {
    val file = java.io.File(path)
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
        .associate { line -> line.substringBefore("=").trim() to line.substringAfter("=").trim().removeSurrounding("\"") }
}

class DepartmentService(private val apiKey: String?) {
    private val log = LoggerFactory.getLogger(DepartmentService::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** Snapshot stored in the app repo (when there's no API key or the API fails) */
    private val snapshot: DepartmentList by lazy {
        ApiJson.decodeFromString(
            DepartmentList.serializer(),
            DepartmentService::class.java.classLoader.getResource("departments.json")!!.readText(),
        )
    }

    @Volatile private var cached: Pair<Instant, DepartmentList>? = null

    /** Calls the API only once a day. On failure, the last successful value; failing that, the snapshot. */
    fun current(): DepartmentList {
        cached?.let { (at, list) -> if (Duration.between(at, Instant.now()) < Duration.ofHours(24)) return list }
        val fresh = runCatching { fetch() }
            .onFailure { log.warn("department API failed: {}", it.message) }
            .getOrNull()
        if (fresh != null) cached = Instant.now() to fresh
        return fresh ?: cached?.second ?: snapshot
    }

    fun fetch(): DepartmentList {
        val key = requireNotNull(apiKey?.takeIf { it.isNotBlank() }) { "DATA_GO_API_KEY가 없습니다" }
        val query = mapOf("page" to "1", "perPage" to "2000", "cond[학교명::EQ]" to SCHOOL_NAME)
            .entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val res = http.send(
            HttpRequest.newBuilder(URI("$API?$query"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Infuser $key")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) {
            when (res.statusCode()) {
                401 -> "API 키가 올바르지 않습니다 (401)"
                500 -> "공공데이터 API 서버 오류 (500)"
                else -> "HTTP ${res.statusCode()}"
            }
        }
        val body = Json.parseToJsonElement(res.body()).jsonObject
        val rows = body["data"]!!.jsonArray.map { it.jsonObject }
        val year = rows.firstOrNull()?.get("조사년도")?.jsonPrimitive?.contentOrNull ?: "?"
        return DepartmentList(
            source = DataSource(
                label = "공공데이터포털 대학 학과 정보 (${year}년 조사)",
                asOf = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString(),
                isSample = false,
                url = "https://www.data.go.kr/data/15014632/openapi.do",
            ),
            items = buildDepartments(rows),
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
