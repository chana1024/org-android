package com.orgutil.domain.agenda

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.LocalDate

class OrgAgendaBuilderTest {

    private val parser = OrgAgendaParser()
    private val builder = OrgAgendaBuilder()

    @Test
    fun `daily dashboard includes unplanned next and wait actions but skips planned actions`() {
        val entries = parse(
            "gtd.org",
            """
                * NEXT Unplanned action
                * NEXT Scheduled action
                SCHEDULED: <2026-05-16 Sat>
                * WAIT Waiting action
                * WAIT Deadline action
                DEADLINE: <2026-05-16 Sat>
            """.trimIndent()
        )

        val agenda = builder.build(
            entries = entries,
            goalText = "Goal",
            today = LocalDate.of(2026, 5, 16)
        )

        assertEquals(listOf("Unplanned action"), agenda.daily.nextActions.map { it.title })
        assertEquals(listOf("Waiting action"), agenda.daily.waiting.map { it.title })
    }

    @Test
    fun `project control lists only projects and stuck projects have no next or wait child`() {
        val entries = parse(
            "projects.org",
            """
                * PROJ Active project
                ** NEXT Do it
                * PROJ Stuck project
                ** TODO Clarify
            """.trimIndent()
        )

        val agenda = builder.build(
            entries = entries,
            goalText = "",
            today = LocalDate.of(2026, 5, 16)
        )

        assertEquals(listOf("Active project", "Stuck project"), agenda.projectControl.projects.map { it.title })
        assertEquals(listOf("Stuck project"), agenda.projectControl.stuckProjects.map { it.title })
    }

    @Test
    fun `area control identifies neglected areas by missing project child and future trigger`() {
        val entries = parse(
            "areas.org",
            """
                * AREA Neglected area
                * AREA Active area
                ** PROJ Related project
                * AREA Reviewed area
                SCHEDULED: <2026-06-01 Mon>
            """.trimIndent()
        )

        val agenda = builder.build(
            entries = entries,
            goalText = "",
            today = LocalDate.of(2026, 5, 16)
        )

        assertEquals(
            listOf("Neglected area", "Active area", "Reviewed area"),
            agenda.areaControl.areas.map { it.title }
        )
        assertEquals(listOf("Neglected area"), agenda.areaControl.neglectedAreas.map { it.title })
    }

    @Test
    fun `weekly review includes next fourteen days and review pools`() {
        val entries = parse(
            "mixed.org",
            """
                * TODO Due soon
                DEADLINE: <2026-05-20 Wed>
                * TODO Due later
                DEADLINE: <2026-06-15 Mon>
                * WAIT External
                * HOLD Paused
                * MAYBE Someday
                * TODO Inbox item :INBOX:
            """.trimIndent()
        )

        val agenda = builder.build(
            entries = entries,
            goalText = "",
            today = LocalDate.of(2026, 5, 16)
        )

        assertEquals(listOf("Due soon"), agenda.weekly.nextDays.map { it.title })
        assertEquals(listOf("External"), agenda.weekly.waiting.map { it.title })
        assertEquals(listOf("Paused"), agenda.weekly.hold.map { it.title })
        assertEquals(listOf("Someday"), agenda.weekly.maybe.map { it.title })
        assertEquals(listOf("Inbox item"), agenda.weekly.inbox.map { it.title })
        assertFalse(agenda.weekly.nextDays.any { it.title == "Due later" })
    }

    @Test
    fun `plain active timestamps appear in today and next fourteen days`() {
        val entries = parse(
            "calendar.org",
            """
                * NEXT Timed today
                <2026-05-16 Sat 10:00>
                * TODO Timed soon
                <2026-05-20 Wed 14:30>
                * TODO Timed later
                <2026-06-15 Mon 09:00>
            """.trimIndent()
        )

        val agenda = builder.build(
            entries = entries,
            goalText = "",
            today = LocalDate.of(2026, 5, 16)
        )

        assertEquals(listOf("Timed today"), agenda.daily.today.map { it.title })
        assertEquals(listOf("Timed today", "Timed soon"), agenda.weekly.nextDays.map { it.title })
        assertFalse(agenda.weekly.nextDays.any { it.title == "Timed later" })
    }

    private fun parse(fileName: String, content: String): List<OrgAgendaEntry> {
        return parser.parseFile(mock(Uri::class.java), fileName, content)
    }
}
