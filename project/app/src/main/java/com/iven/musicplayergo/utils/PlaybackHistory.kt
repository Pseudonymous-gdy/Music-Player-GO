package com.iven.musicplayergo.utils

import com.iven.musicplayergo.GoPreferences
import com.iven.musicplayergo.models.HistoryEntry
import com.iven.musicplayergo.models.Music
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackHistory {

    private const val MAX_SIZE = 100
    private val lock = Any()
    private val historyState = MutableStateFlow(loadInitialHistory())

    private fun loadInitialHistory(): List<HistoryEntry> =
        GoPreferences.getPrefsInstance().playHistory ?: emptyList()

    fun historyFlow(): StateFlow<List<HistoryEntry>> = historyState.asStateFlow()

    fun current(): List<HistoryEntry> = historyState.value

    fun logPlayStart(song: Music?, launchedBy: String) {
        val songId = song?.id ?: return
        val normalizedMusic = song.copy(startFrom = 0, launchedBy = launchedBy)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val items = historyState.value.toMutableList()
            val index = items.indexOfFirst { it.music.id == songId }
            val updatedEntry = if (index >= 0) {
                val existing = items.removeAt(index)
                existing.copy(
                    music = normalizedMusic,
                    playedAt = now,
                    playCount = existing.playCount + 1
                )
            } else {
                HistoryEntry(
                    music = normalizedMusic,
                    playedAt = now,
                    playCount = 1,
                    totalDuration = 0L
                )
            }
            items.add(0, updatedEntry)
            if (items.size > MAX_SIZE) {
                items.subList(MAX_SIZE, items.size).clear()
            }
            updateState(items)
        }
    }

    fun addListeningTime(song: Music?, listenedMs: Long) {
        val songId = song?.id ?: return
        if (listenedMs <= 0) return
        synchronized(lock) {
            val items = historyState.value.toMutableList()
            val index = items.indexOfFirst { it.music.id == songId }
            if (index >= 0) {
                val existing = items[index]
                items[index] = existing.copy(totalDuration = existing.totalDuration + listenedMs)
                updateState(items)
            }
        }
    }

    fun topFrequent(limit: Int = 50): List<HistoryEntry> =
        historyState.value.sortedByDescending { it.playCount }.take(limit)

    fun topRecent(limit: Int = 50): List<HistoryEntry> =
        historyState.value.sortedByDescending { it.playedAt }.take(limit)

    fun entryForSong(songId: Long?): HistoryEntry? =
        historyState.value.firstOrNull { it.music.id == songId }

    fun statsForArtist(artist: String?): HistoryStats? {
        if (artist.isNullOrBlank()) return null
        val matches = historyState.value.filter { it.music.artist == artist }
        if (matches.isEmpty()) return null
        return HistoryStats(
            playCount = matches.sumOf { it.playCount },
            totalDuration = matches.sumOf { it.totalDuration },
            lastPlayed = matches.maxOfOrNull { it.playedAt }
        )
    }

    fun statsForSongs(songIds: Collection<Long>): HistoryStats? {
        if (songIds.isEmpty()) return null
        val set = songIds.toSet()
        val matches = historyState.value.filter { set.contains(it.music.id) }
        if (matches.isEmpty()) return null
        return HistoryStats(
            playCount = matches.sumOf { it.playCount },
            totalDuration = matches.sumOf { it.totalDuration },
            lastPlayed = matches.maxOfOrNull { it.playedAt }
        )
    }

    fun clear() {
        synchronized(lock) {
            historyState.value = emptyList()
            GoPreferences.getPrefsInstance().playHistory = emptyList()
        }
    }

    private fun updateState(newItems: MutableList<HistoryEntry>) {
        val snapshot = newItems.toList()
        historyState.value = snapshot
        GoPreferences.getPrefsInstance().playHistory = snapshot
    }
}

data class HistoryStats(
    val playCount: Int,
    val totalDuration: Long,
    val lastPlayed: Long?
)
