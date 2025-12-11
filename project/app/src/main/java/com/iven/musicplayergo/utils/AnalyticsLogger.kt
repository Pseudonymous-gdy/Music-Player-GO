package com.iven.musicplayergo.utils

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.iven.musicplayergo.GoPreferences
import com.iven.musicplayergo.models.Music
import com.iven.musicplayergo.network.BehaviorEventPayload
import com.iven.musicplayergo.network.BehaviorPredictResponse
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AnalyticsLogger {

    private const val TAG = "AnalyticsLogger"

    @Volatile
    private var firebaseAnalytics: FirebaseAnalytics? = null
    private val prefs get() = GoPreferences.getPrefsInstance()
    private val sequenceCounter by lazy { AtomicLong(prefs.analyticsSequence) }

    private fun ensureSessionId(): String {
        val now = System.currentTimeMillis()
        val twelveHours = 12 * 60 * 60 * 1000L
        val existingId = prefs.analyticsSessionId
        val startedAt = prefs.analyticsSessionStartedAt
        val shouldRefresh = existingId.isNullOrBlank() || startedAt == 0L || now - startedAt > twelveHours
        if (shouldRefresh) {
            val newId = UUID.randomUUID().toString()
            prefs.analyticsSessionId = newId
            prefs.analyticsSessionStartedAt = now
            prefs.analyticsSequence = 0L
            sequenceCounter.set(0L)
            return newId
        }
        return existingId!!
    }

    fun init(context: Context) {
        try {
            if (firebaseAnalytics == null) {
                FirebaseApp.initializeApp(context.applicationContext)
                firebaseAnalytics = FirebaseAnalytics.getInstance(context.applicationContext).apply {
                    setAnalyticsCollectionEnabled(true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed; analytics disabled", e)
        }
        ensureSessionId()
    }

    private fun logEvent(name: String, params: Map<String, Any?> = emptyMap()) {
        val sessionId = ensureSessionId()
        val sequence = sequenceCounter.incrementAndGet().also { prefs.analyticsSequence = it }
        val timestamp = System.currentTimeMillis()

        val sanitizedParams = params.mapValues { it.value?.toString() }.toMutableMap()
        sanitizedParams["session_id"] = sessionId
        sanitizedParams["seq"] = sequence.toString()
        sanitizedParams["timestamp"] = timestamp.toString()

        firebaseAnalytics?.logEvent(name, buildBundle(sanitizedParams))

        BehaviorReporter.recordEvent(
            BehaviorEventPayload(
                sessionId = sessionId,
                sequence = sequence,
                eventName = name,
                timestamp = timestamp,
                params = sanitizedParams
            )
        )
    }

    private fun buildBundle(params: Map<String, String?>): Bundle = Bundle().apply {
        params.forEach { (key, value) ->
            value?.toLongOrNull()?.let {
                putLong(key, it)
                return@forEach
            }
            value?.toDoubleOrNull()?.let {
                putDouble(key, it)
                return@forEach
            }
            putString(key, value)
        }
    }

    fun logScreenView(screenName: String, screenClass: String = "MainActivity") {
        logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            mapOf(
                FirebaseAnalytics.Param.SCREEN_NAME to screenName,
                FirebaseAnalytics.Param.SCREEN_CLASS to screenClass
            )
        )
    }

    fun logPlayButtonClick(songTitle: String?, artist: String?) {
        logEvent(
            FirebaseAnalytics.Event.SELECT_CONTENT,
            mapOf(
                FirebaseAnalytics.Param.ITEM_ID to "btn_play",
                FirebaseAnalytics.Param.ITEM_NAME to (songTitle ?: "unknown"),
                "artist_name" to (artist ?: "")
            )
        )
    }

    fun logSongListenDuration(song: Music?, listenedSeconds: Long) {
        val completedAt = System.currentTimeMillis()
        val payload = mapOf(
            "song_id" to (song?.id ?: -1),
            "song_title" to (song?.title ?: song?.displayName ?: "unknown"),
            "listen_duration" to listenedSeconds,
            "completed_at" to completedAt
        )
        logEvent("song_complete", payload)
        logEvent("habit_listen", payload)
    }

    fun logTabView(tabName: String, index: Int) {
        logEvent("tab_view", mapOf("tab" to tabName, "index" to index))
    }

    fun logTabDuration(tabName: String, durationMs: Long) {
        logEvent("tab_duration", mapOf("tab" to tabName, "duration_ms" to durationMs))
    }

    fun logSearch(query: String, screen: String) {
        logEvent("search", mapOf("screen" to screen, "query" to query))
    }

    fun logRecommendationClick(song: Music, position: Int, query: String) {
        logEvent(
            "recommend_click",
            mapOf(
                "song_id" to song.id,
                "title" to song.title,
                "artist" to song.artist,
                "position" to position,
                "query" to query
            )
        )
    }

    fun logRefreshRecommendations(source: String) {
        logEvent("recommend_refresh", mapOf("source" to source))
    }

    fun logSongSelected(song: Music?, source: String) {
        logEvent(
            "song_selected",
            mapOf(
                "song_id" to (song?.id ?: -1),
                "title" to song?.title,
                "artist" to song?.artist,
                "source" to source
            )
        )
    }

    fun logPredictionResult(source: String, count: Int) {
        logEvent("prediction_result", mapOf("source" to source, "count" to count))
    }

    fun logFavoriteAction(song: Music?, action: String) {
        logEvent(
            "favorite_action",
            mapOf(
                "song_id" to (song?.id ?: -1),
                "title" to song?.title,
                "artist" to song?.artist,
                "action" to action
            )
        )
    }

    suspend fun requestPredictions(topK: Int = 10): BehaviorPredictResponse? =
        withContext(Dispatchers.IO) {
            BehaviorReporter.requestPredictions(ensureSessionId(), topK)
        }
}
