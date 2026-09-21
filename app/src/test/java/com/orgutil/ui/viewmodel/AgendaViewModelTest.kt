package com.orgutil.ui.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.orgutil.domain.agenda.AreaControlAgenda
import com.orgutil.domain.agenda.DailyAgenda
import com.orgutil.domain.agenda.OrgAgenda
import com.orgutil.domain.agenda.ProjectControlAgenda
import com.orgutil.domain.agenda.WeeklyAgenda
import com.orgutil.domain.usecase.GetOrgAgendaUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Mock lateinit var getOrgAgendaUseCase: GetOrgAgendaUseCase

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
    fun `refresh loads agenda into state`() = runTest {
        val agenda = emptyAgenda(goalText = "Goal")
        `when`(getOrgAgendaUseCase()).thenReturn(Result.success(agenda))

        val viewModel = AgendaViewModel(getOrgAgendaUseCase)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(agenda, viewModel.uiState.value.agenda)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `setMode updates selected agenda view`() = runTest {
        `when`(getOrgAgendaUseCase()).thenReturn(Result.success(emptyAgenda()))

        val viewModel = AgendaViewModel(getOrgAgendaUseCase)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setMode(AgendaViewMode.PROJECTS)

        assertEquals(AgendaViewMode.PROJECTS, viewModel.uiState.value.selectedMode)
    }

    private fun emptyAgenda(goalText: String = "") = OrgAgenda(
        goalText = goalText,
        daily = DailyAgenda(emptyList(), emptyList(), emptyList(), emptyList()),
        weekly = WeeklyAgenda(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        projectControl = ProjectControlAgenda(emptyList(), emptyList()),
        areaControl = AreaControlAgenda(emptyList(), emptyList())
    )
}
