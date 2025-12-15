package com.example.musicplayergo.di

import android.content.Context
import com.example.musicplayergo.repository.MediaStoreMusicRepository
import com.example.musicplayergo.repository.MusicRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing data layer dependencies.
 *
 * @InstallIn(SingletonComponent::class) means these dependencies
 * will live for the entire lifetime of the application.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * Provides the MusicRepository implementation.
     *
     * @Singleton ensures only one instance is created and shared.
     * In tests, we can replace this with @TestInstallIn to provide FakeMusicRepository.
     */
    @Provides
    @Singleton
    fun provideMusicRepository(
        @ApplicationContext context: Context
    ): MusicRepository {
        return MediaStoreMusicRepository(context)
    }
}
