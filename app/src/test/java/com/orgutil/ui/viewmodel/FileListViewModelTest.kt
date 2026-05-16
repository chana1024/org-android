package com.orgutil.ui.viewmodel

import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.orgutil.domain.indexing.FileIndexRequestResult
import com.orgutil.domain.indexing.FileIndexScheduler
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.usecase.AddToFavoritesUseCase
import com.orgutil.domain.usecase.GetOrgFilesUseCase
import com.orgutil.domain.usecase.GetStoredDocumentTreeUseCase
import com.orgutil.domain.usecase.RemoveFromFavoritesUseCase
import com.orgutil.domain.usecase.StoreDocumentTreeUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
class FileListViewModelTest {

@get:Rule
val instantTaskExecutorRule = InstantTaskExecutorRule()

@Mock lateinit var getOrgFilesUseCase: GetOrgFilesUseCase
@Mock lateinit var addToFavoritesUseCase: AddToFavoritesUseCase
@Mock lateinit var removeFromFavoritesUseCase: RemoveFromFavoritesUseCase
@Mock lateinit var storeDocumentTreeUseCase: StoreDocumentTreeUseCase
@Mock lateinit var getStoredDocumentTreeUseCase: GetStoredDocumentTreeUseCase
@Mock lateinit var fileIndexScheduler: FileIndexScheduler
@Mock lateinit var mockUri: Uri

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

private fun createViewModel(): FileListViewModel {
return FileListViewModel(
getOrgFilesUseCase = getOrgFilesUseCase,
addToFavoritesUseCase = addToFavoritesUseCase,
removeFromFavoritesUseCase = removeFromFavoritesUseCase,
storeDocumentTreeUseCase = storeDocumentTreeUseCase,
getStoredDocumentTreeUseCase = getStoredDocumentTreeUseCase,
fileIndexScheduler = fileIndexScheduler
)
}

@Test
fun `loadFiles updates state with files when successful`() = runTest {
val mockFiles = listOf(
OrgFileInfo(
uri = mockUri,
name = "test.org",
lastModified = 123456789L,
size = 1024L
)
)
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(mockFiles))
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

val state = viewModel.uiState.value
assert(!state.isLoading)
assert(!state.isIndexRequestInFlight)
assert(state.hasPendingIndexRefresh)
assert(state.files == mockFiles)
assert(state.error == null)
}

@Test
fun `clearError sets error to null`() = runTest {
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

viewModel.clearError()

assert(viewModel.uiState.value.error == null)
}

@Test
fun `triggerIndexing updates error when scheduling fails`() = runTest {
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))
`when`(fileIndexScheduler.requestIndexing())
.thenReturn(FileIndexRequestResult.Failed("boom"))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

val state = viewModel.uiState.value
assert(state.error == "Failed to start indexing: boom")
assert(!state.isIndexRequestInFlight)
assert(!state.hasPendingIndexRefresh)
}

@Test
fun `triggerIndexing delegates to scheduler`() = runTest {
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

verify(fileIndexScheduler).requestIndexing()
assert(viewModel.uiState.value.error == null)
assert(!viewModel.uiState.value.isIndexRequestInFlight)
assert(viewModel.uiState.value.hasPendingIndexRefresh)
}

@Test
fun `refreshIndex does not reload files immediately when scheduling succeeds`() = runTest {
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))
`when`(fileIndexScheduler.requestIndexing())
.thenReturn(FileIndexRequestResult.Enqueued)
.thenReturn(FileIndexRequestResult.Enqueued)

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

viewModel.refreshIndex()
testDispatcher.scheduler.advanceUntilIdle()

verify(fileIndexScheduler, times(2)).requestIndexing()
verify(getOrgFilesUseCase, times(1)).invoke(null, "", true)
verifyNoMoreInteractions(getOrgFilesUseCase)
assert(!viewModel.uiState.value.isIndexRequestInFlight)
assert(viewModel.uiState.value.hasPendingIndexRefresh)
assert(viewModel.uiState.value.error == null)
}

@Test
fun `refreshIndex does not reload files when scheduling fails`() = runTest {
val failure = IllegalStateException("boom")
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))
`when`(fileIndexScheduler.requestIndexing())
.thenReturn(FileIndexRequestResult.Enqueued)
.thenReturn(FileIndexRequestResult.Failed(failure.message ?: "Unknown failure"))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

viewModel.refreshIndex()
testDispatcher.scheduler.advanceUntilIdle()

verify(fileIndexScheduler, times(2)).requestIndexing()
verify(getOrgFilesUseCase, times(1)).invoke(null, "", true)
verifyNoMoreInteractions(getOrgFilesUseCase)
assert(viewModel.uiState.value.error == "Failed to start indexing: boom")
assert(!viewModel.uiState.value.isIndexRequestInFlight)
assert(viewModel.uiState.value.hasPendingIndexRefresh)
}

@Test
fun `onDirectoryClicked pushes current directory to history and clears search`() = runTest {
val rootUri = org.mockito.Mockito.mock(Uri::class.java)
val childUri = org.mockito.Mockito.mock(Uri::class.java)
val childDirectory = OrgFileInfo(
uri = childUri,
name = "child",
lastModified = 0L,
isDirectory = true,
size = 0L,
isFavorite = false
)

`when`(rootUri.pathSegments).thenReturn(listOf("tree", "root"))
`when`(childUri.pathSegments).thenReturn(listOf("tree", "root", "child"))
`when`(getStoredDocumentTreeUseCase()).thenReturn(rootUri)
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)
`when`(getOrgFilesUseCase(rootUri, "", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(rootUri, "needle", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(childUri, "", true)).thenReturn(flowOf(emptyList()))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

viewModel.onSearchQueryChanged("needle")
testDispatcher.scheduler.advanceUntilIdle()
viewModel.onDirectoryClicked(childDirectory)
testDispatcher.scheduler.advanceUntilIdle()

val state = viewModel.uiState.value
assert(state.searchQuery.isEmpty())
assert(state.pathHistory == listOf(rootUri))
assert(state.currentDirectory?.uri == childUri)
verify(getOrgFilesUseCase).invoke(rootUri, "", true)
verify(getOrgFilesUseCase).invoke(childUri, "", true)
}

@Test
fun `onBackButtonPressed reloads parent directory and clears search`() = runTest {
val rootUri = org.mockito.Mockito.mock(Uri::class.java)
val childUri = org.mockito.Mockito.mock(Uri::class.java)
val childDirectory = OrgFileInfo(
uri = childUri,
name = "child",
lastModified = 0L,
isDirectory = true,
size = 0L,
isFavorite = false
)

`when`(rootUri.pathSegments).thenReturn(listOf("tree", "root"))
`when`(childUri.pathSegments).thenReturn(listOf("tree", "root", "child"))
`when`(getStoredDocumentTreeUseCase()).thenReturn(rootUri)
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)
`when`(getOrgFilesUseCase(rootUri, "", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(childUri, "", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(childUri, "needle", true)).thenReturn(flowOf(emptyList()))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

viewModel.onDirectoryClicked(childDirectory)
testDispatcher.scheduler.advanceUntilIdle()
viewModel.onSearchQueryChanged("needle")
testDispatcher.scheduler.advanceUntilIdle()

val handled = viewModel.onBackButtonPressed()
testDispatcher.scheduler.advanceUntilIdle()

val state = viewModel.uiState.value
assert(handled)
assert(state.searchQuery.isEmpty())
assert(state.pathHistory.isEmpty())
assert(state.currentDirectory?.uri == rootUri)
verify(getOrgFilesUseCase, times(2)).invoke(rootUri, "", true)
verify(getOrgFilesUseCase).invoke(childUri, "", true)
}

@Test
fun `setSearchMode switches to file list while browsing a directory`() = runTest {
val rootUri = org.mockito.Mockito.mock(Uri::class.java)
val childUri = org.mockito.Mockito.mock(Uri::class.java)
val childDirectory = OrgFileInfo(
uri = childUri,
name = "child",
lastModified = 0L,
isDirectory = true,
size = 0L,
isFavorite = false
)

`when`(rootUri.pathSegments).thenReturn(listOf("tree", "root"))
`when`(childUri.pathSegments).thenReturn(listOf("tree", "root", "child"))
`when`(getStoredDocumentTreeUseCase()).thenReturn(rootUri)
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)
`when`(getOrgFilesUseCase(rootUri, "", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(childUri, "", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(childUri, "needle", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(childUri, "needle", false)).thenReturn(flowOf(emptyList()))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()
viewModel.onDirectoryClicked(childDirectory)
testDispatcher.scheduler.advanceUntilIdle()
viewModel.onSearchQueryChanged("needle")
testDispatcher.scheduler.advanceUntilIdle()

viewModel.setSearchMode(FileListQueryMode.FILE_LIST)
testDispatcher.scheduler.advanceUntilIdle()

val state = viewModel.uiState.value
assert(state.queryMode == FileListQueryMode.FILE_LIST)
verify(getOrgFilesUseCase).invoke(childUri, "needle", true)
verify(getOrgFilesUseCase).invoke(childUri, "needle", false)
}

@Test
fun `stored tree startup keeps full text mode and loads root directory`() = runTest {
val rootUri = org.mockito.Mockito.mock(Uri::class.java)

`when`(rootUri.pathSegments).thenReturn(listOf("tree", "root"))
`when`(getStoredDocumentTreeUseCase()).thenReturn(rootUri)
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)
`when`(getOrgFilesUseCase(rootUri, "", true)).thenReturn(flowOf(emptyList()))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

val state = viewModel.uiState.value
assert(state.queryMode == FileListQueryMode.FULL_TEXT)
assert(state.currentDirectory?.uri == rootUri)
}

@Test
fun `setSearchMode switches between full text and file list`() = runTest {
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

assert(viewModel.uiState.value.queryMode == FileListQueryMode.FULL_TEXT)

viewModel.setSearchMode(FileListQueryMode.FILE_LIST)
testDispatcher.scheduler.advanceUntilIdle()
assert(viewModel.uiState.value.queryMode == FileListQueryMode.FILE_LIST)

viewModel.setSearchMode(FileListQueryMode.FULL_TEXT)
testDispatcher.scheduler.advanceUntilIdle()
assert(viewModel.uiState.value.queryMode == FileListQueryMode.FULL_TEXT)
}

@Test
fun `setSearchMode to current mode does not trigger extra reload`() = runTest {
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()

viewModel.setSearchMode(FileListQueryMode.FULL_TEXT)
testDispatcher.scheduler.advanceUntilIdle()

assert(viewModel.uiState.value.queryMode == FileListQueryMode.FULL_TEXT)
verify(getOrgFilesUseCase, times(1)).invoke(null, "", true)
}

@Test
fun `setSearchMode reloads current query with file list semantics`() = runTest {
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(null, "needle", false)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(null, "needle", true)).thenReturn(flowOf(emptyList()))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()
viewModel.onSearchQueryChanged("needle")
testDispatcher.scheduler.advanceUntilIdle()

viewModel.setSearchMode(FileListQueryMode.FILE_LIST)
testDispatcher.scheduler.advanceUntilIdle()

assert(viewModel.uiState.value.queryMode == FileListQueryMode.FILE_LIST)
verify(getOrgFilesUseCase).invoke(null, "needle", true)
verify(getOrgFilesUseCase).invoke(null, "needle", false)
}

@Test
fun `clearing query in full text keeps mode but reloads empty search`() = runTest {
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(null, "needle", false)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(null, "needle", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()
viewModel.onSearchQueryChanged("needle")
testDispatcher.scheduler.advanceUntilIdle()
viewModel.onSearchQueryChanged("")
testDispatcher.scheduler.advanceUntilIdle()

val state = viewModel.uiState.value
assert(state.queryMode == FileListQueryMode.FULL_TEXT)
assert(state.searchQuery.isEmpty())
verify(getOrgFilesUseCase, times(2)).invoke(null, "", true)
}

@Test
fun `onDocumentTreeSelected keeps current search mode and clears query`() = runTest {
val rootUri = org.mockito.Mockito.mock(Uri::class.java)

`when`(rootUri.pathSegments).thenReturn(listOf("tree", "root"))
`when`(getStoredDocumentTreeUseCase()).thenReturn(null)
`when`(fileIndexScheduler.requestIndexing()).thenReturn(FileIndexRequestResult.Enqueued)
`when`(getOrgFilesUseCase(null, "", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(null, "needle", true)).thenReturn(flowOf(emptyList()))
`when`(getOrgFilesUseCase(rootUri, "", true)).thenReturn(flowOf(emptyList()))

viewModel = createViewModel()
testDispatcher.scheduler.advanceUntilIdle()
viewModel.onSearchQueryChanged("needle")
testDispatcher.scheduler.advanceUntilIdle()
viewModel.onDocumentTreeSelected(rootUri)
testDispatcher.scheduler.advanceUntilIdle()

val state = viewModel.uiState.value
assert(state.queryMode == FileListQueryMode.FULL_TEXT)
assert(state.searchQuery.isEmpty())
assert(state.currentDirectory?.uri == rootUri)
verify(getOrgFilesUseCase).invoke(null, "needle", true)
verify(getOrgFilesUseCase).invoke(rootUri, "", true)
}
}
