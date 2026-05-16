package com.orgutil.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(
    onFileSelected: (Uri) -> Unit,
    onNavigateToCapture: () -> Unit
) {
    // Default to files tab, and preserve the selected tab when navigating away/back
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }

    Row(modifier = Modifier.fillMaxSize()) {
        // Vertical tabs on the left edge
        VerticalTabBar(
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { selectedTabIndex = it }
        )

        // Content area
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTabIndex) {
                0 -> FileListScreen(
                    onFileSelected = onFileSelected,
                    onNavigateToCapture = onNavigateToCapture
                )
                1 -> FavoritesScreen(
                    onFileSelected = onFileSelected,
                    onNavigateBack = {} // No longer needed in tabbed view
                )
            }
        }
    }
}

@Composable
private fun VerticalTabBar(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(80.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // Files tab
            VerticalTab(
                selected = selectedTabIndex == 0,
                onClick = { onTabSelected(0) },
                icon = Icons.Default.Description,
                label = "Files"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Favorites tab
            VerticalTab(
                selected = selectedTabIndex == 1,
                onClick = { onTabSelected(1) },
                icon = Icons.Default.Star,
                label = "Favorites"
            )
        }
    }
}

@Composable
private fun VerticalTab(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(80.dp)
            .height(80.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
