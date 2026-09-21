package com.orgutil.ui.viewmodel

import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.repository.OrgFileRepository
import com.orgutil.domain.usecase.ReadOrgFileUseCase
import com.orgutil.domain.usecase.SaveOrgFileUseCase
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
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
class FileEditorViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Mock lateinit var repository: OrgFileRepository
    @Mock lateinit var uri: Uri

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: FileEditorViewModel

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        viewModel = FileEditorViewModel(
            readOrgFileUseCase = ReadOrgFileUseCase(repository),
            saveOrgFileUseCase = SaveOrgFileUseCase(repository)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadFile stores valid highlight range and switches to edit mode`() = runTest {
        val document = OrgDocument(
            uri = uri,
            fileName = "test.org",
            content = "alpha needle omega",
            lastModified = 0L,
            nodes = emptyList()
        )
        `when`(repository.readOrgFile(uri)).thenReturn(Result.success(document))

        viewModel.loadFile(uri, highlightOffset = 6, highlightLength = 6)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assert(!state.isInViewMode)
        assert(state.highlightStart == 6)
        assert(state.highlightEnd == 12)
    }

    @Test
    fun `loadFile recomputes highlight from query instead of trusting stale offset`() = runTest {
        val document = OrgDocument(
            uri = uri,
            fileName = "test.org",
            content = "changed prefix needle omega",
            lastModified = 0L,
            nodes = emptyList()
        )
        `when`(repository.readOrgFile(uri)).thenReturn(Result.success(document))

        viewModel.loadFile(uri, highlightOffset = 0, highlightLength = 6, highlightQuery = "needle")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assert(!state.isInViewMode)
        assert(state.highlightStart == document.content.indexOf("needle"))
        assert(state.highlightEnd == document.content.indexOf("needle") + "needle".length)
    }
}
