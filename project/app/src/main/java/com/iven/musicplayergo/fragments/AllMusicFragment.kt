package com.iven.musicplayergo.fragments

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
import androidx.recyclerview.widget.RecyclerView
import com.iven.musicplayergo.GoConstants
import com.iven.musicplayergo.GoPreferences
import com.iven.musicplayergo.MusicViewModel
import com.iven.musicplayergo.R
import com.iven.musicplayergo.databinding.FragmentAllMusicBinding
import com.iven.musicplayergo.databinding.MusicItemBinding
import com.iven.musicplayergo.extensions.setTitleColor
import com.iven.musicplayergo.extensions.toFormattedDate
import com.iven.musicplayergo.extensions.toFormattedDuration
import com.iven.musicplayergo.extensions.toName
import com.iven.musicplayergo.models.Music
import com.iven.musicplayergo.player.MediaPlayerHolder
import com.iven.musicplayergo.ui.MediaControlInterface
import com.iven.musicplayergo.ui.UIControlInterface
import com.iven.musicplayergo.utils.Lists
import com.iven.musicplayergo.utils.Popups
import com.iven.musicplayergo.utils.Theming
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupTextProvider

// ...existing imports...
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.iven.musicplayergo.utils.createZipFromFiles
import com.iven.musicplayergo.network.ArchiveService

/**
 * A simple [Fragment] subclass.
 * Use the [AllMusicFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class AllMusicFragment : Fragment(), SearchView.OnQueryTextListener {

    private var _allMusicFragmentBinding: FragmentAllMusicBinding? = null
    private lateinit var mUIControlInterface: UIControlInterface
    private lateinit var mMediaControlInterface: MediaControlInterface

    // view model
    private lateinit var mMusicViewModel: MusicViewModel

    // sorting
    private lateinit var mSortMenuItem: MenuItem
    private var mSorting = GoPreferences.getPrefsInstance().allMusicSorting

    private var mAllMusic: List<Music>? = null

    private val sIsFastScrollerPopup get() = (mSorting == GoConstants.ASCENDING_SORTING || mSorting == GoConstants.DESCENDING_SORTING) && GoPreferences.getPrefsInstance().songsVisualization != GoConstants.FN

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // This makes sure that the container activity has implemented
        // the callback interface. If not, it throws an exception
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _allMusicFragmentBinding = FragmentAllMusicBinding.inflate(inflater, container, false)
        return _allMusicFragmentBinding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

                    with (findItem(R.id.action_search).actionView as SearchView) {
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
                || it.itemId == R.id.album_sorting_inv) {

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

            return@setOnMenuItemClickListener true
        }
    }

    fun onSongVisualizationChanged() = if (_allMusicFragmentBinding != null) {
        mAllMusic = Lists.getSortedMusicListForAllMusic(mSorting, mAllMusic)
        setMusicDataSource(mAllMusic)
        true
    } else {
        false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        setMusicDataSource(
            Lists.processQueryForMusic(newText,
                Lists.getSortedMusicListForAllMusic(mSorting, mMusicViewModel.deviceMusicFiltered)
            ) ?: mAllMusic)
        return false
    }

    override fun onQueryTextSubmit(query: String?) = false


    // try upload music here. new logic 
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // ...existing setup code (RecyclerView 等) ...

        // 找到布局里的 "选择并上传" 按钮（确保 id 为 uploadSelectBtn）
        val uploadBtn = view.findViewById<View>(R.id.uploadSelectBtn)
        uploadBtn?.setOnClickListener {
            // 从 Activity 的 MusicViewModel 获取当前检测到的歌曲列表
            val deviceList = (activity as? MainActivity)?.musicViewModel?.deviceMusic?.value ?: mutableListOf()
            if (deviceList.isEmpty()) {
                Toast.makeText(requireContext(), "没有检测到音乐", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 准备显示的项（显示名）和对应路径数组
            val titles = deviceList.map { it?.displayName ?: it?.title ?: it?.path?.let { p -> java.io.File(p).name } ?: "未知" }.toTypedArray()
            val paths = deviceList.mapNotNull { it?.path }
            val checked = BooleanArray(titles.size)

            // 多选对话框
            AlertDialog.Builder(requireContext())
                .setTitle("选择要上传的歌曲")
                .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("上传") { _, _ ->
                    // 收集被选中的路径
                    val selected = paths.filterIndexed { idx, _ -> idx < checked.size && checked[idx] }
                    if (selected.isEmpty()) {
                        Toast.makeText(requireContext(), "未选择任何歌曲", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    // 上传流程：在协程中压缩并上传
                    lifecycleScope.launch {
                        try {
                            val zipFile = withContext(Dispatchers.IO) {
                                createZipFromFiles(requireContext(), selected, "selected_music.zip")
                            }
                            val reqFile = zipFile.asRequestBody("application/zip".toMediaTypeOrNull())
                            val part = MultipartBody.Part.createFormData("file", zipFile.name, reqFile)
                            val userIdBody = "user123".toRequestBody("text/plain".toMediaTypeOrNull())

                            val resp = withContext(Dispatchers.IO) {
                                ArchiveService.api.uploadArchive(part, userIdBody)
                            }

                            Toast.makeText(requireContext(), "上传结果: ${resp.ok} ${resp.message}", Toast.LENGTH_LONG).show()
                            zipFile.delete()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "上传失败: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    private inner class AllMusicAdapter : RecyclerView.Adapter<AllMusicAdapter.SongsHolder>(), PopupTextProvider {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongsHolder {
            val binding = MusicItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
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
            return mAllMusic?.size!!
        }

        override fun onBindViewHolder(holder: SongsHolder, position: Int) {
            holder.bindItems(mAllMusic?.get(holder.absoluteAdapterPosition))
        }

        inner class SongsHolder(private val binding: MusicItemBinding): RecyclerView.ViewHolder(binding.root) {

            fun bindItems(itemSong: Music?) {

                with(binding) {

                    val formattedDuration = itemSong?.duration?.toFormattedDuration(
                        isAlbum = false,
                        isSeekBar = false
                    )

                    duration.text = getString(R.string.duration_date_added, formattedDuration,
                        itemSong?.dateAdded?.toFormattedDate())
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
                        val vh = _allMusicFragmentBinding?.allMusicRv?.findViewHolderForAdapterPosition(absoluteAdapterPosition)
                        Popups.showPopupForSongs(
                            requireActivity(),
                            vh?.itemView,
                            itemSong,
                            GoConstants.ARTIST_VIEW
                        )
                        return@setOnLongClickListener true
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @return A new instance of fragment AllMusicFragment.
         */
        @JvmStatic
        fun newInstance() = AllMusicFragment()
    }
}
