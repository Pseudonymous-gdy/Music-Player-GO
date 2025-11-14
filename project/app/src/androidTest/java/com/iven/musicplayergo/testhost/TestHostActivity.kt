package com.iven.musicplayergo.testhost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.iven.musicplayergo.R
import com.iven.musicplayergo.models.Music
import com.iven.musicplayergo.ui.MediaControlInterface
import com.iven.musicplayergo.ui.UIControlInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 仅在 androidTest 使用的宿主 Activity：
 * - 可承载 AllMusicFragment / HistoryFragment
 * - 截获 MediaControlInterface 回调，便于断言
 */
class TestHostActivity : AppCompatActivity(), MediaControlInterface, UIControlInterface {

    companion object {
        const val EXTRA_FRAGMENT = "extra_fragment"
        const val FRAG_ALL_MUSIC = "all_music"
        const val FRAG_HISTORY = "history"
    }

    // 拦截随机播放回调数据
    val shuffledSongsRef = AtomicReference<List<Music>?>()
    val shuffledLatch = CountDownLatch(1)

    // 可选：用于给 AllMusicFragment 注入测试版 ViewModel（如项目 VM 需要）
    var customViewModelFactory: ViewModelProvider.Factory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = androidx.fragment.app.FragmentContainerView(this).apply {
            id = R.id.content
            layoutParams = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT
            )
        }
        val root = androidx.coordinatorlayout.widget.CoordinatorLayout(this).apply {
            id = R.id.root
            addView(container)
        }
        setContentView(root)

        // 如需覆盖默认 VM 工厂：供 AllMusicFragment 通过 requireActivity() 获取
        customViewModelFactory?.let { factory ->
            this@TestHostActivity.defaultViewModelProviderFactory = factory
        }

        if (savedInstanceState == null) {
            when (intent.getStringExtra(EXTRA_FRAGMENT)) {
                FRAG_ALL_MUSIC -> {
                    val fragment = com.iven.musicplayergo.fragments.AllMusicFragment.newInstance()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.content, fragment, "all_music")
                        .commitNow()
                }
                FRAG_HISTORY -> {
                    val fragment = com.iven.musicplayergo.fragments.HistoryFragment()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.content, fragment, "history")
                        .commitNow()
                }
                else -> {
                    // 默认加载 History，方便多数测试
                    val fragment = com.iven.musicplayergo.fragments.HistoryFragment()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.content, fragment, "history")
                        .commitNow()
                }
            }
        }
    }

    // MediaControlInterface

    override fun onSongSelected(song: Music?, songs: List<Music>?, songLaunchedBy: String) {
        // 测试里无需实现
    }

    override fun onSongsShuffled(songs: List<Music>?, songLaunchedBy: String) {
        // 与 MainActivity 行为一致：在此进行打散，避免 Fragment 传入原序
        val result = songs?.shuffled()
        shuffledSongsRef.set(result)
        shuffledLatch.countDown()
    }

    override fun onAddToQueue(song: Music?) {}
    override fun onAddAlbumToQueue(songs: List<Music>?, forcePlay: Pair<Boolean, Music?>) {}
    override fun onUpdatePlayingAlbumSongs(songs: List<Music>?) {}
    override fun onPlaybackSpeedToggled() {}
    override fun onHandleCoverOptionsUpdate() {}
    override fun onUpdatePositionFromNP(position: Int) {}

    // UIControlInterface（仅提供空实现即可满足 Fragment 需求）
    override fun onCloseActivity() {}
    override fun onOpenSleepTimerDialog() {}
    override fun onFavoritesUpdated(clear: Boolean) {}
    override fun onFavoriteAddedOrRemoved() {}
    override fun onOpenEqualizer() {}
    override fun onOpenQueueDialog() {}
    override fun onOpenNowPlayingDialog() {}
    override fun onOpenPlayingArtistAlbum() {}
}
