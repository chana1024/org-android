package com.orgutil.ui.screens

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orgutil.domain.agenda.OrgAgenda
import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.ui.viewmodel.AgendaUiState
import com.orgutil.ui.viewmodel.AgendaViewMode
import com.orgutil.ui.viewmodel.AgendaViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(
    onFileSelected: (Uri, Int?, Int?, String?) -> Unit,
    viewModel: AgendaViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("GTD Agenda") },
                    actions = {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh agenda"
                            )
                        }
                    }
                )
                AgendaModeChips(
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
                uiState.isLoading -> CircularProgressIndicator(
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

@Composable
private fun AgendaModeChips(
    selectedMode: AgendaViewMode,
    onModeSelected: (AgendaViewMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AgendaViewMode.values().forEach { mode ->
            FilterChip(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                label = { Text(mode.label) }
            )
        }
    }
}

@Composable
private fun AgendaContent(
    uiState: AgendaUiState,
    onFileSelected: (Uri, Int?, Int?, String?) -> Unit
) {
    val agenda = requireNotNull(uiState.agenda)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (uiState.selectedMode == AgendaViewMode.DAILY && agenda.goalText.isNotBlank()) {
            item {
                GoalCard(agenda.goalText)
            }
        }

        val sections = agenda.sectionsFor(uiState.selectedMode)
        sections.forEach { section ->
            item {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (section.entries.isEmpty()) {
                item {
                    EmptySectionCard(section.emptyText)
                }
            } else {
                items(section.entries) { entry ->
                    AgendaEntryCard(
                        entry = entry,
                        onClick = {
                            onFileSelected(
                                entry.uri,
                                entry.titleOffset,
                                entry.title.length,
                                null
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalCard(goalText: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Total goal",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = goalText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaEntryCard(
    entry: OrgAgendaEntry,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (entry.parentTitles.size * 12).dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                entry.todo?.let { todo ->
                    CompactLabel(todo)
                }
                entry.priority?.let { priority ->
                    CompactLabel(
                        text = "#$priority",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Text(
                    text = entry.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.parentTitles.isNotEmpty()) {
                Text(
                    text = entry.parentTitles.joinToString(" > "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val planningText = entry.planningText()
            val detailText = listOf(entry.fileName, planningText)
                .filter { it.isNotBlank() }
                .joinToString("  ")
            if (detailText.isNotBlank()) {
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CompactLabel(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptySectionCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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

private fun OrgAgendaEntry.planningText(): String {
    return listOfNotNull(
        scheduled?.let { "Scheduled: $it" },
        deadline?.let { "Deadline: $it" },
        timestamp?.let { "Time: $it" }
    ).joinToString("  ")
}

private val AgendaViewMode.label: String
    get() = when (this) {
        AgendaViewMode.DAILY -> "Daily"
        AgendaViewMode.WEEKLY -> "Weekly"
        AgendaViewMode.PROJECTS -> "Projects"
        AgendaViewMode.AREAS -> "Areas"
    }
