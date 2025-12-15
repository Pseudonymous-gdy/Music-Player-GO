package com.example.musicplayergo.di

import com.example.musicplayergo.repository.FakeMusicRepository
import com.example.musicplayergo.repository.MusicRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Test module that replaces DataModule in instrumented tests.
 *
 * @TestInstallIn replaces @InstallIn(SingletonComponent::class) from DataModule
 * so that FakeMusicRepository is used instead of MediaStoreMusicRepository.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DataModule::class]
)
object TestDataModule {

    @Provides
    @Singleton
    fun provideFakeMusicRepository(): FakeMusicRepository {
        return FakeMusicRepository()
    }

    @Provides
    @Singleton
    fun provideMusicRepository(
        fakeMusicRepository: FakeMusicRepository
    ): MusicRepository {
        return fakeMusicRepository
    }
}
