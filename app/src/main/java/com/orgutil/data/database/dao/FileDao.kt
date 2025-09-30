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

    @Transaction
    @Query("SELECT * FROM file_metadata WHERE fileName LIKE '%' || :query || '%'")
    fun searchFilesByName(query: String): Flow<List<FileMetadataEntity>>

    @Transaction
    @Query(
        """
        SELECT * FROM file_metadata
        WHERE path IN (SELECT path FROM file_content_fts WHERE content MATCH :query)
        """
    )
    fun searchFilesByContent(query: String): Flow<List<FileMetadataEntity>>

    @Transaction
    @Query(
        """
        SELECT * FROM file_metadata
        WHERE fileName LIKE '%' || :query || '%' OR path IN (SELECT path FROM file_content_fts WHERE content MATCH :ftsQuery)
        """
    )
    fun searchFilesByNameAndContent(query: String, ftsQuery: String): Flow<List<FileMetadataEntity>>

    @Query("SELECT path FROM file_metadata")
    suspend fun getAllFilePaths(): List<String>
}