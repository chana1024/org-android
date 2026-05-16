package com.orgutil.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import com.orgutil.domain.indexing.FileIndexRequestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class WorkManagerFileIndexSchedulerTest {

    @Test
    fun `requestIndexing enqueues unique one time work`() {
        val gateway = RecordingWorkManagerGateway()
        val scheduler = WorkManagerFileIndexScheduler(gateway)

        val result = scheduler.requestIndexing()

        assertTrue(result === FileIndexRequestResult.Enqueued)
        assertEquals("file_indexing", gateway.oneTimeName)
        assertEquals(ExistingWorkPolicy.KEEP, gateway.oneTimePolicy)
        val request = gateway.oneTimeRequest
        assertEquals(FileIndexerWorker::class.java.name, request?.workSpec?.workerClassName)
    }

    @Test
    fun `requestIndexing returns failure when gateway throws`() {
        val scheduler = WorkManagerFileIndexScheduler(
            ThrowingWorkManagerGateway(IllegalStateException("boom"))
        )

        val result = scheduler.requestIndexing()

        assertEquals(FileIndexRequestResult.Failed("boom"), result)
    }

    @Test
    fun `ensurePeriodicIndexing enqueues unique periodic work`() {
        val gateway = RecordingWorkManagerGateway()
        val scheduler = WorkManagerFileIndexScheduler(gateway)

        val result = scheduler.ensurePeriodicIndexing()

        assertTrue(result === FileIndexRequestResult.Enqueued)
        assertEquals("file-indexer", gateway.periodicName)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, gateway.periodicPolicy)
        val request = gateway.periodicRequest
        assertEquals(FileIndexerWorker::class.java.name, request?.workSpec?.workerClassName)
        assertEquals(TimeUnit.MINUTES.toMillis(15), request?.workSpec?.intervalDuration)
    }

    @Test
    fun `ensurePeriodicIndexing returns failure when gateway throws`() {
        val scheduler = WorkManagerFileIndexScheduler(
            ThrowingWorkManagerGateway(IllegalStateException("periodic boom"))
        )

        val result = scheduler.ensurePeriodicIndexing()

        assertEquals(FileIndexRequestResult.Failed("periodic boom"), result)
    }

    private class RecordingWorkManagerGateway : WorkManagerGateway {
        var oneTimeName: String? = null
        var oneTimePolicy: ExistingWorkPolicy? = null
        var oneTimeRequest: OneTimeWorkRequest? = null
        var periodicName: String? = null
        var periodicPolicy: ExistingPeriodicWorkPolicy? = null
        var periodicRequest: PeriodicWorkRequest? = null

        override fun enqueueUniqueWork(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest
        ) {
            oneTimeName = name
            oneTimePolicy = policy
            oneTimeRequest = request
        }

        override fun enqueueUniquePeriodicWork(
            name: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest
        ) {
            periodicName = name
            periodicPolicy = policy
            periodicRequest = request
        }
    }

    private class ThrowingWorkManagerGateway(
        private val throwable: Throwable
    ) : WorkManagerGateway {
        override fun enqueueUniqueWork(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest
        ) {
            throw throwable
        }

        override fun enqueueUniquePeriodicWork(
            name: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest
        ) {
            throw throwable
        }
    }
}
