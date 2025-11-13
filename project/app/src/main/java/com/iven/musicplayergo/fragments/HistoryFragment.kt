package com.iven.musicplayergo.fragments

import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.iven.musicplayergo.GoPreferences
import com.iven.musicplayergo.GoConstants
import com.iven.musicplayergo.R
import com.iven.musicplayergo.databinding.FragmentHistoryBinding
import com.iven.musicplayergo.databinding.HistoryItemBinding
import com.iven.musicplayergo.extensions.handleViewVisibility
import com.iven.musicplayergo.extensions.loadWithError
import com.iven.musicplayergo.extensions.toName
import com.iven.musicplayergo.extensions.waitForCover
import com.iven.musicplayergo.models.HistoryEntry
import com.iven.musicplayergo.player.MediaPlayerHolder
import com.iven.musicplayergo.ui.MediaControlInterface
import com.iven.musicplayergo.ui.UIControlInterface
import com.iven.musicplayergo.utils.PlaybackHistory
import com.iven.musicplayergo.utils.Popups
import com.iven.musicplayergo.utils.Theming
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HistoryFragment : Fragment(), SearchView.OnQueryTextListener {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding

    private lateinit var mUIControlInterface: UIControlInterface
    private lateinit var mMediaControlInterface: MediaControlInterface

    private val historyAdapter = HistoryAdapter()
    private var historyEntries: List<HistoryEntry> = emptyList()
    private var displayedEntries: List<HistoryEntry> = emptyList()
    private var historyQuery: String = ""
    private var historySorting = GoConstants.DEFAULT_SORTING

    override fun onAttach(context: Context) {
        super.onAttach(context)
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
        observeHistory()
    }

    private fun setupToolbar(root: View) {
        val toolbar: MaterialToolbar? = root.findViewById(R.id.search_toolbar)
        toolbar ?: return

        toolbar.inflateMenu(R.menu.menu_history)
        toolbar.title = getString(R.string.play_history)
        toolbar.overflowIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_sort)
        toolbar.setNavigationOnClickListener { mUIControlInterface.onCloseActivity() }
        toolbar.menu.setGroupCheckable(R.id.sorting, true, true)
        toolbar.menu.findItem(R.id.default_sorting)?.isChecked = true
        toolbar.menu.findItem(R.id.action_clear_history)?.isVisible = historyEntries.isNotEmpty()

        (toolbar.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.apply {
            setOnQueryTextListener(this@HistoryFragment)
            setOnQueryTextFocusChangeListener { _, hasFocus ->
                toolbar.menu.setGroupVisible(R.id.sorting, !hasFocus)
                toolbar.menu.findItem(R.id.sleeptimer).isVisible = !hasFocus
                toolbar.menu.findItem(R.id.action_clear_history).isVisible =
                    !hasFocus && historyEntries.isNotEmpty()
            }
        }

        toolbar.setOnMenuItemClickListener(::onToolbarItemSelected)

        tintSleepTimerIcon(MediaPlayerHolder.getInstance().isSleepTimer)
    }

    private fun setupList() {
        binding?.allMusicRv?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupFab() {
        val fab: ExtendedFloatingActionButton? = binding?.shuffleFab
        fab?.setOnClickListener {
            if (displayedEntries.isEmpty()) {
                Toast.makeText(requireContext(), R.string.history_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mMediaControlInterface.onSongsShuffled(
                displayedEntries.map { it.music },
                GoConstants.ARTIST_VIEW
            )
        }
    }

    private fun observeHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackHistory.historyFlow().collectLatest { entries ->
                    historyEntries = entries
                    binding?.searchToolbar?.menu?.findItem(R.id.action_clear_history)?.isVisible =
                        entries.isNotEmpty() && historyQuery.isBlank()
                    applyHistoryFilters()
                }
            }
        }
    }

    private fun onToolbarItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.sleeptimer -> {
                mUIControlInterface.onOpenSleepTimerDialog()
                true
            }
            R.id.action_clear_history -> {
                PlaybackHistory.clear()
                Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.default_sorting,
            R.id.date_added_sorting,
            R.id.ascending_sorting,
            R.id.descending_sorting -> {
                historySorting = mapSortId(item.itemId)
                item.isChecked = true
                applyHistoryFilters()
                true
            }
            else -> false
        }
    }

    private fun mapSortId(itemId: Int) = when (itemId) {
        R.id.ascending_sorting -> GoConstants.ASCENDING_SORTING
        R.id.descending_sorting -> GoConstants.DESCENDING_SORTING
        R.id.date_added_sorting -> GoConstants.DATE_ADDED_SORTING
        else -> GoConstants.DEFAULT_SORTING
    }

    private fun applyHistoryFilters() {
        val filtered = historyEntries.filter(::matchesQuery)
        val sorted = sortHistory(filtered)
        displayedEntries = sorted
        historyAdapter.submitList(sorted)
        updateEmptyState(sorted)
        updateFab(sorted)
    }

    private fun matchesQuery(entry: HistoryEntry): Boolean {
        if (historyQuery.isBlank()) return true
        val query = historyQuery.lowercase(Locale.getDefault())
        val music = entry.music
        return listOf(
            music.title,
            music.displayName,
            music.artist,
            music.album
        ).any { field ->
            field?.lowercase(Locale.getDefault())?.contains(query) == true
        }
    }

    private fun sortHistory(list: List<HistoryEntry>): List<HistoryEntry> = when (historySorting) {
        GoConstants.ASCENDING_SORTING -> list.sortedBy { it.music.toName()?.lowercase(Locale.getDefault()) }
        GoConstants.DESCENDING_SORTING -> list.sortedByDescending { it.music.toName()?.lowercase(Locale.getDefault()) }
        GoConstants.DATE_ADDED_SORTING -> list.sortedBy { it.playedAt }
        else -> list.sortedByDescending { it.playedAt }
    }

    private fun updateEmptyState(sorted: List<HistoryEntry>) {
        val isEmpty = sorted.isEmpty()
        binding?.allMusicRv?.isVisible = !isEmpty
        binding?.emptyHistory?.apply {
            isVisible = isEmpty
            text = if (historyEntries.isEmpty()) {
                getString(R.string.history_empty)
            } else {
                getString(R.string.history_no_results)
            }
        }
    }

    private fun updateFab(sorted: List<HistoryEntry>) {
        binding?.shuffleFab?.apply {
            isVisible = sorted.isNotEmpty()
            text = sorted.size.toString()
        }
    }

    /** 提供给 MainActivity 更新睡眠图标用（MainActivity.updateSleepTimerIcon 会调用） */
    fun tintSleepTimerIcon(enabled: Boolean) {
        binding?.searchToolbar?.let { Theming.tintSleepTimerMenuItem(it, enabled) }
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        historyQuery = newText?.trim().orEmpty()
        applyHistoryFilters()
        binding?.searchToolbar?.menu?.findItem(R.id.action_clear_history)?.isVisible =
            historyEntries.isNotEmpty() && historyQuery.isBlank()
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean = false

    override fun onDestroyView() {
        binding?.allMusicRv?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        @JvmStatic
        fun newInstance() = HistoryFragment()
    }

    private inner class HistoryAdapter :
        ListAdapter<HistoryEntry, HistoryAdapter.HistoryHolder>(HistoryDiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder {
            val binding = HistoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return HistoryHolder(binding)
        }

        override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
            holder.bind(getItem(holder.absoluteAdapterPosition))
        }

        inner class HistoryHolder(private val itemBinding: HistoryItemBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(entry: HistoryEntry) {
                val music = entry.music
                with(itemBinding) {
                    historyTitle.text = music.toName()
                    historySubtitle.text = getString(
                        R.string.artist_and_album,
                        music.artist,
                        music.album
                    )
                    historyPlayedAt.text = DateUtils.getRelativeTimeSpanString(
                        entry.playedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE
                    )

                    val showCovers = GoPreferences.getPrefsInstance().isCovers
                    historyCover.handleViewVisibility(show = showCovers)
                    if (showCovers) {
                        historyCover.background.alpha = Theming.getAlbumCoverAlpha(requireContext())
                        music.albumId?.waitForCover(requireContext()) { bmp, error ->
                            historyCover.loadWithError(bmp, error, R.drawable.ic_music_note_cover_alt)
                        } ?: historyCover.loadWithError(
                            null,
                            true,
                            R.drawable.ic_music_note_cover_alt
                        )
                    }

                    root.setOnClickListener {
                        with(MediaPlayerHolder.getInstance()) {
                            if (isCurrentSongFM) currentSongFM = null
                        }
                        mMediaControlInterface.onSongSelected(
                            music,
                            displayedEntries.map { it.music },
                            music.launchedBy
                        )
                    }

                    val overflowAnchor = historyOverflow
                    overflowAnchor.isVisible = true
                    overflowAnchor.setOnClickListener {
                        Popups.showPopupForSongs(
                            requireActivity(),
                            overflowAnchor,
                            music,
                            music.launchedBy
                        )
                    }

                    root.setOnLongClickListener {
                        Popups.showPopupForSongs(
                            requireActivity(),
                            it,
                            music,
                            music.launchedBy
                        )
                        true
                    }
                }
            }
        }
    }

    private object HistoryDiffCallback : DiffUtil.ItemCallback<HistoryEntry>() {
        override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean =
            oldItem.music.id == newItem.music.id && oldItem.playedAt == newItem.playedAt

        override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean =
            oldItem == newItem
    }
}
