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
