package com.example.musicplayergo.repository

import com.example.musicplayergo.GoConstants
import com.example.musicplayergo.models.Music
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fake implementation of MusicRepository for testing.
 *
 * This allows tests to:
 * 1. Control what music data is returned
 * 2. Run without real MediaStore access
 * 3. Execute consistently in CI environments
 */
@Singleton
class FakeMusicRepository @Inject constructor() : MusicRepository {

    private val _musicFlow = MutableStateFlow<List<Music>>(emptyList())
    private var musicList: List<Music> = createDefaultTestMusic()

    /**
     * Set custom music data for testing.
     */
    fun setMusicData(music: List<Music>) {
        musicList = music
        _musicFlow.value = music
    }

    /**
     * Clear all music data.
     */
    fun clearMusicData() {
        musicList = emptyList()
        _musicFlow.value = emptyList()
    }

    /**
     * Reset to default test data.
     */
    fun resetToDefault() {
        musicList = createDefaultTestMusic()
        _musicFlow.value = musicList
    }

    override suspend fun loadDeviceMusic(): List<Music> {
        _musicFlow.value = musicList
        return musicList
    }

    override fun observeMusic(): Flow<List<Music>> = _musicFlow.asStateFlow()

    override suspend fun refreshMusicLibrary() {
        _musicFlow.value = musicList
    }

    companion object {
        /**
         * Create default test music data.
         */
        fun createDefaultTestMusic(): List<Music> = listOf(
            Music(
                artist = "Test Artist A",
                year = 2023,
                track = 1,
                title = "Test Song 1",
                displayName = "test_song_1.mp3",
                duration = 180_000L,
                album = "Test Album A",
                albumId = 101L,
                relativePath = "/test/music",
                id = 1L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000000
            ),
            Music(
                artist = "Test Artist B",
                year = 2023,
                track = 2,
                title = "Test Song 2",
                displayName = "test_song_2.mp3",
                duration = 200_000L,
                album = "Test Album B",
                albumId = 102L,
                relativePath = "/test/music",
                id = 2L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000001
            ),
            Music(
                artist = "Test Artist C",
                year = 2022,
                track = 3,
                title = "Test Song 3",
                displayName = "test_song_3.mp3",
                duration = 240_000L,
                album = "Test Album C",
                albumId = 103L,
                relativePath = "/test/music",
                id = 3L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000002
            ),
            Music(
                artist = "Test Artist D",
                year = 2022,
                track = 4,
                title = "Test Song 4",
                displayName = "test_song_4.mp3",
                duration = 220_000L,
                album = "Test Album D",
                albumId = 104L,
                relativePath = "/test/music",
                id = 4L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000003
            ),
            Music(
                artist = "Test Artist E",
                year = 2021,
                track = 5,
                title = "Test Song 5",
                displayName = "test_song_5.mp3",
                duration = 260_000L,
                album = "Test Album E",
                albumId = 105L,
                relativePath = "/test/music",
                id = 5L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000004
            )
        )
    }
}
