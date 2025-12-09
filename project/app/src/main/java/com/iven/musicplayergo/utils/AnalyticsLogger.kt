package com.iven.musicplayergo.utils

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
//import com.google.firebase.analytics.ktx.analytics
//import com.google.firebase.ktx.Firebase
import com.iven.musicplayergo.models.Music

object AnalyticsLogger {

    @Volatile
    private var firebaseAnalytics: FirebaseAnalytics? = null

    fun init(context: Context) {
        if (firebaseAnalytics == null) {
            firebaseAnalytics = FirebaseAnalytics.getInstance(context).apply {
                setAnalyticsCollectionEnabled(true)
            }
        }
    }

    fun logScreenView(screenName: String, screenClass: String = "MainActivity") {
        firebaseAnalytics?.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
        })
    }

    fun logPlayButtonClick(songTitle: String?, artist: String?) {
        firebaseAnalytics?.logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, "btn_play")
            putString(FirebaseAnalytics.Param.ITEM_NAME, songTitle ?: "unknown")
            putString("artist_name", artist ?: "")
            putString("click_timestamp", System.currentTimeMillis().toString())
        })
    }

    fun logSongListenDuration(song: Music?, listenedSeconds: Long) {
        firebaseAnalytics?.logEvent("song_complete", Bundle().apply {
            putString("song_title", song?.title ?: song?.displayName ?: "unknown")
            putLong("listen_duration", listenedSeconds)
        })
    }
}
