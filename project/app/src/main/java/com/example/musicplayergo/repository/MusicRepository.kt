package com.example.musicplayergo.repository

import com.example.musicplayergo.models.Music
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for accessing music data.
 *
 * This abstraction allows us to:
 * 1. Decouple ViewModel from data sources (MediaStore, Room, Network)
 * 2. Enable dependency injection for testability
 * 3. Provide a single source of truth for music data
 */
interface MusicRepository {

    /**
     * Load all music from the device.
     * This is a one-time operation that returns a list of music.
     *
     * @return List of Music objects, or null if loading fails
     */
    suspend fun loadDeviceMusic(): List<Music>?

    /**
     * Observe music data changes as a Flow.
     * Useful for reactive UI updates when music library changes.
     *
     * @return Flow emitting List of Music whenever data changes
     */
    fun observeMusic(): Flow<List<Music>>

    /**
     * Refresh music library from the data source.
     * Useful for manual refresh operations.
     */
    suspend fun refreshMusicLibrary()
}
