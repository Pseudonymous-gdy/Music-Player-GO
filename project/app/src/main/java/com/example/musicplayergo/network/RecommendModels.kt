package com.example.musicplayergo.network

data class RecommendQueryRequest(
    val playlist: List<String> = emptyList(),
    val candidates: List<String> = emptyList(),
    val exclude_playlist: Boolean = true,
    val n: Int = 5
)

data class RecommendResponse(
    val recommendations: List<RecommendationItem> = emptyList()
)

data class RecommendationItem(
    val id: String,
    val name: String? = null,
    val artist: String? = null,
    val score: Double? = null
)

data class SongsResponse(
    val songs: List<ServerSong> = emptyList()
)

data class ServerSong(
    val id: String,
    val name: String? = null,
    val artist: String? = null
)
