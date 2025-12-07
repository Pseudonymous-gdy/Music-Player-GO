package com.iven.musicplayergo

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iven.musicplayergo.MusicViewModel
import com.iven.musicplayergo.models.Music
import com.iven.musicplayergo.testhost.TestHostActivity
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AllMusicShuffleEspressoTest {

    @Test
    fun all_songs_shuffle_fab_triggers_random_play() {
        // 构造一组测试音乐列表
        val songs = listOf(
            Music(
                artist = "Artist A",
                year = 0,
                track = 0,
                title = "Song 1",
                displayName = "Song 1.mp3",
                duration = 120_000L,
                album = "Album A",
                albumId = 11L,
                relativePath = "/music",
                id = 1L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 0
            ),
            Music(
                artist = "Artist B",
                year = 0,
                track = 0,
                title = "Song 2",
                displayName = "Song 2.mp3",
                duration = 120_000L,
                album = "Album B",
                albumId = 22L,
                relativePath = "/music",
                id = 2L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 0
            ),
            Music(
                artist = "Artist C",
                year = 0,
                track = 0,
                title = "Song 3",
                displayName = "Song 3.mp3",
                duration = 120_000L,
                album = "Album C",
                albumId = 33L,
                relativePath = "/music",
                id = 3L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 0
            ),
            Music(
                artist = "Artist D",
                year = 0,
                track = 0,
                title = "Song 4",
                displayName = "Song 4.mp3",
                duration = 120_000L,
                album = "Album D",
                albumId = 44L,
                relativePath = "/music",
                id = 4L,
                launchedBy = GoConstants.ARTIST_VIEW,
                startFrom = 0,
                dateAdded = 0
            ),
        )

        val intent = Intent(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
            TestHostActivity::class.java
        ).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_ALL_MUSIC)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(
                    activity,
                    androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
                        ApplicationProvider.getApplicationContext()
                    )
                )[MusicViewModel::class.java]
                vm.deviceMusic.value = songs.toMutableList()
                vm.deviceMusicFiltered = songs.toMutableList()
            }

            // 等待 AllMusicFragment 完成渲染并显示 shuffle FAB
            onView(withId(R.id.shuffle_fab))
                .check(matches(isDisplayed()))
                .perform(click())

            // 拦截到 onSongsShuffled 回调
            scenario.onActivity { activity ->
                val ok = activity.shuffledLatch.await(3, TimeUnit.SECONDS)
                assertThat("onSongsShuffled should be called", ok, `is`(true))
                val shuffled = activity.shuffledSongsRef.get()
                assertThat(shuffled, notNullValue())
                assertThat(shuffled!!.size, `is`(songs.size))
                // 验证打散：顺序不应与原序完全一致（极小概率相同，可根据需要放宽断言）
                assertThat(shuffled, not(equalTo(songs)))
                // 验证元素集合一致
                assertThat(shuffled.toSet(), `is`(songs.toSet()))
            }
        }
    }
}
