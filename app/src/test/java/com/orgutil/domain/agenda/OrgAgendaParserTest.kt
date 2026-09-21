package com.orgutil.domain.agenda

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.LocalDate

class OrgAgendaParserTest {

    private val parser = OrgAgendaParser()
    private val uri = mock(Uri::class.java)

    @Test
    fun `parse recognizes gtd todo keywords including project and area`() {
        val content = """
            #+TITLE: GTD Projects
            #+FILETAGS: :gtd:

            * PROJ [#A] English :study:
            DEADLINE: <2026-08-15 Sat>
            ** NEXT Read article
            ** WAIT Reply from tutor
        """.trimIndent()

        val entries = parser.parseFile(uri, "projects.org", content)

        val project = entries.first()
        assertEquals("PROJ", project.todo)
        assertEquals("English", project.title)
        assertEquals("A", project.priority)
        assertTrue(project.tags.contains("study"))
        assertTrue(project.tags.contains("gtd"))
        assertEquals(LocalDate.of(2026, 8, 15), project.deadline)
        assertEquals(2, project.children.size)
        assertEquals("NEXT", project.children[0].todo)
        assertEquals("WAIT", project.children[1].todo)
    }

    @Test
    fun `parse records source offsets for headline navigation`() {
        val content = "#+TITLE: Inbox\n\n* TODO First\nBody\n* NEXT Second\n"

        val entries = parser.parseFile(uri, "inbox.org", content)

        assertEquals(content.indexOf("* TODO First"), entries[0].sourceOffset)
        assertEquals(content.indexOf("First"), entries[0].titleOffset)
        assertEquals(content.indexOf("* NEXT Second"), entries[1].sourceOffset)
        assertEquals(content.indexOf("Second"), entries[1].titleOffset)
    }

    @Test
    fun `parse records active timestamp dates and parent path`() {
        val content = """
            * PROJ Parent project
            ** NEXT Timed child
            <2026-05-16 Sat 10:00>
        """.trimIndent()

        val entries = parser.parseFile(uri, "timed.org", content)

        val child = entries.first().children.first()
        assertEquals(LocalDate.of(2026, 5, 16), child.timestamp)
        assertEquals(listOf("Parent project"), child.parentTitles)
    }
}
