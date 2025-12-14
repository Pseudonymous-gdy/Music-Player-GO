package com.example.musicplayergo.network

import com.google.gson.annotations.SerializedName

data class RecommendQueryRequest(
    val user_id: String? = null,  // 用户ID，用于服务器区分不同用户
    val playlist: List<String> = emptyList(),
    val candidates: List<String> = emptyList(),
    val exclude_playlist: Boolean = true,
    val n: Int = 5,
    val policy: String? = null  // 推荐策略：LinUCB 或 LinUCB+
)

data class RecommendResponse(
    val recommendations: List<RecommendationItem> = emptyList()
)

data class RecommendationItem(
    // 兼容两种格式：旧服务器返回 "id"，新服务器返回 "song_name"
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("song_name")
    val song_name: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val score: Double? = null
) {
    // 获取实际的 id，优先使用 id，如果没有则使用 song_name
    fun getServerId(): String {
        // 确保返回非空值，如果两者都为空则返回空字符串（不应该发生）
        return id ?: song_name ?: ""
    }
}

data class SongsResponse(
    val songs: List<ServerSong> = emptyList()
)

data class ServerSong(
    val id: String,
    val name: String? = null,
    val artist: String? = null
)

data class RecommendFeedbackRequest(
    val user_id: String?,
    val song_name: String,
    val reward: Double,
    val playlist: List<String> = emptyList(),
    val candidates: List<String>? = null,
    val policy: String? = null
)

data class RecommendFeedbackResponse(
    val status: String? = null,
    val error: String? = null
)
