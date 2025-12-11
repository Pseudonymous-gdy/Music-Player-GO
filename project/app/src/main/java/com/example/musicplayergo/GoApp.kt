package com.example.musicplayergo

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.request.CachePolicy
import com.example.musicplayergo.utils.Theming
import com.example.musicplayergo.utils.AnalyticsLogger


class GoApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        GoPreferences.initPrefs(applicationContext)
        AnalyticsLogger.init(this)
        AppCompatDelegate.setDefaultNightMode(Theming.getDefaultNightMode(applicationContext))
    }

    override fun newImageLoader() = ImageLoader.Builder(this)
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(true)
        .build()
}
