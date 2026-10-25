package kr.ac.catholic.cukmoa.server.scrape

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// Board list HTML → RawRow. Selectors are the same as test.py (the original Python collector).

/** One list row, before being combined with board info. */
data class RawRow(
    val title: String,
    /** YYYY-MM-DD */
    val date: String,
    val author: String,
    val views: Long?,
    val href: String,
    val image: String? = null,
    val pinned: Boolean,
    val category: String? = null,
)

private val DATE_RE = Regex("""(\d{4})[.\-/](\d{2})[.\-/](\d{2})""")
private val ARTICLE_RE = Regex("""(?:articleNo|ARTL_NUM)=(\d+)""")

/** "2026.10.16" / "2026-10-16" → "2026-10-16" */
fun isoDate(s: String): String? = DATE_RE.find(s)?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }

/** Only when the whole cell is a date (dates inside titles are ignored). */
private fun isoDateOnly(s: String): String? = DATE_RE.matchEntire(s)?.let { isoDate(s) }

fun makeLink(pageUrl: String, origin: String, href: String): String = when {
    href.startsWith("?") -> pageUrl + href
    href.startsWith("/") -> origin + href
    else -> href
}

/** Post number for dedupe. Links carry article.offset, which changes when pages shift, so links aren't used for dedupe. */
fun articleNo(link: String): String = ARTICLE_RE.find(link)?.groupValues?.get(1) ?: link

/** Collapse whitespace to single spaces (Python " ".join(x.split())). */
private fun Element.clean(): String = text().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

private fun views(s: String): Long? = s.trim().replace(",", "").toLongOrNull()

/** e-cyber LMS notices (list fetched via POST, 20 rows each). This year's posts appear without a year, like "10.16". */
fun parseLms(html: String, year: Int): List<RawRow> {
    val tbody = Jsoup.parse(html).selectFirst("tbody") ?: return emptyList()
    return tbody.select("tr").mapNotNull { tr ->
        val cols = tr.select("td")
        if (cols.size < 6) return@mapNotNull null
        val a = cols[2].selectFirst("a") ?: return@mapNotNull null
        val datePart = cols[4].clean().substringBefore(' ')
        val date = if (datePart.length == 5) "$year-${datePart.replace('.', '-')}" else isoDate(datePart) ?: return@mapNotNull null
        RawRow(
            title = a.clean(),
            date = date,
            author = cols[3].clean(),
            views = views(cols[5].text()),
            href = a.attr("href"),
            pinned = cols[0].selectFirst("img") != null,
        )
    }
}

/** School notices (www.catholic.ac.kr notice.do). Has a category column (일반, 학사 …). */
fun parseMainNotice(html: String): List<RawRow> {
    val tbody = Jsoup.parse(html).selectFirst("tbody") ?: return emptyList()
    return tbody.select("tr").mapNotNull { tr ->
        val cols = tr.select("td")
        if (cols.size < 6) return@mapNotNull null
        val a = cols[2].selectFirst("a.b-title") ?: return@mapNotNull null
        val date = isoDate(cols[3].clean()) ?: return@mapNotNull null
        RawRow(
            title = a.clean(),
            date = date,
            author = cols[4].clean(),
            views = views(cols[5].text()),
            href = a.attr("href"),
            pinned = cols[0].selectFirst("span.b-num-notice") != null,
            category = cols[1].clean().ifEmpty { null },
        )
    }
}

/** External contests and events (notice_outside.do). */
fun parseOutside(html: String): List<RawRow> =
    Jsoup.parse(html).select("div.bn-list-common01 table tbody tr").mapNotNull { tr ->
        val cols = tr.select("td")
        if (cols.size < 5) return@mapNotNull null
        val a = tr.selectFirst("a.b-title") ?: return@mapNotNull null
        val date = isoDate(cols[2].clean()) ?: return@mapNotNull null
        RawRow(
            title = a.clean(),
            date = date,
            author = cols[3].clean(),
            views = views(cols[4].text()),
            href = a.attr("href"),
            pinned = cols[0].selectFirst("span.b-num-notice") != null,
        )
    }

/** School events (notice_event.do, photo board). */
fun parseEvents(html: String): List<RawRow> =
    Jsoup.parse(html).select("li.b-img-con-box").mapNotNull { item ->
        val date = item.selectFirst("span.b-date")?.let { isoDate(it.clean()) } ?: return@mapNotNull null
        RawRow(
            title = item.selectFirst("div.b-img-title span")?.clean().orEmpty(),
            date = date,
            author = item.selectFirst("span.b-writer")?.clean().orEmpty(),
            views = item.selectFirst("span.b-view")?.let { views(it.text()) },
            href = item.selectFirst("a")?.attr("href").orEmpty(),
            image = item.selectFirst("div.b-img-box img")?.attr("src")?.ifEmpty { null },
            pinned = false,
        )
    }

/** AI, Electronics & Information Convergence department boards (table or photo layout). */
fun parseDept(html: String): List<RawRow> {
    val doc = Jsoup.parse(html)
    val items = doc.select("table tbody tr").ifEmpty { doc.select("li.b-img-con-box") }
    return items.mapNotNull { el ->
        val a = el.selectFirst("a.b-title") ?: el.selectFirst("a[href]") ?: return@mapNotNull null
        // Find the date-only cell first so dates inside titles aren't picked up.
        val date = (el.select("span.b-date, td.b-td-date") + el.select("td")).firstNotNullOfOrNull { isoDateOnly(it.clean()) }
            ?: isoDate(el.clean())
            ?: return@mapNotNull null
        RawRow(
            title = (el.selectFirst("div.b-img-title span") ?: a).clean(),
            date = date,
            author = el.selectFirst(".b-writer")?.clean().orEmpty(),
            views = el.selectFirst(".b-view")?.let { views(it.text()) },
            href = a.attr("href"),
            image = el.selectFirst("div.b-img-box img")?.attr("src")?.ifEmpty { null },
            pinned = el.selectFirst(".b-num-notice") != null || el.hasClass("b-top-box"),
        )
    }
}

private val DAY_RE = Regex("""^(\d{1,2})""")
private val MONTH_DAY_RE = Regex("""^(\d{1,2})\.(\d{1,2})""")
private val MONTH_RE = Regex("""(?:(\d{4})년)?\s*(\d{1,2})월""")

/** One academic calendar row */
data class CalendarRow(val title: String, val start: String, val end: String?, val campuses: List<String>)

/**
 * School academic calendar (calendar2024_list.do, selectedYear=academic year).
 * March through February of the next year. End dates appear as "13 (금)" within the same month, "07.13(월)" for another month.
 */
fun parseCalendar(html: String, academicYear: Int): List<CalendarRow> {
    val box = Jsoup.parse(html).selectFirst("div.b-cal-list-box") ?: return emptyList()
    val out = mutableListOf<CalendarRow>()
    for (monthBlock in box.children()) {
        // "3월", or "2027년 1월" once it rolls into the next year
        val head = MONTH_RE.find(monthBlock.selectFirst("> p")?.text().orEmpty()) ?: continue
        val month = head.groupValues[2].toInt()
        val year = head.groupValues[1].toIntOrNull() ?: if (month < 3) academicYear + 1 else academicYear
        for (entry in monthBlock.select("> div > div")) {
            val dateP = entry.selectFirst("> p") ?: continue
            val parts = dateP.text().split("~").map { it.trim() }
            val startDay = DAY_RE.find(parts[0])?.groupValues?.get(1)?.toInt() ?: continue
            val start = iso(year, month, startDay)
            val end = parts.getOrNull(1)?.let { e ->
                MONTH_DAY_RE.find(e)?.let { m ->
                    val em = m.groupValues[1].toInt()
                    iso(if (em < month) year + 1 else year, em, m.groupValues[2].toInt())
                } ?: DAY_RE.find(e)?.groupValues?.get(1)?.toInt()?.let { iso(year, month, it) }
            }
            for (li in entry.select("ul > li")) {
                val campuses = li.select("div.b-cal-list-cate span").map { it.text().trim() }
                val title = li.ownText().trim().ifEmpty { li.clean() }
                if (title.isNotEmpty()) out += CalendarRow(title, start, end?.takeIf { it != start }, campuses)
            }
        }
    }
    return out
}

private fun iso(y: Int, m: Int, d: Int) = "%04d-%02d-%02d".format(y, m, d)

/**
 * Professor profiles on department websites (faculty.do / teacher.do). All full-time faculty appear on one page.
 * Photos (base64) and contact details aren't collected — the app links to the profile.
 */
fun parseFaculty(html: String, major: String, pageUrl: String): List<kr.ac.catholic.cukmoa.model.FacultyMember> =
    Jsoup.parse(html).select("div.bn-list-faculty > ul > li").mapNotNull { li ->
        val box = li.selectFirst(".b-prof-con-box") ?: return@mapNotNull null
        val name = box.selectFirst(".b-name")?.clean()?.ifEmpty { null } ?: return@mapNotNull null
        val persNo = li.selectFirst("a[href*=persNo]")?.attr("href")?.substringAfter("persNo=")?.substringBefore("&")
            ?: return@mapNotNull null
        val info = box.select(".b-info-box li").associate { item ->
            val label = item.selectFirst("span")?.clean().orEmpty()
            val p = item.selectFirst("p")
            val spans = p?.select("span")?.map { it.clean() }?.filter { it.isNotEmpty() }.orEmpty()
            label to (if (spans.isNotEmpty()) spans.joinToString("\u0000") else p?.clean().orEmpty())
        }
        kr.ac.catholic.cukmoa.model.FacultyMember(
            id = persNo,
            name = name,
            nameEn = box.selectFirst(".b-name-en")?.clean()?.ifEmpty { null },
            position = box.select("p.b-name-ko > span").firstOrNull { !it.hasClass("b-name") }?.clean().orEmpty(),
            // Roles differ per department, so the department name is attached (so they don't mix when merging joint appointments)
            role = box.selectFirst(".b-prof-cate")?.clean()?.ifEmpty { null }?.let { r -> listOfNotNull(info["소속학과"]?.ifEmpty { null }, r).joinToString(" ") },
            majors = listOf(major),
            departments = listOfNotNull(info["소속학과"]?.ifEmpty { null }),
            office = info["연구실"]?.ifEmpty { null },
            field = info["연구분야"]?.split(Regex("\\s{2,}")).orEmpty().joinToString(", ").ifEmpty { null },
            keywords = info["연구키워드"]?.split('\u0000')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            intro = box.selectFirst("pre.introKoList")?.text()?.trim()?.ifEmpty { null },
            profileUrl = "$pageUrl?mode=view&persNo=$persNo",
        )
    }

/** Merges a professor listed in several departments (joint appointment) into one. Uses the first department's info and adds departments and roles. */
fun mergeFaculty(lists: List<List<kr.ac.catholic.cukmoa.model.FacultyMember>>): List<kr.ac.catholic.cukmoa.model.FacultyMember> =
    lists.flatten().groupBy { it.id }.values.map { same ->
        same.first().copy(
            majors = same.flatMap { it.majors }.distinct(),
            departments = same.flatMap { it.departments }.distinct(),
            role = same.mapNotNull { it.role }.distinct().joinToString(", ").ifEmpty { null },
        )
    }

private val SEMESTER_RE = Regex("""([1-4])\s*-\s*([12])""")

/**
 * Department major roadmap (course/roadmap.do).
 * The table merges cells with rowspan/colspan, so cells are spread onto a grid to find column → semester and row → track.
 * If there's no table, only an image, returns just the image address.
 */
fun parseMajorRoadmap(html: String, major: String, department: String, url: String): kr.ac.catholic.cukmoa.model.MajorRoadmap {
    val doc = Jsoup.parse(html, url)
    val content = doc.selectFirst("#cms-content") ?: doc
    val legend = content.select("ul.subject-ul > li").mapNotNull { li ->
        val key = li.selectFirst("span.subject")?.text()?.trim() ?: return@mapNotNull null
        key to li.ownText().trim()
    }.toMap()
    val base = kr.ac.catholic.cukmoa.model.MajorRoadmap(major = major, department = department, url = url, legend = legend)
    val table = content.selectFirst("table.cuk-table")
        ?: return base.copy(imageUrl = content.selectFirst("img[src]")?.absUrl("src")?.ifEmpty { null })

    // Header row: column number → semester (the first column is the track name)
    val semesterByCol = mutableMapOf<Int, kr.ac.catholic.cukmoa.model.SemesterKey>()
    var col = 0
    for (th in table.select("thead tr").first()?.children().orEmpty()) {
        val span = th.attr("colspan").toIntOrNull() ?: 1
        SEMESTER_RE.find(th.text())?.let { m ->
            val key = kr.ac.catholic.cukmoa.model.SemesterKey.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            repeat(span) { semesterByCol[col + it] = key }
        }
        col += span
    }

    val occupied = mutableSetOf<Pair<Int, Int>>()
    val trackByRow = mutableMapOf<Int, String>()
    val entries = mutableListOf<kr.ac.catholic.cukmoa.model.MajorRoadmapEntry>()
    table.select("tbody > tr").forEachIndexed { r, tr ->
        var c = 0
        for (cell in tr.children()) {
            while ((r to c) in occupied) c++
            val rs = cell.attr("rowspan").toIntOrNull() ?: 1
            val cs = cell.attr("colspan").toIntOrNull() ?: 1
            for (dr in 0 until rs) for (dc in 0 until cs) occupied += (r + dr) to (c + dc)
            if (cell.tagName() == "th") {
                val name = cell.text().replace(Regex("\\s+"), " ").trim()
                for (dr in 0 until rs) trackByRow[r + dr] = name
            } else {
                for (li in cell.select("li")) {
                    val marks = li.select("span.subject").map { s -> s.text().trim().let { legend[it] ?: it } }
                    val copy = li.clone()
                    copy.select("span.subject").remove()
                    copy.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("")) } // Line breaks are joined
                    val name = copy.text().replace(Regex("\\s+"), " ").trim()
                    if (name.isEmpty()) continue
                    for (dc in 0 until cs) {
                        val semester = semesterByCol[c + dc] ?: continue
                        entries += kr.ac.catholic.cukmoa.model.MajorRoadmapEntry(trackByRow[r].orEmpty(), semester, name, marks)
                    }
                }
            }
            c += cs
        }
    }
    return base.copy(entries = entries.distinct())
}
