package com.example.musicplayergo

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayergo.models.Music
import com.example.musicplayergo.repository.FakeMusicRepository
import com.example.musicplayergo.testhost.TestHostActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Espresso tests for AllMusicFragment shuffle functionality.
 * Uses Hilt dependency injection with FakeMusicRepository.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AllMusicShuffleEspressoTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var fakeMusicRepository: FakeMusicRepository

    private lateinit var testSongs: List<Music>

    @Before
    fun setUp() {
        hiltRule.inject()

        // Create test data
        testSongs = listOf(
            Music(
                artist = "Artist A",
                year = 2023,
                track = 1,
                title = "Song 1",
                displayName = "Song 1.mp3",
                duration = 120_000L,
                album = "Album A",
                albumId = 11L,
                relativePath = "/music",
                id = 1L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000000
            ),
            Music(
                artist = "Artist B",
                year = 2023,
                track = 2,
                title = "Song 2",
                displayName = "Song 2.mp3",
                duration = 120_000L,
                album = "Album B",
                albumId = 22L,
                relativePath = "/music",
                id = 2L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000001
            ),
            Music(
                artist = "Artist C",
                year = 2022,
                track = 3,
                title = "Song 3",
                displayName = "Song 3.mp3",
                duration = 120_000L,
                album = "Album C",
                albumId = 33L,
                relativePath = "/music",
                id = 3L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000002
            ),
            Music(
                artist = "Artist D",
                year = 2022,
                track = 4,
                title = "Song 4",
                displayName = "Song 4.mp3",
                duration = 120_000L,
                album = "Album D",
                albumId = 44L,
                relativePath = "/music",
                id = 4L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 1700000003
            )
        )

        // Set test data in repository
        fakeMusicRepository.setMusicData(testSongs)
    }

    /**
     * Test that shuffle FAB triggers the shuffle callback.
     * Verifies that:
     * 1. TestHostActivity launches with AllMusicFragment
     * 2. Shuffle FAB is displayed and clickable
     * 3. Clicking FAB triggers onSongsShuffled callback
     * 4. Shuffled songs have the same elements but potentially different order
     */
    @Test
    fun allSongs_shuffleFab_triggersShuffleCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_ALL_MUSIC)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use { scenario ->
            // Wait for fragment to load
            Thread.sleep(1000)

            // Verify shuffle FAB is displayed
            onView(withId(R.id.shuffle_fab))
                .check(matches(isDisplayed()))
                .check(matches(isClickable()))

            // Click the shuffle FAB
            onView(withId(R.id.shuffle_fab))
                .perform(click())

            // Verify shuffle callback was triggered
            scenario.onActivity { activity ->
                val shuffleTriggered = activity.shuffledLatch.await(3, TimeUnit.SECONDS)
                assertThat("onSongsShuffled should be called", shuffleTriggered, `is`(true))

                val shuffledSongs = activity.shuffledSongsRef.get()
                assertThat("Shuffled songs should not be null", shuffledSongs, notNullValue())

                // Verify shuffled songs contain the same elements
                if (shuffledSongs != null) {
                    assertThat(
                        "Shuffled songs should have same size as original",
                        shuffledSongs.size,
                        `is`(testSongs.size)
                    )

                    // Verify all original songs are present (by id)
                    val originalIds = testSongs.map { it.id }.toSet()
                    val shuffledIds = shuffledSongs.map { it.id }.toSet()
                    assertThat(
                        "Shuffled songs should contain same song ids",
                        shuffledIds,
                        `is`(originalIds)
                    )
                }
            }
        }
    }

    /**
     * Test that TestHostActivity properly initializes with injected repository.
     */
    @Test
    fun allMusicFragment_repositoryIsInjected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_ALL_MUSIC)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertThat(
                    "MusicRepository should be injected",
                    activity.musicRepository,
                    notNullValue()
                )
            }
        }
    }
}
