package kr.ac.catholic.cukmoa.server

import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

// Department website registry (sites/department-sites.json, edited with tools/department-sites.py).
// Every major lists its homepage, roadmap, graduation requirements, professor pages and announcement boards.
// The scraper collects from these URLs, so adding a department is a data edit, not a code change.

/** One major's pages, as entered in the registry. Empty strings and lists = not entered. */
data class SiteEntry(
    val department: String,
    val campus: String,
    val site: String,
    val roadmap: String,
    val graduation: String,
    val professors: List<String>,
    val announcements: List<String>,
)

/** A department board to scrape: code of the site it lives on, board name, list URL. */
data class SiteBoard(val code: String, val board: String, val category: String, val url: String, val origin: String)

/** A roadmap page: [key] stores it (the site code, or "code/department" when several majors share one site). */
data class SiteRoadmap(val key: String, val code: String, val department: String, val url: String)

private const val CONVERGENCE = "융복합전공"

// School CMS paths: https://{sub}.catholic.ac.kr/{site}/{section}/{page}.do
private val CMS = Regex("""^https?://([A-Za-z0-9-]+)\.catholic\.ac\.kr/([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)\.do$""")

/** Pages listed as boards that aren't notice lists (FAQs, galleries, scholarships, faculty) */
private val NOT_A_BOARD = Regex("faq|gallery|honorary|scholarship", RegexOption.IGNORE_CASE)

/**
 * Site code for a URL: the CMS site segment ("aibme", "BLE" → "ble", "major_convergence"),
 * or the subdomain for other layouts (songsin.catholic.ac.kr/ko/… → "songsin").
 */
fun siteCode(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val sub = host.removeSuffix(".catholic.ac.kr").takeIf { it != host }?.removePrefix("www.") ?: return null
    val first = uri.path.orEmpty().trim('/').substringBefore('/').lowercase()
    return when {
        first.isEmpty() || first == "ko" || first.endsWith(".do") -> sub
        else -> first.replace('-', '_')
    }
}

/** Board category from its page name (school boards are categorised for the app's filters). */
fun boardCategory(board: String): String {
    val b = board.lowercase()
    return when {
        "recruit" in b -> "recruitment"
        listOf("career", "job", "employ").any { it in b } -> "career"
        listOf("contest", "external", "outside").any { it in b } -> "external"
        "graduate" in b -> "graduate"
        listOf("news", "event").any { it in b } -> "news"
        else -> "notice"
    }
}

class SiteRegistry(val entries: List<SiteEntry>) {

    /** Department name → its site code (only majors with at least one announcement board or professor page). */
    val codeByDepartment: Map<String, String> = run {
        val direct = entries.mapNotNull { e ->
            val url = e.announcements.firstOrNull() ?: e.professors.firstOrNull() ?: e.site.takeIf { it.isNotBlank() }
            url?.let { siteCode(it) }?.let { e.department to it }
        }.toMap()
        // Every 융복합전공 shares one site (notices and professors), also those entered without links or on other sites
        val convergence = direct.filterKeys { CONVERGENCE in it }.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: return@run direct
        direct + entries.filter { CONVERGENCE in it.department }.associate { it.department to convergence }
    }

    /** Site code → department names on it (several majors can share one site) */
    val departmentsByCode: Map<String, List<String>> = codeByDepartment.entries.groupBy({ it.value }, { it.key })

    /** Department shown for professors listed without one: the majors on the site, or "융복합전공" for the shared 융복합 site */
    fun facultyDepartments(code: String): List<String> {
        val names = departmentsByCode[code].orEmpty()
        return if (names.isNotEmpty() && names.all { CONVERGENCE in it }) listOf(CONVERGENCE) else names
    }

    fun entry(department: String): SiteEntry? = entries.firstOrNull { it.department == department }

    /** Every notice board once, with the site code of the major that lists it. */
    val boards: List<SiteBoard> = buildList {
        val seen = mutableSetOf<String>()
        val keys = mutableSetOf<String>()
        for (e in entries) for (url in e.announcements) {
            val m = CMS.matchEntire(url.trim()) ?: continue
            val (sub, site, section, page) = m.destructured
            if (section == "faculty" || NOT_A_BOARD.containsMatchIn(page)) continue
            val clean = url.trim().replaceFirst("http://", "https://")
            if (!seen.add(clean)) continue
            val code = site.lowercase().replace('-', '_')
            // Same page name in two sections of one site (community/notice and notice/notice) → keep both apart
            val board = if (keys.add("$code/$page")) page else "$section-$page".also { keys.add("$code/$it") }
            add(SiteBoard(code, board, boardCategory(page), clean, "https://$sub.catholic.ac.kr"))
        }
    }

    /** Site code → its professor pages (each page once) */
    val facultyPages: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        val seen = mutableSetOf<String>()
        for (e in this@SiteRegistry.entries) {
            val code = codeByDepartment[e.department] ?: continue
            for (url in e.professors) {
                val clean = url.trim().replaceFirst("http://", "https://")
                if (clean.isNotEmpty() && seen.add(clean)) getOrPut(code) { mutableListOf() }.add(clean)
            }
        }
    }

    /** Roadmap pages, one per major. Majors sharing a site get "code/department" keys so they don't overwrite each other. */
    val roadmaps: List<SiteRoadmap> = entries.mapNotNull { e ->
        val url = e.roadmap.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val code = codeByDepartment[e.department] ?: return@mapNotNull null
        val shared = (departmentsByCode[code]?.size ?: 1) > 1
        SiteRoadmap(if (shared) "$code/${e.department}" else code, code, e.department, url)
    }

    /** Board code → display name for the announcement filters ("국제경영학과·세무회계금융학과" for shared sites) */
    val majorNames: Map<String, String> = departmentsByCode.mapValues { (_, names) -> names.joinToString("·") }

    companion object {
        private val log = LoggerFactory.getLogger(SiteRegistry::class.java)

        fun parse(text: String): SiteRegistry {
            val items = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
            fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            fun JsonObject.list(k: String): List<String> = when (val v = this[k]) {
                is JsonArray -> v.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                null -> emptyList()
                else -> listOfNotNull(v.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }) // older files: one URL
            }
            return SiteRegistry(
                items.map { o ->
                    SiteEntry(o.s("department"), o.s("campus"), o.s("site"), o.s("roadmap"), o.s("graduation"), o.list("professors"), o.list("announcements"))
                }.filter { it.department.isNotEmpty() },
            )
        }

        /** Reads the registry file; a missing or broken file means no department sites (school-wide boards still work). */
        fun load(path: String): SiteRegistry {
            val file = File(path)
            if (!file.exists()) {
                log.warn("department site registry not found at {}: department boards, professors and roadmaps are off", file.absolutePath)
                return SiteRegistry(emptyList())
            }
            return runCatching { parse(file.readText()) }
                .onFailure { log.warn("department site registry unreadable: {}", it.message) }
                .getOrElse { SiteRegistry(emptyList()) }
                .also { log.info("department sites: {} majors, {} boards, {} faculty sites, {} roadmaps", it.entries.size, it.boards.size, it.facultyPages.size, it.roadmaps.size) }
        }
    }
}
