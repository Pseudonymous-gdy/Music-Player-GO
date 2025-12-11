package com.iven.musicplayergo.utils

import android.util.Log
import com.iven.musicplayergo.network.BehaviorEventPayload
import com.iven.musicplayergo.network.BehaviorPredictRequest
import com.iven.musicplayergo.network.BehaviorPredictResponse
import com.iven.musicplayergo.network.BehaviorService
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BehaviorReporter {

    private const val TAG = "BehaviorReporter"
    private const val MAX_EVENTS = 50
    private val recentEvents = ArrayDeque<BehaviorEventPayload>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun recordEvent(payload: BehaviorEventPayload) {
        synchronized(recentEvents) {
            if (recentEvents.size >= MAX_EVENTS) {
                recentEvents.removeFirst()
            }
            recentEvents.addLast(payload)
        }
        scope.launch {
            try {
                BehaviorService.api.sendEvent(payload)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send event ${payload.eventName}", e)
            }
        }
    }

    suspend fun requestPredictions(sessionId: String, topK: Int = 10): BehaviorPredictResponse? {
        val snapshot = synchronized(recentEvents) { recentEvents.toList() }
        return try {
            withContext(Dispatchers.IO) {
                BehaviorService.api.predict(
                    BehaviorPredictRequest(
                        sessionId = sessionId,
                        recentEvents = snapshot,
                        topK = topK
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Prediction request failed", e)
            null
        }
    }
}
