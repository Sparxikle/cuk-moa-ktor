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
        // Pages for adjunct, hospital or music teachers have an article number instead, some (의대, 간호대) nothing
        val articleNo = li.selectFirst("[data-article-no]")?.attr("data-article-no")?.ifEmpty { null }
        val page = pageUrl.substringAfter("//").substringBefore('?')
        val info = box.select(".b-info-box li").associate { item ->
            val label = item.selectFirst("span")?.clean().orEmpty()
            val p = item.selectFirst("p")
            val spans = p?.select("span")?.map { it.clean() }?.filter { it.isNotEmpty() }.orEmpty()
            label to (if (spans.isNotEmpty()) spans.joinToString("\u0000") else p?.clean().orEmpty())
        }
        kr.ac.catholic.cukmoa.model.FacultyMember(
            id = persNo ?: articleNo?.let { "art:$page:$it" } ?: "page:$page:$name",
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
            profileUrl = when {
                persNo != null -> "$pageUrl?mode=view&persNo=$persNo"
                articleNo != null -> "$pageUrl?mode=view&articleNo=$articleNo"
                else -> pageUrl
            },
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
    val image = { base.copy(imageUrl = content.selectFirst("img[src]")?.absUrl("src")?.ifEmpty { null }) }
    // Other layouts (headers over two rows, years without semesters, no header row, semesters as rows …)
    val other = { content.select("table").firstNotNullOfOrNull { gridRoadmap(it, legend) }?.let { base.copy(entries = it) } ?: image() }
    val table = content.selectFirst("table.cuk-table") ?: return other()

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
    return if (entries.isEmpty()) other() else base.copy(entries = entries.distinct())
}

private val YEAR_RE = Regex("""([1-9])\s*학년""")
private val TERM_RE = Regex("""([12])\s*학기""")

/** Year and term named in a header ("2-1학기", "3학년", "1학기", "의학과 2학년"); null when it names neither */
private fun periodOf(text: String): Pair<Int?, Int?>? {
    SEMESTER_RE.find(text)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    val year = YEAR_RE.find(text)?.groupValues?.get(1)?.toInt()
    val term = TERM_RE.find(text)?.groupValues?.get(1)?.toInt()
    return if (year == null && term == null) null else year to term
}

/** Course names in a cell: list items, "•" lines, or the whole cell. Credits "(3/3)", "(필2)" and notes "*…" are dropped. */
private fun cellCourses(cell: org.jsoup.nodes.Element, legend: Map<String, String>): List<Pair<String, List<String>>> {
    fun clean(raw: String) = raw.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
        .replace(Regex("\\s\\*.*$"), "")
        .replace(Regex("<[^>]*>"), "")
        .replace(Regex("\\s*\\([^()]*\\d[^()]*\\).*$"), "")
        .removePrefix("•").trim()
    fun names(text: String) =
        (if (Regex("""\(\d+/\d+""").containsMatchIn(text)) text.split(Regex(",\\s*")) else listOf(text))
            .map(::clean).filter { it.any(Char::isLetter) }
    val items = cell.select("li")
    if (items.isNotEmpty()) return items.flatMap { li ->
        val marks = li.select("span.subject").map { it.text().trim().let { k -> legend[k] ?: k } }
        val copy = li.clone()
        copy.select("span.subject").remove()
        copy.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("")) }
        names(copy.text()).map { it to marks }
    }
    val lines = cell.html().split(Regex("<br\\s*/?>")).map { Jsoup.parse(it).text().trim() }.filter { it.isNotEmpty() }
    return if (lines.count { it.startsWith("•") } >= 2) lines.filter { it.startsWith("•") }.flatMap { names(it) }.map { it to emptyList() }
    else names(lines.joinToString("")).map { it to emptyList() }
}

/**
 * Roadmap tables the classic layout doesn't cover. Cells are spread onto a grid (rowspan/colspan), then:
 * - columns get a period from the header rows above them (any number of rows; "1학년" alone = year only),
 *   or from td classes roadmap01…04 when there is no header row (two columns of one year = its two semesters);
 * - columns without a period are track labels;
 * - with no period in any column, rows carry the period ("1-1학기") and the column headers name the tracks (음악과).
 * Only years 1–4; a table running past 4학년 (약학 6년제) gives null so the page image is used instead.
 */
internal fun gridRoadmap(table: org.jsoup.nodes.Element, legend: Map<String, String>): List<kr.ac.catholic.cukmoa.model.MajorRoadmapEntry>? {
    val rows = table.select("tr").filter { it.parents().firstOrNull { p -> p.tagName() == "table" } == table }
    val grid = mutableMapOf<Pair<Int, Int>, org.jsoup.nodes.Element>()
    val origin = mutableListOf<Triple<Int, Int, org.jsoup.nodes.Element>>()
    rows.forEachIndexed { r, tr ->
        var c = 0
        for (cell in tr.children().filter { it.tagName() == "td" || it.tagName() == "th" }) {
            while ((r to c) in grid) c++
            val rs = cell.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
            val cs = cell.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
            for (dr in 0 until rs) for (dc in 0 until cs) grid[(r + dr) to (c + dc)] = cell
            origin += Triple(r, c, cell)
            c += cs
        }
    }
    val width = (grid.keys.maxOfOrNull { it.second } ?: return null) + 1
    fun text(e: org.jsoup.nodes.Element) = e.text().replace('\u00a0', ' ').trim()

    // Header rows: leading rows of th only, or without lists and with ≥ 2 period cells (성악과's td header)
    val headerRows = rows.indices.takeWhile { r ->
        val cells = rows[r].children()
        cells.all { it.tagName() == "th" } && rows[r].select("li").isEmpty() ||
            rows[r].select("li").isEmpty() && cells.count { periodOf(text(it)) != null } >= 2
    }
    val colPeriod = (0 until width).associateWith { c ->
        var year: Int? = null
        var term: Int? = null
        for (r in headerRows) grid[r to c]?.let { periodOf(text(it)) }?.let { (y, t) -> if (y != null) year = y; if (t != null) term = t }
        year?.let { it to term }
    }.filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
    if (headerRows.isEmpty()) {
        // No header row: td class "roadmap0Y" gives the year
        val yearOf = (0 until width).associateWith { c ->
            origin.firstOrNull { (_, oc, e) -> oc == c && e.tagName() == "td" }?.third?.className()
                ?.let { Regex("""roadmap0([1-4])""").find(it)?.groupValues?.get(1)?.toInt() }
        }.filterValues { it != null }.mapValues { it.value!! }
        yearOf.values.distinct().forEach { y ->
            val cols = yearOf.filterValues { it == y }.keys.sorted()
            cols.forEachIndexed { i, c -> colPeriod[c] = y to (if (cols.size == 2) i + 1 else null) }
        }
    }
    val allYears = colPeriod.values.map { it.first }
    if (allYears.any { it > 4 }) return null

    fun entry(track: String, year: Int, term: Int?, name: String, marks: List<String>) =
        if (term != null) kr.ac.catholic.cukmoa.model.MajorRoadmapEntry(track, kr.ac.catholic.cukmoa.model.SemesterKey.of(year, term), name, marks)
        else kr.ac.catholic.cukmoa.model.MajorRoadmapEntry(track, null, name, marks, year = year)

    val entries = mutableListOf<kr.ac.catholic.cukmoa.model.MajorRoadmapEntry>()
    val dataRows = rows.indices.filter { it !in headerRows }
    if (colPeriod.isNotEmpty()) {
        val labelCols = (0 until width).filter { it !in colPeriod }
        for ((r, c, cell) in origin) {
            if (r in headerRows || c in labelCols) continue
            val track = labelCols.mapNotNull { grid[r to it] }.distinct().map { text(it) }.filter { it.isNotEmpty() }.joinToString(" · ")
            if ("학점" in track) continue // credit totals row
            val span = cell.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
            val periods = (c until c + span).mapNotNull { colPeriod[it] }.distinct()
            for ((name, marks) in cellCourses(cell, legend)) for ((y, t) in periods) entries += entry(track, y, t, name, marks)
        }
    } else {
        // Semesters as rows: the row's "1-1학기" cell; tracks from the column headers ("성악(66학점)" → "성악")
        val trackOf = (0 until width).associateWith { c ->
            headerRows.mapNotNull { grid[it to c] }.lastOrNull()?.let { text(it).replace(Regex("\\([^)]*\\)"), "").trim() }.orEmpty()
        }
        val rowPeriod = dataRows.associateWith { r ->
            (0 until width).mapNotNull { grid[r to it] }.distinct().firstNotNullOfOrNull { e -> SEMESTER_RE.find(text(e))?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } }
        }
        if (rowPeriod.values.all { it == null } || rowPeriod.values.any { it != null && it.first > 4 }) return null
        for ((r, c, cell) in origin) {
            val (y, t) = rowPeriod[r] ?: continue
            if (periodOf(text(cell)) != null || cell.tagName() == "th") continue
            val span = cell.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
            val tracks = (c until c + span).map { trackOf[it].orEmpty() }.filter { it.isNotEmpty() }.distinct()
            if (tracks.any { it == "비고" }) continue
            val track = if (tracks.size == 1) tracks.single() else "공통"
            for ((name, marks) in cellCourses(cell, legend)) entries += entry(track, y, t, name, marks)
        }
    }
    return entries.distinct().takeIf { it.size >= 3 }
}

/** Pages that only link to other departments' roadmaps (자유전공학부): those links, else empty */
fun roadmapLinks(html: String, url: String): List<String> {
    val host = runCatching { java.net.URI(url).host }.getOrNull()
    return Jsoup.parse(html, url).select("#cms-content a[href*=roadmap], table a[href*=roadmap]").map { it.absUrl("href") }
        .filter { runCatching { java.net.URI(it).host }.getOrNull().let { h -> h != null && h != host } }.distinct()
}

private val YEAR_TERM = Regex("""([1-6])\s*-\s*([12])""")

/**
 * 융복합전공 curriculum pages (major_convergence/curriculum-info/…), the "교과과정" tab: the
 * 학년별 과목편성 table lists each course with its year-semester ("2-1, 2-2", "2-1/4-2") and area ("GH / 경영회계").
 * A course offered in several semesters appears once per semester. Courses marked "(고유)" in the 교육목적별 분류
 * table get the mark "고유과목" (they count toward the major's own requirements).
 */
fun parseConvergenceCurriculum(html: String, major: String, department: String, url: String): kr.ac.catholic.cukmoa.model.MajorRoadmap {
    val doc = Jsoup.parse(html, url)
    fun courseKey(s: String) = s.replace(Regex("\\(고유\\)|\\s"), "")
    val own = doc.select("table").firstOrNull { it.selectFirst("caption")?.text()?.contains("교육목적별") == true }
        ?.select("td")?.map { it.text().trim() }?.filter { it.endsWith("(고유)") }?.map { courseKey(it) }?.toSet().orEmpty()
    val table = doc.select("table").firstOrNull { it.selectFirst("caption")?.text()?.contains("학년별") == true }
    // Column order differs per major: find columns by header
    // (이수구분|과목번호|교과목명|시간/학점|이수학년/학기|비고(전공/영역) or 이수시기|교과목명|교과목번호|시간/학점|이수구분|개설전공|비고)
    val headers = table?.select("tr")?.firstOrNull { it.select("th").size >= 5 }?.select("th")?.map { it.text().filterNot(Char::isWhitespace) }.orEmpty()
    fun col(fallback: Int, match: (String) -> Boolean) = headers.indexOfFirst(match).takeIf { it >= 0 } ?: fallback
    val nameCol = col(2) { "교과목명" in it }
    val periodCol = col(4) { "이수" in it && ("학기" in it || "시기" in it) }
    val trackCol = col(5) { "개설전공" in it || "비고(전공" in it }
    val entries = table?.select("tr")?.mapNotNull { tr -> tr.select("td").map { it.text().trim() }.takeIf { it.size >= 5 } }
        ?.flatMap { cells ->
            val name = cells.getOrNull(nameCol).orEmpty().removeSuffix("(고유)").trim()
            if (name.isEmpty()) return@flatMap emptyList()
            val track = cells.getOrNull(trackCol)?.substringAfter('/', "")?.trim()?.ifEmpty { null } ?: cells.getOrNull(trackCol).orEmpty()
            val marks = if (courseKey(name) in own) listOf("고유과목") else emptyList()
            YEAR_TERM.findAll(cells.getOrNull(periodCol).orEmpty()).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                .filter { (y, _) -> y in 1..4 }.distinct()
                .map { (y, t) -> kr.ac.catholic.cukmoa.model.MajorRoadmapEntry(track, kr.ac.catholic.cukmoa.model.SemesterKey.of(y, t), name, marks) }
                .toList()
        }.orEmpty()
    return kr.ac.catholic.cukmoa.model.MajorRoadmap(
        major = major, department = department, url = url, entries = entries,
        legend = if (own.isNotEmpty()) mapOf("고유" to "고유과목") else emptyMap(),
    )
}

/**
 * Professor lists drawn as cards (융복합전공 주임교수·겸임·초빙 and similar pages): each li has p.name
 * (position in a span) and dt/dd pairs. No faculty number on these pages, so the id is made from the page and name.
 * Phone and e-mail are left out, as on the regular faculty pages.
 */
fun parseCardFaculty(html: String, major: String, pageUrl: String, position: String): List<kr.ac.catholic.cukmoa.model.FacultyMember> =
    Jsoup.parse(html, pageUrl).select("p.name").mapNotNull { nameEl ->
        val name = nameEl.ownText().trim().ifEmpty { return@mapNotNull null }
        // <li> card > … > div.text_cont (p.name, dl rows); div.bottom lists the degrees they teach
        val card = nameEl.parents().firstOrNull { it.tagName() == "li" } ?: nameEl.parent() ?: return@mapNotNull null
        val info = nameEl.parent()!!.select("> dl").filter { it.selectFirst("dt") != null }.associate { dl ->
            val dd = dl.selectFirst("dd")
            val items = dd?.select("li")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
            dl.selectFirst("dt")!!.text().filterNot { it.isWhitespace() } to (if (items.isNotEmpty()) items.joinToString("\n") else dd?.text()?.trim().orEmpty())
        }
        val degrees = card.select(".bottom li").map { it.text().trim() }.filter { it.isNotEmpty() }
        kr.ac.catholic.cukmoa.model.FacultyMember(
            id = "card:${pageUrl.substringAfter("//").substringBefore('?')}:$name",
            name = name,
            position = nameEl.selectFirst("span")?.text()?.trim()?.ifEmpty { null } ?: position,
            majors = listOf(major),
            departments = listOfNotNull(info["소속"]?.ifEmpty { null }),
            office = info["연구실위치"]?.ifEmpty { null },
            field = (info["연구분야"] ?: info["전문분야"])?.replace(Regex("[\\s\u00a0]*/[\\s\u00a0]*"), ", ")?.ifEmpty { null },
            keywords = (info["담당과목"]?.split(',', '\n') ?: degrees).map { it.trim() }.filter { it.isNotEmpty() },
            intro = listOfNotNull(info["경력"]?.ifEmpty { null }, info["학력"]?.ifEmpty { null }).joinToString("\n\n").ifEmpty { null },
            profileUrl = pageUrl,
        )
    }
