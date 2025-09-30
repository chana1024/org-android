package com.orgutil.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.database.entity.FileContentFtsEntity
import com.orgutil.data.database.entity.FileMetadataEntity

@Database(entities = [FileMetadataEntity::class, FileContentFtsEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
}
