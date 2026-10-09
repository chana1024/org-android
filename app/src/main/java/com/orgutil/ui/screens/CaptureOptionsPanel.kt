package com.orgutil.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orgutil.R
import com.orgutil.data.repository.PLANNING_DEFAULT_TIME
import com.orgutil.domain.agenda.OrgAgendaParser
import com.orgutil.domain.usecase.CaptureEntryFormatter
import com.orgutil.domain.usecase.CaptureHabitRepeat
import com.orgutil.domain.usecase.CaptureOptions
import com.orgutil.ui.theme.OrgMono
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Editable quick-capture options — one shared draft for CaptureScreen and the
 * widget QuickCaptureDialog. Defaults are the plain capture of old (no
 * keyword, no SCHEDULED, no habit); nothing persists between captures.
 */
data class CaptureOptionsDraft(
    val keyword: String? = null,
    val scheduledOn: Boolean = false,
    val scheduledDate: LocalDate = LocalDate.now(),
    val timeText: String = DEFAULT_TIME_TEXT,
    val habitOn: Boolean = false,
    val repeaterType: String = "++",
    val countText: String = "1",
    val unit: Char = 'd',
    /** null = not chosen yet; false = due on the scheduled day; true = window. */
    val deadlineChoice: Boolean? = null,
    val windowCountText: String = "3",
    val windowUnit: Char = 'd'
)

/**
 * Resolves the draft into the typed [CaptureOptions] the write path takes, or
 * null while the form itself is incomplete (malformed time / deadline not yet
 * chosen). Field-level mistakes (bad count, window ≤ interval, done-keyword
 * habit, …) still build an options object and are rejected with a reason by
 * [CaptureEntryFormatter.validate], so the UI shows the exact write-path rule.
 */
fun CaptureOptionsDraft.buildOptions(): CaptureOptions? {
    if (scheduledOn && !isValidTimeText(timeText)) return null
    if (habitOn && deadlineChoice == null) return null
    val count = countText.toIntOrNull() ?: 0
    val windowCount = windowCountText.toIntOrNull() ?: 0
    return CaptureOptions(
        todoKeyword = keyword,
        scheduledDate = if (scheduledOn) scheduledDate else null,
        scheduledTime = if (timeText.isBlank()) PLANNING_DEFAULT_TIME else parseTimeText(timeText),
        habit = if (habitOn) {
            CaptureHabitRepeat(
                repeaterType = repeaterType,
                count = count,
                unit = unit,
                deadlineCount = if (deadlineChoice == true) windowCount else null,
                deadlineUnit = windowUnit
            )
        } else {
            null
        }
    )
}

/** User-facing problem with the current draft, or null when it can be saved. */
@Composable
fun CaptureOptionsDraft.draftError(content: String): String? {
    if (scheduledOn && !isValidTimeText(timeText)) {
        return stringResource(R.string.planning_dialog_invalid_time)
    }
    if (habitOn && deadlineChoice == null) {
        return stringResource(R.string.capture_options_deadline_unchosen)
    }
    val options = buildOptions() ?: return stringResource(R.string.capture_options_deadline_unchosen)
    if (content.isBlank()) return null // blank input is the button's own gate
    return CaptureEntryFormatter.validate(content, options)
}

/**
 * Compact optional capture decorations, shared by the in-app screen and the
 * widget dialog: three toggle rows (TODO state / SCHEDULED / habit) that stay
 * collapsed to chips until enabled, using the same equal-segment choice,
 * Material 3 calendar picker and HH:mm time input as the Agenda's planning
 * and habit dialogs. Shows the live "Will write:" preview.
 */
@Composable
fun CaptureOptionsPanel(
    draft: CaptureOptionsDraft,
    onDraftChange: (CaptureOptionsDraft) -> Unit,
    inputText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StateRow(draft, onDraftChange)
        ScheduledRow(draft, onDraftChange)
        HabitRow(draft, onDraftChange)

        draft.draftError(inputText)?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (!draft.isPlainDraft) {
            // Incomplete drafts (deadline unchosen) still preview the chosen
            // parts; a malformed time hides the preview — its error shows.
            val previewOptions = draft.buildOptions()
                ?: draft.copy(deadlineChoice = draft.deadlineChoice ?: false).buildOptions()
            if (previewOptions != null) {
                Text(
                    text = stringResource(R.string.planning_dialog_preview_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = CaptureEntryFormatter.previewLines(inputText, previewOptions)
                        .joinToString("\n"),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private val CaptureOptionsDraft.isPlainDraft: Boolean
    get() = keyword == null && !scheduledOn && !habitOn

// ---- rows ------------------------------------------------------------------

/** 状态: 无 / DONE / NEXT / 更多 — the Doom keyword sequence, never invented tags. */
@Composable
private fun StateRow(
    draft: CaptureOptionsDraft,
    onDraftChange: (CaptureOptionsDraft) -> Unit
) {
    var moreOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.capture_options_state_label),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(48.dp)
        )
        OptionChoice(
            text = stringResource(R.string.planning_dialog_none),
            selected = draft.keyword == null,
            // A habit must stay actionable: it always carries a not-done
            // keyword, so 无 is not offered while the habit option is on.
            enabled = !draft.habitOn,
            onClick = { onDraftChange(draft.copy(keyword = null)) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        OptionChoice(
            text = "DONE",
            selected = draft.keyword == "DONE",
            onClick = { onDraftChange(draft.copy(keyword = "DONE")) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        OptionChoice(
            text = "NEXT",
            selected = draft.keyword == "NEXT",
            onClick = { onDraftChange(draft.copy(keyword = "NEXT")) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        val other = draft.keyword?.takeIf { it != "DONE" && it != "NEXT" }
        OptionChoice(
            text = other ?: stringResource(R.string.capture_options_state_more),
            selected = other != null,
            onClick = { moreOpen = true },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }

    if (draft.habitOn) {
        Text(
            text = stringResource(R.string.capture_options_habit_state_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (moreOpen) {
        AlertDialog(
            onDismissRequest = { moreOpen = false },
            title = { Text(stringResource(R.string.capture_options_state_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    OrgAgendaParser.TODO_KEYWORDS_ORDERED.forEach { keyword ->
                        TextButton(
                            onClick = {
                                onDraftChange(draft.copy(keyword = keyword))
                                moreOpen = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = keyword,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono),
                                fontWeight = if (draft.keyword == keyword) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                color = if (draft.keyword == keyword) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { moreOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** SCHEDULED: 无 / 日期 with the calendar picker, HH:mm time and 今天 shortcut. */
@Composable
private fun ScheduledRow(
    draft: CaptureOptionsDraft,
    onDraftChange: (CaptureOptionsDraft) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.planning_dialog_scheduled_label),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .width(92.dp)
                .padding(top = 10.dp)
        )
        OptionChoice(
            text = stringResource(R.string.planning_dialog_none),
            selected = !draft.scheduledOn,
            // A habit requires its repeating SCHEDULED — clearing it here is
            // the habit row's job, never silently offered.
            enabled = !draft.habitOn,
            onClick = { onDraftChange(draft.copy(scheduledOn = false)) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        OptionChoice(
            text = stringResource(R.string.planning_dialog_date_choice),
            selected = draft.scheduledOn,
            onClick = { onDraftChange(draft.copy(scheduledOn = true)) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
    if (draft.scheduledOn) {
        // Date picker + time + 今天 share one row only when the section is
        // wide enough (same threshold as PlanningDatesDialog); on narrow
        // phones the fixed time (108dp) and 今天 (76dp) widths would squeeze
        // the date below yyyy-MM-dd width, so the date takes a full row and
        // time+今天 share the next — never a tiny truncated date box.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= 340.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CaptureDateField(
                        date = draft.scheduledDate,
                        onDate = { onDraftChange(draft.copy(scheduledDate = it)) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    OutlinedTextField(
                        value = draft.timeText,
                        onValueChange = { onDraftChange(draft.copy(timeText = it)) },
                        label = { Text(stringResource(R.string.planning_dialog_time_label)) },
                        isError = !isValidTimeText(draft.timeText),
                        singleLine = true,
                        modifier = Modifier.width(108.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono)
                    )
                    OptionChoice(
                        text = stringResource(R.string.planning_dialog_today),
                        selected = false,
                        onClick = {
                            onDraftChange(
                                draft.copy(scheduledOn = true, scheduledDate = LocalDate.now())
                            )
                        },
                        modifier = Modifier
                            .width(76.dp)
                            .fillMaxHeight()
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CaptureDateField(
                        date = draft.scheduledDate,
                        onDate = { onDraftChange(draft.copy(scheduledDate = it)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = draft.timeText,
                            onValueChange = { onDraftChange(draft.copy(timeText = it)) },
                            label = { Text(stringResource(R.string.planning_dialog_time_label)) },
                            isError = !isValidTimeText(draft.timeText),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono)
                        )
                        OptionChoice(
                            text = stringResource(R.string.planning_dialog_today),
                            selected = false,
                            onClick = {
                                onDraftChange(
                                    draft.copy(scheduledOn = true, scheduledDate = LocalDate.now())
                                )
                            },
                            modifier = Modifier
                                .width(84.dp)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

/** 习惯: 无 / 启用 with the HabitScheduleDialog's repeat + deadline controls. */
@Composable
private fun HabitRow(
    draft: CaptureOptionsDraft,
    onDraftChange: (CaptureOptionsDraft) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.capture_options_habit_label),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .width(92.dp)
                .padding(top = 10.dp)
        )
        OptionChoice(
            text = stringResource(R.string.planning_dialog_none),
            selected = !draft.habitOn,
            onClick = { onDraftChange(draft.copy(habitOn = false)) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        OptionChoice(
            text = stringResource(R.string.capture_options_habit_on),
            selected = draft.habitOn,
            onClick = {
                // Enabling a habit needs its repeating SCHEDULED (keep any
                // date/time already chosen; today 08:00 otherwise) and an
                // actionable keyword — default NEXT, visibly selected.
                onDraftChange(
                    draft.copy(
                        habitOn = true,
                        scheduledOn = true,
                        keyword = draft.keyword ?: "NEXT"
                    )
                )
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
    if (draft.habitOn) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Repeat type: three equal segments on one line.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.habit_dialog_repeat_label),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(48.dp)
                )
                CaptureEntryFormatter.REPEATER_TYPES.forEach { type ->
                    OptionChoice(
                        text = type,
                        selected = draft.repeaterType == type,
                        onClick = { onDraftChange(draft.copy(repeaterType = type)) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
            Text(
                text = stringResource(R.string.habit_dialog_repeat_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Cadence: "Every [count] [unit]" on one line.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.habit_dialog_cadence_label),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(48.dp)
                )
                OutlinedTextField(
                    value = draft.countText,
                    onValueChange = {
                        onDraftChange(draft.copy(countText = it.filter(Char::isDigit).take(3)))
                    },
                    isError = draft.countText.toIntOrNull()?.let { it < 1 } != false,
                    singleLine = true,
                    modifier = Modifier.width(64.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono)
                )
                UNIT_LABELS.forEach { (u, label) ->
                    OptionChoice(
                        text = label,
                        selected = draft.unit == u,
                        onClick = { onDraftChange(draft.copy(unit = u)) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }

            // Deadline: an explicit choice, like the Agenda habit dialog.
            Text(
                text = stringResource(R.string.habit_dialog_deadline_label),
                style = MaterialTheme.typography.labelMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OptionChoice(
                    text = stringResource(R.string.habit_dialog_deadline_on_time),
                    selected = draft.deadlineChoice == false,
                    onClick = { onDraftChange(draft.copy(deadlineChoice = false)) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                OptionChoice(
                    text = stringResource(R.string.habit_dialog_deadline_window),
                    selected = draft.deadlineChoice == true,
                    onClick = { onDraftChange(draft.copy(deadlineChoice = true)) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
            if (draft.deadlineChoice == null) {
                Text(
                    text = stringResource(R.string.habit_dialog_deadline_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (draft.deadlineChoice == true) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = draft.windowCountText,
                        onValueChange = {
                            onDraftChange(
                                draft.copy(windowCountText = it.filter(Char::isDigit).take(3))
                            )
                        },
                        isError = draft.windowCountText.toIntOrNull()?.let { it < 1 } != false,
                        singleLine = true,
                        modifier = Modifier.width(64.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono)
                    )
                    UNIT_LABELS.forEach { (u, label) ->
                        OptionChoice(
                            text = label,
                            selected = draft.windowUnit == u,
                            onClick = { onDraftChange(draft.copy(windowUnit = u)) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

// ---- shared controls (same idiom as PlanningDatesDialog/HabitScheduleDialog) --

/**
 * One equal-share option segment: outlined when idle, primary-tinted when
 * selected. Centered single-line label inside a bounded-height surface, so
 * equal-width choices in one row can never collapse into vertical text.
 */
@Composable
private fun OptionChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            1.dp,
            when {
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected -> MaterialTheme.colorScheme.primary
                    enabled -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Date entry (mono) — a tappable summary that opens the Material 3 calendar
 * picker in its own dialog window (same conversion as PlanningDatesDialog),
 * so it never fights this surface's scroll or narrow width.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CaptureDateField(
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    var pickerOpen by remember { mutableStateOf(false) }

    Surface(
        onClick = { pickerOpen = true },
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = date.format(DATE_INPUT_FORMAT),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono)
            )
        }
    }

    if (pickerOpen) {
        // The picker models selection as UTC epoch millis; convert both ways
        // in UTC so the tapped calendar day is exactly what we write.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onDate(
                                Instant.ofEpochMilli(millis)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                            )
                        }
                        pickerOpen = false
                    }
                ) { Text(stringResource(R.string.planning_dialog_date_choice)) }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * Blank counts as valid (means "date only" and resolves to the 08:00
 * default); anything else must be a strict HH:mm.
 */
private fun isValidTimeText(text: String): Boolean =
    text.isBlank() || runCatching { LocalTime.parse(text, TIME_INPUT_FORMAT) }.isSuccess

/** Resolves the input to a concrete time, defaulting blank to 08:00. */
private fun parseTimeText(text: String): LocalTime =
    if (text.isBlank()) PLANNING_DEFAULT_TIME
    else runCatching { LocalTime.parse(text, TIME_INPUT_FORMAT) }.getOrDefault(PLANNING_DEFAULT_TIME)

private val DATE_INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_INPUT_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private const val DEFAULT_TIME_TEXT = "08:00"
private val UNIT_LABELS = listOf('d' to "Day", 'w' to "Week", 'm' to "Month", 'y' to "Year")
