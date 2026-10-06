package com.orgutil.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import com.orgutil.ui.theme.OrgMono

/**
 * Draft app bar: a slim custom 56dp bar — not the M3 TopAppBar. Title is
 * 16sp bold with an optional mono chip beside it; 32dp icon buttons on
 * the right; optional mono trailing text (counters) before the actions.
 *
 * [applyStatusInset] must be true ONLY for screens that own the window
 * edge (capture, editor — direct nav destinations). Tab screens render
 * inside MainScreen, whose own top bar already consumes the status-bar
 * inset; padding again would double it.
 */
@Composable
fun OrgTopBar(
    title: String,
    modifier: Modifier = Modifier,
    applyStatusInset: Boolean = true,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector? = null,
    titleChip: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
    monoTrailing: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(if (applyStatusInset) Modifier.statusBarsPadding() else Modifier)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(32.dp)
                        .padding(end = 4.dp)
                ) {
                    backIcon?.let {
                        Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                if (titleContent != null) {
                    titleContent()
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        titleChip?.invoke()
                    }
                }
                subtitle?.invoke()
            }
            monoTrailing?.invoke()
            actions?.invoke(this)
        }
    }
}

/** Draft icon button: 32dp target with an 18dp glyph. */
@Composable
fun OrgTopBarIcon(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Mono chip beside the title (repo name, dates). */
@Composable
fun OrgMonoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun Modifier.orgTopBarIcon(): Modifier = this
