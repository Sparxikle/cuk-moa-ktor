package kr.ac.catholic.cukmoa.server

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.ac.catholic.cukmoa.model.AI_COLLEGE
import kr.ac.catholic.cukmoa.model.ApiJson
import kr.ac.catholic.cukmoa.model.DataSource
import kr.ac.catholic.cukmoa.model.Department
import kr.ac.catholic.cukmoa.model.DepartmentBoards
import kr.ac.catholic.cukmoa.model.DepartmentList
import org.slf4j.LoggerFactory

// School department list + main courses and related careers per department.
// Source: Public Data Portal "National University Department Standard Data" (Korean Council for University Education, api.data.go.kr)
// The API key stays on the server only (environment variable DATA_GO_API_KEY or .env). Never put it in the app.

private const val API = "https://api.data.go.kr/openapi/tn_pubr_public_univ_major_api"
const val SCHOOL_NAME = "가톨릭대학교"

/** Campus is determined by location (city/district) */
private val CAMPUS = mapOf("부천시" to "성심", "서초구" to "성의", "종로구" to "성신")
private val CAMPUS_ORDER = listOf("성심", "성의", "성신")

/** Names that are admission or administrative units, not departments (…계열, …대학, 교양과정, etc.) */
private fun isAdmissionUnit(name: String) =
    name.endsWith("계열") || name.endsWith("대학") || name.startsWith("기타") ||
        name in setOf("교양과정", "교직과", "융합전공학부")

/** Unify middle-dot notation in names (・ U+30FB, ㆍ U+318D → · U+00B7) */
fun normalizeName(s: String) = s.trim().replace('・', '·').replace('ㆍ', '·')

/** "A +B+ C" → [A, B, C] (drops empty values and duplicates, keeps order) */
private fun splitPlus(s: String) = s.split('+').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * API rows → department list shown in the app.
 * - Closed departments and admission units are dropped.
 * - College info is used as is if present; otherwise AI departments with collected announcements go under the College of AI, Electronics & Information Convergence,
 *   names containing 융복합전공 go under "융복합전공", and the rest are grouped by the school's own field (공학계열, etc.).
 * - Each department gets its main courses, related careers, admission quota and number of graduates.
 */
fun buildDepartments(rows: List<JsonObject>): List<Department> {
    fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    return rows
        .filter { it.s("scsbjtSttsNm") != "폐과" && !isAdmissionUnit(it.s("scsbjtNm")) }
        .map { r ->
            val name = normalizeName(r.s("scsbjtNm"))
            val campus = CAMPUS[r.s("sggNm")] ?: r.s("sggNm")
            val college = r.s("collegeNm").takeIf { it.isNotEmpty() && it != "단과대구분없음" }
                ?: when {
                    name in DepartmentBoards -> AI_COLLEGE
                    "융복합전공" in name -> "융복합전공"
                    else -> r.s("univOneslfAfilNm").takeIf { it.isNotEmpty() }?.let { "${it}계열" } ?: "기타"
                }
            Department(
                id = "$campus/$name",
                name = name,
                college = college,
                campus = campus,
                board = DepartmentBoards[name],
                subjects = splitPlus(r.s("mainSubjNm")),
                careers = splitPlus(r.s("relatCrNm")),
                quota = r.s("mtcltnFxnoCnt").toIntOrNull(),
                graduates = r.s("graCnt").toIntOrNull(),
            )
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

/** Response JSON → rows. The Public Data Portal returns errors with status 200 too, so check resultCode. */
fun parseUnivMajorResponse(body: String): Pair<String, List<JsonObject>> {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }
        .getOrElse { error("공공데이터 API 응답을 읽지 못했습니다: ${body.take(120)}") }
    val header = root["header"]?.jsonObject
    val code = header?.get("resultCode")?.jsonPrimitive?.contentOrNull
    check(code == "00") { "공공데이터 API 오류 ($code): ${header?.get("resultMsg")?.jsonPrimitive?.contentOrNull}" }
    val item = root["body"]!!.jsonObject["items"]!!.jsonObject["item"]!!
    val rows = if (item is JsonArray) item.map { it.jsonObject } else listOf(item.jsonObject) // A single result comes as an object
    val year = rows.firstOrNull()?.get("yr")?.jsonPrimitive?.contentOrNull ?: "?"
    return year to rows
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
        val query = mapOf("serviceKey" to key, "pageNo" to "1", "numOfRows" to "1000", "type" to "json", "SCHL_NM" to SCHOOL_NAME)
            .entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val res = http.send(
            HttpRequest.newBuilder(URI("$API?$query")).timeout(Duration.ofSeconds(20)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) {
            when (res.statusCode()) {
                401 -> "API 키가 올바르지 않습니다 (401)"
                500 -> "공공데이터 API 서버 오류 (500)"
                else -> "HTTP ${res.statusCode()}"
            }
        }
        val (year, rows) = parseUnivMajorResponse(res.body())
        return DepartmentList(
            source = DataSource(
                label = "전국대학별학과정보 (${year}년, 한국대학교육협의회)",
                asOf = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString(),
                isSample = false,
            ),
            items = buildDepartments(rows),
        )
    }
}
