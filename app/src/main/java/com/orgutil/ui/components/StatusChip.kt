package com.orgutil.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.orgutil.R
import com.orgutil.domain.indexing.FileIndexStatus
import com.orgutil.domain.sync.GitSyncStatus
import androidx.compose.ui.res.stringResource

/**
 * Non-interactive status chips shared by the Files header and elsewhere:
 * file indexing state and git sync state, each with a busy spinner.
 */

@Composable
fun IndexStatusChip(
    status: FileIndexStatus,
    isRequestInFlight: Boolean,
    modifier: Modifier = Modifier
) {
    val text = when (status) {
        FileIndexStatus.Idle -> stringResource(R.string.index_status_idle)
        FileIndexStatus.Enqueued -> stringResource(R.string.index_status_queued)
        FileIndexStatus.Running -> stringResource(R.string.index_status_running)
        FileIndexStatus.Succeeded -> stringResource(R.string.index_status_succeeded)
        is FileIndexStatus.Failed -> stringResource(R.string.index_status_failed, status.message)
    }

    AssistChip(
        modifier = modifier,
        onClick = {},
        enabled = false,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            if (isRequestInFlight || status == FileIndexStatus.Running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    )
}

@Composable
fun GitSyncStatusChip(
    status: GitSyncStatus,
    modifier: Modifier = Modifier
) {
    val text = when (status) {
        GitSyncStatus.Idle -> stringResource(R.string.git_sync_status_idle)
        GitSyncStatus.Enqueued -> stringResource(R.string.git_sync_status_queued)
        is GitSyncStatus.Running -> stringResource(R.string.git_sync_status_running)
        is GitSyncStatus.Succeeded -> stringResource(
            R.string.git_sync_status_succeeded,
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(status.at))
        )
        is GitSyncStatus.Conflict ->
            stringResource(R.string.git_sync_status_conflict, status.files.size)
        is GitSyncStatus.Failed ->
            stringResource(R.string.git_sync_status_failed, status.message)
    }
    val isBusy = status is GitSyncStatus.Running || status is GitSyncStatus.Enqueued

    AssistChip(
        modifier = modifier,
        onClick = {},
        enabled = false,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    )
}
