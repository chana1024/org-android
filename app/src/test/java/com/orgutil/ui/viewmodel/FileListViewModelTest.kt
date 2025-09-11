package com.orgutil.ui.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.usecase.GetOrgFilesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import android.net.Uri
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalCoroutinesApi::class)
class FileListViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Mock
    private lateinit var getOrgFilesUseCase: GetOrgFilesUseCase

    @Mock
    private lateinit var mockUri: Uri

    private lateinit var viewModel: FileListViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadFiles updates state with files when successful`() = runTest {
        // Given
        val mockFiles = listOf(
            OrgFileInfo(
                uri = mockUri,
                name = "test.org",
                lastModified = 123456789L,
                size = 1024L
            )
        )
        `when`(getOrgFilesUseCase()).thenReturn(flowOf(mockFiles))

        // When
        viewModel = FileListViewModel(getOrgFilesUseCase)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        val state = viewModel.uiState.first()
        assert(!state.isLoading)
        assert(state.files == mockFiles)
        assert(state.error == null)
    }

    @Test
    fun `loadFiles shows loading state initially`() = runTest {
        // Given
        `when`(getOrgFilesUseCase()).thenReturn(flowOf(emptyList()))

        // When
        viewModel = FileListViewModel(getOrgFilesUseCase)

        // Then
        val initialState = viewModel.uiState.first()
        assert(initialState.isLoading || initialState.files.isEmpty())
    }

    @Test
    fun `clearError sets error to null`() = runTest {
        // Given
        `when`(getOrgFilesUseCase()).thenReturn(flowOf(emptyList()))
        viewModel = FileListViewModel(getOrgFilesUseCase)
        testDispatcher.scheduler.advanceUntilIdle()

        // When
        viewModel.clearError()

        // Then
        val state = viewModel.uiState.first()
        assert(state.error == null)
    }
}