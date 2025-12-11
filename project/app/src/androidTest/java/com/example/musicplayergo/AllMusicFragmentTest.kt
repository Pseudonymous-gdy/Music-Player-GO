package com.example.musicplayergo

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import com.example.musicplayergo.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf


@RunWith(AndroidJUnit4::class)
class AllMusicFragmentTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private fun openAllMusicTab() {
        // 默认启动在第一个 tab（Artists），连滑两次到 Songs
        onView(withId(R.id.view_pager2)).perform(swipeLeft())   // -> Albums
        onView(withId(R.id.view_pager2)).perform(swipeLeft())   // -> Songs
    }
    @Test
    fun allMusicFragment_basicUI_isDisplayed() {
        openAllMusicTab()
        // 再等一下让 Fragment 完成切换
        Thread.sleep(1000)
        // 现在检查 “全部音乐” 列表是否在屏幕上
        onView(withId(R.id.all_music_rv))
            .check(matches(isDisplayed()))

        // 顺便检查“随机播放”按钮也在
        onView(
            allOf(
                withId(R.id.shuffle_fab),
                isDescendantOfA(withId(R.id.all_music_root))
            )
        ).check(matches(isDisplayed()))
    }
    @Test
    fun allMusicFragment_shuffleFab_isVisibleFlag() {
        SystemClock.sleep(1000)
        openAllMusicTab()
        onView(
            allOf(
                withId(R.id.shuffle_fab),
                isDescendantOfA(withId(R.id.all_music_root))
            )
        )
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
            .check(matches(isClickable()))
    }

    /**
     * 2. 测试“选择并上传”按钮是否显示并可点击
     */
    @Test
    fun allMusicFragment_actionUpload_isDisplayedAndClickable() {
        // 1. 切到“Songs”这个标签页
        openAllMusicTab()
        // 2. 只匹配 “Songs” 页面里 toolbar 上的那个上传按钮
        onView(
            allOf(
                withId(R.id.action_upload),
                isDescendantOfA(withId(R.id.all_music_root)),  // 限定在 Songs 页
                isDisplayed()
            )
        )
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
            .check(matches(isClickable()))
    }
}
