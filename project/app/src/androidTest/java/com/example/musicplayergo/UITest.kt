package com.example.musicplayergo

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.BoundedMatcher
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayergo.models.Music
import com.example.musicplayergo.repository.FakeMusicRepository
import com.example.musicplayergo.testhost.TestHostActivity
import com.example.musicplayergo.utils.PlaybackHistory
import com.example.musicplayergo.utils.Theming
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * UI Tests using TestHostActivity with Hilt dependency injection.
 * These tests verify UI components work correctly with test data.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UITest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var fakeMusicRepository: FakeMusicRepository

    private lateinit var testSongs: List<Music>

    @Before
    fun setUp() {
        hiltRule.inject()

        // Clear history for clean test state
        PlaybackHistory.clear()

        // Create test music data
        testSongs = listOf(
            Music(
                artist = "Test Artist 1",
                year = 2023,
                track = 1,
                title = "Test Song 1",
                displayName = "test_song_1.mp3",
                duration = 180_000L,
                album = "Test Album 1",
                albumId = 101L,
                relativePath = "/test/music",
                id = 1L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000000
            ),
            Music(
                artist = "Test Artist 2",
                year = 2023,
                track = 2,
                title = "Test Song 2",
                displayName = "test_song_2.mp3",
                duration = 200_000L,
                album = "Test Album 2",
                albumId = 102L,
                relativePath = "/test/music",
                id = 2L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000001
            ),
            Music(
                artist = "Test Artist 3",
                year = 2022,
                track = 3,
                title = "Test Song 3",
                displayName = "test_song_3.mp3",
                duration = 220_000L,
                album = "Test Album 3",
                albumId = 103L,
                relativePath = "/test/music",
                id = 3L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000002
            )
        )

        // Set up test data in repository
        fakeMusicRepository.setMusicData(testSongs)
    }

    @After
    fun tearDown() {
        PlaybackHistory.clear()
    }

    /**
     * Test 1: AllMusicFragment shuffle FAB is displayed and clickable.
     */
    @Test
    fun allMusicFragment_shuffleFab_isDisplayedAndClickable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_ALL_MUSIC)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use {
            // Wait for fragment to load
            Thread.sleep(1000)

            // Verify shuffle FAB is displayed and clickable
            onView(withId(R.id.shuffle_fab))
                .check(matches(isDisplayed()))
                .check(matches(isClickable()))
        }
    }

    /**
     * Test 2: Clicking shuffle FAB triggers the shuffle callback.
     */
    @Test
    fun allMusicFragment_shuffleFab_triggersCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_ALL_MUSIC)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use { scenario ->
            // Wait for fragment to load
            Thread.sleep(1000)

            // Click shuffle FAB
            onView(withId(R.id.shuffle_fab))
                .perform(click())

            // Wait for callback
            Thread.sleep(500)

            // Verify callback was triggered
            scenario.onActivity { activity ->
                val triggered = activity.shuffledLatch.count == 0L ||
                    activity.shuffledSongsRef.get() != null
                assertThat(
                    "Shuffle callback should be triggered",
                    triggered,
                    `is`(true)
                )
            }
        }
    }

    /**
     * Test 3: HistoryFragment displays RecyclerView for history items.
     */
    @Test
    fun historyFragment_displaysRecyclerView() {
        // Pre-populate some history
        testSongs.forEach { song ->
            PlaybackHistory.log(song, GoConstants.ARTIST_VIEW)
            Thread.sleep(10)
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_HISTORY)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use {
            // Wait for fragment to load and collect flow
            Thread.sleep(1500)

            // Verify RecyclerView is displayed
            onView(withId(R.id.allMusicRv))
                .check(matches(isDisplayed()))
        }
    }

    /**
     * Test 4: History shuffle button triggers callback when history exists.
     */
    @Test
    fun historyFragment_shuffleButton_worksWithHistory() {
        // Pre-populate some history
        testSongs.forEach { song ->
            PlaybackHistory.log(song, GoConstants.ARTIST_VIEW)
            Thread.sleep(10)
        }
        Thread.sleep(100)

        // Verify history is populated
        assertThat(
            "History should have items",
            PlaybackHistory.current().size,
            greaterThan(0)
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_HISTORY)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use { scenario ->
            // Wait for fragment to load
            Thread.sleep(1500)

            // Verify shuffle FAB in history fragment
            onView(withId(R.id.shuffle_fab))
                .check(matches(isDisplayed()))
                .check(matches(isClickable()))
                .perform(click())

            // Wait for callback
            Thread.sleep(500)

            // Verify shuffle was triggered
            scenario.onActivity { activity ->
                val triggered = activity.shuffledLatch.count == 0L
                assertThat(
                    "History shuffle should trigger callback",
                    triggered,
                    `is`(true)
                )
            }
        }
    }

    /**
     * Test 5: PlaybackHistory maintains correct order (most recent first).
     */
    @Test
    fun playbackHistory_maintainsCorrectOrder() {
        // Clear and add songs in specific order
        PlaybackHistory.clear()
        Thread.sleep(50)

        // Play songs in order: 1, 2, 3
        PlaybackHistory.log(testSongs[0], GoConstants.ARTIST_VIEW)
        Thread.sleep(20)
        PlaybackHistory.log(testSongs[1], GoConstants.ARTIST_VIEW)
        Thread.sleep(20)
        PlaybackHistory.log(testSongs[2], GoConstants.ARTIST_VIEW)
        Thread.sleep(100)

        val history = PlaybackHistory.current()

        // Most recently played (Song 3) should be first
        assertThat(
            "Most recent song should be first",
            history.first().music.id,
            `is`(3L)
        )

        // Verify all songs are in history
        assertThat(
            "History should contain all songs",
            history.size,
            `is`(3)
        )
    }

    /**
     * Test 6: Hilt injection works correctly in test environment.
     */
    @Test
    fun hiltInjection_worksCorrectly() {
        // Verify repository is injected
        assertThat(
            "FakeMusicRepository should be injected",
            ::fakeMusicRepository.isInitialized,
            `is`(true)
        )

        // Verify we can use the repository
        val musicData = FakeMusicRepository.createDefaultTestMusic()
        assertThat(
            "Default test data should be available",
            musicData.isNotEmpty(),
            `is`(true)
        )
    }

    /**
     * Test 7: Repository data is accessible from Activity.
     */
    @Test
    fun testHostActivity_hasAccessToRepository() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_ALL_MUSIC)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertThat(
                    "Activity should have MusicRepository injected",
                    activity.musicRepository,
                    notNullValue()
                )
            }
        }
    }
}
