package com.iven.musicplayergo.network

import com.google.gson.annotations.SerializedName

data class RecommendQueryRequest(
    val playlist: List<String> = emptyList(),
    val candidates: List<String> = emptyList(),
    val exclude_playlist: Boolean = true,
    val n: Int = 5
)

data class RecommendResponse(
    val recommendations: List<RecommendationItem> = emptyList()
)

/**
 * 兼容后端使用 song_name 字段的返回
 */
data class RecommendationItem(
    val id: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val score: Double? = null,
    @SerializedName("song_name") val songName: String? = null
) {
    val serverKey: String?
        get() = id ?: songName

    val displayTitle: String?
        get() = name ?: songName
}

data class SongsResponse(
    val songs: List<ServerSong> = emptyList()
)

data class ServerSong(
    val id: String? = null,
    val name: String? = null,
    @SerializedName("song_name") val songName: String? = null,
    val artist: String? = null
)
