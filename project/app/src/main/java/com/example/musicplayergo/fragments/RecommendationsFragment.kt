package com.example.musicplayergo.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.musicplayergo.GoConstants
import com.example.musicplayergo.MusicViewModel
import com.example.musicplayergo.R
import com.example.musicplayergo.databinding.FragmentRecommendationsBinding
import com.example.musicplayergo.databinding.MusicItemBinding
import com.example.musicplayergo.extensions.handleViewVisibility
import com.example.musicplayergo.extensions.setTitleColor
import com.example.musicplayergo.extensions.toFormattedDate
import com.example.musicplayergo.extensions.toFormattedDuration
import com.example.musicplayergo.extensions.toName
import com.example.musicplayergo.extensions.toFilenameWithoutExtension
import com.example.musicplayergo.extensions.toContentUri
import com.example.musicplayergo.models.Music
import com.example.musicplayergo.network.RecommendationItem
import com.example.musicplayergo.network.RecommendQueryRequest
import com.example.musicplayergo.network.RecommendService
import com.example.musicplayergo.network.ArchiveService
import com.example.musicplayergo.player.MediaPlayerHolder
import com.example.musicplayergo.ui.MediaControlInterface
import com.example.musicplayergo.ui.UIControlInterface
import com.example.musicplayergo.utils.Lists
import com.example.musicplayergo.utils.Popups
import com.example.musicplayergo.utils.RecommendationRepository
import com.example.musicplayergo.utils.AnalyticsLogger
import com.example.musicplayergo.utils.Theming
import com.example.musicplayergo.utils.Versioning
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

        // 简化请求：不发送 playlist，让服务器推荐所有可用歌曲
        // 这样可以避免因为排除所有候选而导致 400 错误
        // 服务器会基于所有可用歌曲进行推荐
        val request = RecommendQueryRequest(
            user_id = RecommendationRepository.getUserId(),  // 添加用户ID
            playlist = emptyList(),  // 不发送 playlist，避免排除问题
            candidates = emptyList(),  // 不指定候选，使用服务器上所有歌曲
            exclude_playlist = false,  // 不排除
            n = 10,
            policy = RecommendationRepository.getRecommendationPolicy()  // 使用保存的策略
        )

        Log.d(TAG, "发送推荐请求: playlist=${request.playlist.size}, candidates=${request.candidates.size}, exclude=${request.exclude_playlist}, n=${request.n}")

        val response = try {
            withContext(Dispatchers.IO) {
                RecommendService.api.queryRecommendation(request)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recommendations", e)
            // 打印更详细的错误信息
            if (e is retrofit2.HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                Log.e(TAG, "HTTP Error: ${e.code()}, body: $errorBody")
            }
            Toast.makeText(requireContext(), R.string.recommendation_request_failed, Toast.LENGTH_SHORT).show()
            return emptyList()
        }

        val mapped = mapRecommendationsToLocalSongs(
            response.recommendations,
            mMusicViewModel.deviceMusic.value.orEmpty()
        )
        if (mapped.isEmpty()) {
            // 如果服务器返回了推荐但本地没有匹配，或者服务器返回空结果
            if (response.recommendations.isEmpty()) {
                // 服务器返回空结果，可能是没有可用的推荐
                if (serverIds.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.recommendation_no_upload, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.recommendation_no_local_match, Toast.LENGTH_SHORT).show()
                }
            } else {
                // 服务器返回了推荐但本地没有匹配
                Toast.makeText(requireContext(), R.string.recommendation_no_local_match, Toast.LENGTH_SHORT).show()
            }
        } else {
            AnalyticsLogger.logPredictionResult("recommendation_api", mapped.size)
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
     * 显示用户登录对话框
     */
    private fun showUserLoginDialog(onLoginSuccess: (userId: String, policy: String) -> Unit) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(
            android.R.layout.simple_list_item_1, null
        ).apply {
            // 创建一个自定义布局
        }

        // 创建自定义布局
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        // 用户ID输入框
        val userIdEditText = EditText(requireContext()).apply {
            hint = "请输入用户ID"
            setText(RecommendationRepository.getUserId())
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        layout.addView(userIdEditText)

        // 密码输入框（假装）
        val passwordEditText = EditText(requireContext()).apply {
            hint = "请输入密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        layout.addView(passwordEditText)

        // 推荐模式选择
        val policyLabel = android.widget.TextView(requireContext()).apply {
            text = "推荐模式："
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 16)
            }
        }
        layout.addView(policyLabel)

        val policyRadioGroup = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val linucbRadio = RadioButton(requireContext()).apply {
            text = "LinUCB"
            id = View.generateViewId()
        }
        val linucbPlusRadio = RadioButton(requireContext()).apply {
            text = "LinUCB+"
            id = View.generateViewId()
        }

        policyRadioGroup.addView(linucbRadio)
        policyRadioGroup.addView(linucbPlusRadio)

        // 设置默认选中
        val currentPolicy = RecommendationRepository.getRecommendationPolicy()
        when (currentPolicy) {
            "LinUCB+" -> linucbPlusRadio.isChecked = true
            else -> linucbRadio.isChecked = true
        }

        layout.addView(policyRadioGroup)

        AlertDialog.Builder(requireContext())
            .setTitle("用户登录")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val userId = userIdEditText.text.toString().trim()
                val password = passwordEditText.text.toString() // 假装使用，实际不验证
                val selectedPolicy = when {
                    linucbPlusRadio.isChecked -> "LinUCB+"
                    else -> "LinUCB"
                }

                if (userId.isBlank()) {
                    Toast.makeText(requireContext(), "用户ID不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // 保存用户ID和推荐策略
                RecommendationRepository.saveUserId(userId)
                RecommendationRepository.saveRecommendationPolicy(selectedPolicy)

                Toast.makeText(
                    requireContext(),
                    "登录成功：用户ID=$userId, 模式=$selectedPolicy",
                    Toast.LENGTH_SHORT
                ).show()

                onLoginSuccess(userId, selectedPolicy)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 选择并上传歌曲到推荐服务
     */
    private fun showUploadDialog() {
        // 先显示登录对话框
        showUserLoginDialog { userId, policy ->
            // 登录成功后，继续显示上传对话框
            showUploadSongSelectionDialog(userId, policy)
        }
    }

    /**
     * 显示歌曲选择上传对话框
     */
    private fun showUploadSongSelectionDialog(userId: String, policy: String) {
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

                            val userIdBody = userId
                                .toRequestBody("text/plain".toMediaTypeOrNull())

                            val resp = withContext(Dispatchers.IO) {
                                ArchiveService.api.uploadAudio(filePart, artistBody, userIdBody)
                            }

                            val serverSongId = resp.getServerSongId()
                            if (serverSongId == null) {
                                Log.e(TAG, "上传响应中缺少 song_id 或 song_name")
                                fail++
                                continue
                            }

                            // 如果 m.id 为空，无法保存映射
                            if (m.id == null) {
                                Log.w(TAG, "本地歌曲 ID 为空，无法保存映射")
                                fail++
                                continue
                            }

                            // 如果服务器返回了 feature_path，保存映射；如果没有，只保存 serverSongId（用于后续推荐查询）
                            if (!resp.featurePath.isNullOrBlank() || !resp.feature.isNullOrBlank()) {
                                RecommendationRepository.saveFeatureMapping(
                                    localSongId = m.id,
                                    serverSongId = serverSongId,
                                    featurePath = resp.featurePath,
                                    rawFeature = resp.feature
                                )
                            } else {
                                // 新服务器可能不返回 feature_path，但仍然需要保存 serverSongId 映射
                                // 使用 serverSongId 作为 featureUrl（临时方案）
                                val prefs = com.example.musicplayergo.GoPreferences.getPrefsInstance()
                                val features = prefs.recommendationFeatures?.toMutableList() ?: mutableListOf()
                                features.removeAll { it.localSongId == m.id }
                                features.add(com.example.musicplayergo.models.RecommendationFeature(
                                    localSongId = m.id, // 此时 m.id 已经确认不为 null
                                    serverSongId = serverSongId,
                                    featureUrl = serverSongId // 临时使用 serverSongId 作为 featureUrl
                                ))
                                prefs.recommendationFeatures = features
                            }
                            Log.d(TAG, "上传成功: $serverSongId")
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

                    if (success > 0) {
                        refreshRecommendations()
                    }
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
            val serverId = item.getServerId()
            if (serverId.isBlank()) {
                Log.w(TAG, "推荐项缺少 id 或 song_name: name=${item.name}")
                return@forEach
            }

            val mappedById = RecommendationRepository.getLocalSongIdForServer(serverId)
            val song = when {
                mappedById != null -> {
                    deviceSongs.firstOrNull { it.id == mappedById }?.also {
                        Log.d(TAG, "匹配成功（ID映射）: serverId=$serverId -> localId=${it.id}, title=${it.title}")
                    }
                }
                else -> {
                    val matched = findMusicByMetadata(
                        deviceSongs,
                        normalizeTitle(item.name),
                        normalize(item.artist)
                    )
                    if (matched != null) {
                        Log.d(TAG, "匹配成功（元数据）: serverId=$serverId, name=${item.name}, artist=${item.artist} -> localId=${matched.id}, title=${matched.title}")
                    } else {
                        Log.w(TAG, "匹配失败: serverId=$serverId, name=${item.name}, artist=${item.artist}")
                    }
                    matched
                }
            }
            song?.let { mapped.add(it) }
        }
        Log.d(TAG, "推荐匹配结果: 总数=${items.size}, 匹配成功=${mapped.size}, 匹配失败=${items.size - mapped.size}")
        return mapped.distinctBy { it.id }
    }

    private fun findMusicByMetadata(
        songs: List<Music>,
        title: String?,
        artist: String?
    ): Music? {
        if (title.isNullOrBlank()) {
            return null
        }

        val normalizedArtist = normalize(artist)

        // 首先尝试精确匹配（标题 + 艺术家）
        if (!normalizedArtist.isNullOrBlank()) {
            songs.firstOrNull { song ->
                val songTitle = normalizeTitle(song.title ?: song.displayName?.toFilenameWithoutExtension())
                val songArtist = normalize(song.artist)
                songTitle == title && songArtist == normalizedArtist
            }?.let { return it }
        }

        // 如果精确匹配失败，尝试仅匹配标题
        songs.firstOrNull { song ->
            val songTitle = normalizeTitle(song.title ?: song.displayName?.toFilenameWithoutExtension())
            songTitle == title
        }?.let { return it }

        // 如果还是失败，尝试模糊匹配（标题包含或包含标题）
        songs.firstOrNull { song ->
            val songTitle = normalizeTitle(song.title ?: song.displayName?.toFilenameWithoutExtension())
            songTitle != null && (
                songTitle.contains(title) || title.contains(songTitle)
            )
        }?.let { return it }

        return null
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
