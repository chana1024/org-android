package com.orgutil.domain.usecase

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.orgutil.domain.repository.GitSyncRepository
import com.orgutil.domain.sync.GitSyncOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Runs one git sync, refusing to start unless the app has direct filesystem
 * access (All files access on API 30+, WRITE_EXTERNAL_STORAGE before that).
 */
class RunGitSyncUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitSyncRepository: GitSyncRepository
) {
    suspend operator fun invoke(): Result<GitSyncOutcome> {
        if (!hasStorageAccess()) {
            return Result.failure(
                IllegalStateException(
                    "Storage access not granted. Grant All files access in Sync settings."
                )
            )
        }
        return gitSyncRepository.runSync()
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
