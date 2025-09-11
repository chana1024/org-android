package com.orgutil.data.mapper

import com.orgutil.domain.model.OrgNode
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class OrgParserWrapperTest {

    private lateinit var orgParserWrapper: OrgParserWrapper

    @Before
    fun setup() {
        orgParserWrapper = OrgParserWrapper()
    }

    @Test
    fun `parseContent should parse simple org content correctly`() {
        // Given
        val orgContent = """
            * TODO Task 1 :work:project:
              This is the content of task 1.
            ** DONE Subtask 1.1
               This is subtask content.
        """.trimIndent()

        // When
        val result = orgParserWrapper.parseContent(orgContent)

        // Then
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
        
        val firstNode = result.first()
        assertEquals(1, firstNode.level)
        assertEquals("Task 1", firstNode.title)
        assertEquals("TODO", firstNode.todo)
        assertTrue(firstNode.tags.contains("work"))
        assertTrue(firstNode.tags.contains("project"))
    }

    @Test
    fun `parseContent should handle empty content gracefully`() {
        // Given
        val emptyContent = ""

        // When
        val result = orgParserWrapper.parseContent(emptyContent)

        // Then
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `writeContent should generate valid org format`() {
        // Given
        val nodes = listOf(
            OrgNode(
                level = 1,
                title = "Test Task",
                content = "This is test content",
                tags = listOf("work", "urgent"),
                todo = "TODO",
                priority = "A"
            )
        )

        // When
        val result = orgParserWrapper.writeContent(nodes)

        // Then
        assertNotNull(result)
        assertTrue(result.contains("* TODO"))
        assertTrue(result.contains("[#A]"))
        assertTrue(result.contains("Test Task"))
        assertTrue(result.contains(":work:urgent:"))
        assertTrue(result.contains("This is test content"))
    }

    @Test
    fun `writeContent should handle empty nodes list`() {
        // Given
        val emptyNodes = emptyList<OrgNode>()

        // When
        val result = orgParserWrapper.writeContent(emptyNodes)

        // Then
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }
}