package com.iven.musicplayergo.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
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
import com.iven.musicplayergo.utils.Versioning
import com.iven.musicplayergo.extensions.toContentUri
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupTextProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import com.iven.musicplayergo.network.ArchiveService
import android.util.Log
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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

        // 2) 选择并上传按钮逻辑（layout 里要有 @+id/uploadSelectBtn）
        val uploadBtn = view.findViewById<View>(R.id.uploadSelectBtn)
        uploadBtn?.setOnClickListener {
            showUploadDialog()
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

            } else if (it.itemId == R.id.action_upload) {
                showUploadDialog()
            } else if (it.itemId != R.id.action_search) {
                mUIControlInterface.onOpenSleepTimerDialog()
            }

            true
        }
    }

    /**
     * 显示上传对话框并处理上传逻辑
     */
    private fun showUploadDialog() {
        val deviceList: List<Music> = mMusicViewModel.deviceMusic.value ?: emptyList()
        if (deviceList.isEmpty()) {
            Toast.makeText(requireContext(), "没有检测到音乐", Toast.LENGTH_SHORT).show()
            return
        }

        val titles = deviceList.map { m ->
            m.displayName ?: m.title ?: m.relativePath?.let { p ->
                if (Versioning.isQ()) {
                    // Android 10+ 使用相对路径，取文件名
                    p.split("/").lastOrNull() ?: "未知"
                } else {
                    // Android 10 以下使用完整路径
                    File(p).name
                }
            } ?: "未知"
        }.toTypedArray()

        val checked = BooleanArray(titles.size)

        AlertDialog.Builder(requireContext())
            .setTitle("选择要上传的歌曲")
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("上传") { _, _ ->
                val selectedMusic = deviceList.filterIndexed { idx, _ ->
                    idx < checked.size && checked[idx]
                }
                if (selectedMusic.isEmpty()) {
                    Toast.makeText(requireContext(), "未选择任何歌曲", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // 协程中逐首上传到 /api/audio/upload
                viewLifecycleOwner.lifecycleScope.launch {
                    var success = 0
                    var fail = 0
                    for (m in selectedMusic) {
                        try {
                            val reqFile: RequestBody
                            val filename: String

                            if (Versioning.isQ()) {
                                // Android 10+ 使用 Content URI
                                val uri = m.id?.toContentUri()
                                if (uri == null) {
                                    fail++
                                    continue
                                }

                                // 从 Content URI 读取文件并创建临时文件
                                val inputStream = requireContext().contentResolver.openInputStream(uri)
                                if (inputStream == null) {
                                    fail++
                                    continue
                                }

                                filename = m.displayName ?: m.title ?: "audio_${m.id}.mp3"
                                val tempFile = File(requireContext().cacheDir, "upload_${System.currentTimeMillis()}_$filename")

                                inputStream.use { input ->
                                    FileOutputStream(tempFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }

                                if (!tempFile.exists()) {
                                    fail++
                                    continue
                                }

                                reqFile = tempFile.asRequestBody("audio/*".toMediaTypeOrNull())

                                // 上传后删除临时文件
                                tempFile.deleteOnExit()
                            } else {
                                // Android 10 以下使用文件路径
                                val path = m.relativePath
                                if (path.isNullOrBlank()) {
                                    fail++
                                    continue
                                }

                                val f = File(path)
                                if (!f.exists()) {
                                    fail++
                                    continue
                                }

                                filename = f.name
                                reqFile = f.asRequestBody("audio/*".toMediaTypeOrNull())
                            }

                            val filePart = MultipartBody.Part.createFormData(
                                name = "file",   // 必须叫 file，对上后端 file: UploadFile
                                filename = filename,
                                body = reqFile
                            )

                            val artistBody: RequestBody? =
                                if (!m.artist.isNullOrBlank()) {
                                    m.artist!!.toRequestBody("text/plain".toMediaTypeOrNull())
                                } else {
                                    null
                                }

                            val resp = withContext(Dispatchers.IO) {
                                ArchiveService.api.uploadAudio(filePart, artistBody)
                            }

                            // 这里你可以把 resp.song_id / resp.feature_path 等存起来
                            Log.d("Upload", "上传成功: ${resp.song_id}")
                            success++
                        } catch (e: Exception) {
                            val errorMsg = when (e) {
                                is UnknownHostException -> "无法连接到服务器，请检查网络和服务器地址"
                                is SocketTimeoutException -> "连接超时，请检查服务器是否运行"
                                is HttpException -> "服务器错误: ${e.code()} - ${e.message()}"
                                else -> "上传失败: ${e.message ?: e.javaClass.simpleName}"
                            }
                            Log.e("Upload", "上传失败: $errorMsg", e)
                            e.printStackTrace()
                            fail++
                        }
                    }

                    val message = if (fail > 0) {
                        "上传完成: 成功 $success 首，失败 $fail 首\n请查看 Logcat 查看详细错误信息"
                    } else {
                        "上传完成: 成功 $success 首"
                    }
                    Toast.makeText(
                        requireContext(),
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                    
                    // 在 Logcat 中输出详细信息
                    if (fail > 0) {
                        Log.w("Upload", "上传结果: 成功 $success 首，失败 $fail 首")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
