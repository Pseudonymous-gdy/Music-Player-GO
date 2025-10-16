package com.iven.musicplayergo.fragments

import android.content.Context
import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.iven.musicplayergo.R
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.iven.musicplayergo.databinding.FragmentHistoryBinding
import com.iven.musicplayergo.player.MediaPlayerHolder
import com.iven.musicplayergo.ui.MediaControlInterface
import com.iven.musicplayergo.ui.UIControlInterface
import com.iven.musicplayergo.utils.Theming

/**
 * History 页面：遵循与 AllMusic 相同的 UI 结构（toolbar + RecyclerView + FAB），
 * 先把界面与菜单/睡眠定时器接线好，数据源后续再接入。
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding

    private lateinit var mUIControlInterface: UIControlInterface
    private lateinit var mMediaControlInterface: MediaControlInterface

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Activity 必须实现 UIControlInterface / MediaControlInterface（与你项目其它 Fragment 一致）
        try {
            mUIControlInterface = activity as UIControlInterface
            mMediaControlInterface = activity as MediaControlInterface
        } catch (e: ClassCastException) {
            e.printStackTrace()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar(view)
        setupList()
        setupFab()
    }

    private fun setupToolbar(root: View) {
        // 与 fragment_history.xml 约定：优先 search_toolbar，兜底 toolbar
        val tb: MaterialToolbar? = root.findViewById(R.id.search_toolbar)
        tb ?: return

        // 菜单与样式（保持与 AllMusic 风格一致；menu_history 若无，可先用 menu_music_search 也行）
        tb.inflateMenu(R.menu.menu_history)

        // Title and search default state to match other screens
        tb.title = getString(R.string.play_history)
        tb.menu.findItem(R.id.action_search)?.let { item ->
            val sv = item.actionView as? SearchView
            if (item.isActionViewExpanded) item.collapseActionView()
            sv?.setQuery("", false)
            sv?.clearFocus()
        }

        tb.overflowIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_sort)
        tb.setNavigationOnClickListener { mUIControlInterface.onCloseActivity() }

        // 搜索聚焦时隐藏排序/睡眠按钮（与 AllMusic 相同交互）
        (tb.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.apply {
            setOnQueryTextFocusChangeListener { _, hasFocus ->
                tb.menu.setGroupVisible(R.id.sorting, !hasFocus)
                tb.menu.findItem(R.id.sleeptimer).isVisible = !hasFocus
            }
        }

        // 这里只接线“睡眠定时器”按钮，排序项作为占位
        tb.setOnMenuItemClickListener {
            if (it.itemId != R.id.action_search) {
                mUIControlInterface.onOpenSleepTimerDialog()
            }
            true
        }

        // 首次进入时，依据当前 SleepTimer 状态着色
        tintSleepTimerIcon(MediaPlayerHolder.getInstance().isSleepTimer)
    }

    private fun setupList() {
        // 先把 RecyclerView 初始化好；适配器/数据后续接入“最近播放”列表即可
        binding?.allMusicRv?.adapter = null
    }

    private fun setupFab() {
        val fab: ExtendedFloatingActionButton? = binding?.root?.findViewById(R.id.shuffle_fab)
        fab?.setOnClickListener {
            // 如果未来要支持“从历史随机播放”，在此接入数据源：
            // mMediaControlInterface.onSongsShuffled(historySongs, GoConstants.ARTIST_VIEW)
        }
    }

    /** 提供给 MainActivity 更新睡眠图标用（MainActivity.updateSleepTimerIcon 会调用） */
    fun tintSleepTimerIcon(enabled: Boolean) {
        val root = view ?: return
        val tb: MaterialToolbar? = root.findViewById(R.id.search_toolbar)
        tb?.let { Theming.tintSleepTimerMenuItem(it, enabled) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance() = HistoryFragment()
    }
}
