package com.orgutil.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orgutil.domain.agenda.OrgAgenda
import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.ui.components.OrgStateChip
import com.orgutil.ui.components.orgStateIsDone
import com.orgutil.ui.components.PriorityChip
import com.orgutil.ui.theme.OrgMono
import com.orgutil.ui.viewmodel.AgendaUiState
import com.orgutil.ui.viewmodel.AgendaViewMode
import com.orgutil.ui.viewmodel.AgendaViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgendaScreen(
    onFileSelected: (Uri, Int?, Int?, String?) -> Unit,
    viewModel: AgendaViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Re-enters composition on every tab switch back to Agenda: silently
    // reload so edits (also external ones) show up without tapping refresh.
    LaunchedEffect(Unit) {
        viewModel.refresh(showLoading = false)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "GTD Agenda",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = remember { LocalDate.now().format(HEADER_DATE) },
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh agenda"
                            )
                        }
                    }
                )
                AgendaModePills(
                    selectedMode = uiState.selectedMode,
                    onModeSelected = viewModel::setMode
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> LoadingIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )

                uiState.error != null -> AgendaError(
                    message = uiState.error.orEmpty(),
                    onDismiss = viewModel::clearError,
                    modifier = Modifier.align(Alignment.Center)
                )

                uiState.agenda == null -> Text(
                    text = "No agenda data",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge
                )

                else -> AgendaContent(
                    uiState = uiState,
                    onFileSelected = onFileSelected
                )
            }
        }
    }
}

/** Draft pills: active = highest container + teal dot; inactive = low container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaModePills(
    selectedMode: AgendaViewMode,
    onModeSelected: (AgendaViewMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AgendaViewMode.entries.forEach { mode ->
            val active = selectedMode == mode
            Surface(
                onClick = { onModeSelected(mode) },
                shape = RoundedCornerShape(8.dp),
                color = if (active) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (active) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgendaContent(
    uiState: AgendaUiState,
    onFileSelected: (Uri, Int?, Int?, String?) -> Unit
) {
    val agenda = requireNotNull(uiState.agenda)
    val sections = agenda.sectionsFor(uiState.selectedMode)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (uiState.selectedMode == AgendaViewMode.DAILY) {
            item { AgendaStatsCard(sections = sections, goalText = agenda.goalText) }
        }

        sections.forEach { section ->
            item(key = "${section.title}-card") {
                AgendaSectionCard(section) { entry ->
                    onFileSelected(
                        entry.uri,
                        entry.titleOffset,
                        entry.title.length,
                        null
                    )
                }
            }
        }
    }
}

/**
 * Draft stats card: today ring progress + goal + mono counts
 * (Today / Next / Waiting / Inbox from the daily sections).
 */
@Composable
private fun AgendaStatsCard(
    sections: List<AgendaSection>,
    goalText: String
) {
    val today = sections.firstOrNull { it.title == "Today" }?.entries.orEmpty()
    val counts = sections.map { it.entries.size }
    val doneToday = today.count { it.todo != null && orgStateIsDone(it.todo) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                val progress = if (today.isEmpty()) 0f else doneToday.toFloat() / today.size
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 3.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                if (goalText.isNotBlank()) {
                    Text(
                        text = goalText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "Today",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "$doneToday of ${today.size} done",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Text(
                    text = "T:${counts.getOrElse(0) { 0 }} · N:${counts.getOrElse(1) { 0 }} · " +
                        "W:${counts.getOrElse(2) { 0 }} · I:${counts.getOrElse(3) { 0 }}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}


/** One section = one card holding the rows (draft grouping). */
@Composable
private fun AgendaSectionCard(
    section: AgendaSection,
    onEntryClick: (OrgAgendaEntry) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column {
            // Section header: chevron + bold small title + mono count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${section.entries.size}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (section.entries.isEmpty()) {
                Text(
                    text = section.emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            } else {
                section.entries.forEachIndexed { index, entry ->
                    AgendaEntryRow(
                        entry = entry,
                        showDivider = index > 0,
                        onClick = { onEntryClick(entry) }
                    )
                }
            }
        }
    }
}

/** Draft row: mono breadcrumb above, chips + title left, mono time right. */
@Composable
private fun AgendaEntryRow(
    entry: OrgAgendaEntry,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }
        if (entry.parentTitles.isNotEmpty()) {
            Text(
                text = entry.parentTitles.joinToString(" > "),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            entry.priority?.let { PriorityChip(priority = it) }
            entry.todo?.let { OrgStateChip(state = it) }
            Text(
                text = entry.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (entry.todo != null && orgStateIsDone(entry.todo)) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            EntryTimeChip(entry)
        }
    }
}

/** Right-aligned mono time: deadline on error tint, scheduled plain, else —. */
@Composable
private fun EntryTimeChip(entry: OrgAgendaEntry) {
    val (text, urgent) = when {
        entry.deadline != null -> entry.deadline.format(SHORT_DATE).let { it to true }
        entry.scheduled != null -> entry.scheduled.format(SHORT_DATE).let { it to false }
        entry.timestamp != null -> entry.timestamp.format(SHORT_DATE).let { it to false }
        else -> "—" to false
    }
    val prefix = when {
        entry.deadline != null -> "D: "
        entry.scheduled != null -> "S: "
        else -> ""
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (urgent) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Text(
            text = prefix + text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
            color = if (urgent) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun AgendaError(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}

private data class AgendaSection(
    val title: String,
    val entries: List<OrgAgendaEntry>,
    val emptyText: String
)

private fun OrgAgenda.sectionsFor(mode: AgendaViewMode): List<AgendaSection> {
    return when (mode) {
        AgendaViewMode.DAILY -> listOf(
            AgendaSection("Today", daily.today, "No planned items for today"),
            AgendaSection("Next actions", daily.nextActions, "No unplanned next actions"),
            AgendaSection("Waiting / follow-up", daily.waiting, "No unplanned waiting items"),
            AgendaSection("Inbox to clarify", daily.inbox, "Inbox is empty")
        )

        AgendaViewMode.WEEKLY -> listOf(
            AgendaSection("Next 14 days", weekly.nextDays, "No planned items in the next 14 days"),
            AgendaSection("Stuck Projects", weekly.stuckProjects, "No stuck projects"),
            AgendaSection("Waiting", weekly.waiting, "No waiting items"),
            AgendaSection("On hold", weekly.hold, "No hold items"),
            AgendaSection("Someday / Maybe", weekly.maybe, "No someday items"),
            AgendaSection("Inbox", weekly.inbox, "Inbox is empty")
        )

        AgendaViewMode.PROJECTS -> listOf(
            AgendaSection("GTD Projects", projectControl.projects, "No projects"),
            AgendaSection("Stuck Projects", projectControl.stuckProjects, "No stuck projects")
        )

        AgendaViewMode.AREAS -> listOf(
            AgendaSection("GTD Areas", areaControl.areas, "No areas"),
            AgendaSection("Neglected Areas", areaControl.neglectedAreas, "No neglected areas")
        )
    }
}

private val HEADER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM dd")
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd")

private val AgendaViewMode.label: String
    get() = when (this) {
        AgendaViewMode.DAILY -> "Daily"
        AgendaViewMode.WEEKLY -> "Weekly"
        AgendaViewMode.PROJECTS -> "Projects"
        AgendaViewMode.AREAS -> "Areas"
    }
