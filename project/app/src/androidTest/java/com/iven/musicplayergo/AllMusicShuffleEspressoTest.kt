package com.iven.musicplayergo

import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        // TODO: 按你的 Music 数据类实际构造字段进行填充（至少 id/artist/album/title/albumId）
        val songs = listOf(
            Music(1L, "Song 1", "Artist A", "Album A", 11L, 0, GoConstants.ARTIST_VIEW),
            Music(2L, "Song 2", "Artist B", "Album B", 22L, 0, GoConstants.ARTIST_VIEW),
            Music(3L, "Song 3", "Artist C", "Album C", 33L, 0, GoConstants.ARTIST_VIEW),
            Music(4L, "Song 4", "Artist D", "Album D", 44L, 0, GoConstants.ARTIST_VIEW),
        )

        // 使用自定义 ViewModel 工厂向 AllMusicFragment 注入 deviceMusic/deviceMusicFiltered
        // 注意：这里假设项目的 MusicViewModel 类型为 com.iven.musicplayergo.viewmodels.MusicViewModel，
        // 且包含可观察的 LiveData<List<Music>> deviceMusic 以及列表 deviceMusicFiltered。
        // 如有出入，请根据你的实际 VM API 调整。
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.name == "com.iven.musicplayergo.viewmodels.MusicViewModel") {
                    // 使用动态代理或测试子类会更优；为示例简化，这里假设该 VM 有无参构造并可赋值字段
                    val vm = modelClass.getDeclaredConstructor().newInstance()
                    // 通过反射设置字段/属性（根据你的 VM 实际字段名调整）
                    val deviceMusicField = modelClass.getDeclaredField("deviceMusic")
                    deviceMusicField.isAccessible = true
                    deviceMusicField.set(vm, MutableLiveData<List<Music>>().apply { postValue(songs) })

                    val deviceMusicFilteredField = modelClass.getDeclaredField("deviceMusicFiltered")
                    deviceMusicFilteredField.isAccessible = true
                    deviceMusicFilteredField.set(vm, songs)

                    @Suppress("UNCHECKED_CAST")
                    return vm as T
                }
                return modelClass.getDeclaredConstructor().newInstance()
            }
        }

        val intent = Intent(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
            TestHostActivity::class.java
        ).apply {
            putExtra(TestHostActivity.EXTRA_FRAGMENT, TestHostActivity.FRAG_ALL_MUSIC)
        }

        ActivityScenario.launch<TestHostActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.customViewModelFactory = factory
            }

            // 等待 AllMusicFragment 完成渲染并显示 shuffle FAB
            onView(withId(R.id.shuffleFab))
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
