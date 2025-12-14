package com.example.musicplayergo

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.musicplayergo.fragments.HistoryFragment
import com.example.musicplayergo.testhost.TestHostActivity
import com.example.musicplayergo.utils.PlaybackHistory
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// RecyclerView.ViewHolder 的简便匹配（直接用 HistoryFragment 内部 ViewHolder 类型名避免依赖）
class HistoryFrequentlyPlayedEspressoTest {

    @Before
    fun setup() {
        // 确保历史为空
        PlaybackHistory.clear()

        // 预置三首歌，其中 A 重复多次，模拟“频繁播放”
        val songA = com.example.musicplayergo.models.Music(
            artist = "Artist X",
            year = 0,
            track = 0,
            title = "Song A",
            displayName = "SongA.mp3",
            duration = 120_000L,
            album = "Album X",
            albumId = 100L,
            relativePath = "/music",
            id = 1L,
            launchedBy = GoConstants.ARTIST_VIEW,
            startFrom = 0,
            dateAdded = 0
        )
        val songB = com.example.musicplayergo.models.Music(
            artist = "Artist Y",
            year = 0,
            track = 0,
            title = "Song B",
            displayName = "SongB.mp3",
            duration = 120_000L,
            album = "Album Y",
            albumId = 200L,
            relativePath = "/music",
            id = 2L,
            launchedBy = GoConstants.ARTIST_VIEW,
            startFrom = 0,
            dateAdded = 0
        )
        val songC = com.example.musicplayergo.models.Music(
            artist = "Artist Z",
            year = 0,
            track = 0,
            title = "Song C",
            displayName = "SongC.mp3",
            duration = 120_000L,
            album = "Album Z",
            albumId = 300L,
            relativePath = "/music",
            id = 3L,
            launchedBy = GoConstants.ARTIST_VIEW,
            startFrom = 0,
            dateAdded = 0
        )

        // 记录播放顺序：B -> A -> C -> A（A 被频繁播放，且应在历史中“去重后置顶”）
        PlaybackHistory.log(songB, GoConstants.ARTIST_VIEW)
        Thread.sleep(10)
        PlaybackHistory.log(songA, GoConstants.ARTIST_VIEW)
        Thread.sleep(10)
        PlaybackHistory.log(songC, GoConstants.ARTIST_VIEW)
        Thread.sleep(10)
        PlaybackHistory.log(songA, GoConstants.ARTIST_VIEW)
    }

    @After
    fun teardown() {
        PlaybackHistory.clear()
    }

    @Test
    fun history_displays_frequently_played_on_top_without_duplicates() {
        val intent = Intent(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
            TestHostActivity::class.java
        ).putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_HISTORY)

        ActivityScenario.launch<TestHostActivity>(intent).use {
            // 列表首项 title 应为 "Song A"
            onView(withId(R.id.allMusicRv))
                .check(matches(isDisplayed()))

            // 断言第 0 项的 title 文本
            onView(allOf(withId(R.id.history_title), isDescendantOfA(nthChildOf(withId(R.id.allMusicRv), 0))))
                .check(matches(withText("Song A")))
        }
    }
}

/**
 * RecyclerView 子项定位辅助（按位置匹配某子 view）
 */
fun nthChildOf(parentMatcher: org.hamcrest.Matcher<android.view.View>, childPosition: Int)
        : org.hamcrest.Matcher<android.view.View> {
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
