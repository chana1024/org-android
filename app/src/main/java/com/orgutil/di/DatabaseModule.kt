package com.orgutil.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.orgutil.data.database.AppDatabase
import com.orgutil.data.database.dao.FileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        Log.d("DatabaseModule", "Creating AppDatabase...")
        val database = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "org-util-db"
        )
        .fallbackToDestructiveMigration()
        .build()
        Log.d("DatabaseModule", "AppDatabase created successfully at: ${context.getDatabasePath("org-util-db").absolutePath}")
        return database
    }

    @Provides
    fun provideFileDao(appDatabase: AppDatabase): FileDao {
        Log.d("DatabaseModule", "Providing FileDao from AppDatabase")
        return appDatabase.fileDao()
    }
}
