package com.iven.musicplayergo.network

data class BehaviorEventPayload(
    val sessionId: String,
    val sequence: Long,
    val eventName: String,
    val timestamp: Long,
    val userId: String? = null,
    val params: Map<String, String?> = emptyMap()
)

data class BehaviorPredictRequest(
    val sessionId: String,
    val recentEvents: List<BehaviorEventPayload> = emptyList(),
    val topK: Int = 10
)

data class BehaviorPredictResponse(
    val recommendations: List<RecommendationItem> = emptyList(),
    val modelVersion: String? = null
)
