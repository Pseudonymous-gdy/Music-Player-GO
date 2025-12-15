package com.example.musicplayergo

import android.app.Application
import dagger.hilt.android.testing.CustomTestApplication

/**
 * Base class for test application.
 * Initializes GoPreferences which is required for tests to run.
 */
open class TestGoAppBase : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize GoPreferences - required by many components
        GoPreferences.initPrefs(applicationContext)
    }
}

/**
 * Custom test application that extends TestGoAppBase.
 * Hilt will generate HiltTestApplication_Application class from this.
 */
@CustomTestApplication(TestGoAppBase::class)
interface TestGoApp
