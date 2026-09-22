package com.orgutil.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.orgutil.data.database.entity.FileContentEntity
import com.orgutil.data.database.entity.FileContentFtsEntity
import com.orgutil.data.database.entity.FileMetadataEntity
import com.orgutil.data.database.entity.FileSearchResult
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileMetadata(metadata: FileMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllFileMetadata(metadata: List<FileMetadataEntity>)

    @Query("SELECT * FROM file_metadata")
    fun getAllFileMetadata(): Flow<List<FileMetadataEntity>>

    @Query("SELECT * FROM file_metadata WHERE path IN (:paths)")
    suspend fun getFileMetadataByPaths(paths: List<String>): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE path = :path LIMIT 1")
    suspend fun getFileMetadataByPath(path: String): FileMetadataEntity?

    @Query("DELETE FROM file_metadata WHERE path IN (:paths)")
    suspend fun deleteFileMetadataByPaths(paths: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileContentFts(content: FileContentFtsEntity)

    @Query("DELETE FROM file_content_fts WHERE path = :path")
    suspend fun deleteFileContentByPath(path: String)

    /**
     * Deletes then re-inserts the FTS row. FTS4's INSERT OR REPLACE appends a
     * new row without removing the old one, which leaves "ghost" documents
     * whose deleted content still matches queries; an explicit DELETE first
     * does remove the old row. All steps run in one transaction so a crash can
     * never leave metadata/content/FTS out of sync.
     */
    @Transaction
    suspend fun replaceIndexedFile(
        metadata: FileMetadataEntity,
        ftsContent: FileContentFtsEntity,
        plainContent: FileContentEntity
    ) {
        deleteFileContentByPath(metadata.path)
        insertFileContentFts(ftsContent)
        insertFileContentRow(plainContent)
        insertFileMetadata(metadata)
    }

    @Transaction
    suspend fun replaceIndexedFiles(
        metadata: List<FileMetadataEntity>,
        ftsContent: List<FileContentFtsEntity>,
        plainContent: List<FileContentEntity>
    ) {
        val paths = metadata.map { it.path }
        deleteFileContentByPaths(paths)
        insertAllFileContentFts(ftsContent)
        insertAllFileContentRows(plainContent)
        insertAllFileMetadata(metadata)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllFileContentFts(content: List<FileContentFtsEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileContentRow(content: FileContentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllFileContentRows(content: List<FileContentEntity>)

    @Query("DELETE FROM file_content_fts WHERE path IN (:paths)")
    suspend fun deleteFileContentByPaths(paths: List<String>)

    @Query("DELETE FROM file_content WHERE path IN (:paths)")
    suspend fun deletePlainContentByPaths(paths: List<String>)

    /** Removes every trace of the given files (metadata + FTS + plain content) atomically. */
    @Transaction
    suspend fun deleteIndexedFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        deleteFileContentByPaths(paths)
        deletePlainContentByPaths(paths)
        deleteFileMetadataByPaths(paths)
    }

    /**
     * Point lookup for previews / recent-content reads. Uses the plain table,
     * where `path` is the primary key; the FTS table has no index on `path`.
     */
    @Query("SELECT * FROM file_content WHERE path = :path LIMIT 1")
    suspend fun getFileContentByPath(path: String): FileContentEntity?

    /**
     * Full-text search returning metadata joined with the original content in
     * one query (previews must not do a per-hit content lookup).
     *
     * [ftsQuery] must come from [com.orgutil.domain.search.FtsQueryBuilder].
     */
    @Query(
        """
        SELECT m.path AS path, m.fileName AS fileName, m.lastModified AS lastModified,
               m.size AS size, c.content AS content
        FROM file_content_fts f
        INNER JOIN file_metadata m ON m.path = f.path
        INNER JOIN file_content c ON c.path = f.path
        WHERE f.content MATCH :ftsQuery
        ORDER BY m.fileName COLLATE NOCASE
        """
    )
    suspend fun searchFilesWithContent(ftsQuery: String): List<FileSearchResult>

    /**
     * Metadata rows whose FTS content is missing. After a failed content read
     * or a migration, these paths are re-indexed by the next update pass
     * (self-healing instead of silently half-indexed).
     */
    @Query("SELECT path FROM file_metadata WHERE path NOT IN (SELECT path FROM file_content_fts)")
    suspend fun getMetadataPathsWithoutFtsContent(): List<String>

    @Query("SELECT path FROM file_metadata")
    suspend fun getAllFilePaths(): List<String>
}
