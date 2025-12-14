package com.example.musicplayergo.utils

import com.example.musicplayergo.GoPreferences
import com.example.musicplayergo.models.HistoryEntry
import com.example.musicplayergo.models.Music
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackHistory {
//frequently played

    private const val MAX_SIZE = 100
    private val lock = Any()
    private val historyState = MutableStateFlow(loadInitialHistory())

    private fun loadInitialHistory(): List<HistoryEntry> =
        GoPreferences.getPrefsInstance().playHistory ?: emptyList()

    fun historyFlow(): StateFlow<List<HistoryEntry>> = historyState.asStateFlow()

    fun current(): List<HistoryEntry> = historyState.value

    fun log(song: Music?, launchedBy: String) {
        val songId = song?.id ?: return
        val normalizedMusic = song.copy(
            startFrom = 0,
            launchedBy = launchedBy
        )
        val entry = HistoryEntry(
            music = normalizedMusic,
            playedAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            val withoutDuplicate = historyState.value.filterNot { it.music.id == songId }
            val trimmed = (listOf(entry) + withoutDuplicate).take(MAX_SIZE)
            historyState.value = trimmed
            GoPreferences.getPrefsInstance().playHistory = trimmed
        }
    }

    fun clear() {
        synchronized(lock) {
            historyState.value = emptyList()
            GoPreferences.getPrefsInstance().playHistory = emptyList()
        }
    }
}
