package com.fauxx.di

import com.fauxx.data.custompool.CustomPoolRepository
import com.fauxx.data.custompool.CustomPoolSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Exposes the user's own searches and sites (#328) to the engine as a [CustomPoolSource]. Kept out
 * of [AppModule] so test modules that replace AppModule only have to re-provide the DAO.
 */
@Module
@InstallIn(SingletonComponent::class)
object CustomPoolModule {

    @Provides
    @Singleton
    fun provideCustomPoolSource(repository: CustomPoolRepository): CustomPoolSource = repository
}
