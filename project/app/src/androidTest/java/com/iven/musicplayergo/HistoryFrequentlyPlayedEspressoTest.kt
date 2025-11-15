package com.iven.musicplayergo

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iven.musicplayergo.fragments.HistoryFragment
import com.iven.musicplayergo.testhost.TestHostActivity
import com.iven.musicplayergo.utils.PlaybackHistory
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
        // TODO: 按你的 Music 数据类实际构造字段进行填充（至少 id/artist/album/title/albumId）
        val songA = com.iven.musicplayergo.models.Music(
            id = 1L,
            title = "Song A",
            artist = "Artist X",
            album = "Album X",
            albumId = 100L,
            startFrom = 0,
            launchedBy = GoConstants.ARTIST_VIEW
        )
        val songB = com.iven.musicplayergo.models.Music(
            id = 2L,
            title = "Song B",
            artist = "Artist Y",
            album = "Album Y",
            albumId = 200L,
            startFrom = 0,
            launchedBy = GoConstants.ARTIST_VIEW
        )
        val songC = com.iven.musicplayergo.models.Music(
            id = 3L,
            title = "Song C",
            artist = "Artist Z",
            album = "Album Z",
            albumId = 300L,
            startFrom = 0,
            launchedBy = GoConstants.ARTIST_VIEW
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
            onView(allOf(withId(R.id.historyTitle), isDescendantOfA(nthChildOf(withId(R.id.allMusicRv), 0))))
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
