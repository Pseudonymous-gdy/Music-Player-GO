package com.example.musicplayergo.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayergo.GoConstants
import com.example.musicplayergo.GoPreferences
import com.example.musicplayergo.MusicViewModel
import com.example.musicplayergo.R
import com.example.musicplayergo.databinding.FragmentAllMusicBinding
import com.example.musicplayergo.databinding.MusicItemBinding
import com.example.musicplayergo.extensions.setTitleColor
import com.example.musicplayergo.extensions.toFormattedDate
import com.example.musicplayergo.extensions.toFormattedDuration
import com.example.musicplayergo.extensions.toName
import com.example.musicplayergo.models.Music
import com.example.musicplayergo.player.MediaPlayerHolder
import com.example.musicplayergo.ui.MediaControlInterface
import com.example.musicplayergo.ui.UIControlInterface
import com.example.musicplayergo.utils.Lists
import com.example.musicplayergo.utils.Popups
import com.example.musicplayergo.utils.Theming
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupTextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AllMusicFragment : Fragment(), SearchView.OnQueryTextListener {

    private var _allMusicFragmentBinding: FragmentAllMusicBinding? = null
    private val binding get() = _allMusicFragmentBinding

    private lateinit var mUIControlInterface: UIControlInterface
    private lateinit var mMediaControlInterface: MediaControlInterface

    private lateinit var mMusicViewModel: MusicViewModel

    private lateinit var mSortMenuItem: MenuItem
    private var mSorting = GoPreferences.getPrefsInstance().allMusicSorting

    private var mAllMusic: List<Music>? = null

    private val sIsFastScrollerPopup
        get() = (mSorting == GoConstants.ASCENDING_SORTING || mSorting == GoConstants.DESCENDING_SORTING) &&
            GoPreferences.getPrefsInstance().songsVisualization != GoConstants.FN

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            mUIControlInterface = activity as UIControlInterface
            mMediaControlInterface = activity as MediaControlInterface
        } catch (e: ClassCastException) {
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _allMusicFragmentBinding = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _allMusicFragmentBinding = FragmentAllMusicBinding.inflate(inflater, container, false)
        return _allMusicFragmentBinding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1) 观察 ViewModel，数据到达后初始化 UI
        mMusicViewModel =
            ViewModelProvider(requireActivity())[MusicViewModel::class.java].apply {
                deviceMusic.observe(viewLifecycleOwner) { returnedMusic ->
                    if (!returnedMusic.isNullOrEmpty()) {
                        mAllMusic = Lists.getSortedMusicListForAllMusic(
                            mSorting,
                            mMusicViewModel.deviceMusicFiltered
                        )
                        finishSetup()
                    }
                }
            }

    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setMusicDataSource(musicList: List<Music>?) {
        musicList?.run {
            mAllMusic = this
            _allMusicFragmentBinding?.allMusicRv?.adapter?.notifyDataSetChanged()
        }
    }

    private fun finishSetup() {
        _allMusicFragmentBinding?.run {

            allMusicRv.adapter = AllMusicAdapter()

            FastScrollerBuilder(allMusicRv).useMd2Style().build()

            shuffleFab.text = mAllMusic?.size.toString()
            val fabColor = ColorUtils.blendARGB(
                Theming.resolveColorAttr(requireContext(), R.attr.toolbar_bg),
                Theming.resolveThemeColor(resources),
                0.10f
            )
            shuffleFab.backgroundTintList = ColorStateList.valueOf(fabColor)
            shuffleFab.setOnClickListener {
                mMediaControlInterface.onSongsShuffled(
                    mAllMusic,
                    GoConstants.ARTIST_VIEW
                )
            }

            searchToolbar.let { stb ->

                stb.inflateMenu(R.menu.menu_music_search)
                stb.overflowIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_sort)
                stb.setNavigationOnClickListener {
                    mUIControlInterface.onCloseActivity()
                }

                with(stb.menu) {

                    mSortMenuItem = Lists.getSelectedSortingForMusic(mSorting, this).apply {
                        setTitleColor(Theming.resolveThemeColor(resources))
                    }

                    with(findItem(R.id.action_search).actionView as SearchView) {
                        setOnQueryTextListener(this@AllMusicFragment)
                        setOnQueryTextFocusChangeListener { _, hasFocus ->
                            stb.menu.setGroupVisible(R.id.sorting, !hasFocus)
                            stb.menu.findItem(R.id.sleeptimer).isVisible = !hasFocus
                        }
                    }

                    setMenuOnItemClickListener(stb.menu)
                }
            }
        }

        tintSleepTimerIcon(enabled = MediaPlayerHolder.getInstance().isSleepTimer)
    }

    fun tintSleepTimerIcon(enabled: Boolean) {
        _allMusicFragmentBinding?.searchToolbar?.run {
            Theming.tintSleepTimerMenuItem(this, enabled)
        }
    }

    private fun setMenuOnItemClickListener(menu: Menu) {
        _allMusicFragmentBinding?.searchToolbar?.setOnMenuItemClickListener {

            if (it.itemId == R.id.default_sorting || it.itemId == R.id.ascending_sorting
                || it.itemId == R.id.descending_sorting || it.itemId == R.id.date_added_sorting
                || it.itemId == R.id.date_added_sorting_inv || it.itemId == R.id.artist_sorting
                || it.itemId == R.id.artist_sorting_inv || it.itemId == R.id.album_sorting
                || it.itemId == R.id.album_sorting_inv
            ) {

                mSorting = it.order
                mAllMusic = Lists.getSortedMusicListForAllMusic(mSorting, mAllMusic)

                setMusicDataSource(mAllMusic)

                mSortMenuItem.setTitleColor(
                    Theming.resolveColorAttr(requireContext(), android.R.attr.textColorPrimary)
                )

                mSortMenuItem = Lists.getSelectedSortingForMusic(mSorting, menu).apply {
                    setTitleColor(Theming.resolveThemeColor(resources))
                }

                GoPreferences.getPrefsInstance().allMusicSorting = mSorting

            } else if (it.itemId != R.id.action_search) {
                mUIControlInterface.onOpenSleepTimerDialog()
            }

            true
        }
    }

    fun onSongVisualizationChanged() =
        if (_allMusicFragmentBinding != null) {
            mAllMusic = Lists.getSortedMusicListForAllMusic(mSorting, mAllMusic)
            setMusicDataSource(mAllMusic)
            true
        } else {
            false
        }

    override fun onQueryTextChange(newText: String?): Boolean {
        setMusicDataSource(
            Lists.processQueryForMusic(
                newText,
                Lists.getSortedMusicListForAllMusic(mSorting, mMusicViewModel.deviceMusicFiltered)
            ) ?: mAllMusic
        )
        return false
    }

    override fun onQueryTextSubmit(query: String?) = false

    private inner class AllMusicAdapter :
        RecyclerView.Adapter<AllMusicAdapter.SongsHolder>(),
        PopupTextProvider {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongsHolder {
            val binding =
                MusicItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return SongsHolder(binding)
        }

        override fun getPopupText(position: Int): String {
            if (sIsFastScrollerPopup) {
                mAllMusic?.get(position)?.title?.run {
                    if (isNotEmpty()) return first().toString()
                }
            }
            return ""
        }

        override fun getItemCount(): Int {
            return mAllMusic?.size ?: 0
        }

        override fun onBindViewHolder(holder: SongsHolder, position: Int) {
            holder.bindItems(mAllMusic?.get(holder.absoluteAdapterPosition))
        }

        inner class SongsHolder(private val binding: MusicItemBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bindItems(itemSong: Music?) {
                with(binding) {

                    val formattedDuration = itemSong?.duration?.toFormattedDuration(
                        isAlbum = false,
                        isSeekBar = false
                    )

                    duration.text = getString(
                        R.string.duration_date_added,
                        formattedDuration,
                        itemSong?.dateAdded?.toFormattedDate()
                    )
                    title.text = itemSong.toName()
                    subtitle.text =
                        getString(R.string.artist_and_album, itemSong?.artist, itemSong?.album)

                    root.setOnClickListener {
                        with(MediaPlayerHolder.getInstance()) {
                            if (isCurrentSongFM) currentSongFM = null
                        }
                        mMediaControlInterface.onSongSelected(
                            itemSong,
                            mAllMusic,
                            GoConstants.ARTIST_VIEW
                        )
                    }

                    root.setOnLongClickListener {
                        val vh =
                            _allMusicFragmentBinding?.allMusicRv
                                ?.findViewHolderForAdapterPosition(absoluteAdapterPosition)
                        Popups.showPopupForSongs(
                            requireActivity(),
                            vh?.itemView,
                            itemSong,
                            GoConstants.ARTIST_VIEW
                        )
                        true
                    }
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance() = AllMusicFragment()
    }
}
