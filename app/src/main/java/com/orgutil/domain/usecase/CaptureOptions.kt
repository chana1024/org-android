package com.orgutil.domain.usecase

import com.orgutil.data.repository.PLANNING_DEFAULT_TIME
import com.orgutil.data.repository.habitDurationDays
import com.orgutil.domain.agenda.OrgAgendaParser
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Habit repeat chosen in quick capture — the same grammar the Agenda's
 * habit dialog writes ([com.orgutil.data.repository.OrgHeadingStyleService.HabitSchedule]):
 * `+`/`++`/`.+` type, count, d/w/m/y unit and an optional deadline window.
 */
data class CaptureHabitRepeat(
    val repeaterType: String,
    val count: Int,
    val unit: Char,
    val deadlineCount: Int? = null,
    val deadlineUnit: Char = 'd'
) {
    val repeaterDays: Int get() = habitDurationDays(count, unit)
    val deadlineDays: Int? get() = deadlineCount?.let { habitDurationDays(it, deadlineUnit) }
}

/**
 * Optional quick-capture decorations. The default instance is the plain
 * capture of old: no TODO keyword, no SCHEDULED, no habit —
 * [CaptureEntryFormatter.format] then emits byte-identical output to the
 * legacy `formatAsOrgModeHeader`.
 */
data class CaptureOptions(
    val todoKeyword: String? = null,
    val scheduledDate: LocalDate? = null,
    val scheduledTime: LocalTime = PLANNING_DEFAULT_TIME,
    val habit: CaptureHabitRepeat? = null
) {
    val isPlain: Boolean get() = todoKeyword == null && scheduledDate == null && habit == null
}

/**
 * Single shared typed draft → Org text formatter for quick capture, with the
 * validation both surfaces (CaptureScreen, QuickCaptureDialog) and the write
 * path ([AddToCaptureFileUseCase]) agree on. Rules mirror the Agenda side:
 * keywords come from [OrgAgendaParser.TODO_KEYWORDS_ORDERED] (never invented),
 * habit repeats reuse `habitDurationDays` and its `/deadline > repeat` rule,
 * and a habit may not be captured in a done keyword (it would instantly
 * repeat-reset and hide the entry).
 */
object CaptureEntryFormatter {

    /** null = valid; otherwise a user-facing reason. */
    fun validate(content: String, options: CaptureOptions): String? {
        if (content.isBlank()) return CONTENT_BLANK
        options.todoKeyword?.let { keyword ->
            if (keyword !in OrgAgendaParser.TODO_KEYWORDS_ORDERED) return UNKNOWN_KEYWORD
        }
        val habit = options.habit ?: return null
        if (options.scheduledDate == null) return HABIT_NEEDS_SCHEDULED
        if (options.todoKeyword != null && options.todoKeyword in OrgAgendaParser.DONE_KEYWORDS) {
            return HABIT_DONE_KEYWORD
        }
        if (habit.repeaterType !in REPEATER_TYPES) return INVALID_REPEATER
        if (habit.count < 1) return INVALID_COUNT
        if (habit.unit !in UNITS) return INVALID_REPEATER
        habit.deadlineCount?.let { deadlineCount ->
            if (deadlineCount < 1 || habit.deadlineUnit !in UNITS) return INVALID_REPEATER
            val deadlineDays = habit.deadlineDays
            if (deadlineDays != null && deadlineDays <= habit.repeaterDays) {
                return INVALID_DEADLINE_WINDOW
            }
        }
        return null
    }

    /**
     * Formats one level-1 heading exactly as the capture file expects:
     * `* [KEYWORD ]TITLE`, then the SCHEDULED planning line, then the
     * PROPERTIES drawer (STYLE habit + CREATED) — Org's canonical
     * planning-before-properties order, the same layout
     * [com.orgutil.data.repository.OrgHeadingStyleService] writes. Multi-line
     * content keeps the legacy split: first line is the title, the rest an
     * indented body (so a body line starting with `*` can never become a new
     * heading). With default options the output is byte-identical to the
     * legacy `formatAsOrgModeHeader`.
     */
    fun format(content: String, options: CaptureOptions, now: Date = Date()): String {
        val createdStamp = SimpleDateFormat(CREATED_FORMAT, Locale.getDefault()).format(now)
        val sb = StringBuilder()
        sb.append("\n").append(headingLine(content, options))
        scheduledLine(options)?.let { sb.append("\n  ").append(it) }
        sb.append("\n  :PROPERTIES:")
        if (options.habit != null) sb.append("\n  :STYLE: habit")
        sb.append("\n  :CREATED: ").append(createdStamp)
        sb.append("\n  :END:")
        content.lines().drop(1).forEach { line -> sb.append("\n  ").append(line) }
        sb.append("\n")
        return sb.toString()
    }

    /**
     * The exact lines the capture will write (minus the timestamp-only
     * CREATED stamp and body) — the live preview both capture surfaces show.
     */
    fun previewLines(content: String, options: CaptureOptions): List<String> {
        return buildList {
            add(headingLine(content, options))
            scheduledLine(options)?.let(::add)
            if (options.habit != null) add(":STYLE: habit")
        }
    }

    /** `* [TODO ]First line of the content` — exactly one level-1 heading. */
    private fun headingLine(content: String, options: CaptureOptions): String {
        return "* " + (options.todoKeyword?.plus(" ") ?: "") +
            sanitizedTitle(content.lines().first(), options)
    }

    /**
     * A structured capture (any option chosen) never keeps leading stars or a
     * duplicated keyword in the title: `** NEXT x` + NEXT → `* NEXT x`, never
     * `* NEXT ** NEXT x`. A chosen keyword option also wins over a typed one
     * (`TODO x` + DONE → `DONE x`). The plain path leaves the typed text
     * exactly as before.
     */
    private fun sanitizedTitle(rawTitle: String, options: CaptureOptions): String {
        var title = rawTitle
        if (!options.isPlain) {
            title = title.trimStart('*', ' ', '\t')
        }
        if (options.todoKeyword != null) {
            val firstWord = title.substringBefore(' ')
            if (firstWord in OrgAgendaParser.TODO_KEYWORDS_ORDERED) {
                title = title.removePrefix(firstWord).trimStart()
            }
        }
        return title
    }

    /** What the agenda parser will read back as this capture's todo keyword. */
    fun expectedParsedTodo(content: String, options: CaptureOptions): String? {
        options.todoKeyword?.let { return it }
        val title = sanitizedTitle(content.lines().first(), options)
        return title.substringBefore(' ')
            .takeIf { it in OrgAgendaParser.TODO_KEYWORDS_ORDERED }
    }

    /** `SCHEDULED: <2026-10-08 Thu 08:00 .+1w/3d>` or null when none was chosen. */
    private fun scheduledLine(options: CaptureOptions): String? {
        val date = options.scheduledDate ?: return null
        val sb = StringBuilder("SCHEDULED: <")
        sb.append(date.format(STAMP_DATE_FORMAT))
        sb.append(' ').append(options.scheduledTime.format(STAMP_TIME_FORMAT))
        options.habit?.let { habit ->
            sb.append(' ').append(habit.repeaterType).append(habit.count).append(habit.unit)
            habit.deadlineCount?.let { deadlineCount ->
                sb.append('/').append(deadlineCount).append(habit.deadlineUnit)
            }
        }
        return sb.append(">").toString()
    }

    /** User-facing reasons, phrased like the sibling services' consts. */
    const val CONTENT_BLANK = "内容为空，未保存"
    const val UNKNOWN_KEYWORD = "无效的 TODO 状态"
    const val HABIT_NEEDS_SCHEDULED = "习惯需要先设置 SCHEDULED 开始日期"
    const val HABIT_DONE_KEYWORD = "习惯不能以已完成状态创建：完成后会立即重置并重复，条目会被隐藏；请选择未完成状态"
    const val INVALID_REPEATER = "无效的习惯重复周期"
    const val INVALID_COUNT = "重复次数需为不小于 1 的数字"
    const val INVALID_DEADLINE_WINDOW =
        "截止窗口必须长于重复周期（Org 规则：/deadline 间隔需大于 scheduled 间隔）"

    val REPEATER_TYPES = listOf("+", "++", ".+")
    val UNITS = listOf('d', 'w', 'm', 'y')

    private const val CREATED_FORMAT = "yyyy-MM-dd HH:mm"
    private val STAMP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.US)
    private val STAMP_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
}
