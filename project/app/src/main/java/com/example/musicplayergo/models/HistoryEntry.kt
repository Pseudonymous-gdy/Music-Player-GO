package com.example.musicplayergo.models

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class HistoryEntry(
    val music: Music,
    val playedAt: Long
)
