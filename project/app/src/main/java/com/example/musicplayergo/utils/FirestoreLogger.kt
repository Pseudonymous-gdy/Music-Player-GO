package com.example.musicplayergo.utils

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.musicplayergo.models.Music
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Firestore logger for user behavior tracking
 * Uploads user actions (play, pause, skip, favorite) to Firestore
 */
object FirestoreLogger {

    private const val TAG = "FirestoreLogger"
    private const val COLLECTION_USER_BEHAVIORS = "user_behaviors"

    @Volatile
    private var firestore: FirebaseFirestore? = null

    @Volatile
    private var userId: String? = null

    /**
     * Initialize Firestore logger
     */
    fun init(context: Context) {
        try {
            if (firestore == null) {
                firestore = FirebaseFirestore.getInstance()
                userId = getDeviceId(context)
                Log.d(TAG, "✓ Firestore initialized successfully")
                Log.d(TAG, "   User ID: ${userId?.take(8)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Firestore init failed", e)
        }
    }

    /**
     * Get unique device ID
     */
    private fun getDeviceId(context: Context): String {
        return try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Upload user behavior to Firestore
     */
    suspend fun logBehavior(
        sessionId: String,
        sequence: Long,
        eventType: String,
        timestamp: Long,
        song: Music? = null,
        additionalData: Map<String, Any?>? = null
    ) = withContext(Dispatchers.IO) {
        if (firestore == null) {
            Log.w(TAG, "Firestore not initialized, skipping log")
            return@withContext
        }

        try {
            val data = buildBehaviorData(
                sessionId = sessionId,
                sequence = sequence,
                eventType = eventType,
                timestamp = timestamp,
                song = song,
                additionalData = additionalData
            )

            firestore!!.collection(COLLECTION_USER_BEHAVIORS)
                .add(data)
                .await()

            Log.d(TAG, "📤 Uploaded: $eventType | Song: ${song?.title ?: "N/A"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload behavior: $eventType", e)
        }
    }

    /**
     * Build behavior data map for Firestore
     */
    private fun buildBehaviorData(
        sessionId: String,
        sequence: Long,
        eventType: String,
        timestamp: Long,
        song: Music?,
        additionalData: Map<String, Any?>?
    ): Map<String, Any?> {
        val data = mutableMapOf<String, Any?>(
            "userId" to (userId ?: "unknown"),
            "sessionId" to sessionId,
            "sequence" to sequence,
            "eventType" to eventType,
            "timestamp" to timestamp
        )

        // Add song information if available
        song?.let {
            data["songId"] = it.id
            data["songTitle"] = it.title ?: it.displayName ?: "unknown"
            data["artist"] = it.artist ?: "unknown"
            data["album"] = it.album ?: "unknown"
            data["duration"] = it.duration
        }

        // Add additional data if provided
        additionalData?.let { additional ->
            data["additionalData"] = additional
        }

        return data
    }

    /**
     * Log play event
     */
    suspend fun logPlayEvent(
        sessionId: String,
        sequence: Long,
        song: Music?
    ) {
        logBehavior(
            sessionId = sessionId,
            sequence = sequence,
            eventType = "play",
            timestamp = System.currentTimeMillis(),
            song = song
        )
    }

    /**
     * Log pause event
     */
    suspend fun logPauseEvent(
        sessionId: String,
        sequence: Long,
        song: Music?,
        playedDuration: Long? = null
    ) {
        val additionalData = playedDuration?.let {
            mapOf("playedDuration" to it)
        }

        logBehavior(
            sessionId = sessionId,
            sequence = sequence,
            eventType = "pause",
            timestamp = System.currentTimeMillis(),
            song = song,
            additionalData = additionalData
        )
    }

    /**
     * Log skip event
     */
    suspend fun logSkipEvent(
        sessionId: String,
        sequence: Long,
        song: Music?,
        direction: String  // "next" or "previous"
    ) {
        logBehavior(
            sessionId = sessionId,
            sequence = sequence,
            eventType = "skip_$direction",
            timestamp = System.currentTimeMillis(),
            song = song,
            additionalData = mapOf("direction" to direction)
        )
    }

    /**
     * Log favorite add event
     */
    suspend fun logFavoriteAddEvent(
        sessionId: String,
        sequence: Long,
        song: Music?
    ) {
        logBehavior(
            sessionId = sessionId,
            sequence = sequence,
            eventType = "favorite_add",
            timestamp = System.currentTimeMillis(),
            song = song
        )
    }

    /**
     * Log favorite remove event
     */
    suspend fun logFavoriteRemoveEvent(
        sessionId: String,
        sequence: Long,
        song: Music?
    ) {
        logBehavior(
            sessionId = sessionId,
            sequence = sequence,
            eventType = "favorite_remove",
            timestamp = System.currentTimeMillis(),
            song = song
        )
    }
}
