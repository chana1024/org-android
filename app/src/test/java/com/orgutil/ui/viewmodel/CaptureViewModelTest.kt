package com.orgutil.ui.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.orgutil.domain.sync.GitSyncRequestResult
import com.orgutil.domain.sync.GitSyncScheduler
import com.orgutil.domain.usecase.AddToCaptureFileUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Mock lateinit var addToCaptureFileUseCase: AddToCaptureFileUseCase
    @Mock lateinit var gitSyncScheduler: GitSyncScheduler

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: CaptureViewModel

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        viewModel = CaptureViewModel(addToCaptureFileUseCase, gitSyncScheduler)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `successful quick capture requests configured git sync`() = runTest {
        `when`(addToCaptureFileUseCase("capture")).thenReturn(Result.success(Unit))
        `when`(gitSyncScheduler.requestSyncIfConfigured())
            .thenReturn(GitSyncRequestResult.Enqueued)

        viewModel.addToCaptureFile("capture")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(gitSyncScheduler).requestSyncIfConfigured()
        assert(viewModel.uiState.value.successMessage != null)
        assert(viewModel.uiState.value.error == null)
    }

    @Test
    fun `failed quick capture does not request git sync`() = runTest {
        `when`(addToCaptureFileUseCase("capture"))
            .thenReturn(Result.failure(IllegalStateException("write failed")))

        viewModel.addToCaptureFile("capture")
        testDispatcher.scheduler.advanceUntilIdle()

        verifyNoInteractions(gitSyncScheduler)
        assert(viewModel.uiState.value.successMessage == null)
        assert(viewModel.uiState.value.error == "添加失败: write failed")
    }
}
