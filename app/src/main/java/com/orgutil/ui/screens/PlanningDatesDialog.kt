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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.orgutil.data.repository.PlanningDateEdit
import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.ui.theme.OrgMono
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Compact planning-date editor for one agenda entry — the ordinary
 * SCHEDULED / DEADLINE counterpart of [HabitScheduleDialog], fully separate
 * from habit setup. Each keyword is an independent 无/日期 choice: absent
 * keywords start at 无 (tapping 日期 adds one for today), existing ones
 * prefill their parsed date, and picking 无 clears that keyword only.
 * Dates are chosen in the Material 3 calendar picker (no typed input);
 * timestamps carry a precise time of day: an existing stamp's time is
 * prefilled (and kept unless edited), anything else defaults to 08:00.
 * Editing rewrites just the date+time head — repeaters and deadline
 * windows keep their bytes. A habit's repeating SCHEDULED can be re-dated
 * here but never cleared (that is the HABIT dialog's job). The live
 * preview shows the exact Org timestamps that will be written; the form
 * scrolls on short screens.
 */
@Composable
fun PlanningDatesDialog(
    entry: OrgAgendaEntry,
    busy: Boolean = false,
    onConfirm: (scheduled: PlanningDateEdit, deadline: PlanningDateEdit) -> Unit,
    onDismiss: () -> Unit
) {
    // A habit's SCHEDULED carries the repeater: it may be re-dated here but
    // never cleared (the write path rejects that; reflect it in the UI).
    val scheduledClearBlocked = entry.habit != null

    var scheduledOn by remember(entry) { mutableStateOf(entry.scheduled != null) }
    var scheduledDate by remember(entry) { mutableStateOf(entry.scheduled ?: LocalDate.now()) }
    // Existing stamps prefill (and so preserve) their time; new ones start
    // at the 08:00 default. Blank input means "just the date" → 08:00.
    var scheduledTimeText by remember(entry) {
        mutableStateOf(entry.scheduledTime?.format(TIME_INPUT_FORMAT) ?: DEFAULT_TIME_TEXT)
    }
    var deadlineOn by remember(entry) { mutableStateOf(entry.deadline != null) }
    var deadlineDate by remember(entry) { mutableStateOf(entry.deadline ?: LocalDate.now()) }
    var deadlineTimeText by remember(entry) {
        mutableStateOf(entry.deadlineTime?.format(TIME_INPUT_FORMAT) ?: DEFAULT_TIME_TEXT)
    }

    val scheduledTime = remember(scheduledTimeText) { parseTimeInput(scheduledTimeText) }
    val deadlineTime = remember(deadlineTimeText) { parseTimeInput(deadlineTimeText) }
    val invalidTime = (scheduledOn && !isValidTimeInput(scheduledTimeText)) ||
        (deadlineOn && !isValidTimeInput(deadlineTimeText))
    val valid = !invalidTime

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planning_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Which heading this applies to.
                Text(
                    text = "${"*".repeat(entry.level)} ${entry.todo.orEmpty().plus(" ")}${entry.title}".trim(),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                PlanningKeywordSection(
                    keyword = stringResource(R.string.planning_dialog_scheduled_label),
                    enabled = scheduledOn,
                    date = scheduledDate,
                    onDate = { scheduledDate = it },
                    timeText = scheduledTimeText,
                    onTimeText = { scheduledTimeText = it },
                    onEnabled = { scheduledOn = it },
                    onToday = {
                        scheduledOn = true
                        scheduledDate = LocalDate.now()
                    },
                    clearBlocked = scheduledClearBlocked,
                    hadDate = entry.scheduled != null
                )

                PlanningKeywordSection(
                    keyword = stringResource(R.string.planning_dialog_deadline_label),
                    enabled = deadlineOn,
                    date = deadlineDate,
                    onDate = { deadlineDate = it },
                    timeText = deadlineTimeText,
                    onTimeText = { deadlineTimeText = it },
                    onEnabled = { deadlineOn = it },
                    onToday = {
                        deadlineOn = true
                        deadlineDate = LocalDate.now()
                    },
                    clearBlocked = false,
                    hadDate = entry.deadline != null
                )

                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.planning_dialog_writing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Live preview of the exact Org planning line(s) that will be written.
                Text(
                    text = stringResource(R.string.planning_dialog_preview_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = previewLine(
                        keyword = "SCHEDULED",
                        edit = if (scheduledOn) {
                            PlanningDateEdit.SetDate(scheduledDate, scheduledTime)
                        } else {
                            null
                        }
                    ) + "\n" + previewLine(
                        keyword = "DEADLINE",
                        edit = if (deadlineOn) {
                            PlanningDateEdit.SetDate(deadlineDate, deadlineTime)
                        } else {
                            null
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    val scheduledEdit = if (scheduledOn) {
                        PlanningDateEdit.SetDate(scheduledDate, scheduledTime)
                    } else {
                        PlanningDateEdit.Clear
                    }
                    val deadlineEdit = if (deadlineOn) {
                        PlanningDateEdit.SetDate(deadlineDate, deadlineTime)
                    } else {
                        PlanningDateEdit.Clear
                    }
                    onConfirm(scheduledEdit, deadlineEdit)
                }
            ) {
                Text(stringResource(R.string.planning_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** One keyword's block: 无/日期 choice, picker date + time inputs and hints. */
@Composable
private fun PlanningKeywordSection(
    keyword: String,
    enabled: Boolean,
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    timeText: String,
    onTimeText: (String) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onToday: () -> Unit,
    clearBlocked: Boolean,
    hadDate: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 无 / 日期: equal halves of one row, equal heights. 无 is disabled
        // for a repeating SCHEDULED (habit) — clearing it is the HABIT
        // dialog's job, never silently offered here.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = keyword,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .width(92.dp)
                    .padding(top = 10.dp)
            )
            PlanningChoice(
                text = stringResource(R.string.planning_dialog_none),
                selected = !enabled,
                enabled = !clearBlocked,
                onClick = { onEnabled(false) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            PlanningChoice(
                text = stringResource(R.string.planning_dialog_date_choice),
                selected = enabled,
                onClick = { onEnabled(true) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
        if (clearBlocked && hadDate) {
            Text(
                text = stringResource(R.string.planning_dialog_habit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (enabled) {
            // Date picker + time + Today share one row only when the section
            // is wide enough; on narrow phones the fixed time (108dp) and
            // Today (76dp) widths squeeze the date button below yyyy-MM-dd
            // width, so its value truncates. There the date takes a full row
            // and time+Today share the next — same inputs, same behavior,
            // just stacked.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 340.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PlanningDateField(
                            date = date,
                            onDate = onDate,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        PlanningTimeField(
                            value = timeText,
                            onValueChange = onTimeText,
                            modifier = Modifier.width(108.dp)
                        )
                        PlanningTodayChoice(
                            onToday = onToday,
                            modifier = Modifier
                                .width(76.dp)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlanningDateField(
                            date = date,
                            onDate = onDate,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PlanningTimeField(
                                value = timeText,
                                onValueChange = onTimeText,
                                modifier = Modifier.weight(1f)
                            )
                            PlanningTodayChoice(
                                onToday = onToday,
                                modifier = Modifier
                                    .width(84.dp)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
            if (!isValidTimeInput(timeText)) {
                Text(
                    text = stringResource(R.string.planning_dialog_invalid_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (hadDate) {
                // Only the date+time head is rewritten; repeaters and
                // deadline windows keep their exact bytes.
                Text(
                    text = stringResource(R.string.planning_dialog_keep_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Date entry (mono) — a tappable summary that opens the Material 3 calendar
 * picker in its own dialog window, so it never fights this dialog's scroll or
 * narrow width. Confirming without a selection keeps the current date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanningDateField(
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

/** Time input (HH:mm, mono); width is the caller's job. */
@Composable
private fun PlanningTimeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.planning_dialog_time_label)) },
        isError = !isValidTimeInput(value),
        singleLine = true,
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono)
    )
}

/** The compact 今天 shortcut, sized by the caller to match its row. */
@Composable
private fun PlanningTodayChoice(
    onToday: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlanningChoice(
        text = stringResource(R.string.planning_dialog_today),
        selected = false,
        enabled = true,
        onClick = onToday,
        modifier = modifier
    )
}

/** The preview line for one keyword: the exact timestamp, or （清除） when off. */
private fun previewLine(keyword: String, edit: PlanningDateEdit?): String = when (edit) {
    is PlanningDateEdit.SetDate -> {
        val time = edit.time ?: PLANNING_DEFAULT_TIME
        "$keyword: <${edit.date.format(DATE_INPUT_FORMAT)} " +
            "${edit.date.format(WEEKDAY_FORMAT)} ${time.format(TIME_INPUT_FORMAT)}>"
    }
    else -> "$keyword: ${CLEARED_MARK}"
}

/**
 * Blank counts as valid (means "date only" and resolves to the 08:00
 * default); anything else must be a strict HH:mm.
 */
private fun isValidTimeInput(text: String): Boolean =
    text.isBlank() || runCatching { LocalTime.parse(text, TIME_INPUT_FORMAT) }.isSuccess

/** Resolves the input to a concrete time, defaulting blank to 08:00. */
private fun parseTimeInput(text: String): LocalTime =
    if (text.isBlank()) PLANNING_DEFAULT_TIME
    else runCatching { LocalTime.parse(text, TIME_INPUT_FORMAT) }.getOrDefault(PLANNING_DEFAULT_TIME)

/**
 * One equal-share option segment: outlined when idle, primary-tinted when
 * selected. Centered single-line label inside a bounded-height surface, so
 * equal-width choices in one row can never collapse into vertical text.
 */
@Composable
private fun PlanningChoice(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
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
 * The row's directly visible Schedule quick action: edits ONLY this entry's
 * SCHEDULED stamp — today 08:00 is proposed for a new stamp, an existing
 * stamp's date and time prefill (repeaters keep their bytes on write) — and
 * any DEADLINE is left exactly as parsed ([PlanningDateEdit.Keep]). Date via
 * the Material 3 calendar picker, time as a precise HH:mm.
 */
@Composable
fun ScheduleQuickDialog(
    entry: OrgAgendaEntry,
    busy: Boolean = false,
    onConfirm: (date: LocalDate, time: LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    var date by remember(entry) { mutableStateOf(entry.scheduled ?: LocalDate.now()) }
    var timeText by remember(entry) {
        mutableStateOf(entry.scheduledTime?.format(TIME_INPUT_FORMAT) ?: DEFAULT_TIME_TEXT)
    }
    val time = remember(timeText) { parseTimeInput(timeText) }
    val validTime = isValidTimeInput(timeText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Which heading this applies to.
                Text(
                    text = "${"*".repeat(entry.level)} ${entry.todo.orEmpty().plus(" ")}${entry.title}".trim(),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PlanningDateField(
                        date = date,
                        onDate = { date = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    PlanningTodayChoice(
                        onToday = { date = LocalDate.now() },
                        modifier = Modifier
                            .width(76.dp)
                            .fillMaxHeight()
                    )
                }
                PlanningTimeField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    modifier = Modifier.fillMaxWidth()
                )
                if (!validTime) {
                    Text(
                        text = stringResource(R.string.planning_dialog_invalid_time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = stringResource(R.string.schedule_dialog_keep_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.planning_dialog_writing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Live preview of the exact Org planning line that will be written.
                Text(
                    text = stringResource(R.string.planning_dialog_preview_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = previewLine(
                        keyword = "SCHEDULED",
                        edit = PlanningDateEdit.SetDate(date, time)
                    ),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = validTime && !busy,
                onClick = { onConfirm(date, time) }
            ) {
                Text(stringResource(R.string.planning_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private val DATE_INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val WEEKDAY_FORMAT = DateTimeFormatter.ofPattern("EEE", Locale.US)
private val TIME_INPUT_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private const val DEFAULT_TIME_TEXT = "08:00"
private const val CLEARED_MARK = "（清除）"
