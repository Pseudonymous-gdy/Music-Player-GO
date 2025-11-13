package com.iven.musicplayergo

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.request.CachePolicy
import com.iven.musicplayergo.utils.Theming
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform


class GoApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        GoPreferences.initPrefs(applicationContext)
        AppCompatDelegate.setDefaultNightMode(Theming.getDefaultNightMode(applicationContext))
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    override fun newImageLoader() = ImageLoader.Builder(this)
        .diskCachePolicy(CachePolicy.DISABLED)
        .crossfade(true)
        .build()
}
