package kr.ac.catholic.cukmoa.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.ac.catholic.cukmoa.model.ClassTime
import kr.ac.catholic.cukmoa.model.OpenClass

// The school's 개설과목 조회 needs a login, so its JSON export (classes.json) is imported by hand once a semester:
//   ./gradlew run --args="--import-classes ../classes.json"
// Only the fields the app uses are kept. Staff IDs and IPs in the export (inptId, updtIp …) are dropped.

private const val ROWS = "DS_CURR_OPSB010"

/** openShtm → semester ("10" → "1", "20" → "2"; other codes as is) */
private fun semester(code: String) = when (code) {
    "10" -> "1"
    "20" -> "2"
    else -> code
}

private val TIME_PART = Regex("""^([월화수목금토일])(\d+)(?:~(\d+))?(?:\(([^)]*)\))?$""")

/** "화5~6(M306), 목10(B338)" → meetings. Parts that don't match are skipped. */
fun parseClassTimes(desc: String?): List<ClassTime> =
    desc.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { part ->
        val m = TIME_PART.matchEntire(part) ?: return@mapNotNull null
        val start = m.groupValues[2].toInt()
        val end = m.groupValues[3].toIntOrNull() ?: start
        ClassTime(m.groupValues[1], minOf(start, end), maxOf(start, end), m.groupValues[4].trim().ifEmpty { null })
    }

/** Export JSON → classes. Rows without a subject number, class number or name are skipped. */
fun parseOpenClasses(body: String): List<OpenClass> {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { error("개설과목 JSON을 읽지 못했습니다") }
    val rows = (root[ROWS] as? JsonArray ?: error("$ROWS 배열이 없습니다")).map { it.jsonObject }
    fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    return rows.mapNotNull { r ->
        val (hours, credits) = r.s("pntCnt")?.split('/')?.map { it.trim().toIntOrNull() }.let { it?.getOrNull(0) to it?.getOrNull(1) }
        OpenClass(
            year = r.s("openYyyy")?.toIntOrNull() ?: return@mapNotNull null,
            semester = semester(r.s("openShtm") ?: return@mapNotNull null),
            subjectNo = r.s("sbjtNo") ?: return@mapNotNull null,
            classNo = r.s("clssNo") ?: return@mapNotNull null,
            name = r.s("sbjtKorNm") ?: return@mapNotNull null,
            department = normalizeName(r.s("sustNm").orEmpty()),
            category = r.s("pobtFgNm").orEmpty(),
            grade = r.s("pobtGrade")?.toIntOrNull(),
            foreignLanguage = r.s("orgLangLtYnChk"),
            professors = r.s("respProfNm")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            competency = r.s("corsTemp2Nm"),
            enrolled = r.s("tlsnAplyRcnt")?.toIntOrNull(),
            hours = hours,
            credits = credits,
            times = parseClassTimes(r.s("tmTblDesc")),
        )
    }.distinctBy { listOf(it.year, it.semester, it.subjectNo, it.classNo) }
}
