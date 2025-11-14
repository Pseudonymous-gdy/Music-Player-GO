package com.iven.musicplayergo.utils

import com.iven.musicplayergo.GoPreferences
import com.iven.musicplayergo.models.RecommendationFeature
import com.iven.musicplayergo.network.ArchiveService

object RecommendationRepository {

    private val prefs get() = GoPreferences.getPrefsInstance()

    private val cachedFeatures: List<RecommendationFeature>
        get() = prefs.recommendationFeatures ?: emptyList()

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
}
