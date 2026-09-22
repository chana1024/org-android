package com.orgutil.di

import com.orgutil.data.datasource.FileDataSource
import com.orgutil.data.datasource.FileDataSourceImpl
import com.orgutil.data.repository.FavoriteRepositoryImpl
import com.orgutil.data.repository.GitSyncRepositoryImpl
import com.orgutil.data.repository.OrgAgendaRepositoryImpl
import com.orgutil.data.repository.OrgFileIndexUpdater
import com.orgutil.data.repository.OrgFileRepositoryImpl
import com.orgutil.domain.indexing.FileIndexRunner
import com.orgutil.domain.indexing.FileIndexScheduler
import com.orgutil.domain.repository.FavoriteRepository
import com.orgutil.domain.repository.GitSyncRepository
import com.orgutil.domain.repository.OrgAgendaRepository
import com.orgutil.domain.repository.OrgFileRepository
import com.orgutil.domain.sync.GitSyncScheduler
import com.orgutil.worker.WorkManagerFileIndexScheduler
import com.orgutil.worker.WorkManagerGitSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindOrgFileRepository(
        orgFileRepositoryImpl: OrgFileRepositoryImpl
    ): OrgFileRepository

    @Binds
    @Singleton
    abstract fun bindOrgAgendaRepository(
        orgAgendaRepositoryImpl: OrgAgendaRepositoryImpl
    ): OrgAgendaRepository

    @Binds
    @Singleton
    abstract fun bindFileDataSource(
        fileDataSourceImpl: FileDataSourceImpl
    ): FileDataSource

    @Binds
    @Singleton
    abstract fun bindFavoriteRepository(
        favoriteRepositoryImpl: FavoriteRepositoryImpl
    ): FavoriteRepository

    @Binds
    @Singleton
    abstract fun bindFileIndexRunner(
        orgFileIndexUpdater: OrgFileIndexUpdater
    ): FileIndexRunner

    @Binds
    @Singleton
    abstract fun bindFileIndexScheduler(
        workManagerFileIndexScheduler: WorkManagerFileIndexScheduler
    ): FileIndexScheduler

    @Binds
    @Singleton
    abstract fun bindGitSyncRepository(
        gitSyncRepositoryImpl: GitSyncRepositoryImpl
    ): GitSyncRepository

    @Binds
    @Singleton
    abstract fun bindGitSyncScheduler(
        workManagerGitSyncScheduler: WorkManagerGitSyncScheduler
    ): GitSyncScheduler

    companion object {
        @Provides
        @IoDispatcher
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
