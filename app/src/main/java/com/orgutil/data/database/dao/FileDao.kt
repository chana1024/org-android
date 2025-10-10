package com.orgutil.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.orgutil.data.database.entity.FileContentFtsEntity
import com.orgutil.data.database.entity.FileMetadataEntity
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

    @Query("DELETE FROM file_metadata WHERE path IN (:paths)")
    suspend fun deleteFileMetadataByPaths(paths: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileContent(content: FileContentFtsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllFileContent(content: List<FileContentFtsEntity>)

    @Query("DELETE FROM file_content_fts WHERE path IN (:paths)")
    suspend fun deleteFileContentByPaths(paths: List<String>)

    @Query("SELECT * FROM file_metadata WHERE fileName LIKE '%' || :query || '%'")
    suspend fun searchFilesByName(query: String): List<FileMetadataEntity>

    @Query(
        """
        SELECT m.* FROM file_metadata m
        INNER JOIN file_content_fts f ON m.path = f.path
        WHERE f.content MATCH :ftsQuery
        """
    )
    suspend fun searchFilesByContent(ftsQuery: String): List<FileMetadataEntity>

    @Query("SELECT path FROM file_metadata")
    suspend fun getAllFilePaths(): List<String>
}