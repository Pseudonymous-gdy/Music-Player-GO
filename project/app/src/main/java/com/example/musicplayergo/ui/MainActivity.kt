package com.example.musicplayergo.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.widget.Toast
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.animation.doOnEnd
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.example.musicplayergo.*
import com.example.musicplayergo.databinding.MainActivityBinding
import com.example.musicplayergo.databinding.PlayerControlsPanelBinding
import com.example.musicplayergo.dialogs.Dialogs
import com.example.musicplayergo.dialogs.NowPlaying
import com.example.musicplayergo.dialogs.RecyclerSheet
import com.example.musicplayergo.equalizer.EqualizerActivity
import com.example.musicplayergo.equalizer.EqualizerFragment
import com.example.musicplayergo.extensions.*
import com.example.musicplayergo.fragments.AllMusicFragment
import com.example.musicplayergo.fragments.DetailsFragment
import com.example.musicplayergo.fragments.ErrorFragment
import com.example.musicplayergo.fragments.MusicContainersFragment
import com.example.musicplayergo.fragments.HistoryFragment
import com.example.musicplayergo.fragments.RecommendationsFragment
import com.example.musicplayergo.models.Music
import com.example.musicplayergo.player.MediaPlayerHolder
import com.example.musicplayergo.player.MediaPlayerInterface
import com.example.musicplayergo.player.PlayerService
import com.example.musicplayergo.preferences.SettingsFragment
import com.example.musicplayergo.utils.*


class MainActivity : BaseActivity(), UIControlInterface, MediaControlInterface {

    // View binding classes
    private lateinit var mMainActivityBinding: MainActivityBinding
    private lateinit var mPlayerControlsPanelBinding: PlayerControlsPanelBinding

    // View model
    private val mMusicViewModel: MusicViewModel by viewModels()

    // Preferences
    private val mGoPreferences get() = GoPreferences.getPrefsInstance()

    // Fragments
    private var mArtistsFragment: MusicContainersFragment? = null
    private var mAllMusicFragment: AllMusicFragment? = null
    private var mRecommendationsFragment: RecommendationsFragment? = null
    private var mFoldersFragment: MusicContainersFragment? = null
    private var mAlbumsFragment: MusicContainersFragment? = null
    private var mSettingsFragment: SettingsFragment? = null
    private var mDetailsFragment: DetailsFragment? = null
    private var mHistoryFragment: HistoryFragment? = null

    // Booleans
    private val sDetailsFragmentExpanded get() = supportFragmentManager.isFragment(GoConstants.DETAILS_FRAGMENT_TAG)
    private var sAllowCommit = true
    private val sLaunchedByTile get() = intent != null && intent.hasExtra(GoConstants.LAUNCHED_BY_TILE)

    private var sCloseDetailsFragment = true

    private var sRevealAnimationRunning = false

    private var mTabToRestore = -1

    private var currentTabStart = 0L
    private var currentTabName: String = GoConstants.SONGS_TAB

    // Queue dialog
    private var mQueueDialog: RecyclerSheet? = null

    // Now playing
    private var mNpDialog: NowPlaying? = null

    // Favorites dialog
    private var mFavoritesDialog: RecyclerSheet? = null

    // Sleep timer dialog
    private var mSleepTimerDialog: RecyclerSheet? = null

    // Music player things
    private val mMediaPlayerHolder get() = MediaPlayerHolder.getInstance()

    // Our PlayerService
    private lateinit var mPlayerService: PlayerService
    private var sBound = false
    private lateinit var mBindingIntent: Intent

    private fun checkIsPlayer(showError: Boolean): Boolean {
        if (!mMediaPlayerHolder.isMediaPlayer && !mMediaPlayerHolder.isSongFromPrefs && showError) {
            Toast.makeText(this, R.string.error_bad_id, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    // Defines callbacks for service binding, passed to bindService()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            // get bound service and instantiate MediaPlayerHolder
            val binder = service as PlayerService.LocalBinder
            mPlayerService = binder.getService()
            sBound = true

            mMediaPlayerHolder.mediaPlayerInterface = mMediaPlayerInterface

            // load music and setup UI
            mMusicViewModel.deviceMusic.observe(this@MainActivity) { returnedMusic ->
                finishSetup(returnedMusic)
            }
            mMusicViewModel.getDeviceMusic()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            sBound = false
        }
    }

    private val equalizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == EqualizerFragment.EQUALIZER_CANCELED_RESULT) {
            mSettingsFragment?.getPreferencesFragment()?.enableEqualizerOption()
        }
    }

    override fun onEnableEqualizer() {
        mSettingsFragment?.getPreferencesFragment()?.enableEqualizerOption()
    }

    override fun onUpdateSortings() {
        mSettingsFragment?.getPreferencesFragment()?.updateResetSortingsOption()
    }

    private fun doBindService() {
        mBindingIntent = Intent(this, PlayerService::class.java).also {
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private val onBackPressedCallback: OnBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            handleBackPressed()
        }
    }

    private fun handleBackPressed() {
        when {
            sDetailsFragmentExpanded -> closeDetailsFragment(isAnimation = mGoPreferences.isAnimations)
            else -> if (mMainActivityBinding.viewPager2.currentItem != 0) {
                mMainActivityBinding.viewPager2.currentItem = 0
            } else {
                onCloseActivity()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sAllowCommit = false
        outState.putInt(GoConstants.RESTORE_FRAGMENT, mMainActivityBinding.viewPager2.currentItem)
    }

    override fun onResumeFragments() {
        sAllowCommit = true
        super.onResumeFragments()
    }

    override fun onDestroy() {
        flushTabDuration()
        super.onDestroy()
        equalizerLauncher.unregister()
        mMusicViewModel.cancel()
        if (!mMediaPlayerHolder.isPlaying && ::mPlayerService.isInitialized && mPlayerService.isRunning && !mMediaPlayerHolder.isSongFromPrefs) {
            ServiceCompat.stopForeground(mPlayerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopService(mBindingIntent)
            if (sBound) unbindService(connection)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && intent.hasExtra(GoConstants.LAUNCHED_BY_TILE)) {
            if (!mMediaPlayerHolder.isPlaying) mMediaPlayerHolder.resumeOrPause()
            return
        }
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (currentTabStart == 0L) {
            trackTabChange(mMainActivityBinding.viewPager2.currentItem)
        }
        if (mMediaPlayerHolder.isMediaPlayer && !mMediaPlayerHolder.isSongFromPrefs) {
            mMediaPlayerHolder.onRestartSeekBarCallback()
        }
    }

    // Pause SeekBar callback
    override fun onPause() {
        super.onPause()
        flushTabDuration()

        if (mMediaPlayerHolder.isMediaPlayer && !mMediaPlayerHolder.isSongFromPrefs) {
            if (!mMediaPlayerHolder.isCurrentSongFM) {
                mMediaPlayerInterface.onBackupSong()
            }
            with(mMediaPlayerHolder) {
                onPauseSeekBarCallback()
                if (!isPlaying) giveUpAudioFocus()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        mNpDialog?.dismissAllowingStateLoss()
        mQueueDialog?.dismissAllowingStateLoss()
        mFavoritesDialog?.dismissAllowingStateLoss()
        mSleepTimerDialog?.dismissAllowingStateLoss()
    }

    // Manage request permission result
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        if (Versioning.isMarshmallow()) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            when (requestCode) {
                GoConstants.PERMISSION_REQUEST_READ_EXTERNAL_STORAGE -> {
                    // If request is cancelled, the result arrays are empty.
                    if ((grantResults.isNotEmpty() && grantResults.first() != PackageManager.PERMISSION_GRANTED)) {
                        // Permission denied, boo! Error!
                        notifyError(GoConstants.TAG_NO_PERMISSION)
                        return
                    }
                    // Permission was granted, yay! Do bind service
                    doBindService()
                }
            }
        }
    }

    override fun onDenyPermission() {
        notifyError(GoConstants.TAG_NO_PERMISSION)
    }

    override fun onStart() {
        super.onStart()
        if (Permissions.hasToAskForReadStoragePermission(this)) {
            Permissions.manageAskForReadStoragePermission(this)
            return
        }
        doBindService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = Theming.getOrientation()

        var newTheme = Theming.resolveTheme(this)
        if (sLaunchedByTile) newTheme = R.style.BaseTheme_Transparent
        setTheme(newTheme)

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        mMainActivityBinding = MainActivityBinding.inflate(layoutInflater)
        mPlayerControlsPanelBinding = PlayerControlsPanelBinding.bind(mMainActivityBinding.root)

        setContentView(mMainActivityBinding.root)

        if (sLaunchedByTile) {
            mMainActivityBinding.loadingProgressBar.handleViewVisibility(false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            mMainActivityBinding.root.applyEdgeToEdge()
        }

        sAllowCommit = true

        savedInstanceState?.run {
            mTabToRestore = getInt(GoConstants.RESTORE_FRAGMENT, -1)
        }

        if (intent.hasExtra(GoConstants.RESTORE_FRAGMENT) && mTabToRestore == -1) {
            mTabToRestore = intent.getIntExtra(GoConstants.RESTORE_FRAGMENT, -1)
        }

        // 只记录屏幕浏览事件，初始化已在 GoApp 中完成
        AnalyticsLogger.logScreenView("MainActivity")
    }

    private fun notifyError(errorType: String) {

        mMainActivityBinding.mainView.animate().run {
            withStartAction {
                mPlayerControlsPanelBinding.playerView.handleViewVisibility(show = false)
                mMainActivityBinding.loadingProgressBar.handleViewVisibility(show = false)
                mMainActivityBinding.viewPager2.handleViewVisibility(show = false)
            }
            duration = 250
            alpha(1.0F)
            withEndAction {
                if (sAllowCommit) {
                    supportFragmentManager.addFragment(
                        ErrorFragment.newInstance(errorType),
                        GoConstants.ERROR_FRAGMENT_TAG
                    )
                }
            }
        }
    }

    private fun finishSetup(music: MutableList<Music>?) {
        if (!music.isNullOrEmpty()) {
            mMainActivityBinding.loadingProgressBar.handleViewVisibility(show = false)

            // Be sure that prefs are initialized
            GoPreferences.initPrefs(this)
            // Ensure HISTORY_TAB is present in activeTabs (migration for existing users)
            run {
                val tabs = mGoPreferences.activeTabs.toMutableList()
                if (!tabs.contains(GoConstants.HISTORY_TAB)) {
                    tabs.add(GoConstants.HISTORY_TAB)
                    mGoPreferences.activeTabs = tabs
                }
                if (!tabs.contains(GoConstants.RECOMMENDATIONS_TAB)) {
                    val insertIndex = tabs.indexOf(GoConstants.SONGS_TAB).takeIf { it != -1 }
                        ?.plus(1) ?: tabs.size
                    tabs.add(insertIndex, GoConstants.RECOMMENDATIONS_TAB)
                    mGoPreferences.activeTabs = tabs
                }
                val tabsDef = mGoPreferences.activeTabsDef.toMutableList()
                if (!tabsDef.contains(GoConstants.RECOMMENDATIONS_TAB)) {
                    val insertIndex = tabsDef.indexOf(GoConstants.SONGS_TAB).takeIf { it != -1 }
                        ?.plus(1) ?: tabsDef.size
                    tabsDef.add(insertIndex, GoConstants.RECOMMENDATIONS_TAB)
                    mGoPreferences.activeTabsDef = tabsDef
                }
            }

            if (!sLaunchedByTile) {

                initMediaButtons()
                initViewPager()

                synchronized(handleRestore()) {
                    mMainActivityBinding.mainView.animate().run {
                        duration = 750
                        alpha(1.0F)
                    }
                }
            } else {
                handleRestore()
            }
            return
        }
        notifyError(GoConstants.TAG_NO_MUSIC)
    }

    // Handle restoring: handle intent data, if any, or restore playback
    private fun handleRestore() {
        synchronized(restorePlayerStatus()) {
            if (sLaunchedByTile) {
                finishAndRemoveTask()
                mMediaPlayerHolder.resumeMediaPlayer()
            }
        }
        handleIntent(intent)
    }

    private fun trackTabChange(position: Int) {
        val now = System.currentTimeMillis()
        if (currentTabStart > 0) {
            AnalyticsLogger.logTabDuration(currentTabName, now - currentTabStart)
        }
        currentTabName = mGoPreferences.activeTabs.toList().getOrElse(position) { "unknown" }
        currentTabStart = now
        AnalyticsLogger.logTabView(currentTabName, position)
    }

    private fun flushTabDuration() {
        if (currentTabStart > 0) {
            val now = System.currentTimeMillis()
            AnalyticsLogger.logTabDuration(currentTabName, now - currentTabStart)
            currentTabStart = 0L
        }
    }

    private fun initViewPager() {
        val pagerAdapter = ScreenSlidePagerAdapter(this)
        with(mMainActivityBinding.viewPager2) {
            offscreenPageLimit = mGoPreferences.activeTabs.toList().size.minus(1)
            adapter = pagerAdapter
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    mArtistsFragment?.stopActionMode()
                    mFoldersFragment?.stopActionMode()
                    trackTabChange(position)
                }
            })
            reduceDragSensitivity()
        }

        initTabLayout()
    }

    private fun initTabLayout() {

        fun closeDetails() {
            if (sAllowCommit && sDetailsFragmentExpanded) {
                supportFragmentManager.goBackFromFragmentNow(mDetailsFragment)
            }
        }

        val accent = Theming.resolveThemeColor(resources)
        val alphaAccentColor = ColorUtils.setAlphaComponent(accent, 200)

        with(mPlayerControlsPanelBinding.tabLayout) {

            tabIconTint = ColorStateList.valueOf(alphaAccentColor)

            TabLayoutMediator(this, mMainActivityBinding.viewPager2) { tab, position ->
                val selectedTab = mGoPreferences.activeTabs.toList()[position]
                tab.setIcon(Theming.getTabIcon(selectedTab))
                tab.setContentDescription(Theming.getTabAccessibilityText(selectedTab))
            }.attach()

            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    tab.icon?.setTint(accent)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {
                    closeDetails()
                    tab.icon?.setTint(alphaAccentColor)
                }

                override fun onTabReselected(tab: TabLayout.Tab) {
                    closeDetails()
                }
            })
        }

        mMainActivityBinding.viewPager2.setCurrentItem(
            when {
                mTabToRestore != -1 -> mTabToRestore
                else -> 0
            },
            false
        )

        mPlayerControlsPanelBinding.tabLayout.getTabAt(mMainActivityBinding.viewPager2.currentItem)?.run {
            select()
            icon?.setTint(Theming.resolveThemeColor(resources))
        }
        trackTabChange(mMainActivityBinding.viewPager2.currentItem)
    }

    private fun initFragmentAt(position: Int): Fragment {
        when (mGoPreferences.activeTabs.toList()[position]) {
            GoConstants.ARTISTS_TAB -> mArtistsFragment = MusicContainersFragment.newInstance(GoConstants.ARTIST_VIEW)
            GoConstants.ALBUM_TAB -> mAlbumsFragment = MusicContainersFragment.newInstance(GoConstants.ALBUM_VIEW)
            GoConstants.SONGS_TAB -> mAllMusicFragment = AllMusicFragment.newInstance()
            GoConstants.RECOMMENDATIONS_TAB -> mRecommendationsFragment = RecommendationsFragment.newInstance()
            GoConstants.FOLDERS_TAB -> mFoldersFragment = MusicContainersFragment.newInstance(GoConstants.FOLDER_VIEW)
            GoConstants.HISTORY_TAB -> mHistoryFragment = HistoryFragment.newInstance()
            else -> mSettingsFragment = SettingsFragment.newInstance()
        }
        return handleOnNavigationItemSelected(position)
    }

    private fun handleOnNavigationItemSelected(index: Int) = when (mGoPreferences.activeTabs.toList()[index]) {
        GoConstants.ARTISTS_TAB -> mArtistsFragment ?: initFragmentAt(index)
        GoConstants.ALBUM_TAB -> mAlbumsFragment ?: initFragmentAt(index)
        GoConstants.SONGS_TAB -> mAllMusicFragment ?: initFragmentAt(index)
        GoConstants.RECOMMENDATIONS_TAB -> mRecommendationsFragment ?: initFragmentAt(index)
        GoConstants.FOLDERS_TAB -> mFoldersFragment ?: initFragmentAt(index)
        GoConstants.HISTORY_TAB -> mHistoryFragment ?: initFragmentAt(index)
        else -> mSettingsFragment ?: initFragmentAt(index)
    }

    private fun openNowPlayingFragment() {
        if (checkIsPlayer(showError = true) && mMediaPlayerHolder.isCurrentSong && mNpDialog == null) {
            mNpDialog = NowPlaying.newInstance().apply {
                show(supportFragmentManager, NowPlaying.TAG_MODAL)
                onNowPlayingCancelled = {
                    mNpDialog = null
                }
            }
        }
    }

    private fun openQueueFragment() {
        if (checkIsPlayer(showError = false) && mMediaPlayerHolder.queueSongs.isNotEmpty() && mQueueDialog == null) {
            mQueueDialog = RecyclerSheet.newInstance(RecyclerSheet.QUEUE_TYPE).apply {
                show(supportFragmentManager, RecyclerSheet.TAG_MODAL_RV)
                onQueueCancelled = {
                    mQueueDialog = null
                }
            }
            return
        }
        Toast.makeText(this, R.string.error_no_queue, Toast.LENGTH_SHORT).show()
    }

    private fun openDetailsFragment(
        selectedArtistOrFolder: String?,
        launchedBy: String,
        selectedSongId: Long?
    ) {
        if (!sDetailsFragmentExpanded) {
            mDetailsFragment =
                DetailsFragment.newInstance(
                    selectedArtistOrFolder,
                    launchedBy,
                    MusicUtils.getPlayingAlbumPosition(
                        selectedArtistOrFolder,
                        mMusicViewModel.deviceAlbumsByArtist
                    ),
                    selectedSongId,
                    canUpdateSongs = mMediaPlayerHolder.currentSong?.artist == selectedArtistOrFolder
                )
            sCloseDetailsFragment = true
            if (sAllowCommit) {
                supportFragmentManager.addFragment(
                    mDetailsFragment,
                    GoConstants.DETAILS_FRAGMENT_TAG
                )
            }
        }
    }

    private fun closeDetailsFragment(isAnimation: Boolean) {
        if (isAnimation) {
            if (!sRevealAnimationRunning) {
                mDetailsFragment?.onHandleBackPressed()?.run {
                    sRevealAnimationRunning = true
                    doOnEnd {
                        if (sAllowCommit) {
                            supportFragmentManager.goBackFromFragmentNow(mDetailsFragment)
                        }
                        sRevealAnimationRunning = false
                    }
                }
            }
            return
        }
        if (sAllowCommit) {
            supportFragmentManager.goBackFromFragmentNow(mDetailsFragment)
        }
    }

    private fun logUserAction(actionType: String, details: Map<String, Any>? = null) {
        UserActionLogger.logAction(applicationContext, actionType, details)
    }

    private fun initMediaButtons() {

        mPlayerControlsPanelBinding.playPauseButton.setOnClickListener {
            val song = mMediaPlayerHolder.currentSong

            song?.let {
                AnalyticsLogger.logPlayButtonClick(it.title, it.artist)
            }

            mutableMapOf<String, Any>(
                "action_source" to "mini_player",
                "is_playing_before" to mMediaPlayerHolder.isPlaying
            ).also { details ->
                song?.id?.let { details["song_id"] = it }
                song?.title?.let { details["song_title"] = it }
                song?.artist?.let { details["artist"] = it }
                logUserAction("click_play", details)
            }

            mMediaPlayerHolder.resumeOrPause()
        }

        with(mPlayerControlsPanelBinding.queueButton) {
            safeClickListener {
                openQueueFragment()
            }
            setOnLongClickListener {
                if (checkIsPlayer(showError = true) && mMediaPlayerHolder.queueSongs.isNotEmpty()) {
                    Dialogs.showClearQueueDialog(this@MainActivity)
                }
                return@setOnLongClickListener true
            }
        }

        with(mPlayerControlsPanelBinding.favoritesButton) {
            safeClickListener {
                if (!mGoPreferences.favorites.isNullOrEmpty() && mFavoritesDialog == null) {
                    mFavoritesDialog = RecyclerSheet.newInstance(RecyclerSheet.FAV_TYPE).apply {
                        show(supportFragmentManager, RecyclerSheet.TAG_MODAL_RV)
                        onFavoritesDialogCancelled = {
                            mFavoritesDialog = null
                        }
                    }
                } else {
                    Toast.makeText(this@MainActivity, R.string.error_no_favorites, Toast.LENGTH_SHORT).show()
                }
            }
            setOnLongClickListener {
                if (!mGoPreferences.favorites.isNullOrEmpty()) {
                    Dialogs.showClearFavoritesDialog(this@MainActivity)
                }
                return@setOnLongClickListener true
            }
        }

        onFavoritesUpdated(clear = false)

        mPlayerControlsPanelBinding.root.findViewById<View>(R.id.historyButton)?.apply {
            safeClickListener {
                // Navigate to the History tab if it is enabled in activeTabs; otherwise show a hint
                val tabs = mGoPreferences.activeTabs.toList()
                val idx = tabs.indexOf(GoConstants.HISTORY_TAB)
                if (idx >= 0) {
                    mMainActivityBinding.viewPager2.currentItem = idx
                } else {
                    Toast.makeText(this@MainActivity, R.string.play_history, Toast.LENGTH_SHORT).show()
                }
            }
            setOnLongClickListener {
                // 与其他按钮风格一致，长按预留（目前无操作）
                return@setOnLongClickListener true
            }
        }

        with(mPlayerControlsPanelBinding.playingSongContainer) {
            safeClickListener {
                openNowPlayingFragment()
            }
            setOnLongClickListener {
                onOpenPlayingArtistAlbum()
                return@setOnLongClickListener true
            }
        }
    }

    override fun onAppearanceChanged(isThemeChanged: Boolean) {
        if (!mMediaPlayerHolder.isCurrentSongFM) mMediaPlayerInterface.onBackupSong()
        if (isThemeChanged) {
            AppCompatDelegate.setDefaultNightMode(
                Theming.getDefaultNightMode(this)
            )
            return
        }
        Theming.applyChanges(this, mMainActivityBinding.viewPager2.currentItem)
    }

    override fun onPlaybackSpeedToggled() {
        //avoid having user stuck at selected playback speed
        val isPlaybackPersisted = mGoPreferences.playbackSpeedMode != GoConstants.PLAYBACK_SPEED_ONE_ONLY
        var playbackSpeed = 1.0F
        if (isPlaybackPersisted) playbackSpeed = mGoPreferences.latestPlaybackSpeed
        mMediaPlayerHolder.setPlaybackSpeed(playbackSpeed)
    }

    private fun updatePlayingStatus() {
        if (mMediaPlayerHolder.isPlaying) {
            mPlayerControlsPanelBinding.playPauseButton.setImageResource(R.drawable.ic_pause)
            return
        }
        mPlayerControlsPanelBinding.playPauseButton.setImageResource(R.drawable.ic_play)
    }

    override fun onFavoriteAddedOrRemoved() {
        onFavoritesUpdated(clear = false)
        mNpDialog?.updateNpFavoritesIcon()
    }

    override fun onUpdatePositionFromNP(position: Int) {
        mPlayerControlsPanelBinding.songProgress.setProgressCompat(position, true)
    }

    override fun onFavoritesUpdated(clear: Boolean) {

        val favorites = mGoPreferences.favorites?.toMutableList()

        if (clear) {
            favorites?.clear()
            mGoPreferences.favorites = null
            mFavoritesDialog?.dismissAllowingStateLoss()
        }

        with(mPlayerControlsPanelBinding.favoritesButton) {
            if (favorites.isNullOrEmpty()) {
                setImageResource(R.drawable.ic_favorite_empty)
                updateIconTint(Theming.resolveWidgetsColorNormal(this@MainActivity))
            } else {
                setImageResource(R.drawable.ic_favorite)
                updateIconTint(Theming.resolveThemeColor(resources))
            }
        }
    }

    private fun restorePlayerStatus() {
        // If we are playing and the activity was restarted
        // update the controls panel
        with(mMediaPlayerHolder) {
            if (isMediaPlayer && isPlaying) {
                onRestartSeekBarCallback()
                updatePlayingInfo(restore = true)
                return
            }

            val song = mGoPreferences.latestPlayedSong
            isSongFromPrefs = song != null

            if (!mGoPreferences.queue.isNullOrEmpty()) {
                queueSongs = mGoPreferences.queue?.toMutableList()!!
                setQueueEnabled(enabled = true, canSkip = false)
            }

            val preQueueSong = mGoPreferences.isQueue
            if (preQueueSong != null) {
                isQueue = preQueueSong
                isQueueStarted = true
                mediaPlayerInterface.onQueueStartedOrEnded(started = true)
            }

            song?.let { restoredSong ->

                val sorting = restoredSong.findRestoreSorting(restoredSong.launchedBy)
                val songs = restoredSong.findRestoreSongs(sorting, mMusicViewModel)

                if (!songs.isNullOrEmpty()) {
                    isPlay = false
                    updateCurrentSong(restoredSong, songs, restoredSong.launchedBy)
                    preparePlayback(restoredSong)
                    updatePlayingInfo(restore = false)
                    mPlayerControlsPanelBinding.songProgress.setProgressCompat(
                        if (isCurrentSongFM) 0 else restoredSong.startFrom,
                        true
                    )
                    return
                }
                notifyError(GoConstants.TAG_SD_NOT_READY)
            }
        }
    }

    // method to update info on controls panel
    private fun updatePlayingInfo(restore: Boolean) {

        val selectedSong = mMediaPlayerHolder.currentSongFM ?: mMediaPlayerHolder.currentSong

        mPlayerControlsPanelBinding.songProgress.progress = 0
        mPlayerControlsPanelBinding.songProgress.max = selectedSong?.duration!!.toInt()

        updatePlayingSongTitle(selectedSong)

        mPlayerControlsPanelBinding.playingArtist.text =
            getString(R.string.artist_and_album, selectedSong.artist, selectedSong.album)

        mNpDialog?.run {
            updateRepeatStatus(onPlaybackCompletion = false)
            updateNpInfo()
        }

        if (restore) {

            if (mMediaPlayerHolder.queueSongs.isNotEmpty() && !mMediaPlayerHolder.isQueueStarted) {
                mMediaPlayerInterface.onQueueEnabled()
            } else {
                mMediaPlayerInterface.onQueueStartedOrEnded(started = mMediaPlayerHolder.isQueueStarted)
            }

            updatePlayingStatus()

            if (::mPlayerService.isInitialized) {
                //stop foreground if coming from pause state
                with(mPlayerService) {
                    if (isRestoredFromPause) {
                        ServiceCompat.stopForeground(mPlayerService, ServiceCompat.STOP_FOREGROUND_DETACH)
                        musicNotificationManager.updateNotification()
                        isRestoredFromPause = false
                    }
                }
            }
        }
    }

    private fun updatePlayingSongTitle(currentSong: Music) {
        var songTitle = currentSong.title
        if (GoPreferences.getPrefsInstance().songsVisualization == GoConstants.FN) {
            songTitle = currentSong.displayName.toFilenameWithoutExtension()
        }
        mPlayerControlsPanelBinding.playingSong.text = songTitle
    }

    private fun getSongSource(): String? {
        val selectedSong = (mMediaPlayerHolder.currentSongFM ?: mMediaPlayerHolder.currentSong)
        return when (mMediaPlayerHolder.launchedBy) {
            GoConstants.FOLDER_VIEW -> selectedSong?.relativePath
            GoConstants.ARTIST_VIEW -> selectedSong?.artist
            else -> selectedSong?.album
        }
    }

    override fun onOpenPlayingArtistAlbum() {
        if (mMediaPlayerHolder.isCurrentSong) {
            GoPreferences.getPrefsInstance().filters?.let { filters ->
                mMediaPlayerHolder.currentSongFM?.run {
                    if (filters.contains(artist) || filters.contains(album) || filters.contains(this.relativePath)) {
                        Toast.makeText(this@MainActivity, R.string.filters_error, Toast.LENGTH_SHORT).show()
                        return
                    }
                }
            }

            val selectedArtistOrFolder = getSongSource()

            if (sDetailsFragmentExpanded) {
                if (mDetailsFragment?.hasToUpdate(selectedArtistOrFolder)!!) {
                    closeDetailsFragment(isAnimation = false)
                } else {
                    mDetailsFragment?.tryToSnapToAlbumPosition(
                        MusicUtils.getPlayingAlbumPosition(
                            selectedArtistOrFolder,
                            mMusicViewModel.deviceAlbumsByArtist,
                        )
                    )
                }
                mDetailsFragment?.scrollToPlayingSong(mMediaPlayerHolder.currentSong?.id)
                return
            }
            openDetailsFragment(
                selectedArtistOrFolder,
                mMediaPlayerHolder.launchedBy,
                mMediaPlayerHolder.currentSong?.id
            )
        }
    }

    override fun onCloseActivity() {
        if (mMediaPlayerHolder.isPlaying) {
            Dialogs.stopPlaybackDialog(this)
            return
        }
        finishAndRemoveTask()
    }

    override fun onHandleCoverOptionsUpdate() {
        mMediaPlayerHolder.updateMediaSessionMetaData()
        mAlbumsFragment?.onUpdateCoverOption()
    }

    override fun onOpenNewDetailsFragment() {
        with(getSongSource()) {
            openDetailsFragment(
                this,
                mMediaPlayerHolder.launchedBy,
                mMediaPlayerHolder.currentSong?.id
            )
        }
    }

    override fun onArtistOrFolderSelected(artistOrFolder: String, launchedBy: String) {
        openDetailsFragment(
            artistOrFolder,
            launchedBy,
            mMediaPlayerHolder.currentSong?.id
        )
    }

    private fun preparePlayback(song: Music?) {
        if (::mPlayerService.isInitialized && !mPlayerService.isRunning) {
            startService(mBindingIntent)
        }
        mMediaPlayerHolder.initMediaPlayer(song, forceReset = false)
    }

    override fun onSongSelected(song: Music?, songs: List<Music>?, songLaunchedBy: String) {
        AnalyticsLogger.logSongSelected(song, songLaunchedBy)
        with(mMediaPlayerHolder) {
            if (isSongFromPrefs) isSongFromPrefs = false
            if (!isPlay) isPlay = true
            if (isQueue != null) setQueueEnabled(enabled = false, canSkip = false)
            val albumSongs = songs ?: MusicUtils.getAlbumSongs(
                song?.artist,
                song?.album,
                mMusicViewModel.deviceAlbumsByArtist
            )
            if (!isCurrentSongFM) {
                updateCurrentSong(song, albumSongs, songLaunchedBy)
            } else {
                mPlayerControlsPanelBinding.songProgress.progress = 0
                mPlayerControlsPanelBinding.songProgress.max = song?.duration!!.toInt()
                updatePlayingSongTitle(song)
                mPlayerControlsPanelBinding.playingArtist.text =
                    getString(R.string.artist_and_album, song.artist, song.album)
            }
            preparePlayback(song)
        }
    }

    override fun onOpenEqualizer() {
        if (checkIsPlayer(showError = true)) {
            if (mGoPreferences.isEqForced) {
                val eqIntent = Intent(this, EqualizerActivity::class.java)
                equalizerLauncher.launch(eqIntent)
            } else {
                mMediaPlayerHolder.openEqualizer(this, equalizerLauncher)
            }
            mNpDialog?.dismissAllowingStateLoss()
        }
    }

    override fun onOpenSleepTimerDialog() {
        if (mSleepTimerDialog == null) {
            mSleepTimerDialog = RecyclerSheet.newInstance(if (mMediaPlayerHolder.isSleepTimer) {
                RecyclerSheet.SLEEPTIMER_ELAPSED_TYPE
            } else {
                RecyclerSheet.SLEEPTIMER_TYPE
            }).apply {
                show(supportFragmentManager, RecyclerSheet.TAG_MODAL_RV)
                onSleepTimerEnabled = { enabled, value ->
                    updateSleepTimerIcon(isEnabled = enabled)
                    if (enabled) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.sleeptimer_enabled, value),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                onSleepTimerDialogCancelled = {
                    mSleepTimerDialog = null
                }
            }
        }
    }

    private fun updateSleepTimerIcon(isEnabled: Boolean) {
        mDetailsFragment?.tintSleepTimerIcon(enabled = isEnabled)
        mArtistsFragment?.tintSleepTimerIcon(enabled = isEnabled)
        mAlbumsFragment?.tintSleepTimerIcon(enabled = isEnabled)
        mAllMusicFragment?.tintSleepTimerIcon(enabled = isEnabled)
        mRecommendationsFragment?.tintSleepTimerIcon(enabled = isEnabled)
        mFoldersFragment?.tintSleepTimerIcon(enabled = isEnabled)
        mHistoryFragment?.tintSleepTimerIcon(enabled = isEnabled)
    }

    override fun onAddToQueue(song: Music?) {
        with(mMediaPlayerHolder) {

            setCanRestoreQueue()

            song?.let { songToQueue ->

                // don't add duplicates
                val currentIndex = queueSongs.findIndex(songToQueue)
                if (currentIndex == RecyclerView.NO_POSITION) {
                    if (isQueue != null && !canRestoreQueue && isQueueStarted) {
                        val currentPosition = queueSongs.findIndex(currentSong)
                        queueSongs.add(currentPosition+1, songToQueue)
                    } else {
                        queueSongs.add(songToQueue)
                    }
                } else {
                    // song is already present but queue is already started
                    // move the song next to the played song
                    if (isQueue != null && !canRestoreQueue && isQueueStarted) {
                        queueSongs.remove(songToQueue)
                        val currentPosition = queueSongs.findIndex(currentSong)
                        queueSongs.add(currentPosition+1, songToQueue)
                    }
                }

                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.queue_song_add, songToQueue.title),
                    Toast.LENGTH_SHORT
                ).show()

                if (canRestoreQueue && restoreQueueSong == null) {
                    restoreQueueSong = songToQueue
                }

                if (!isPlaying || state == GoConstants.PAUSED) {
                    startSongFromQueue(songToQueue)
                }
            }
        }
    }

    override fun onAddAlbumToQueue(songs: List<Music>?, forcePlay: Pair<Boolean, Music?>) {
        if (checkIsPlayer(showError = true) && !songs?.isEmpty()!!) {

            with(mMediaPlayerHolder) {

                if (isCurrentSongFM) currentSongFM = null

                setCanRestoreQueue()

                // don't add duplicates
                addSongsToNextQueuePosition(if (restoreQueueSong != songs.first()) {
                    queueSongs.removeAll(songs.toSet())
                    songs
                } else {
                    songs.minus(songs.first())
                })

                val song = forcePlay.second ?: songs.first()
                if (canRestoreQueue && restoreQueueSong == null) {
                    restoreQueueSong = song
                }

                if (!isPlaying || forcePlay.first) startSongFromQueue(song)
            }
        }
    }

    override fun onSongsShuffled(songs: List<Music>?, songLaunchedBy: String) {
        val cutoff = 100
        songs?.run {
            onAddAlbumToQueue(
                if (size >= cutoff) shuffled().take(cutoff) else shuffled(),
                forcePlay = Pair(first = true, second = null)
            )
        }
    }

    override fun onUpdatePlayingAlbumSongs(songs: List<Music>?) {
        mMediaPlayerHolder.currentSong?.run {
            updatePlayingSongTitle(this)
        }
        if (songs != null) {
            mMediaPlayerHolder.updateCurrentSongs(songs)
            return
        }
        val currentAlbumSize = mMediaPlayerHolder.getCurrentAlbumSize()
        if (currentAlbumSize != 0 && currentAlbumSize > 1) {
            // update current song to reflect this change
            mMediaPlayerHolder.updateCurrentSongs(null)
        }
        if (mAllMusicFragment != null && !mAllMusicFragment?.onSongVisualizationChanged()!!) {
            Theming.applyChanges(this, mMainActivityBinding.viewPager2.currentItem)
        }
    }

    override fun onAddToFilter(stringsToFilter: List<String>?) {

        stringsToFilter?.run {
            Lists.hideItems(this)
        }

        // be sure to update queue, favorites and the controls panel
        MusicUtils.updateMediaPlayerHolderLists(this, mMusicViewModel.getRandomMusic())?.let { song ->
            val songs = MusicUtils.getAlbumSongs(
                song.artist,
                song.album,
                mMusicViewModel.deviceAlbumsByArtist
            )
            with(mMediaPlayerHolder) {
                isPlay = isPlaying
                updateCurrentSong(song, songs, GoConstants.ARTIST_VIEW)
                initMediaPlayer(song, forceReset = false)
            }
            updatePlayingInfo(restore = false)
        }
        Theming.applyChanges(this, mMainActivityBinding.viewPager2.currentItem)
    }

    override fun onFiltersCleared() {
        GoPreferences.getPrefsInstance().filters = null
        Theming.applyChanges(this, mMainActivityBinding.viewPager2.currentItem)
    }

    //method to handle intent to play audio file from external app
    private fun handleIntent(intent: Intent?) {

        intent?.let { it ->
            if (Intent.ACTION_VIEW == it.action) {
                it.data?.let { returnUri ->
                    contentResolver.query(returnUri, null, null, null, null)
                }?.use { cursor ->
                    try {
                        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        mMediaPlayerHolder.currentSongFM = mMusicViewModel.getSongFromIntent(
                            cursor.getString(displayNameIndex)
                        )
                        onSongSelected(mMediaPlayerHolder.currentSongFM, null, GoConstants.ARTIST_VIEW)

                    } catch (e: Exception) {
                        e.printStackTrace()
                        notifyError(GoConstants.TAG_NO_MUSIC_INTENT)
                    }
                }
            }
        }
    }

    // interface to let MediaPlayerHolder update the UI media player controls.
    private val mMediaPlayerInterface = object : MediaPlayerInterface {

        override fun onUpdateRepeatStatus() {
            mNpDialog?.updateRepeatStatus(onPlaybackCompletion = false)
        }

        override fun onClose() {
            //finish activity if visible
            finishAndRemoveTask()
        }

        override fun onPositionChanged(position: Int) {
            mPlayerControlsPanelBinding.songProgress.setProgressCompat(position, true)
            mNpDialog?.updateProgress(position)
        }

        override fun onStateChanged() {
            updatePlayingStatus()
            mNpDialog?.updatePlayingStatus()
            if (mMediaPlayerHolder.state != GoConstants.RESUMED && mMediaPlayerHolder.state != GoConstants.PAUSED) {
                updatePlayingInfo(restore = false)
                if (mMediaPlayerHolder.isQueue != null) {
                    mQueueDialog?.swapQueueSong(mMediaPlayerHolder.currentSong)
                }
                if (sDetailsFragmentExpanded) {
                    mDetailsFragment?.swapSelectedSong(
                        mMediaPlayerHolder.currentSong?.id
                    )
                }
            }
        }

        override fun onQueueEnabled() {
            mPlayerControlsPanelBinding.queueButton.updateIconTint(
                ContextCompat.getColor(this@MainActivity, R.color.widgets_color)
            )
        }

        override fun onQueueStartedOrEnded(started: Boolean) {
            mPlayerControlsPanelBinding.queueButton.updateIconTint(
                when {
                    started -> Theming.resolveThemeColor(resources)
                    mMediaPlayerHolder.queueSongs.isEmpty() -> {
                        mQueueDialog?.dismissAllowingStateLoss()
                        Theming.resolveWidgetsColorNormal(this@MainActivity)
                    }
                    else -> {
                        mQueueDialog?.dismissAllowingStateLoss()
                        ContextCompat.getColor(
                            this@MainActivity,
                            R.color.widgets_color
                        )
                    }
                }
            )
        }

        override fun onBackupSong() {
            if (checkIsPlayer(showError = false) && !mMediaPlayerHolder.isPlaying) {
                mGoPreferences.latestPlayedSong =
                    mMediaPlayerHolder.currentSong?.copy(
                        startFrom = mMediaPlayerHolder.playerPosition,
                        launchedBy = mMediaPlayerHolder.launchedBy
                    )
            }
        }

        override fun onUpdateSleepTimerCountdown(value: Long) {
            mSleepTimerDialog?.run {
                if (sheetType == RecyclerSheet.SLEEPTIMER_ELAPSED_TYPE) {
                    val newValue = value.toFormattedDuration(isAlbum = false, isSeekBar = true)
                    updateCountdown(newValue)
                }
            }
        }

        override fun onStopSleepTimer() {
            mSleepTimerDialog?.dismissAllowingStateLoss()
            updateSleepTimerIcon(isEnabled = false)
        }

        override fun onUpdateFavorites() {
            onFavoriteAddedOrRemoved()
        }

        override fun onRepeat(toastMessage: Int) {
            Toast.makeText(this@MainActivity, toastMessage, Toast.LENGTH_SHORT).show()
        }

        override fun onListEnded() {
            Toast.makeText(this@MainActivity, R.string.error_list_ended, Toast.LENGTH_SHORT).show()
        }
    }

    // ViewPager2 adapter class
    private inner class ScreenSlidePagerAdapter(fa: FragmentActivity): FragmentStateAdapter(fa) {
        override fun getItemCount() = mGoPreferences.activeTabs.toList().size
        override fun createFragment(position: Int): Fragment = handleOnNavigationItemSelected(position)
    }
}
