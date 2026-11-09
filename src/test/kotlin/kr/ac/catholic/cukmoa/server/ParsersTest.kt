package kr.ac.catholic.cukmoa.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kr.ac.catholic.cukmoa.model.FacultyMember
import kr.ac.catholic.cukmoa.model.SemesterKey
import kr.ac.catholic.cukmoa.server.scrape.parseFaculty
import kr.ac.catholic.cukmoa.server.scrape.parseMajorRoadmap
import kr.ac.catholic.cukmoa.server.scrape.roadmapLinks
import kr.ac.catholic.cukmoa.server.scrape.mergeCardFaculty
import kr.ac.catholic.cukmoa.server.scrape.parseCardFaculty
import kr.ac.catholic.cukmoa.server.scrape.parseConvergenceCurriculum
import kr.ac.catholic.cukmoa.server.scrape.RawRow
import kr.ac.catholic.cukmoa.server.scrape.articleNo
import kr.ac.catholic.cukmoa.server.scrape.isoDate
import kr.ac.catholic.cukmoa.server.scrape.makeLink
import kr.ac.catholic.cukmoa.server.scrape.parseDept
import kr.ac.catholic.cukmoa.server.scrape.parseEvents
import kr.ac.catholic.cukmoa.server.scrape.parseLms
import kr.ac.catholic.cukmoa.server.scrape.parseMainNotice
import kr.ac.catholic.cukmoa.server.scrape.parseOutside

// Checks selectors against real board HTML (saved 2026-10-25). If the school site changes, this breaks first.
class ParsersTest {
    private fun fixture(name: String) = javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    private fun assertWellFormed(rows: List<RawRow>, minRows: Int) {
        assertTrue(rows.size >= minRows, "expected at least $minRows rows, got ${rows.size}")
        rows.forEach {
            assertTrue(it.title.isNotBlank(), "blank title")
            assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(it.date), "bad date ${it.date}")
            assertTrue(it.href.isNotBlank(), "blank href for ${it.title}")
        }
    }

    @Test
    fun `department board table`() {
        val rows = parseDept(fixture("dept_csie_notice.html"))
        assertWellFormed(rows, 10)
        assertTrue(rows.any { it.pinned }, "csie notice has pinned posts")
        assertTrue(rows.all { it.views != null })
    }

    @Test
    fun `main site notice keeps its category`() {
        val rows = parseMainNotice(fixture("main_notice.html"))
        assertWellFormed(rows, 10)
        assertTrue(rows.all { !it.category.isNullOrBlank() })
    }

    @Test
    fun `events gallery and outside board`() {
        assertWellFormed(parseEvents(fixture("events.html")), 1)
        assertWellFormed(parseOutside(fixture("outside.html")), 10)
    }

    @Test
    fun `lms dates without year get the current year`() {
        val rows = parseLms(fixture("lms.html"), 2026)
        assertWellFormed(rows, 10)
        assertTrue(rows.all { it.href.startsWith("/") })
    }

    @Test
    fun `helpers`() {
        assertEquals("2026-10-16", isoDate("2026.10.16"))
        assertEquals("276825", articleNo("https://x/notice.do?mode=view&articleNo=276825&article.offset=10"))
        assertEquals("5866978", articleNo("https://x/notice_view_form.acl?ARTL_NUM=5866978&start=1"))
        assertEquals("https://a/b.do?mode=view", makeLink("https://a/b.do", "https://a", "?mode=view"))
        assertEquals("https://a/img.png", makeLink("https://a/b.do", "https://a", "/img.png"))
    }

    @Test
    fun `융복합전공 curriculum tab becomes a roadmap`() {
        val r = parseConvergenceCurriculum(fixture("convergence_global.html"), "major_convergence/글로벌인문경영융복합전공", "글로벌인문경영융복합전공", "https://major-convergence.catholic.ac.kr/major_convergence/curriculum-info/global.do")
        println(r.entries.joinToString("\n"))
        assertTrue(r.entries.size >= 10)
        assertTrue(r.entries.all { it.name.isNotBlank() && "(고유)" !in it.name })
        assertTrue(r.entries.any { "고유과목" in it.marks })
    }

    @Test
    fun `융복합전공 professor cards`() {
        val url = "https://major-convergence.catholic.ac.kr/major_convergence/undergraduate-info/faculty.do"
        val main = parseCardFaculty(fixture("convergence_faculty.html"), "major_convergence", url, "주임교수")
        val adjunct = parseCardFaculty(fixture("convergence_adjunct.html"), "major_convergence", url.replace("faculty", "adjunct"), "겸임·초빙교수")
        println((main + adjunct).joinToString("\n"))
        assertTrue(main.isNotEmpty() && adjunct.isNotEmpty())
        assertTrue(main.any { it.name == "김유중" })
        assertTrue((main + adjunct).all { it.majors == listOf("major_convergence") && it.id.startsWith("card:") })
    }

    @Test
    fun `card professors with a home profile merge into it`() {
        val home = FacultyMember(id = "123", name = "김유중", position = "교수", majors = listOf("csie"), departments = listOf("컴퓨터정보공학부"), profileUrl = "")
        val card = FacultyMember(id = "card:x:김유중", name = "김유중", position = "주임교수", majors = listOf("major_convergence"), departments = listOf("컴퓨터정보공학부"), profileUrl = "")
        val other = FacultyMember(id = "card:x:홍길동", name = "홍길동", position = "주임교수", majors = listOf("major_convergence"), departments = emptyList(), profileUrl = "")
        val merged = mergeCardFaculty(listOf(home, card, other))
        assertEquals(listOf("123", "card:x:홍길동"), merged.map { it.id })
        assertEquals(listOf("csie", "major_convergence"), merged[0].majors)
    }

    private fun roadmap(name: String) = parseMajorRoadmap(fixture("roadmap_$name.html"), name, name, "https://$name.catholic.ac.kr/$name/course/roadmap.do")

    @Test
    fun `roadmaps with year-only headers, two header rows, no header or td headers`() {
        val ble = roadmap("ble")
        assertTrue(ble.entries.any { it.year == 1 && it.semester == null && it.name == "바이오로직스공학개론" && it.track == "공통이수과목" && it.marks.isNotEmpty() })
        val philosophy = roadmap("philosophy")
        assertTrue(philosophy.entries.any { it.semester == SemesterKey.S2_1 && it.name == "논리학" && it.track == "기초" })
        // No header row: td class roadmap01…04, two columns per year
        val design = roadmap("design")
        assertTrue(design.entries.any { it.semester == SemesterKey.S4_2 && it.name == "소비자학 캡스톤디자인" })
        val fn = roadmap("fn")
        assertTrue(fn.entries.any { it.semester == SemesterKey.S4_1 && it.name == "임상영양학" })
        val voice = roadmap("voice")
        assertTrue(voice.entries.any { it.semester == SemesterKey.S4_2 && it.name == "졸업연주" })
        listOf("business", "children", "med1", "med2").forEach { assertTrue(roadmap(it).entries.size >= 20, it) }
    }

    @Test
    fun `music roadmap has semesters as rows and majors as columns`() {
        val music = roadmap("music")
        assertTrue(music.entries.any { it.semester == SemesterKey.S1_1 && it.track == "공통" && it.name == "시창1" })
        assertTrue(music.entries.any { it.semester == SemesterKey.S4_1 && it.track == "작곡" && it.name == "재즈화성" })
        assertTrue(music.entries.none { it.track == "비고" })
    }

    @Test
    fun `nursing curriculum skips credits and credit totals`() {
        val nursing = roadmap("nursing")
        assertTrue(nursing.entries.any { it.semester == SemesterKey.S1_1 && it.name == "생물학" && it.track == "교양필수" })
        assertTrue(nursing.entries.none { it.name.all { c -> c.isDigit() || c == '.' } })
    }

    @Test
    fun `six-year pharmacy table falls back to the curriculum image, 자유전공 only links`() {
        val pharmacy = roadmap("pharmacy")
        assertTrue(pharmacy.entries.isEmpty())
        assertEquals("https://pharmacy.catholic.ac.kr/_res/cuk/pharmacy/img/content/curriculum.jpg", pharmacy.imageUrl)
        assertTrue(roadmap("liberal").entries.isEmpty())
        assertTrue(roadmapLinks(fixture("roadmap_liberal.html"), "https://liberal.catholic.ac.kr/liberal/course/roadmap.do").size > 30)
    }

    @Test
    fun `faculty pages without faculty numbers use the article number or the name`() {
        val voice = parseFaculty(fixture("faculty_voice.html"), "voice", "https://voice.catholic.ac.kr/voice/faculty/faculty.do")
        val head = voice.first { it.name == "이재완" }
        assertEquals("art:voice.catholic.ac.kr/voice/faculty/faculty.do:271044", head.id)
        assertEquals("https://voice.catholic.ac.kr/voice/faculty/faculty.do?mode=view&articleNo=271044", head.profileUrl)
        assertEquals(7, voice.size)
        val nursing = parseFaculty(fixture("faculty_nursing.html"), "nursing", "https://nursing.catholic.ac.kr/nursing/research/DCN2.do")
        assertEquals(7, nursing.size)
        assertTrue(nursing.all { it.id.startsWith("page:") && it.profileUrl == "https://nursing.catholic.ac.kr/nursing/research/DCN2.do" })
    }

    @Test
    fun `융복합전공 curriculum with columns in another order`() {
        val r = parseConvergenceCurriculum(fixture("convergence_big_data.html"), "major_convergence/빅데이터인문경영융복합전공", "빅데이터인문경영융복합전공", "https://major-convergence.catholic.ac.kr/major_convergence/curriculum-info/big_data.do")
        assertTrue(r.entries.size >= 30)
        assertTrue(r.entries.any { it.name == "빅데이터분석의기초" && it.semester == SemesterKey.S2_2 && it.track == "융복합전공" })
    }
}
