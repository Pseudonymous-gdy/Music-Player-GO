package com.iven.musicplayergo.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import com.iven.musicplayergo.MusicViewModel
import com.iven.musicplayergo.R
import com.iven.musicplayergo.databinding.FragmentRecommendationsBinding
import com.iven.musicplayergo.databinding.MusicItemBinding
import com.iven.musicplayergo.extensions.handleViewVisibility
import com.iven.musicplayergo.extensions.setTitleColor
import com.iven.musicplayergo.extensions.toFormattedDate
import com.iven.musicplayergo.extensions.toFormattedDuration
import com.iven.musicplayergo.extensions.toName
import com.iven.musicplayergo.extensions.toFilenameWithoutExtension
import com.iven.musicplayergo.extensions.toContentUri
import com.iven.musicplayergo.models.Music
import com.iven.musicplayergo.network.RecommendationItem
import com.iven.musicplayergo.network.RecommendQueryRequest
import com.iven.musicplayergo.network.RecommendService
import com.iven.musicplayergo.network.ArchiveService
import com.iven.musicplayergo.player.MediaPlayerHolder
import com.iven.musicplayergo.ui.MediaControlInterface
import com.iven.musicplayergo.ui.UIControlInterface
import com.iven.musicplayergo.utils.Lists
import com.iven.musicplayergo.utils.Popups
import com.iven.musicplayergo.utils.RecommendationRepository
import com.iven.musicplayergo.utils.AnalyticsLogger
import com.iven.musicplayergo.utils.Theming
import com.iven.musicplayergo.utils.Versioning
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupTextProvider
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RecommendationsFragment : Fragment(), SearchView.OnQueryTextListener {

    private var _binding: FragmentRecommendationsBinding? = null
    private val binding get() = _binding

    private lateinit var mUIControlInterface: UIControlInterface
    private lateinit var mMediaControlInterface: MediaControlInterface
    private lateinit var mMusicViewModel: MusicViewModel

    private val recommendationsAdapter = RecommendationsAdapter()

    private var recommendedSongs: List<Music> = emptyList()
    private var displayedSongs: List<Music> = emptyList()
    private var currentQuery: String = ""
    private var isLoading = false
    private var screenEnterTimestamp = 0L

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
        AnalyticsLogger.logTabDuration(
            "recommendations_fragment",
            System.currentTimeMillis() - screenEnterTimestamp
        )
        super.onDestroyView()
        _binding = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentRecommendationsBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupList()
        setupFab()
        binding?.uploadSelectBtn?.setOnClickListener { showUploadDialog() }
        setupViewModel()
        screenEnterTimestamp = System.currentTimeMillis()
        AnalyticsLogger.logScreenView("Recommendations", "RecommendationsFragment")
    }

    private fun setupToolbar() {
        binding?.searchToolbar?.let { toolbar ->
            toolbar.inflateMenu(R.menu.menu_recommendations)
            toolbar.title = getString(R.string.recommendations)
            toolbar.overflowIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_more_vert)
            toolbar.setNavigationOnClickListener { mUIControlInterface.onCloseActivity() }

            toolbar.menu.findItem(R.id.action_refresh)?.setTitleColor(
                Theming.resolveThemeColor(resources)
            )

            (toolbar.menu.findItem(R.id.action_search).actionView as? SearchView)?.apply {
                setOnQueryTextListener(this@RecommendationsFragment)
                setOnQueryTextFocusChangeListener { _, hasFocus ->
                    toolbar.menu.findItem(R.id.sleeptimer).isVisible = !hasFocus
                    toolbar.menu.findItem(R.id.action_refresh).isVisible = !hasFocus
                    toolbar.menu.findItem(R.id.action_upload).isVisible = !hasFocus
                }
            }

            toolbar.setOnMenuItemClickListener(::onToolbarItemSelected)
            tintSleepTimerIcon(MediaPlayerHolder.getInstance().isSleepTimer)
        }
    }

    private fun setupList() {
        binding?.recommendationsRv?.apply {
            adapter = recommendationsAdapter
            setHasFixedSize(true)
            FastScrollerBuilder(this).useMd2Style().build()
        }
    }

    private fun setupFab() {
        binding?.shuffleFab?.let { fab ->
            val fabColor = ColorUtils.blendARGB(
                Theming.resolveColorAttr(requireContext(), R.attr.toolbar_bg),
                Theming.resolveThemeColor(resources),
                0.10f
            )
            fab.backgroundTintList = ColorStateList.valueOf(fabColor)
            fab.setOnClickListener {
                if (displayedSongs.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.recommendation_empty_result, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                mMediaControlInterface.onSongsShuffled(displayedSongs, GoConstants.ARTIST_VIEW)
            }
            fab.text = displayedSongs.size.toString()
            fab.iconTint = ColorStateList.valueOf(Theming.resolveThemeColor(resources))
        }
    }

    private fun setupViewModel() {
        mMusicViewModel =
            ViewModelProvider(requireActivity())[MusicViewModel::class.java].apply {
                deviceMusic.observe(viewLifecycleOwner) { music ->
                    if (!music.isNullOrEmpty()) {
                        refreshRecommendations()
                    } else {
                        showEmptyState(true, R.string.error_no_music)
                    }
                }
            }
    }

    private fun onToolbarItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                refreshRecommendations()
                true
            }
            R.id.action_upload -> {
                showUploadDialog()
                true
            }
            R.id.sleeptimer -> {
                mUIControlInterface.onOpenSleepTimerDialog()
                true
            }
            else -> false
        }
    }

    private fun refreshRecommendations() {
        AnalyticsLogger.logRefreshRecommendations("user_refresh")
        if (isLoading) return
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = fetchRecommendations()
            recommendedSongs = songs
            setLoading(false)
            applyQuery(currentQuery)
        }
    }

    private suspend fun fetchRecommendations(): List<Music> {
        val features = RecommendationRepository.getAllFeatures()
        val serverIds = features.mapNotNull { it.serverSongId }

        val request = RecommendQueryRequest(
            playlist = serverIds,
            n = if (serverIds.isEmpty()) 10 else min(10, serverIds.size * 2)
        )
        val response = try {
            withContext(Dispatchers.IO) {
                RecommendService.api.queryRecommendation(request)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recommendations", e)
            Toast.makeText(requireContext(), R.string.recommendation_request_failed, Toast.LENGTH_SHORT).show()
            return emptyList()
        }

        val mapped = mapRecommendationsToLocalSongs(
            response.recommendations,
            mMusicViewModel.deviceMusic.value.orEmpty()
        )
        if (mapped.isEmpty()) {
            Toast.makeText(requireContext(), R.string.recommendation_no_local_match, Toast.LENGTH_SHORT).show()
        } else {
            AnalyticsLogger.logPredictionResult("feature_based", mapped.size)
        }
        return mapped
    }

    private fun applyQuery(query: String?) {
        currentQuery = query.orEmpty()
        displayedSongs = when {
            query.isNullOrBlank() -> recommendedSongs
            else -> Lists.processQueryForMusic(query, recommendedSongs) ?: recommendedSongs
        }
        recommendationsAdapter.swapList(displayedSongs)
        updateFab()
        showEmptyState(displayedSongs.isEmpty() && !isLoading, R.string.recommendation_empty_result)
    }

    private fun updateFab() {
        binding?.shuffleFab?.text = displayedSongs.size.toString()
    }

    private fun setLoading(isLoading: Boolean) {
        this.isLoading = isLoading
        binding?.recommendationsLoading?.handleViewVisibility(show = isLoading)
        if (isLoading) showEmptyState(false, null)
    }

    private fun showEmptyState(show: Boolean, message: Int?) {
        binding?.recommendationsEmpty?.apply {
            handleViewVisibility(show = show)
            message?.let { setText(it) }
        }
    }

    /**
     * 选择并上传歌曲到推荐服务
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
                    p.split("/").lastOrNull() ?: "未知"
                } else {
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

                viewLifecycleOwner.lifecycleScope.launch {
                    var success = 0
                    var fail = 0
                    for (m in selectedMusic) {
                        try {
                            val reqFile: RequestBody
                            val filename: String

                            if (Versioning.isQ()) {
                                val uri = m.id?.toContentUri()
                                if (uri == null) {
                                    fail++
                                    continue
                                }

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
                                tempFile.deleteOnExit()
                            } else {
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
                                name = "file",
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

                            RecommendationRepository.saveFeatureMapping(
                                localSongId = m.id,
                                serverSongId = resp.song_id,
                                featurePath = resp.feature_path,
                                rawFeature = resp.feature
                            )
                            Log.d(TAG, "上传成功: ${resp.song_id}")
                            success++
                        } catch (e: Exception) {
                            val errorMsg = when (e) {
                                is UnknownHostException -> "无法连接到服务器，请检查网络和服务器地址"
                                is SocketTimeoutException -> "连接超时，请检查服务器是否运行"
                                is HttpException -> "服务器错误: ${e.code()} - ${e.message()}"
                                else -> "上传失败: ${e.message ?: e.javaClass.simpleName}"
                            }
                            Log.e(TAG, "上传失败: $errorMsg", e)
                            fail++
                        }
                    }

                    val message = if (fail > 0) {
                        "上传完成: 成功 $success 首，失败 $fail 首\n请查看 Logcat 查看详细错误信息"
                    } else {
                        "上传完成: 成功 $success 首"
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

                    if (fail > 0) {
                        Log.w(TAG, "上传结果: 成功 $success 首，失败 $fail 首")
                    }

                    // 点击上传后，无论成功失败都主动拉取服务器推荐
                    refreshRecommendations()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        newText?.takeIf { it.isNotBlank() }?.let {
            AnalyticsLogger.logSearch(it, "recommendations")
        }
        applyQuery(newText)
        return false
    }

    override fun onQueryTextSubmit(query: String?) = false

    fun tintSleepTimerIcon(enabled: Boolean) {
        binding?.searchToolbar?.run {
            Theming.tintSleepTimerMenuItem(this, enabled)
        }
    }

    private fun mapRecommendationsToLocalSongs(
        items: List<RecommendationItem>,
        deviceSongs: List<Music>
    ): List<Music> {
        val mapped = mutableListOf<Music>()
        items.forEach { item ->
            val serverKey = item.serverKey
            val mappedById = RecommendationRepository.getLocalSongIdForServer(serverKey)
            val song = when {
                mappedById != null -> deviceSongs.firstOrNull { it.id == mappedById }
                else -> findMusicByMetadata(
                    deviceSongs,
                    normalizeTitle(item.displayTitle),
                    normalize(item.artist)
                )
            }
            song?.let { mapped.add(it) }
        }
        return mapped.distinctBy { it.id }
    }

    private fun findMusicByMetadata(
        songs: List<Music>,
        title: String?,
        artist: String?
    ): Music? {
        val normalizedArtist = artist
        return songs.firstOrNull { song ->
            val rawTitle = song.title ?: song.displayName?.toFilenameWithoutExtension()
            val songTitle = normalizeTitle(rawTitle)
            val songArtist = normalize(song.artist)
            when {
                songTitle == null || title == null -> false
                normalizedArtist.isNullOrBlank() -> songTitle == title
                else -> songTitle == title && songArtist == normalizedArtist
            }
        }
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.lowercase(Locale.getDefault())?.takeIf { it.isNotEmpty() }

    private fun normalizeTitle(value: String?): String? = normalize(value)

    private inner class RecommendationsAdapter :
        RecyclerView.Adapter<RecommendationsAdapter.RecommendationHolder>(),
        PopupTextProvider {

        private var items: List<Music> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecommendationHolder {
            val binding =
                MusicItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return RecommendationHolder(binding)
        }

        override fun getPopupText(position: Int): String {
            if (position in items.indices) {
                items[position].title?.run {
                    if (isNotEmpty()) return first().toString()
                }
            }
            return ""
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecommendationHolder, position: Int) {
            holder.bindItems(items[holder.absoluteAdapterPosition])
        }

        @SuppressLint("NotifyDataSetChanged")
        fun swapList(newItems: List<Music>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class RecommendationHolder(private val binding: MusicItemBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bindItems(itemSong: Music) {
                with(binding) {
                    val formattedDuration = itemSong.duration?.toFormattedDuration(
                        isAlbum = false,
                        isSeekBar = false
                    )

                    duration.text = getString(
                        R.string.duration_date_added,
                        formattedDuration,
                        itemSong.dateAdded?.toFormattedDate()
                    )
                    title.text = itemSong.toName()
                    subtitle.text = getString(
                        R.string.artist_and_album,
                        itemSong.artist,
                        itemSong.album
                    )

                    root.setOnClickListener {
                        AnalyticsLogger.logRecommendationClick(
                            itemSong,
                            bindingAdapterPosition,
                            currentQuery
                        )
                        with(MediaPlayerHolder.getInstance()) {
                            if (isCurrentSongFM) currentSongFM = null
                        }
                        mMediaControlInterface.onSongSelected(
                            itemSong,
                            displayedSongs,
                            GoConstants.ARTIST_VIEW
                        )
                    }

                    root.setOnLongClickListener {
                        val vh =
                            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let {
                                _binding?.recommendationsRv?.findViewHolderForAdapterPosition(it)
                            }
                        Popups.showPopupForSongs(
                            requireActivity(),
                            vh?.itemView ?: root,
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
        private const val TAG = "RecommendationsTab"

        @JvmStatic
        fun newInstance() = RecommendationsFragment()
    }
}
