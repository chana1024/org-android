package com.orgutil.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
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
