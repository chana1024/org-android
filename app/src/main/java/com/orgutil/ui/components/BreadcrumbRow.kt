package com.orgutil.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orgutil.ui.theme.OrgMono

/**
 * Tappable directory breadcrumb ("OrgUtil Teal Light" Files header):
 * `‹ up` pops one level, the root segment is teal (jump straight to the
 * root), ancestors are low-emphasis and the current directory is bold.
 * Long paths scroll horizontally.
 */
data class BreadcrumbCrumb(
    /** Display name (folder name or root label such as "~/org"). */
    val label: String,
    /** True when tapping this crumb navigates to the repository root. */
    val isRoot: Boolean = false
)

@Composable
fun BreadcrumbRow(
    crumbs: List<BreadcrumbCrumb>,
    onCrumbSelected: (Int) -> Unit,
    onUp: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onUp != null) {
            IconButton(onClick = onUp, modifier = Modifier.padding(end = 2.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Up one level",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        crumbs.forEachIndexed { index, crumb ->
            val isCurrent = index == crumbs.lastIndex
            val color = when {
                isCurrent -> MaterialTheme.colorScheme.onSurface
                crumb.isRoot -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = crumb.label,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = color,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clickable(enabled = !isCurrent) { onCrumbSelected(index) }
            )
            if (!isCurrent) {
                Text(
                    text = " › ",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}
