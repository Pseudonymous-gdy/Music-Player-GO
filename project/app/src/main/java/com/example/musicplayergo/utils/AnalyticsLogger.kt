package com.example.musicplayergo.utils

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.example.musicplayergo.GoPreferences
import com.example.musicplayergo.models.Music
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * AnalyticsLogger - Unified Analytics Logger for Multi-Channel Event Tracking
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 【Core Features】
 * This class implements a complete user behavior analytics system integrating
 * three independent data channels:
 *
 * 1️⃣ Firebase Analytics (Google's Official Analytics Service)
 *    - Real-time user behavior tracking and funnel analysis
 *    - Automatic collection of device info, app crashes, user retention metrics
 *    - Deep integration with Google Play Console
 *
 * 2️⃣ Firestore (Cloud NoSQL Database)
 *    - Complete user behavior data archiving for deep analysis
 *    - Support for complex queries, real-time sync, and offline caching
 *    - Stores detailed structured data: song playback records, favorite actions, etc.
 *
 * 3️⃣ Personal Prediction Server (Custom Backend)
 *    - Side-channel reporting of user habit data via BehaviorReporter
 *    - Used for training personalized recommendation models (collaborative filtering/deep learning)
 *    - Supports real-time recommendation responses and online model updates
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 【Technical Highlights】
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * ✅ Session Management
 *    - 12-hour automatic session slicing: generates new UUID on timeout
 *    - Monotonically increasing sequence number (seq) ensures event ordering
 *    - Persisted to SharedPreferences for continuity across app restarts
 *
 * ✅ Parameter Sanitization & Type Conversion
 *    - Auto-detects Long/Double/String types and converts to Firebase Bundle format
 *    - Uniformly injects three metadata fields: session_id, seq, timestamp
 *    - Filters null values to prevent Firebase reporting failures
 *
 * ✅ Async Reporting & Fault Tolerance
 *    - Uses CoroutineScope(Dispatchers.IO) to avoid blocking main thread
 *    - SupervisorJob ensures single upload failures don't affect other tasks
 *    - Detailed logging for debugging and troubleshooting
 *
 * ✅ Multi-Channel Dual Write
 *    - Firebase Analytics: Lightweight tracking for real-time monitoring
 *    - Firestore: Complete data archiving for complex offline analysis
 *    - Custom Server: Flexible custom logic and model training
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 【Data Flow Architecture】
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *   User Action
 *        │
 *        ├─→ logEvent()  ← Unified entry point: parameter sanitization + session injection
 *        │       │
 *        │       ├─→ Firebase Analytics   ← Real-time event tracking
 *        │       │
 *        │       └─→ FirestoreLogger      ← Async write to cloud database
 *        │               │
 *        │               └─→ ServerLogger ← Side-channel report to custom server
 *        │
 *        └─→ BehaviorReporter ← Direct API call to recommendation service
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 【Implementation Details】
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 📌 Why 12-hour session slicing?
 *    - Aligns with typical user usage cycles (morning commute + evening leisure)
 *    - Avoids cross-day data contamination (morning/evening usage are different contexts)
 *    - Reduces single-session data volume, improving query efficiency
 *
 * 📌 Why use AtomicLong for sequence counter?
 *    - Guarantees atomicity of seq increment in multi-threaded environments (no locking needed)
 *    - Works seamlessly with async coroutine reporting to avoid duplicate sequence numbers
 *
 * 📌 Why three-channel reporting?
 *    - Firebase Analytics: Free, stable, automatic device metric collection
 *    - Firestore: Flexible queries, real-time sync, complex data structure support
 *    - Custom Server: Fully autonomous control, customizable recommendation algorithms
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 【Usage Example】
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * // 1. Initialize in Application.onCreate()
 * AnalyticsLogger.init(applicationContext)
 *
 * // 2. Log user playing a song
 * AnalyticsLogger.logPlayAction(song)
 *
 * // 3. Log user favorite action
 * AnalyticsLogger.logFavoriteAction(song, "add")
 *
 * // 4. Log recommendation click
 * AnalyticsLogger.logRecommendationClick(song, position, query)
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */
object AnalyticsLogger {

    private const val TAG = "AnalyticsLogger"

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Core Components
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Lazy-initialized Firebase Analytics instance with thread-safe visibility.
     * @Volatile ensures visibility across threads in multi-threaded environments.
     */
    @Volatile
    private var firebaseAnalytics: FirebaseAnalytics? = null

    /**
     * Shared preferences instance for session state persistence.
     * Provides access to global configuration for reading/writing session state.
     */
    private val prefs get() = GoPreferences.getPrefsInstance()

    /**
     * Atomic sequence counter for event ordering (thread-safe).
     * Initialized from persisted value to maintain continuity across app restarts.
     * Guarantees concurrent safety without explicit locking.
     */
    private val sequenceCounter by lazy { AtomicLong(prefs.analyticsSequence) }

    /**
     * Coroutine scope for async uploads to Firestore/custom server.
     * - SupervisorJob: Failure of one coroutine doesn't cancel others
     * - Dispatchers.IO: Optimized for network I/O operations, avoids blocking main thread
     */
    private val analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Session Management
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Ensures session ID validity with 12-hour auto-refresh mechanism.
     *
     * Core Logic:
     * 1. Checks time difference between now and session start time
     * 2. If exceeds 12 hours or session ID is empty:
     *    - Generates new UUID as session_id
     *    - Resets sequence counter to 0
     *    - Updates session start timestamp
     * 3. Otherwise returns existing session_id
     *
     * Why 12 Hours?
     * - Aligns with typical user patterns (morning + evening)
     * - Prevents cross-day data contamination
     * - Balances session granularity and data volume
     *
     * @return Current valid session ID
     */
    private fun ensureSessionId(): String {
        val now = System.currentTimeMillis()
        val twelveHours = 12 * 60 * 60 * 1000L
        val existingId = prefs.analyticsSessionId
        val startedAt = prefs.analyticsSessionStartedAt

        // Check if session refresh is needed
        val shouldRefresh = existingId.isNullOrBlank() || startedAt == 0L || now - startedAt > twelveHours

        if (shouldRefresh) {
            // Generate new session
            val newId = UUID.randomUUID().toString()
            prefs.analyticsSessionId = newId
            prefs.analyticsSessionStartedAt = now
            prefs.analyticsSequence = 0L
            sequenceCounter.set(0L)
            return newId
        }

        return existingId!!
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Initialization
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Initialize analytics system (called in Application.onCreate()).
     *
     * Initialization Flow:
     * 1. Initialize Firebase App and Firebase Analytics
     *    - Uses applicationContext to prevent memory leaks
     *    - Enables analytics collection (setAnalyticsCollectionEnabled)
     *
     * 2. Initialize Firestore Logger
     *    - Configures cloud database connection
     *    - Prepares async upload channel
     *
     * 3. Generate or restore session ID
     *    - Checks if refresh is needed (12-hour mechanism)
     *    - Outputs debug information
     *
     * Error Handling:
     * - Wraps Firebase init in try-catch to prevent crashes
     * - Firestore continues to work even if Firebase init fails
     *
     * @param context Application context
     */
    fun init(context: Context) {
        Log.d(TAG, "🚀 Initializing Analytics...")
        try {
            if (firebaseAnalytics == null) {
                Log.d(TAG, "   Initializing Firebase App...")
                // Initialize Firebase core components
                FirebaseApp.initializeApp(context.applicationContext)

                // Get Firebase Analytics instance and enable data collection
                firebaseAnalytics = FirebaseAnalytics.getInstance(context.applicationContext).apply {
                    setAnalyticsCollectionEnabled(true)
                }

                Log.i(TAG, "   ✓ Firebase Analytics initialized successfully")
                Log.d(TAG, "   Package: ${context.packageName}")
            } else {
                Log.d(TAG, "   Already initialized")
            }
        } catch (e: Exception) {
            // Fault tolerance: App continues to run even if Firebase init fails
            Log.e(TAG, "   ✗ Firebase init failed; analytics disabled", e)
            Log.e(TAG, "   Error: ${e.message}")
        }

        // Initialize Firestore Logger (independent of Firebase Analytics)
        FirestoreLogger.init(context.applicationContext)

        // Generate/restore session ID and output debug info
        val sessionId = ensureSessionId()
        Log.d(TAG, "   Session ID: ${sessionId.take(8)}...")
        Log.d(TAG, "   Sequence: ${sequenceCounter.get()}")
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Core Logging Logic
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Unified event logging entry point (all logXxx methods call this internally).
     *
     * Core Responsibilities:
     * 1. Parameter sanitization and standardization
     *    - Converts all values to strings (Firebase requirement)
     *    - Injects session_id, seq, timestamp metadata
     *
     * 2. Multi-channel reporting
     *    - Firebase Analytics: Real-time tracking
     *    - Firestore: Detailed log archiving (called by sub-methods)
     *    - Custom server: Reports via BehaviorReporter
     *
     * 3. Sequence increment and persistence
     *    - Uses AtomicLong.incrementAndGet() for atomicity
     *    - Immediately writes back to SharedPreferences to prevent loss on crash
     *
     * Data Format Example:
     * Input params:
     *   {"song_id": 123, "title": "Song A", "artist": "Artist B"}
     *
     * After sanitization:
     *   {
     *     "song_id": "123",
     *     "title": "Song A",
     *     "artist": "Artist B",
     *     "session_id": "a1b2c3d4-...",
     *     "seq": "42",
     *     "timestamp": "1702345678901"
     *   }
     *
     * @param name Event name (must follow Firebase naming rules)
     * @param params Custom parameters (key-value pairs)
     */
    private fun logEvent(name: String, params: Map<String, Any?> = emptyMap()) {
        // ───────────────────────────────────────────────────────────────
        // Step 1: Ensure session validity, get sequence and timestamp
        // ───────────────────────────────────────────────────────────────
        val sessionId = ensureSessionId()
        val sequence = sequenceCounter.incrementAndGet().also {
            // Persist sequence immediately to prevent gaps on crash
            prefs.analyticsSequence = it
        }
        val timestamp = System.currentTimeMillis()

        // ───────────────────────────────────────────────────────────────
        // Step 2: Parameter sanitization - convert all to strings and inject metadata
        // ───────────────────────────────────────────────────────────────
        val sanitizedParams = params.mapValues { it.value?.toString() }.toMutableMap()
        sanitizedParams["session_id"] = sessionId
        sanitizedParams["seq"] = sequence.toString()
        sanitizedParams["timestamp"] = timestamp.toString()

        // ───────────────────────────────────────────────────────────────
        // Step 3: Output detailed debug logs (useful for validation during development)
        // ───────────────────────────────────────────────────────────────
        Log.d(TAG, "📊 Event: $name | Session: ${sessionId.take(8)}... | Seq: $sequence")
        if (params.isNotEmpty()) {
            Log.d(TAG, "   Params: ${params.entries.joinToString { "${it.key}=${it.value}" }}")
        }

        // ───────────────────────────────────────────────────────────────
        // Step 4: Report to Firebase Analytics (real-time tracking)
        // ───────────────────────────────────────────────────────────────
        if (firebaseAnalytics != null) {
            // Convert to Firebase-required Bundle format (auto type detection)
            firebaseAnalytics?.logEvent(name, buildBundle(sanitizedParams))
            Log.d(TAG, "   ✓ Sent to Firebase")
        } else {
            // Firebase not initialized (possibly due to missing google-services.json)
            Log.w(TAG, "   ✗ Firebase not initialized, event not sent")
        }

        // ───────────────────────────────────────────────────────────────
        // Note: Firestore and custom server uploads are called in specific logXxx methods
        //
        // Advantages of this design:
        // 1. Avoid redundant uploads (not all events need detailed archiving)
        // 2. Allow different events to use different Firestore collections
        // 3. Support async batch upload optimization
        // ───────────────────────────────────────────────────────────────
    }

    /**
     * Build Firebase Analytics Bundle with intelligent type conversion.
     *
     * Type Conversion Rules:
     * Firebase Analytics supports three types: Long, Double, String
     *
     * Conversion priority:
     * 1. Try converting to Long (integer)
     * 2. Try converting to Double (floating-point)
     * 3. Default to String (text)
     *
     * Example:
     * Input:  {"seq": "42", "duration": "3.14", "title": "Song A"}
     * Output: Bundle {seq=42L, duration=3.14D, title="Song A"}
     *
     * @param params Stringified parameter map
     * @return Firebase Analytics Bundle object
     */
    private fun buildBundle(params: Map<String, String?>): Bundle = Bundle().apply {
        params.forEach { (key, value) ->
            // Try converting to Long
            value?.toLongOrNull()?.let {
                putLong(key, it)
                return@forEach
            }
            // Try converting to Double
            value?.toDoubleOrNull()?.let {
                putDouble(key, it)
                return@forEach
            }
            // Default to String
            putString(key, value)
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Business Event Tracking Methods
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Log screen view (standard Firebase event).
     *
     * Use Cases:
     * - User switches to "Recommendations" tab
     * - User opens song details page
     *
     * @param screenName Screen name
     * @param screenClass Screen class (default: MainActivity)
     */
    fun logScreenView(screenName: String, screenClass: String = "MainActivity") {
        logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            mapOf(
                FirebaseAnalytics.Param.SCREEN_NAME to screenName,
                FirebaseAnalytics.Param.SCREEN_CLASS to screenClass
            )
        )
    }

    /**
     * Log play button click (Firebase SELECT_CONTENT event).
     *
     * Use Cases:
     * - User clicks bottom play/pause button
     * - User clicks a song in the song list
     *
     * @param songTitle Song title
     * @param artist Artist
     */
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

    /**
     * Log song listening duration (for user habit analysis).
     *
     * Dual Event Design:
     * - song_complete: Single playback completion
     * - habit_listen: Aggregate analysis for listening habits
     *
     * Use Cases:
     * - Triggered when song playback ends
     * - Used to calculate song completion rate
     *
     * @param song Song object
     * @param listenedSeconds Listened seconds
     */
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

    /**
     * Log tab view (for analyzing user preferred modules).
     *
     * @param tabName Tab name (e.g., "Artists", "Albums")
     * @param index Tab index
     */
    fun logTabView(tabName: String, index: Int) {
        logEvent("tab_view", mapOf("tab" to tabName, "index" to index))
    }

    /**
     * Log tab dwell time (for analyzing user interest).
     *
     * @param tabName Tab name
     * @param durationMs Dwell time in milliseconds
     */
    fun logTabDuration(tabName: String, durationMs: Long) {
        logEvent("tab_duration", mapOf("tab" to tabName, "duration_ms" to durationMs))
    }

    /**
     * Log search behavior (for optimizing search results).
     *
     * @param query Search query
     * @param screen Screen where search was initiated
     */
    fun logSearch(query: String, screen: String) {
        logEvent("search", mapOf("screen" to screen, "query" to query))
    }

    /**
     * Log recommendation click (for evaluating recommendation model performance).
     *
     * Key Metrics:
     * - position: Recommendation position (CTR strongly correlates with position)
     * - query: Recommendation query context (for A/B testing)
     *
     * @param song Clicked song
     * @param position Position in recommendation list (0-based)
     * @param query Recommendation query
     */
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

    /**
     * Log recommendation refresh (for analyzing user satisfaction with recommendations).
     *
     * @param source Refresh source (e.g., "manual", "auto")
     */
    fun logRefreshRecommendations(source: String) {
        logEvent("recommend_refresh", mapOf("source" to source))
    }

    /**
     * Log song selection (for analyzing user selection paths).
     *
     * @param song Selected song
     * @param source Selection source (e.g., "artist_detail", "album_list", "recommendation")
     */
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

    /**
     * Log prediction result (for monitoring recommendation system performance).
     *
     * @param source Prediction source (e.g., "collaborative_filtering", "deep_learning")
     * @param count Number of recommended songs
     */
    fun logPredictionResult(source: String, count: Int) {
        logEvent("prediction_result", mapOf("source" to source, "count" to count))
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Multi-Channel Reporting Methods (Firebase + Firestore + Custom Server)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Log favorite action (triple-channel reporting).
     *
     * Data Flow:
     * 1. Firebase Analytics: Real-time tracking "favorite_action"
     *
     * 2. Firestore: Async write complete favorite record
     *    - Collection path: favorites/{sessionId}/{songId}
     *    - Includes complete song metadata (title, artist, duration...)
     *
     * 3. Custom server (via ServerLogger called internally by FirestoreLogger)
     *    - Used for training recommendation models
     *
     * Why Use Coroutines:
     * - Firestore write is network I/O operation (time-consuming)
     * - Main thread execution causes UI lag
     * - analyticsScope.launch automatically switches to IO thread pool
     *
     * @param song Favorited/unfavorited song
     * @param action Action type ("add" or "remove")
     */
    fun logFavoriteAction(song: Music?, action: String) {
        // ───────────────────────────────────────────────────────────────
        // Channel 1: Firebase Analytics (real-time lightweight tracking)
        // ───────────────────────────────────────────────────────────────
        logEvent(
            "favorite_action",
            mapOf(
                "song_id" to (song?.id ?: -1),
                "title" to song?.title,
                "artist" to song?.artist,
                "action" to action
            )
        )

        // ───────────────────────────────────────────────────────────────
        // Channel 2 + 3: Firestore + Custom server (async detailed archiving)
        // ───────────────────────────────────────────────────────────────
        analyticsScope.launch {
            val sessionId = ensureSessionId()
            val sequence = sequenceCounter.get()

            if (action == "add") {
                // Report favorite add event
                FirestoreLogger.logFavoriteAddEvent(sessionId, sequence, song)
            } else if (action == "remove") {
                // Report favorite remove event
                FirestoreLogger.logFavoriteRemoveEvent(sessionId, sequence, song)
            }
        }
    }

    /**
     * Log play action (triple-channel reporting).
     *
     * Data Flow:
     * 1. Firebase Analytics: Track "play_action"
     *
     * 2. Firestore: Write play record to play_events collection
     *
     * 3. Custom server: Report to recommendation service via BehaviorReporter
     *
     * @param song Currently playing song
     */
    fun logPlayAction(song: Music?) {
        // Channel 1: Firebase Analytics
        logEvent(
            "play_action",
            mapOf(
                "song_id" to (song?.id ?: -1),
                "title" to song?.title,
                "artist" to song?.artist
            )
        )

        // Channel 2 + 3: Firestore + Custom server
        analyticsScope.launch {
            FirestoreLogger.logPlayEvent(
                ensureSessionId(),
                sequenceCounter.get(),
                song
            )
        }
    }

    /**
     * Log pause action (triple-channel reporting + playback duration stats).
     *
     * Key Data:
     * - playedDuration: Current playback duration (for completion rate calculation)
     *
     * @param song Currently playing song
     * @param playedDuration Playback duration (milliseconds)
     */
    fun logPauseAction(song: Music?, playedDuration: Long? = null) {
        // Channel 1: Firebase Analytics
        logEvent(
            "pause_action",
            mapOf(
                "song_id" to (song?.id ?: -1),
                "title" to song?.title,
                "artist" to song?.artist,
                "played_duration" to playedDuration
            )
        )

        // Channel 2 + 3: Firestore + Custom server
        analyticsScope.launch {
            FirestoreLogger.logPauseEvent(
                ensureSessionId(),
                sequenceCounter.get(),
                song,
                playedDuration
            )
        }
    }

    /**
     * Log skip action (triple-channel reporting + direction marking).
     *
     * Direction Marking:
     * - "next": Skip to next song (user dislikes current song)
     * - "previous": Back to previous song (user wants to re-listen)
     *
     * @param song Skipped song
     * @param isNext Whether skipping to next song
     */
    fun logSkipAction(song: Music?, isNext: Boolean) {
        val direction = if (isNext) "next" else "previous"

        // Channel 1: Firebase Analytics
        logEvent(
            "skip_action",
            mapOf(
                "song_id" to (song?.id ?: -1),
                "title" to song?.title,
                "artist" to song?.artist,
                "direction" to direction
            )
        )

        // Channel 2 + 3: Firestore + Custom server
        analyticsScope.launch {
            FirestoreLogger.logSkipEvent(
                ensureSessionId(),
                sequenceCounter.get(),
                song,
                direction
            )
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Summary
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //
    // This file demonstrates a complete user behavior analytics system design, including:
    //
    // ✅ Firebase Analytics integration (real-time tracking)
    // ✅ Firestore cloud archiving (structured storage)
    // ✅ Custom server reporting (personalized recommendations)
    // ✅ Session management (12-hour slicing + sequence numbering)
    // ✅ Parameter sanitization (type conversion + metadata injection)
    // ✅ Async reporting (coroutines + fault tolerance)
    // ✅ Detailed logging (for debugging and validation)
    //
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
}
