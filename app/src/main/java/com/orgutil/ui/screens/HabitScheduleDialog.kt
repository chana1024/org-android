package com.orgutil.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.orgutil.data.repository.OrgHeadingStyleService
import com.orgutil.data.repository.habitDurationDays
import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.ui.theme.OrgMono
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Mutable form state of the habit scheduling dialog. */
private data class HabitScheduleDraft(
    val dateText: String,
    val repeaterType: String,
    val countText: String,
    val unit: Char,
    val windowCountText: String,
    val windowUnit: Char
)

/**
 * Compact explicit habit-schedule picker for one agenda entry. Everything the
 * write path needs is chosen here: start date, repeat cadence ("Every [n]
 * [unit]" on one line) and a REQUIRED explicit deadline choice — neither
 * deadline option starts selected and Confirm stays disabled until one is
 * tapped. Controls use equal-width segments in shared rows, so nothing can
 * collapse into vertical text on a narrow phone; the whole form scrolls when
 * the screen is short. The live preview shows the exact Org timestamp that
 * will be written. Existing habits prefill date/cadence from their parsed
 * repeater; the deadline choice is never silently selected.
 */
@Composable
fun HabitScheduleDialog(
    entry: OrgAgendaEntry,
    busy: Boolean = false,
    onConfirm: (OrgHeadingStyleService.HabitSchedule) -> Unit,
    onDismiss: () -> Unit
) {
    val prefill = remember(entry) { entry.existingHabitSchedule() ?: DEFAULT_DRAFT }
    var dateText by remember(entry) { mutableStateOf(prefill.dateText) }
    var repeaterType by remember(entry) { mutableStateOf(prefill.repeaterType) }
    var countText by remember(entry) { mutableStateOf(prefill.countText) }
    var unit by remember(entry) { mutableStateOf(prefill.unit) }
    var windowCountText by remember(entry) { mutableStateOf(prefill.windowCountText) }
    var windowUnit by remember(entry) { mutableStateOf(prefill.windowUnit) }

    // Explicit deadline choice: null until the user taps one of the two
    // options — never prefilled, not even from an existing /window.
    var deadlineChoice by remember(entry) { mutableStateOf<Boolean?>(null) }

    val date = remember(dateText) {
        runCatching { LocalDate.parse(dateText, DATE_INPUT_FORMAT) }.getOrNull()
    }
    val count = countText.toIntOrNull()
    val windowCount = windowCountText.toIntOrNull()
    val repeaterDays = count?.takeIf { it >= 1 }?.let { habitDurationDays(it, unit) }
    val windowDays = if (deadlineChoice == true) {
        windowCount?.takeIf { it >= 1 }?.let { habitDurationDays(it, windowUnit) }
    } else {
        null
    }

    val invalidDate = date == null
    val invalidCount = count == null || count < 1
    val deadlineUnchosen = deadlineChoice == null
    val invalidWindow = deadlineChoice == true && (
        windowCount == null || windowCount < 1 ||
            (repeaterDays != null && windowDays != null && windowDays <= repeaterDays)
        )
    val valid = !invalidDate && !invalidCount && !deadlineUnchosen && !invalidWindow

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.habit_dialog_title)) },
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

                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text(stringResource(R.string.habit_dialog_date_label)) },
                    isError = invalidDate,
                    supportingText = if (invalidDate) {
                        { Text(stringResource(R.string.habit_dialog_invalid_date)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono)
                )

                // Repeat type: label + three equal segments on one line.
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
                    REPEATER_TYPES.forEach { type ->
                        HabitChoice(
                            text = type,
                            selected = repeaterType == type,
                            onClick = { repeaterType = type },
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
                        value = countText,
                        onValueChange = { countText = it.filter(Char::isDigit).take(3) },
                        isError = invalidCount,
                        singleLine = true,
                        modifier = Modifier.width(64.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono)
                    )
                    UNIT_LABELS.forEach { (u, label) ->
                        HabitChoice(
                            text = label,
                            selected = unit == u,
                            onClick = { unit = u },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
                if (invalidCount) {
                    Text(
                        text = stringResource(R.string.habit_dialog_invalid_count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Deadline: an explicit choice is required — neither option
                // starts selected and Confirm stays disabled until one is
                // tapped. Equal halves of one row, equal heights.
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
                    HabitChoice(
                        text = stringResource(R.string.habit_dialog_deadline_on_time),
                        selected = deadlineChoice == false,
                        onClick = { deadlineChoice = false },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    HabitChoice(
                        text = stringResource(R.string.habit_dialog_deadline_window),
                        selected = deadlineChoice == true,
                        onClick = { deadlineChoice = true },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
                if (deadlineUnchosen) {
                    Text(
                        text = stringResource(R.string.habit_dialog_deadline_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (deadlineChoice == true) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = windowCountText,
                            onValueChange = { windowCountText = it.filter(Char::isDigit).take(3) },
                            isError = invalidWindow,
                            singleLine = true,
                            modifier = Modifier.width(64.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono)
                        )
                        UNIT_LABELS.forEach { (u, label) ->
                            HabitChoice(
                                text = label,
                                selected = windowUnit == u,
                                onClick = { windowUnit = u },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                    if (invalidWindow) {
                        Text(
                            text = stringResource(R.string.habit_dialog_invalid_window),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.habit_dialog_writing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Live preview of the exact Org timestamp that will be written.
                date?.let { d ->
                    val window = if (deadlineChoice == true) "/${windowCount ?: "?"}${windowUnit}" else ""
                    Text(
                        text = "SCHEDULED: <${d.format(DATE_INPUT_FORMAT)} ${d.format(WEEKDAY_FORMAT)} " +
                            "$repeaterType${count ?: "?"}$unit$window>",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    val d = date ?: return@TextButton
                    val c = count ?: return@TextButton
                    onConfirm(
                        OrgHeadingStyleService.HabitSchedule(
                            date = d,
                            repeaterType = repeaterType,
                            count = c,
                            unit = unit,
                            deadlineCount = if (deadlineChoice == true) windowCount else null,
                            deadlineUnit = windowUnit
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.habit_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * One equal-share option segment: outlined when idle, primary-tinted when
 * selected. Centered single-line label inside a bounded-height surface, so
 * equal-width choices in one row can never collapse into vertical text.
 */
@Composable
private fun HabitChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
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
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Prefills the draft from the entry's parsed habit when it already is one —
 * date/cadence (and window magnitude) only; the deadline CHOICE is never
 * silently selected. Counts are reversed from the parser's day counts with
 * the same org-habit-duration-to-days factors the write path uses.
 */
private fun OrgAgendaEntry.existingHabitSchedule(): HabitScheduleDraft? {
    val habit = habit ?: return null
    val cadence = cadenceForDays(habit.srDays) ?: return null
    val window = habit.drDays?.let { cadenceForDays(it) }
    return HabitScheduleDraft(
        dateText = habit.scheduled.format(DATE_INPUT_FORMAT),
        repeaterType = habit.srType,
        countText = cadence.first.toString(),
        unit = cadence.second,
        windowCountText = window?.first?.toString() ?: "3",
        windowUnit = window?.second ?: 'd'
    )
}

/** Smallest count+unit whose org-habit duration floors to [days], preferring the largest unit. */
private fun cadenceForDays(days: Int): Pair<Int, Char>? {
    if (days < 1) return null
    for ((unit, factor) in listOf('y' to 365.25, 'm' to 30.4, 'w' to 7.0, 'd' to 1.0)) {
        var count = 1
        while (count * factor < days + 1) {
            if (Math.floor(count * factor).toInt() == days) return count to unit
            count++
        }
    }
    return null
}

private val DATE_INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val WEEKDAY_FORMAT = DateTimeFormatter.ofPattern("EEE", Locale.US)
private val REPEATER_TYPES = listOf("+", "++", ".+")
private val UNIT_LABELS = listOf('d' to "Day", 'w' to "Week", 'm' to "Month", 'y' to "Year")
private val DEFAULT_DRAFT = HabitScheduleDraft(
    dateText = LocalDate.now().format(DATE_INPUT_FORMAT),
    repeaterType = "++",
    countText = "1",
    unit = 'd',
    windowCountText = "3",
    windowUnit = 'd'
)
