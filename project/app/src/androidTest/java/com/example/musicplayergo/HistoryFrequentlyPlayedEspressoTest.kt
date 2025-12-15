package com.example.musicplayergo

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayergo.models.Music
import com.example.musicplayergo.repository.FakeMusicRepository
import com.example.musicplayergo.testhost.TestHostActivity
import com.example.musicplayergo.utils.PlaybackHistory
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Espresso tests for HistoryFragment frequently played functionality.
 * Uses Hilt dependency injection with FakeMusicRepository.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HistoryFrequentlyPlayedEspressoTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var fakeMusicRepository: FakeMusicRepository

    private lateinit var songA: Music
    private lateinit var songB: Music
    private lateinit var songC: Music

    @Before
    fun setup() {
        hiltRule.inject()

        // Clear any existing history
        PlaybackHistory.clear()

        // Create test songs
        songA = Music(
            artist = "Artist X",
            year = 2023,
            track = 1,
            title = "Song A",
            displayName = "SongA.mp3",
            duration = 120_000L,
            album = "Album X",
            albumId = 100L,
            relativePath = "/music",
            id = 1L,
            launchedBy = GoConstants.ARTIST_VIEW,
            startFrom = 0,
            dateAdded = 1700000000
        )

        songB = Music(
            artist = "Artist Y",
            year = 2023,
            track = 2,
            title = "Song B",
            displayName = "SongB.mp3",
            duration = 120_000L,
            album = "Album Y",
            albumId = 200L,
            relativePath = "/music",
            id = 2L,
            launchedBy = GoConstants.ARTIST_VIEW,
            startFrom = 0,
            dateAdded = 1700000001
        )

        songC = Music(
            artist = "Artist Z",
            year = 2022,
            track = 3,
            title = "Song C",
            displayName = "SongC.mp3",
            duration = 120_000L,
            album = "Album Z",
            albumId = 300L,
            relativePath = "/music",
            id = 3L,
            launchedBy = GoConstants.ARTIST_VIEW,
            startFrom = 0,
            dateAdded = 1700000002
        )

        // Set up test music in repository
        fakeMusicRepository.setMusicData(listOf(songA, songB, songC))
    }

    @After
    fun teardown() {
        PlaybackHistory.clear()
    }

    /**
     * Test that HistoryFragment launches successfully.
     */
    @Test
    fun historyFragment_launchesSuccessfully() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_HISTORY)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use { scenario ->
            // Wait for fragment to load
            Thread.sleep(500)

            scenario.onActivity { activity ->
                assertThat("Activity should not be null", activity, notNullValue())
            }
        }
    }

    /**
     * Test that PlaybackHistory correctly tracks played songs.
     * Verifies the history logging and deduplication logic.
     */
    @Test
    fun playbackHistory_logsSongsCorrectly() {
        // Initially history should be empty
        assertThat(
            "History should be empty initially",
            PlaybackHistory.current().isEmpty(),
            `is`(true)
        )

        // Log some songs: B -> A -> C -> A (A played twice)
        PlaybackHistory.log(songB, GoConstants.ARTIST_VIEW)
        Thread.sleep(10)
        PlaybackHistory.log(songA, GoConstants.ARTIST_VIEW)
        Thread.sleep(10)
        PlaybackHistory.log(songC, GoConstants.ARTIST_VIEW)
        Thread.sleep(10)
        PlaybackHistory.log(songA, GoConstants.ARTIST_VIEW) // Replay A

        // Wait for state to update
        Thread.sleep(100)

        val history = PlaybackHistory.current()

        // History should have 3 unique songs (deduplicated)
        assertThat(
            "History should have 3 unique songs",
            history.size,
            `is`(3)
        )

        // Most recently played (Song A) should be first due to deduplication
        assertThat(
            "Most recently played song should be first",
            history.first().music.title,
            `is`("Song A")
        )

        // Verify all song ids are present
        val historyIds = history.map { it.music.id }.toSet()
        assertThat(
            "History should contain all unique song ids",
            historyIds,
            `is`(setOf(1L, 2L, 3L))
        )
    }

    /**
     * Test that HistoryFragment displays the RecyclerView when there is history.
     */
    @Test
    fun historyFragment_displaysRecyclerViewWithHistory() {
        // Add some history first
        PlaybackHistory.log(songB, GoConstants.ARTIST_VIEW)
        Thread.sleep(10)
        PlaybackHistory.log(songA, GoConstants.ARTIST_VIEW)
        Thread.sleep(100)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_HISTORY)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use {
            // Wait for fragment to load and collect history flow
            Thread.sleep(1500)

            // Verify RecyclerView is displayed
            onView(withId(R.id.allMusicRv))
                .check(matches(isDisplayed()))
        }
    }

    /**
     * Test that clearing history works correctly.
     */
    @Test
    fun playbackHistory_clearWorks() {
        // Add some history
        PlaybackHistory.log(songA, GoConstants.ARTIST_VIEW)
        PlaybackHistory.log(songB, GoConstants.ARTIST_VIEW)
        Thread.sleep(100)

        assertThat(
            "History should not be empty after logging",
            PlaybackHistory.current().isNotEmpty(),
            `is`(true)
        )

        // Clear history
        PlaybackHistory.clear()
        Thread.sleep(100)

        assertThat(
            "History should be empty after clear",
            PlaybackHistory.current().isEmpty(),
            `is`(true)
        )
    }
}

/**
 * RecyclerView child position matcher utility.
 */
fun nthChildOf(
    parentMatcher: org.hamcrest.Matcher<android.view.View>,
    childPosition: Int
): org.hamcrest.Matcher<android.view.View> {
    return object : org.hamcrest.TypeSafeMatcher<android.view.View>() {
        override fun describeTo(description: org.hamcrest.Description) {
            description.appendText("Nth child of parent matcher: ")
            parentMatcher.describeTo(description)
            description.appendText(" at position $childPosition")
        }

        public override fun matchesSafely(view: android.view.View): Boolean {
            val parent = view.parent
            if (parent !is android.view.ViewGroup || !parentMatcher.matches(parent)) {
                return false
            }
            return parent.getChildAt(childPosition) == view
        }
    }
}
