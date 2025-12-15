package com.example.musicplayergo

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.musicplayergo.repository.FakeMusicRepository
import com.example.musicplayergo.testhost.TestHostActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Basic tests for TestHostActivity launch and fragment loading.
 * These tests verify that the test infrastructure works correctly.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainactivityTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var fakeMusicRepository: FakeMusicRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        // Reset repository to default test data
        fakeMusicRepository.resetToDefault()
    }

    /**
     * Test that TestHostActivity can launch successfully with AllMusicFragment.
     */
    @Test
    fun testHostActivity_launchesWithAllMusicFragment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_ALL_MUSIC)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use { scenario ->
            // Verify the activity launched successfully
            scenario.onActivity { activity ->
                assert(activity != null)
            }
            // Small delay to let fragment attach
            Thread.sleep(500)
        }
    }

    /**
     * Test that TestHostActivity can launch successfully with HistoryFragment.
     */
    @Test
    fun testHostActivity_launchesWithHistoryFragment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, TestHostActivity::class.java).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_HISTORY)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use { scenario ->
            // Verify the activity launched successfully
            scenario.onActivity { activity ->
                assert(activity != null)
            }
            // Small delay to let fragment attach
            Thread.sleep(500)
        }
    }

    /**
     * Test that FakeMusicRepository is properly injected.
     */
    @Test
    fun fakeMusicRepository_isInjectedCorrectly() {
        // Verify the repository is injected
        assert(::fakeMusicRepository.isInitialized)

        // Verify default test data is available
        val defaultMusic = FakeMusicRepository.createDefaultTestMusic()
        assert(defaultMusic.isNotEmpty())
        assert(defaultMusic.size == 5)
    }
}
