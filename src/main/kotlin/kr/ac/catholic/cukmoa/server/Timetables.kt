package kr.ac.catholic.cukmoa.server

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kr.ac.catholic.cukmoa.logic.WEEKDAYS
import kr.ac.catholic.cukmoa.logic.courseFor
import kr.ac.catholic.cukmoa.logic.creditSummary
import kr.ac.catholic.cukmoa.logic.findCourse
import kr.ac.catholic.cukmoa.logic.graduationPlan
import kr.ac.catholic.cukmoa.logic.hasCompleted
import kr.ac.catholic.cukmoa.logic.isMorning
import kr.ac.catholic.cukmoa.logic.overlaps
import kr.ac.catholic.cukmoa.logic.sameCourseKey
import kr.ac.catholic.cukmoa.logic.label
import kr.ac.catholic.cukmoa.logic.subjects
import kr.ac.catholic.cukmoa.logic.timeFit
import kr.ac.catholic.cukmoa.logic.timetableProblems
import kr.ac.catholic.cukmoa.model.ApiJson
import kr.ac.catholic.cukmoa.model.Catalog
import kr.ac.catholic.cukmoa.model.CreditRules
import kr.ac.catholic.cukmoa.model.OpenClass
import kr.ac.catholic.cukmoa.model.TimePreference
import kr.ac.catholic.cukmoa.model.TimetableItem
import kr.ac.catholic.cukmoa.model.TimetablePlan
import kr.ac.catholic.cukmoa.model.TimetablePrefs
import kr.ac.catholic.cukmoa.model.TimetableRequest
import kr.ac.catholic.cukmoa.model.TimetableResponse
import kr.ac.catholic.cukmoa.server.db.Database
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException

// AI timetable maker (POST /api/v1/timetables).
// The server picks candidate classes, asks the AI for 3 timetables, checks every one with timetableProblems,
// and sends the problems back for the AI to fix. Only valid timetables reach the app.

/** 503 AI_UNAVAILABLE */
class ServiceUnavailable(message: String) : Exception(message)

/** 429 RATE_LIMITED */
class TooManyRequests(message: String) : Exception(message)

/** One turn of the conversation with the AI: role "user" or "model". */
data class AiTurn(val role: String, val text: String)

/** The model behind the timetable maker. Returns the JSON text of {"plans": [...]}. Swappable (tests use a fake). */
interface TimetableAi {
    fun generate(system: String, turns: List<AiTurn>): String
}

@Serializable
internal data class AiPlans(val plans: List<TimetablePlan> = emptyList())

private const val PLANS = 3
/** Asked per round: a small model gets some wrong, so more drafts leave enough valid ones to pick from */
private const val DRAFTS = 3
private const val MAX_AI_CALLS = 3
/** "월8~9,수5~6" style time mentions in a reason */
private val TIME_TEXT = Regex("[월화수목금토]\\d+(~\\d+)?(\\s*,\\s*[월화수목금토]?\\d+(~\\d+)?)*\\s*(교시)?")

/** Free-tier quota guard: AI calls per minute and per day (KST) across the server, timetables per IP per hour. */
class TimetableLimiter(
    private val perMinute: Int = 12,
    private val perDay: Int = 450,
    private val perIpHour: Int = 10,
    private val now: () -> Instant = Instant::now,
) {
    private val calls = ArrayDeque<Instant>()
    private var day: LocalDate? = null
    private var dayCount = 0
    private val ips = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    @Synchronized
    fun requestFrom(ip: String) {
        val t = now()
        val q = ips.getOrPut(ip) { ArrayDeque() }
        while (q.isNotEmpty() && Duration.between(q.first(), t) >= Duration.ofHours(1)) q.removeFirst()
        if (q.size >= perIpHour) throw TooManyRequests("시간표는 한 시간에 ${perIpHour}번까지 만들 수 있어요. 잠시 뒤에 다시 해 주세요.")
        q.addLast(t)
    }

    /** Before every AI call. */
    @Synchronized
    fun aiCall() {
        val t = now()
        val today = t.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        if (today != day) { day = today; dayCount = 0 }
        while (calls.isNotEmpty() && Duration.between(calls.first(), t) >= Duration.ofMinutes(1)) calls.removeFirst()
        if (dayCount >= perDay) throw TooManyRequests("오늘 만들 수 있는 시간표를 모두 썼어요. 내일 다시 해 주세요.")
        if (calls.size >= perMinute) throw TooManyRequests("지금 시간표를 만드는 사람이 많아요. 1분 뒤에 다시 해 주세요.")
        calls.addLast(t)
        dayCount++
    }
}

class TimetableService(
    private val db: Database,
    private val catalog: Catalog,
    private val ai: TimetableAi?,
    private val limiter: TimetableLimiter = TimetableLimiter(),
) {
    private val log = LoggerFactory.getLogger(TimetableService::class.java)
    private val cache = ConcurrentHashMap<String, Pair<Instant, TimetableResponse>>()

    fun suggest(req: TimetableRequest, ip: String): TimetableResponse {
        val p = req.prefs
        val cap = CreditRules.max(p.highGpa)
        if (p.minCredits !in CreditRules.MIN..cap || p.maxCredits !in p.minCredits..cap) {
            throw BadRequest("학점은 ${CreditRules.MIN}~${cap}학점 사이로 골라요${if (p.highGpa) "" else " (직전 학기 성적 우수면 ${CreditRules.MAX_HONORS}학점까지)"}.")
        }
        if (p.freeDays.any { it !in WEEKDAYS }) throw BadRequest("공강 요일은 월~토 중에서 골라요.")
        if ((p.note?.length ?: 0) > 200) throw BadRequest("요청은 200자까지 쓸 수 있어요.")
        val ai = ai ?: throw ServiceUnavailable("AI 시간표 기능이 서버에 설정되지 않았어요.")
        val (year, semester, _) = db.latestClassTerm() ?: throw ServiceUnavailable("이번 학기 개설과목이 아직 없어요.")
        val all = db.classes(year, semester)

        val key = ApiJson.encodeToString(TimetableRequest.serializer(), req)
        cache[key]?.let { (at, res) -> if (Duration.between(at, Instant.now()) < Duration.ofMinutes(10)) return res }
        limiter.requestFrom(ip)

        val candidates = candidates(req, all)
        if (candidates.isEmpty()) throw BadRequest("조건에 맞는 개설과목이 없어요. 공강 요일을 줄여 보세요.")
        val turns = mutableListOf(AiTurn("user", prompt(req, candidates)))
        val valid = mutableListOf<TimetablePlan>()
        for (round in 1..MAX_AI_CALLS) {
            // A later round failing (quota, network) still returns the valid timetables found so far
            val text = try {
                limiter.aiCall()
                ai.generate(SYSTEM + outputLanguage(p.language), turns)
            } catch (e: Exception) {
                if (valid.isNotEmpty() && (e is TooManyRequests || e is ServiceUnavailable || e is java.io.IOException)) break
                if (e is java.io.IOException) throw ServiceUnavailable("AI 서버에 연결하지 못했어요.")
                throw e
            }
            val plans = runCatching { ApiJson.decodeFromString(AiPlans.serializer(), text).plans }.getOrElse {
                log.warn("timetable AI returned unreadable JSON: {}", text.take(200))
                emptyList()
            }
            val feedback = mutableListOf<String>()
            // Small models slip on period arithmetic: fix clashes and credit totals here, keeping the AI's picks and order
            plans.map { repair(it, plans, candidates, p) }.forEachIndexed { i, plan ->
                val problems = timetableProblems(plan, all, p, req.student, catalog)
                val same = valid.any { v -> v.items.map { it.subjectNo to it.classNo }.toSet() == plan.items.map { it.subjectNo to it.classNo }.toSet() }
                when {
                    problems.isEmpty() && !same -> valid += plan
                    problems.isNotEmpty() -> feedback += "시간표 ${i + 1} (${plan.title}): " + problems.joinToString("; ")
                }
            }
            log.info("timetable round {}: {} plans, {} valid so far", round, plans.size, valid.size)
            feedback.forEach { log.debug("timetable problem: {}", it) }
            if (valid.size >= PLANS || round == MAX_AI_CALLS) break
            turns += AiTurn("model", text)
            turns += AiTurn(
                "user",
                buildString {
                    appendLine("다음 문제를 고쳐서 시간표를 다시 ${DRAFTS}개 만들어 주세요. 유효한 시간표가 ${PLANS - valid.size}개 더 필요해요. 문제 있는 과목은 빼거나 다른 분반·과목으로 바꿔요.")
                    if (feedback.isEmpty()) appendLine("(응답에 시간표가 부족했어요.)")
                    feedback.forEach { appendLine("- $it") }
                },
            )
        }
        if (valid.isEmpty()) throw ServiceUnavailable("조건에 맞는 시간표를 만들지 못했어요. 학점 범위나 공강 요일을 바꿔 보세요.")
        // Plans that cover every major first, then the closest to the time preference
        val ranked = valid.sortedWith(compareByDescending<TimetablePlan> { majorsCovered(it, all, req.student) }.thenByDescending { it.timeFit(all, p.time) })
        val res = TimetableResponse(year, semester, ranked.take(PLANS))
        cache[key] = Instant.now() to res
        return res
    }

    /**
     * Makes a draft fit: walks the AI's classes in order, swapping to another 분반 of the same subject on a clash
     * and skipping what can't fit or would pass the max credits. Below the min, it fills with classes the AI picked
     * in its other drafts, then with the rest of the candidates (preferred time first).
     */
    internal fun repair(plan: TimetablePlan, drafts: List<TimetablePlan>, candidates: List<OpenClass>, prefs: TimetablePrefs): TimetablePlan {
        val kept = mutableListOf<Pair<OpenClass, String>>()
        fun credits() = kept.sumOf { it.first.credits ?: 0 }
        fun fits(k: OpenClass) = kept.none { (o, _) ->
            o.subjectNo == k.subjectNo || sameCourseKey(o.name) == sameCourseKey(k.name) || o.times.any { t -> k.times.any { t.overlaps(it) } }
        } &&
            credits() + (k.credits ?: 0) <= prefs.maxCredits
        fun tryAdd(subjectNo: String, classNo: String?, reason: String): Boolean {
            val sections = candidates.filter { it.subjectNo == subjectNo }.sortedBy { if (it.classNo == classNo) 0 else 1 }
            val k = sections.firstOrNull { fits(it) } ?: return false
            // Another 분반 than the AI named: times it quoted no longer hold
            kept += k to if (classNo != null && k.classNo != classNo) reason.replace(TIME_TEXT, "").replace(Regex("\\s{2,}"), " ").trim() else reason
            return true
        }
        plan.items.forEach { tryAdd(it.subjectNo, it.classNo, it.reason) }
        // Nothing usable from the AI: leave it invalid so the problems go back to the AI
        if (kept.isNotEmpty() && credits() < prefs.minCredits) {
            val others = drafts.flatMap { it.items }.groupingBy { it.subjectNo }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            val reasons = drafts.flatMap { it.items }.associate { it.subjectNo to it.reason }
            for (no in others) { if (credits() >= prefs.minCredits) break; tryAdd(no, null, reasons[no].orEmpty()) }
            val byTime = candidates.sortedByDescending { k -> if (prefs.time == TimePreference.Any) 0 else k.times.count { it.isMorning() == (prefs.time == TimePreference.Morning) } }
            for (k in byTime) { if (credits() >= prefs.minCredits) break; tryAdd(k.subjectNo, k.classNo, "학점을 채우려고 더한 과목") }
        }
        return plan.copy(items = kept.map { (k, reason) -> TimetableItem(k.subjectNo, k.classNo, reason) })
    }

    /**
     * Classes the AI may choose from: not taken, not on a free day, and either a class of the student's majors,
     * a liberal-arts class, or a course on the student's graduation checklist. Another department's 전공기초 only
     * counts when the checklist names it (e.g. 수학과 calculus for 컴퓨터정보공학부).
     * Ordered 1st major → 2nd major → the rest, so the list itself doesn't favor whichever major sorts first.
     */
    internal fun candidates(req: TimetableRequest, all: List<OpenClass>): List<OpenClass> {
        val s = req.student
        val majors = s.departments.map { it.substringAfter('/') }
        val plan = graduationPlan(catalog, s)
        val checklist = plan.buckets.flatMap { it.checklistCourseIds }.mapNotNull { catalog.findCourse(it) }.map { it.id }.toSet()
        return all.filter { k ->
            !s.hasCompleted(catalog, k) &&
                k.times.none { it.day in req.prefs.freeDays } &&
                (k.department in majors || k.category in LIBERAL || catalog.courseFor(k)?.id in checklist)
        }.sortedBy { k -> majors.indexOf(k.department).let { if (it < 0) majors.size else it } }
    }

    /** How many of the student's majors have at least one class in the plan (for ranking). */
    private fun majorsCovered(plan: TimetablePlan, all: List<OpenClass>, student: kr.ac.catholic.cukmoa.model.StudentState): Int {
        val depts = plan.items.mapNotNull { i -> all.firstOrNull { it.subjectNo == i.subjectNo && it.classNo == i.classNo }?.department }.toSet()
        return student.departments.count { it.substringAfter('/') in depts }
    }

    private fun prompt(req: TimetableRequest, candidates: List<OpenClass>): String {
        val s = req.student
        val p = req.prefs
        val plan = graduationPlan(catalog, s)
        val sum = creditSummary(catalog, s)
        val missing = plan.buckets.flatMap { b -> b.checklistCourseIds.filter { it !in s.completedCourseIds }.mapNotNull { catalog.findCourse(it) }.map { b.label to it } }
        // Grades by course name and area; no names or student IDs are sent
        val grades = s.grades.mapNotNull { (id, g) ->
            catalog.findCourse(id)?.let { "${it.name}(${it.category.label}): ${g.label}" }
                ?: s.takenClasses.firstOrNull { it.subjectNo == id }?.let { "${it.name}(${plan.buckets.firstOrNull { b -> b.id == it.bucket }?.label ?: "일반선택"}): ${g.label}" }
        }
        return buildString {
            appendLine("## 학생")
            val majors = s.departments.map { it.substringAfter('/') }
            appendLine("${s.grade}학년, " + when (majors.size) {
                0 -> "전공 미정"
                1 -> "전공: ${majors[0]}"
                else -> "제1전공: ${majors[0]}, 제2전공(복수전공): ${majors[1]} (두 전공 모두 36학점 이상, 제2전공 학점은 일반선택에 들어가요)"
            })
            appendLine("졸업까지 남은 학점 ${sum.remaining} / ${sum.total}")
            sum.buckets.forEach { b -> appendLine("- ${b.label}: ${b.earned}/${b.required}${if (b.met) " (충족)" else " (남음 ${b.required - b.earned})"}") }
            if (missing.isNotEmpty()) {
                appendLine("아직 안 들은 필수 체크리스트 과목:")
                missing.forEach { (area, c) -> appendLine("- $area: ${c.name}${plan.semesters[c.id]?.let { " (권장 ${it.label})" } ?: ""}") }
            }
            appendLine("성적: ${grades.joinToString(", ").ifEmpty { "입력 없음" }}")
            appendLine()
            appendLine("## 조건")
            appendLine("총 학점 ${p.minCredits}~${p.maxCredits}학점")
            appendLine("공강 요일: ${p.freeDays.joinToString(", ").ifEmpty { "없음" }}")
            appendLine(
                "선호 시간: " + when (p.time) {
                    TimePreference.Morning -> "오전 위주 (1~3교시 = 09~12시)"
                    TimePreference.Afternoon -> "오후 위주 (4교시 = 13시 이후)"
                    TimePreference.Any -> "상관없음"
                },
            )
            p.note?.takeIf { it.isNotBlank() }?.let { appendLine("학생 요청: ${it.trim()}") }
            appendLine()
            appendLine("## 고를 수 있는 개설과목 (학수번호 | 과목명 | 이수구분 | 학점 | 이수학년 | 개설전공 | 분반: 분반번호 교수 시간)")
            candidates.subjects().forEach { sub ->
                val k = sub.first
                val sections = sub.classes.joinToString(" ; ") { c ->
                    "${c.classNo} ${c.professors.joinToString(",").ifEmpty { "-" }} ${c.times.joinToString(",") { it.label() }.ifEmpty { "시간미정" }}${dayPart(c)}"
                }
                appendLine("${k.subjectNo} | ${k.name} | ${k.category} | ${k.credits ?: 0} | ${k.grade ?: "전"} | ${k.department} | $sections")
            }
        }
    }

    /** " [오전]" / " [오후]" / " [오전·오후]", so the model doesn't have to work periods out */
    private fun dayPart(c: OpenClass): String {
        if (c.times.isEmpty()) return ""
        val morning = c.times.count { it.isMorning() }
        return when (morning) {
            c.times.size -> " [오전]"
            0 -> " [오후]"
            else -> " [오전·오후]"
        }
    }

    /** Titles, summaries and reasons in the app's language (class names and categories stay as listed) */
    private fun outputLanguage(language: String): String = when (language) {
        "en" -> "\ntitle, summary, reason은 영어(English)로 써. 과목명과 이수구분은 목록 그대로 둬."
        "zh" -> "\ntitle, summary, reason은 중국어 간체(简体中文)로 써. 과목명과 이수구분은 목록 그대로 둬."
        "ja" -> "\ntitle, summary, reason은 일본어(日本語)로 써. 과목명과 이수구분은 목록 그대로 둬."
        else -> ""
    }

    private companion object {
        val LIBERAL = setOf("기초교양필수", "중핵교양필수", "자유선택교양")
        val SYSTEM = """
            너는 가톨릭대학교 학생의 수강 시간표를 짜 주는 도우미야. 아래 학생 정보와 개설과목 목록만 보고 서로 다른 시간표 ${DRAFTS}개를 만들어.
            규칙 (반드시 지켜):
            - 목록에 있는 학수번호와 분반번호만 그대로 써. 같은 과목은 한 번만.
            - 수업 시간이 서로 겹치면 안 돼. 공강 요일에는 수업을 넣지 마.
            - 총 학점은 조건의 범위 안이어야 해.
            고르는 기준:
            - 아직 안 들은 필수 체크리스트 과목과 남은 학점이 많은 영역을 먼저 채워. 학생 학년에 맞는 과목(이수학년)을 우선해.
            - 전공 과목은 학생 전공 학과(개설전공)의 과목만 넣어. 전공이 두 개면 각 시간표에 두 전공 과목을 모두 넣어 (제1전공을 조금 더 많이).
            - 성적이 좋은 영역과 비슷한 과목은 더 넣고, C 이하를 받은 영역은 꼭 필요한 과목이 아니면 줄여.
            - 선호 시간과 학생 요청을 최대한 따라. 분반 끝의 [오전]/[오후] 표시를 보고 골라.
            - 시간표끼리 방향이 달라야 해 (예: 졸업요건 우선, 전공 집중, 여유로운 시간표, 선호 시간 최우선).
            출력: plans 배열. title은 15자 이내, summary는 한두 문장, items의 reason은 그 과목을 고른 이유를 40자 이내 한국어로.
            reason에는 요일·교시를 쓰지 말고 왜 골랐는지만 써 (예: "중핵교양 남은 6학점 채우기", "A+ 받은 프로그래밍과 이어지는 과목", "3학년 권장 전공").
            이수구분은 목록에 적힌 그대로만 써 (전공선택을 전공필수라고 하지 마).
        """.trimIndent()
    }
}

/**
 * Amazon Bedrock (Converse API). Auth: a Bedrock API key in AWS_BEARER_TOKEN_BEDROCK if set,
 * otherwise the SDK's default provider chain (EC2 instance role). Converse has no response schema, so the shape is
 * spelled out in the system prompt and the JSON object is cut out of the reply.
 */
class BedrockTimetableAi(
    private val modelId: String,
    region: String = "ap-northeast-2",
) : TimetableAi {
    private val log = LoggerFactory.getLogger(BedrockTimetableAi::class.java)
    private val client = BedrockRuntimeClient.builder()
        .region(Region.of(region))
        .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(90)) }
        .build()

    override fun generate(system: String, turns: List<AiTurn>): String {
        val req = ConverseRequest.builder()
            .modelId(modelId)
            .system(SystemContentBlock.fromText(system + JSON_ONLY))
            .messages(
                turns.map { t ->
                    Message.builder()
                        .role(if (t.role == "model") ConversationRole.ASSISTANT else ConversationRole.USER)
                        .content(ContentBlock.fromText(t.text))
                        .build()
                },
            )
            .inferenceConfig { it.temperature(0.7f).maxTokens(4096) }
            .build()

        val res = try {
            client.converse(req)
        } catch (e: ThrottlingException) {
            throw TooManyRequests("AI 사용량이 많아 잠시 쉬고 있어요. 1분 뒤에 다시 해 주세요.")
        } catch (e: AccessDeniedException) {
            log.warn("Bedrock access denied: {}", e.message)
            throw ServiceUnavailable("AI 시간표 기능을 지금 쓸 수 없어요.")
        } catch (e: SdkException) {
            // Network errors, timeouts, validation errors (e.g. wrong model ID)
            log.warn("Bedrock call failed: {}", e.message)
            throw ServiceUnavailable("AI 서버에 연결하지 못했어요.")
        }

        res.usage()?.let { log.info("Bedrock usage: in={} out={}", it.inputTokens(), it.outputTokens()) }
        val text = res.output().message().content().mapNotNull { it.text() }.joinToString("")
        return jsonObjectIn(text)
    }

    private companion object {
        val JSON_ONLY = """

            출력은 JSON 객체 하나만 써. 마크다운, 코드블록, 설명 없이 아래 형식 그대로:
            {"plans":[{"title":"...","summary":"...","items":[{"subjectNo":"...","classNo":"...","reason":"..."}]}]}
        """.trimIndent()
    }
}

/**
 * The outermost {...} in the reply. If there is none, the text is returned as-is so the caller's
 * decode fails softly and the problem goes back to the AI in the next round.
 */
internal fun jsonObjectIn(text: String): String {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    return if (start >= 0 && end > start) text.substring(start, end + 1) else text
}
