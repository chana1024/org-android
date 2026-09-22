package com.orgutil.domain.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * Pins the FTS4 search semantics against a real SQLite (sqlite-jdbc uses the
 * same SQLite engine family as the framework, including the unicode61
 * tokenizer and FTS4):
 * - Chinese substring queries must match encoded content (decision B);
 * - English token-prefix semantics are preserved;
 * - delete-then-insert updates leave no ghost rows (unlike INSERT OR REPLACE).
 */
class FtsSearchSemanticsTest {

    private lateinit var connection: Connection
    private lateinit var statement: Statement

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        statement = connection.createStatement()
        // Same DDL Room generates for FileContentFtsEntity.
        statement.execute(
            "CREATE VIRTUAL TABLE file_content_fts USING FTS4(" +
                "path TEXT NOT NULL, content TEXT NOT NULL, tokenize=unicode61)"
        )
    }

    @After
    fun tearDown() {
        statement.close()
        connection.close()
    }

    private fun index(path: String, content: String) {
        val encoded = CjkTextEncoder.encodeForIndex(content)
        val ps = connection.prepareStatement(
            "INSERT INTO file_content_fts(path, content) VALUES (?, ?)"
        )
        ps.use {
            it.setString(1, path)
            it.setString(2, encoded)
            it.executeUpdate()
        }
    }

    private fun delete(path: String) {
        val ps = connection.prepareStatement("DELETE FROM file_content_fts WHERE path = ?")
        ps.use {
            it.setString(1, path)
            it.executeUpdate()
        }
    }

    private fun matchPaths(rawQuery: String): List<String> {
        val ftsQuery = FtsQueryBuilder.buildMatchQuery(rawQuery) ?: return emptyList()
        query(ftsQuery).use { rs ->
            val paths = mutableListOf<String>()
            while (rs.next()) paths += rs.getString(1)
            return paths
        }
    }

    private fun query(ftsQuery: String) =
        statement.executeQuery("SELECT path FROM file_content_fts WHERE file_content_fts MATCH '$ftsQuery'")

    @Test
    fun `chinese substring query matches inside cjk run`() {
        index("a.org", "这是一段中文测试混合的内容")
        assertTrue(matchPaths("测试").contains("a.org"))
        assertTrue(matchPaths("文测试").contains("a.org"))
        assertTrue(matchPaths("中文测试混合").contains("a.org"))
    }

    @Test
    fun `chinese query for absent text does not match`() {
        index("a.org", "这是一段中文测试混合的内容")
        assertFalse(matchPaths("计划").contains("a.org"))
        assertFalse(matchPaths("计划测试").contains("a.org"))
    }

    @Test
    fun `single cjk char query matches`() {
        index("a.org", "混合内容")
        assertTrue(matchPaths("混").contains("a.org"))
        assertTrue(matchPaths("合").contains("a.org"))
        assertFalse(matchPaths("星").contains("a.org"))
    }

    @Test
    fun `cjk query adjacent to latin token still matches`() {
        index("a.org", "todo中文笔记")
        assertTrue(matchPaths("todo").contains("a.org"))
        assertTrue(matchPaths("中文").contains("a.org"))
        assertTrue(matchPaths("笔记").contains("a.org"))
        assertTrue(matchPaths("todo 中文").contains("a.org"))
    }

    @Test
    fun `english prefix semantics are preserved`() {
        index("a.org", "buy apples in the supermarket")
        index("b.org", "completely different")
        assertTrue(matchPaths("appl").contains("a.org"))
        assertTrue(matchPaths("apples").contains("a.org"))
        assertFalse(matchPaths("appl").contains("b.org"))
        assertFalse(matchPaths("zork").contains("a.org"))
    }

    @Test
    fun `multi word query requires all terms`() {
        index("a.org", "todo list for the garden")
        assertTrue(matchPaths("todo garden").contains("a.org"))
        assertFalse(matchPaths("todo supermarket").contains("a.org"))
    }

    @Test
    fun `punctuation only query matches nothing without error`() {
        index("a.org", "some content")
        assertTrue(matchPaths("***").isEmpty())
    }

    @Test
    fun `delete then insert update leaves no ghost rows`() {
        index("a.org", "first version talks about bananas")
        assertTrue(matchPaths("bananas").contains("a.org"))

        delete("a.org")
        index("a.org", "second version talks about cherries")

        // Old content must no longer match (INSERT OR REPLACE would keep it).
        assertEquals(emptyList<String>(), matchPaths("bananas"))
        assertEquals(listOf("a.org"), matchPaths("cherries"))

        query("cherries").use { rs ->
            var rows = 0
            while (rs.next()) rows++
            assertEquals(1, rows)
        }
    }

    @Test
    fun `fts insert or replace leaves ghost rows - the defect the delete-then-insert prevents`() {
        // Documents why the indexer must never go back to REPLACE.
        val ps = connection.prepareStatement(
            "INSERT OR REPLACE INTO file_content_fts(path, content) VALUES (?, ?)"
        )
        ps.use {
            it.setString(1, "a.org")
            it.setString(2, "version1 bananas")
            it.executeUpdate()
            it.setString(2, "version2 cherries")
            it.executeUpdate()
            it.setString(2, "version3 dates")
            it.executeUpdate()
        }
        // Ghost rows of every old version are still MATCHable.
        assertTrue(matchPaths("bananas").isNotEmpty())
        assertTrue(matchPaths("cherries").isNotEmpty())
        assertTrue(matchPaths("dates").isNotEmpty())
    }

    @Test
    fun `migration 3 to 4 keeps newest content per path and empties fts`() {
        statement.execute("DROP TABLE file_content_fts")
        // v3 schema: FTS table already polluted with ghost rows from REPLACE.
        statement.execute(
            "CREATE VIRTUAL TABLE file_content_fts USING FTS4(" +
                "path TEXT NOT NULL, content TEXT NOT NULL, tokenize=unicode61)"
        )
        statement.execute("CREATE TABLE file_metadata (path TEXT NOT NULL PRIMARY KEY, fileName TEXT NOT NULL)")
        statement.execute("INSERT INTO file_metadata VALUES ('a.org', 'a.org')")

        val insert = connection.prepareStatement(
            "INSERT INTO file_content_fts(rowid, path, content) VALUES (?, ?, ?)"
        )
        insert.use {
            it.setInt(1, 1)
            it.setString(2, "a.org")
            it.setString(3, "old content bananas")
            it.executeUpdate()
            it.setInt(1, 2)
            it.setString(2, "a.org")
            it.setString(3, "new content cherries")
            it.executeUpdate()
        }

        // Exact statements of AppDatabase.MIGRATION_3_4.
        statement.execute(
            "CREATE TABLE IF NOT EXISTS `file_content` (" +
                "`path` TEXT NOT NULL, `content` TEXT NOT NULL, PRIMARY KEY(`path`))"
        )
        statement.execute(
            "INSERT OR REPLACE INTO `file_content` (`path`, `content`) " +
                "SELECT `path`, `content` FROM `file_content_fts` ORDER BY rowid"
        )
        statement.execute("DELETE FROM `file_content_fts`")

        statement.executeQuery("SELECT content FROM file_content WHERE path = 'a.org'").use { rs ->
            assertTrue(rs.next())
            assertEquals("new content cherries", rs.getString(1))
            assertFalse(rs.next())
        }
        statement.executeQuery("SELECT COUNT(*) FROM file_content_fts").use { rs ->
            rs.next()
            assertEquals(0, rs.getInt(1))
        }
        statement.executeQuery("SELECT COUNT(*) FROM file_metadata").use { rs ->
            rs.next()
            assertEquals(1, rs.getInt(1))
        }
    }
}
