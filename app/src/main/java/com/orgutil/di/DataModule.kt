package com.orgutil.di

import com.orgutil.data.datasource.FileDataSource
import com.orgutil.data.datasource.FileDataSourceImpl
import com.orgutil.data.repository.FavoriteRepositoryImpl
import com.orgutil.data.repository.OrgFileRepositoryImpl
import com.orgutil.domain.repository.FavoriteRepository
import com.orgutil.domain.repository.OrgFileRepository
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
}