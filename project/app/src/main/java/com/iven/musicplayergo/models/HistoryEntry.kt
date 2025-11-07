package com.iven.musicplayergo.models

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class HistoryEntry(
    val music: Music,
    val playedAt: Long,
    val playCount: Int = 1,
    val totalDuration: Long = 0L
)
