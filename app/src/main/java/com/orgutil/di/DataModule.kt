package com.orgutil.di

import com.orgutil.data.datasource.FileDataSource
import com.orgutil.data.datasource.FileDataSourceImpl
import com.orgutil.data.repository.FavoriteRepositoryImpl
import com.orgutil.data.repository.OrgFileIndexUpdater
import com.orgutil.data.repository.OrgFileRepositoryImpl
import com.orgutil.domain.indexing.FileIndexRunner
import com.orgutil.domain.indexing.FileIndexScheduler
import com.orgutil.domain.repository.FavoriteRepository
import com.orgutil.domain.repository.OrgFileRepository
import com.orgutil.worker.WorkManagerFileIndexScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
}
