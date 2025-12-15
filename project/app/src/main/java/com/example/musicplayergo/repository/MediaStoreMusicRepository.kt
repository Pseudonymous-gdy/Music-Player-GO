package com.example.musicplayergo.repository

import android.app.Application
import android.content.Context
import android.provider.MediaStore
import com.example.musicplayergo.GoConstants
import com.example.musicplayergo.GoPreferences
import com.example.musicplayergo.R
import com.example.musicplayergo.models.Music
import com.example.musicplayergo.utils.MusicUtils
import com.example.musicplayergo.utils.Versioning
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of MusicRepository that loads music from MediaStore.
 *
 * This class encapsulates all MediaStore access logic, making it easy to:
 * 1. Test by replacing with FakeMusicRepository
 * 2. Add caching or other data sources in the future
 * 3. Keep ViewModel focused on UI logic
 */
@Singleton
class MediaStoreMusicRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : MusicRepository {

    private val _musicFlow = MutableStateFlow<List<Music>>(emptyList())
    private var cachedMusic: List<Music>? = null

    override suspend fun loadDeviceMusic(): List<Music>? = withContext(Dispatchers.IO) {
        try {
            val music = queryForMusic()
            if (music != null) {
                cachedMusic = music
                _musicFlow.value = music
            }
            music
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun observeMusic(): Flow<List<Music>> = _musicFlow.asStateFlow()

    override suspend fun refreshMusicLibrary() {
        loadDeviceMusic()
    }

    /**
     * Query MediaStore for music files.
     * This is extracted from the original MusicViewModel.queryForMusic()
     */
    @Suppress("DEPRECATION")
    private fun queryForMusic(): List<Music>? {
        return try {
            val deviceMusicList = mutableListOf<Music>()
            val application = context.applicationContext as Application

            val pathColumn = if (Versioning.isQ()) {
                MediaStore.Audio.AudioColumns.BUCKET_DISPLAY_NAME
            } else {
                MediaStore.Audio.AudioColumns.DATA
            }

            val projection = arrayOf(
                MediaStore.Audio.AudioColumns.ARTIST, // 0
                MediaStore.Audio.AudioColumns.YEAR, // 1
                MediaStore.Audio.AudioColumns.TRACK, // 2
                MediaStore.Audio.AudioColumns.TITLE, // 3
                MediaStore.Audio.AudioColumns.DISPLAY_NAME, // 4
                MediaStore.Audio.AudioColumns.DURATION, // 5
                MediaStore.Audio.AudioColumns.ALBUM, // 6
                MediaStore.Audio.AudioColumns.ALBUM_ID, // 7
                pathColumn, // 8
                MediaStore.Audio.AudioColumns._ID, // 9
                MediaStore.MediaColumns.DATE_MODIFIED // 10
            )

            val selection = "${MediaStore.Audio.AudioColumns.IS_MUSIC} = 1"
            val sortOrder = MediaStore.Audio.Media.DEFAULT_SORT_ORDER

            val musicCursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )

            musicCursor?.use { cursor ->
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ARTIST)
                val yearIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.YEAR)
                val trackIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TRACK)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TITLE)
                val displayNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DISPLAY_NAME)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM)
                val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM_ID)
                val relativePathIndex = cursor.getColumnIndexOrThrow(pathColumn)
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns._ID)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val audioId = cursor.getLong(idIndex)
                    val audioArtist = cursor.getString(artistIndex)
                    val audioYear = cursor.getInt(yearIndex)
                    val audioTrack = cursor.getInt(trackIndex)
                    val audioTitle = cursor.getString(titleIndex)
                    val audioDisplayName = cursor.getString(displayNameIndex)
                    val audioDuration = cursor.getLong(durationIndex)
                    val audioAlbum = cursor.getString(albumIndex)
                    val albumId = cursor.getLong(albumIdIndex)
                    val audioRelativePath = cursor.getString(relativePathIndex)
                    val audioDateAdded = cursor.getInt(dateAddedIndex)

                    val audioFolderName = if (Versioning.isQ()) {
                        audioRelativePath ?: context.getString(R.string.slash)
                    } else {
                        var returnedPath = File(audioRelativePath).parentFile?.name
                        if (returnedPath == null || returnedPath == "0") {
                            returnedPath = context.getString(R.string.slash)
                        }
                        returnedPath
                    }

                    deviceMusicList.add(
                        Music(
                            audioArtist,
                            audioYear,
                            audioTrack,
                            audioTitle,
                            audioDisplayName,
                            audioDuration,
                            audioAlbum,
                            albumId,
                            audioFolderName,
                            audioId,
                            GoConstants.ARTIST_VIEW,
                            0,
                            audioDateAdded
                        )
                    )
                }
            }

            // Apply filtering and deduplication
            buildLibrary(deviceMusicList)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Build music library with filtering and deduplication.
     * Extracted from MusicViewModel.buildLibrary()
     */
    private fun buildLibrary(musicList: MutableList<Music>): List<Music> {
        // Removing duplicates by comparing everything except path
        var filtered = musicList.distinctBy {
            it.artist to it.year to it.track to it.title to it.duration to it.album
        }

        // Apply user filters
        GoPreferences.getPrefsInstance().filters?.let { filter ->
            filtered = filtered.filter { music ->
                !filter.contains(music.artist) &&
                !filter.contains(music.album) &&
                !filter.contains(music.relativePath)
            }
        }

        return filtered
    }
}
