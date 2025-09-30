package com.orgutil

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.orgutil.worker.FileIndexerWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class OrgUtilApplication: Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() {
            Log.d("OrgUtilApplication", "Creating WorkManager configuration with HiltWorkerFactory. Factory injected: ${::workerFactory.isInitialized}")
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
        }

    override fun onCreate() {
        super.onCreate()
        Log.d("OrgUtilApplication", "Application onCreate called")
        // Delay the worker setup slightly to ensure all dependencies are ready
        setupFileIndexer()
    }

    private fun setupFileIndexer() {
        Log.d("OrgUtilApplication", "Setting up FileIndexer worker")
        try {
            val periodicWorkRequest = PeriodicWorkRequestBuilder<FileIndexerWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "file-indexer",
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
            Log.d("OrgUtilApplication", "FileIndexer worker scheduled successfully")
        } catch (e: Exception) {
            Log.e("OrgUtilApplication", "Failed to setup FileIndexer worker", e)
        }
    }
}
