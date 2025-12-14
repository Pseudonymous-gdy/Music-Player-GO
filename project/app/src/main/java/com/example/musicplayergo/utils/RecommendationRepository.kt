package com.example.musicplayergo.utils

import android.util.Log
import com.example.musicplayergo.GoPreferences
import com.example.musicplayergo.models.RecommendationFeature
import com.example.musicplayergo.network.ArchiveService
import com.example.musicplayergo.network.RecommendFeedbackRequest
import com.example.musicplayergo.network.RecommendService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object RecommendationRepository {

    private val prefs get() = GoPreferences.getPrefsInstance()
    private val feedbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val TAG = "RecommendationRepo"

    private val cachedFeatures: List<RecommendationFeature>
        get() = prefs.recommendationFeatures ?: emptyList()

    /**
     * 获取或生成用户ID
     * 从偏好设置中获取，如果没有则返回默认值
     */
    fun getUserId(): String {
        return prefs.recommendationUserId ?: "android_user_default"
    }

    /**
     * 保存用户ID
     */
    fun saveUserId(userId: String) {
        prefs.recommendationUserId = userId
    }

    /**
     * 获取推荐策略
     */
    fun getRecommendationPolicy(): String {
        return prefs.recommendationPolicy
    }

    /**
     * 保存推荐策略
     */
    fun saveRecommendationPolicy(policy: String) {
        prefs.recommendationPolicy = policy
    }

    fun saveFeatureMapping(
        localSongId: Long?,
        serverSongId: String?,
        featurePath: String?,
        rawFeature: String?
    ) {
        if (localSongId == null) return
        val featureUrl = when {
            !featurePath.isNullOrBlank() -> ArchiveService.buildAbsoluteUrl(featurePath)
            !rawFeature.isNullOrBlank() -> ArchiveService.buildAbsoluteUrl(rawFeature)
            else -> null
        } ?: return

        val features = prefs.recommendationFeatures?.toMutableList() ?: mutableListOf()
        features.removeAll { it.localSongId == localSongId }
        features.add(RecommendationFeature(localSongId, serverSongId, featureUrl))
        prefs.recommendationFeatures = features
    }

    fun getFeatureUrlForSong(localSongId: Long?): String? {
        if (localSongId == null) return null
        return prefs.recommendationFeatures?.firstOrNull { it.localSongId == localSongId }?.featureUrl
    }

    fun getServerIdForSong(localSongId: Long?): String? {
        if (localSongId == null) return null
        return cachedFeatures.firstOrNull { it.localSongId == localSongId }?.serverSongId
    }

    fun getLocalSongIdForServer(serverSongId: String?): Long? {
        if (serverSongId.isNullOrBlank()) return null
        return cachedFeatures.firstOrNull { serverSongId == it.serverSongId }?.localSongId
    }

    fun getAllFeatures(): List<RecommendationFeature> = cachedFeatures

    fun sendFeedbackForLocalSong(
        localSongId: Long?,
        reward: Double,
        source: String,
        listenedSeconds: Long? = null
    ) {
        if (localSongId == null) return
        val serverSongId = getServerIdForSong(localSongId) ?: return
        val request = RecommendFeedbackRequest(
            user_id = getUserId(),
            song_name = serverSongId,
            reward = reward,
            policy = getRecommendationPolicy()
        )
        feedbackScope.launch {
            try {
                val response = RecommendService.api.sendFeedback(request)
                if (response.status != "ok") {
                    Log.w(TAG, "Feedback ($source) received status=${response.status}, error=${response.error}")
                } else {
                    Log.d(TAG, "Feedback ($source) sent for $serverSongId reward=$reward listened=$listenedSeconds")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to send feedback ($source) for $serverSongId", t)
            }
        }
    }
}
