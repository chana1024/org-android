package com.orgutil.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.orgutil.R

private data class MainTab(
    val label: String,
    val icon: ImageVector
)

@Composable
fun MainScreen(
    onFileSelected: (Uri, Int?, Int?, String?) -> Unit,
    onNavigateToCapture: () -> Unit
) {
    // Agenda is the GTD home; preserve the selected tab across navigation
    val tabs = listOf(
        MainTab("Agenda", Icons.AutoMirrored.Filled.EventNote),
        MainTab("Files", Icons.Default.Description),
        MainTab("Favorites", Icons.Default.Star),
        MainTab("Sync", Icons.Default.CloudSync),
        MainTab("Chat", Icons.AutoMirrored.Filled.Chat)
    )
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            // Draft slim nav bar (~60dp, flat surface-container-low) — a
            // custom bar instead of M3 NavigationBar, whose 80dp height and
            // fixed internal touch targets cannot be shrunk without
            // clipping. Navigation-bar insets are applied so nothing is
            // covered on gesture-nav devices.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.navigationBars
                    )
                ) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            val active = selectedTabIndex == index
                            val tint = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable { selectedTabIndex = index },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = tint,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = tint
                                )
                            }
                        }
                    }
                }
            }
        },
        // Quick capture is global ("OrgUtil Teal Light"): one teal FAB
        // above the nav bar instead of a per-screen button.
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCapture) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.quick_capture)
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTabIndex) {
                0 -> AgendaScreen(onFileSelected = onFileSelected)
                1 -> FileListScreen(onFileSelected = onFileSelected)
                2 -> FavoritesScreen(onFileSelected = onFileSelected)
                3 -> SyncScreen()
                4 -> ChatScreen()
            }
        }
    }
}
