package com.iven.musicplayergo.recommendation

import com.chaquo.python.Python
import com.iven.musicplayergo.models.HistoryEntry
import com.iven.musicplayergo.models.Music

object PythonRecommenderBridge {

    fun recommendNext(
        songs: List<Music>,
        history: List<HistoryEntry>,
        favorites: List<Music>?,
        limit: Int = 1
    ): Music? {
        if (!Python.isStarted()) return null
        return try {
            val py = Python.getInstance()
            val module = py.getModule("py_recommender")
            val songsPayload = songs.map { it.toPayload() }
            val historyPayload = history.map { entry ->
                mapOf(
                    "song" to entry.music.toPayload(),
                    "playCount" to entry.playCount,
                    "totalDuration" to entry.totalDuration
                )
            }
            val favoritesPayload = favorites?.map { it.toPayload() } ?: emptyList()
            val result = module.callAttr(
                "recommend",
                songsPayload,
                historyPayload,
                favoritesPayload,
                limit
            )
            val keys = result?.asList()
                ?.mapNotNull { it.toJava(String::class.java) }
                ?: emptyList()
            val firstKey = keys.firstOrNull() ?: return null
            songs.firstOrNull { song ->
                val signature = song.signatureKey()
                signature == firstKey || song.id?.toString() == firstKey
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun Music.toPayload(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "signature" to signatureKey(),
            "title" to title,
            "displayName" to displayName,
            "artist" to artist,
            "album" to album,
            "relativePath" to relativePath,
            "duration" to duration,
            "year" to year,
            "dateAdded" to dateAdded
        )
    }
}
