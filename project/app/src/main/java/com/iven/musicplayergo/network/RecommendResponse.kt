package com.iven.musicplayergo.network

data class RecommendResponse(
    val songs: List<String>,
    val user_id: String? = null
)