package com.example.musicplayergo.utils

import android.util.Log
import com.example.musicplayergo.models.Music

object RecommendationPlaybackObserver {

    private val trackedSongIds = mutableSetOf<Long>()
    private const val TAG = "RecommendationTracker"

    fun trackSongs(songs: List<Music>) {
        synchronized(trackedSongIds) {
            trackedSongIds.clear()
            songs.mapNotNull { it.id }.also {
                trackedSongIds.addAll(it)
            }
        }
    }

    fun onSongStarted(song: Music?) {
        val id = song?.id ?: return
        val shouldLog = synchronized(trackedSongIds) {
            trackedSongIds.remove(id)
        }
        if (shouldLog) {
            Log.d(TAG, "Recommended song playing: ${song?.title ?: "unknown"}")
        }
    }
}
