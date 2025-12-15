package com.example.musicplayergo.testhost

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.example.musicplayergo.models.Music
import com.example.musicplayergo.ui.MediaControlInterface
import com.example.musicplayergo.ui.UIControlInterface
import java.util.concurrent.CountDownLatch
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

    private val containerId = View.generateViewId()
    private val rootId = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = androidx.fragment.app.FragmentContainerView(this).apply {
            id = containerId
            layoutParams = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT
            )
        }
        val root = CoordinatorLayout(this).apply {
            id = rootId
            addView(container)
        }
        setContentView(root)

        if (savedInstanceState == null) {
            when (intent.getStringExtra(EXTRA_FRAGMENT)) {
                FRAG_ALL_MUSIC -> {
                    val fragment = com.example.musicplayergo.fragments.AllMusicFragment.newInstance()
                    supportFragmentManager.beginTransaction()
                        .replace(containerId, fragment, "all_music")
                        .commitNow()
                }
                FRAG_HISTORY -> {
                    val fragment = com.example.musicplayergo.fragments.HistoryFragment()
                    supportFragmentManager.beginTransaction()
                        .replace(containerId, fragment, "history")
                        .commitNow()
                }
                else -> {
                    // 默认加载 History，方便多数测试
                    val fragment = com.example.musicplayergo.fragments.HistoryFragment()
                    supportFragmentManager.beginTransaction()
                        .replace(containerId, fragment, "history")
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
        // 与 MainActivity 行为一致：在此进行打散,避免 Fragment 传入原序
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
    override fun onOpenPlayingArtistAlbum() {}
    override fun onAppearanceChanged(isThemeChanged: Boolean) {}
    override fun onOpenNewDetailsFragment() {}
    override fun onArtistOrFolderSelected(artistOrFolder: String, launchedBy: String) {}
    override fun onAddToFilter(stringsToFilter: List<String>?) {}
    override fun onFiltersCleared() {}
    override fun onDenyPermission() {}
    override fun onEnableEqualizer() {}
    override fun onUpdateSortings() {}
}
