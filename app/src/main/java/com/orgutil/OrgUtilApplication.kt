package com.orgutil

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.orgutil.domain.indexing.FileIndexRequestResult
import com.orgutil.domain.indexing.FileIndexScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OrgUtilApplication: Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var fileIndexScheduler: FileIndexScheduler

    override val workManagerConfiguration: Configuration
        get() {
            safeLogD("OrgUtilApplication", "Creating WorkManager configuration with HiltWorkerFactory. Factory injected: ${::workerFactory.isInitialized}")
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(Log.DEBUG)
                .build()
        }

    override fun onCreate() {
        super.onCreate()
        safeLogD("OrgUtilApplication", "Application onCreate called")
        setupFileIndexer()
    }

    private fun setupFileIndexer() {
        safeLogD("OrgUtilApplication", "Setting up FileIndexer worker")
        when (val result = fileIndexScheduler.ensurePeriodicIndexing()) {
            FileIndexRequestResult.Enqueued -> {
                safeLogD("OrgUtilApplication", "FileIndexer worker scheduled successfully")
            }
            is FileIndexRequestResult.Failed -> {
                safeLogE("OrgUtilApplication", "Failed to setup FileIndexer worker: ${result.message}")
            }
        }
    }
}

private fun safeLogD(tag: String, message: String) {
    runCatching { Log.d(tag, message) }
}

private fun safeLogE(tag: String, message: String, throwable: Throwable? = null) {
    runCatching {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
