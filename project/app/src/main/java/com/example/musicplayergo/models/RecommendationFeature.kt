package com.example.musicplayergo.models

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RecommendationFeature(
    val localSongId: Long,
    val serverSongId: String?,
    val featureUrl: String
)
