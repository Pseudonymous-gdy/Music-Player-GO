package com.example.musicplayergo.fragments

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
import com.example.musicplayergo.GoPreferences
import com.example.musicplayergo.GoConstants
import com.example.musicplayergo.R
import com.example.musicplayergo.databinding.FragmentHistoryBinding
import com.example.musicplayergo.databinding.HistoryItemBinding
import com.example.musicplayergo.extensions.handleViewVisibility
import com.example.musicplayergo.extensions.loadWithError
import com.example.musicplayergo.extensions.toName
import com.example.musicplayergo.extensions.waitForCover
import com.example.musicplayergo.models.HistoryEntry
import com.example.musicplayergo.player.MediaPlayerHolder
import com.example.musicplayergo.ui.MediaControlInterface
import com.example.musicplayergo.ui.UIControlInterface
import com.example.musicplayergo.utils.PlaybackHistory
import com.example.musicplayergo.utils.Popups
import com.example.musicplayergo.utils.Theming
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * HistoryFragment - Playback History UI & Functionality
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 【Core Responsibilities】
 * This fragment serves as the central hub for displaying and managing user playback history.
 * It implements a complete history viewing experience with the following features:
 *
 * 1️⃣ Real-time History Stream Monitoring
 *    - Observes PlaybackHistory.historyFlow() using Kotlin Flow
 *    - Automatically updates UI when new songs are played
 *    - Lifecycle-aware collection (survives configuration changes)
 *
 * 2️⃣ Advanced Search & Filtering
 *    - Full-text search across song title, artist, album
 *    - Case-insensitive query matching
 *    - Real-time filter application as user types
 *
 * 3️⃣ Flexible Sorting Options
 *    - Default: Most recent first (descending by playedAt)
 *    - Date Added: Oldest first (ascending by playedAt)
 *    - Ascending: Alphabetical A-Z by song name
 *    - Descending: Alphabetical Z-A by song name
 *
 * 4️⃣ Rich UI Components
 *    - RecyclerView with efficient DiffUtil updates
 *    - Album cover loading with error fallback
 *    - Relative timestamp display (e.g., "2 hours ago")
 *    - Empty state handling with context-aware messages
 *    - Extended FAB showing song count & shuffle action
 *
 * 5️⃣ User Interactions
 *    - Click to play song (with full playlist context)
 *    - Long-press for context menu (add to favorites, queue, etc.)
 *    - Overflow menu for additional song options
 *    - Swipe-friendly RecyclerView layout
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 【Data Flow Architecture】
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *   PlaybackHistory
 *   (Singleton Repository)
 *        │
 *        ├─→ historyFlow(): Flow<List<HistoryEntry>>
 *        │       │
 *        │       └─→ Emits on: Song play, Clear action
 *        │
 *        ↓
 *   observeHistory()
 *   (Fragment Observer)
 *        │
 *        ├─→ collectLatest { entries → }
 *        │       │
 *        │       └─→ Updates: historyEntries, toolbar menu
 *        │
 *        ↓
 *   applyHistoryFilters()
 *   (Filter Pipeline)
 *        │
 *        ├─→ filter(matchesQuery)   ← Search filtering
 *        ├─→ sortHistory()          ← Apply sort order
 *        ├─→ submitList()           ← Update RecyclerView
 *        ├─→ updateEmptyState()     ← Show/hide empty view
 *        └─→ updateFab()            ← Update FAB badge
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 【Technical Highlights】
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * ✅ Flow-based Reactive UI
 *    - Uses repeatOnLifecycle(STARTED) for safe collection
 *    - Prevents memory leaks and crashes on config changes
 *    - Automatically pauses collection when fragment is stopped
 *
 * ✅ Efficient List Updates
 *    - ListAdapter with DiffUtil for minimal UI refreshes
 *    - Only animates actual changes (avoids full rebind)
 *    - Compares by both music.id AND playedAt timestamp
 *
 * ✅ Album Cover Loading Strategy
 *    - Async image loading with coroutine-based waitForCover()
 *    - Graceful error handling (shows fallback icon)
 *    - Respects user preference (can disable covers globally)
 *    - Adjustable alpha for visual consistency
 *
 * ✅ Search Optimization
 *    - Normalized case-insensitive matching
 *    - Multi-field search (title, artist, album, displayName)
 *    - Instant feedback (no debounce delay)
 *
 * ✅ Empty State Management
 *    - Differentiates between "no history" vs "no search results"
 *    - Context-aware messages improve user understanding
 *    - Automatically shows/hides RecyclerView
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 【UI Components Breakdown】
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 📌 Toolbar (MaterialToolbar)
 *    - Title: "Play History"
 *    - Navigation: Back button (closes fragment)
 *    - Search: Inline SearchView with real-time filtering
 *    - Overflow: Sort options (icon changes to sort indicator)
 *    - Actions: Clear history, Sleep timer
 *
 * 📌 RecyclerView (allMusicRv)
 *    - Layout: LinearLayoutManager (vertical list)
 *    - Adapter: HistoryAdapter (ListAdapter with DiffUtil)
 *    - Items: HistoryHolder (displays song + metadata)
 *    - Fixed size: true (performance optimization)
 *
 * 📌 Extended FAB (shuffleFab)
 *    - Icon: Shuffle symbol
 *    - Text: Song count badge (e.g., "42")
 *    - Action: Shuffle play all displayed history
 *    - Visibility: Hidden when list is empty
 *
 * 📌 Empty State (emptyHistory)
 *    - Message 1: "No playback history yet"
 *    - Message 2: "No results found" (during search)
 *    - Visibility: Shown only when displayedEntries is empty
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 【Usage Example】
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * // Display history fragment in MainActivity
 * supportFragmentManager.beginTransaction()
 *     .replace(R.id.container, HistoryFragment.newInstance())
 *     .addToBackStack(null)
 *     .commit()
 *
 * // User plays a song → PlaybackHistory.add() → historyFlow emits → UI updates
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */
class HistoryFragment : Fragment(), SearchView.OnQueryTextListener {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // View Binding & Interfaces
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * View binding for fragment_history.xml.
     * Nullable to prevent memory leaks (set to null in onDestroyView).
     */
    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding

    /**
     * Interface for UI-level actions (e.g., close activity, open dialogs).
     * Injected from host activity via onAttach().
     */
    private lateinit var mUIControlInterface: UIControlInterface

    /**
     * Interface for media playback control (e.g., play song, shuffle playlist).
     * Injected from host activity via onAttach().
     */
    private lateinit var mMediaControlInterface: MediaControlInterface

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Data & State Management
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * RecyclerView adapter for displaying history entries.
     * Uses ListAdapter for efficient DiffUtil-based updates.
     */
    private val historyAdapter = HistoryAdapter()

    /**
     * Raw history data from PlaybackHistory repository.
     * Updated whenever historyFlow() emits new values.
     */
    private var historyEntries: List<HistoryEntry> = emptyList()

    /**
     * Filtered and sorted history entries currently displayed in UI.
     * Result of applying search query + sort order to historyEntries.
     */
    private var displayedEntries: List<HistoryEntry> = emptyList()

    /**
     * Current search query entered by user.
     * Trimmed and case-normalized for matching.
     */
    private var historyQuery: String = ""

    /**
     * Current sort order selected by user.
     * One of: DEFAULT_SORTING, ASCENDING_SORTING, DESCENDING_SORTING, DATE_ADDED_SORTING.
     */
    private var historySorting = GoConstants.DEFAULT_SORTING

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Fragment Lifecycle Callbacks
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Called when fragment is attached to host activity.
     * Retrieves UI and media control interfaces from activity.
     *
     * @param context Host activity context
     */
    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            mUIControlInterface = activity as UIControlInterface
            mMediaControlInterface = activity as MediaControlInterface
        } catch (e: ClassCastException) {
            e.printStackTrace()
        }
    }

    /**
     * Inflates the fragment layout using ViewBinding.
     *
     * @return Root view of fragment_history.xml
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    /**
     * Called after view is created. Initializes UI components and starts observing data.
     *
     * Setup order:
     * 1. setupToolbar()    - Configure search, sort, clear actions
     * 2. setupList()       - Initialize RecyclerView with adapter
     * 3. setupFab()        - Configure shuffle FAB action
     * 4. observeHistory()  - Start collecting history flow
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar(view)
        setupList()
        setupFab()
        observeHistory()
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // UI Setup Methods
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Configures the toolbar with search, sort, and action menu.
     *
     * Features:
     * - Title: "Play History"
     * - Navigation: Back button to close fragment
     * - Search: Inline SearchView with focus-based menu hiding
     * - Overflow: Sort options (icon changes to sort indicator)
     * - Actions: Clear history (visible only when history exists)
     * - Sleep timer: Tintable icon based on timer state
     *
     * @param root Fragment root view containing toolbar
     */
    private fun setupToolbar(root: View) {
        val toolbar: MaterialToolbar? = root.findViewById(R.id.search_toolbar)
        toolbar ?: return

        // Inflate menu and set basic properties
        toolbar.inflateMenu(R.menu.menu_history)
        toolbar.title = getString(R.string.play_history)
        toolbar.overflowIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_sort)
        toolbar.setNavigationOnClickListener { mUIControlInterface.onCloseActivity() }

        // Configure sorting menu (radio button group)
        toolbar.menu.setGroupCheckable(R.id.sorting, true, true)
        toolbar.menu.findItem(R.id.default_sorting)?.isChecked = true

        // Hide clear action initially if no history
        toolbar.menu.findItem(R.id.action_clear_history)?.isVisible = historyEntries.isNotEmpty()

        // Setup search view with focus-based menu visibility
        (toolbar.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.apply {
            setOnQueryTextListener(this@HistoryFragment)
            setOnQueryTextFocusChangeListener { _, hasFocus ->
                // Hide sort and clear actions during search
                toolbar.menu.setGroupVisible(R.id.sorting, !hasFocus)
                toolbar.menu.findItem(R.id.sleeptimer).isVisible = !hasFocus
                toolbar.menu.findItem(R.id.action_clear_history).isVisible =
                    !hasFocus && historyEntries.isNotEmpty()
            }
        }

        toolbar.setOnMenuItemClickListener(::onToolbarItemSelected)

        // Tint sleep timer icon based on current state
        tintSleepTimerIcon(MediaPlayerHolder.getInstance().isSleepTimer)
    }

    /**
     * Initializes the RecyclerView with layout manager and adapter.
     *
     * Configuration:
     * - Layout: LinearLayoutManager (vertical scrolling)
     * - Adapter: HistoryAdapter (ListAdapter with DiffUtil)
     * - Fixed size: true (performance optimization when item count doesn't change adapter height)
     */
    private fun setupList() {
        binding?.allMusicRv?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
            setHasFixedSize(true)
        }
    }

    /**
     * Configures the Extended FAB for shuffle action.
     *
     * Features:
     * - Text: Displays count of displayed entries
     * - Action: Shuffles and plays all currently displayed songs
     * - Validation: Shows toast if history is empty
     * - Context: Passes ARTIST_VIEW as launch source
     */
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Data Observation & Flow Collection
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Observes PlaybackHistory.historyFlow() for real-time updates.
     *
     * Implementation Details:
     * - Uses viewLifecycleOwner.lifecycleScope for proper cancellation
     * - repeatOnLifecycle(STARTED) ensures collection only when fragment is visible
     * - collectLatest cancels previous collection if new emission arrives
     *
     * Data Flow:
     * 1. PlaybackHistory emits new List<HistoryEntry>
     * 2. historyEntries is updated
     * 3. Clear action visibility is refreshed
     * 4. applyHistoryFilters() re-applies search + sort
     *
     * Why Flow over LiveData?
     * - More flexible transformation operators (map, filter, etc.)
     * - Better handling of one-shot events vs state
     * - Native Kotlin coroutine support
     */
    private fun observeHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackHistory.historyFlow().collectLatest { entries ->
                    historyEntries = entries
                    // Show clear action only when history exists and not searching
                    binding?.searchToolbar?.menu?.findItem(R.id.action_clear_history)?.isVisible =
                        entries.isNotEmpty() && historyQuery.isBlank()
                    applyHistoryFilters()
                }
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Toolbar Menu Interaction
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Handles toolbar menu item clicks.
     *
     * Supported Actions:
     * - Sleep timer: Opens sleep timer dialog via UI interface
     * - Clear history: Clears all history and shows confirmation toast
     * - Sort options: Changes sort order and re-applies filters
     *
     * @param item MenuItem that was clicked
     * @return true if event was handled, false otherwise
     */
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

    /**
     * Maps menu item ID to sort constant.
     *
     * Sort Options:
     * - ASCENDING_SORTING: A-Z by song name
     * - DESCENDING_SORTING: Z-A by song name
     * - DATE_ADDED_SORTING: Oldest first
     * - DEFAULT_SORTING: Most recent first (default)
     *
     * @param itemId Menu item resource ID
     * @return Corresponding GoConstants sort value
     */
    private fun mapSortId(itemId: Int) = when (itemId) {
        R.id.ascending_sorting -> GoConstants.ASCENDING_SORTING
        R.id.descending_sorting -> GoConstants.DESCENDING_SORTING
        R.id.date_added_sorting -> GoConstants.DATE_ADDED_SORTING
        else -> GoConstants.DEFAULT_SORTING
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Filtering & Sorting Logic
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Applies search query and sort order to history entries, then updates UI.
     *
     * Pipeline:
     * 1. Filter: Keep only entries matching search query
     * 2. Sort: Apply current sort order (default/ascending/descending/date)
     * 3. Update: Submit sorted list to adapter (DiffUtil calculates minimal changes)
     * 4. UI Refresh: Update empty state message and FAB badge
     *
     * Why separate filter/sort steps?
     * - Allows independent query and sort changes
     * - Easier to test each transformation
     * - Clear separation of concerns
     */
    private fun applyHistoryFilters() {
        val filtered = historyEntries.filter(::matchesQuery)
        val sorted = sortHistory(filtered)
        displayedEntries = sorted
        historyAdapter.submitList(sorted)
        updateEmptyState(sorted)
        updateFab(sorted)
    }

    /**
     * Checks if history entry matches current search query.
     *
     * Search Strategy:
     * - Searches across multiple fields: title, displayName, artist, album
     * - Case-insensitive matching (normalized to lowercase)
     * - Partial match (substring search)
     * - Empty query matches all entries
     *
     * Example:
     * Query "rock" matches:
     * - Title: "Rock Anthem"
     * - Artist: "The Rockers"
     * - Album: "Classic Rock"
     *
     * @param entry History entry to test
     * @return true if entry matches query or query is blank
     */
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

    /**
     * Sorts history entries based on current sort setting.
     *
     * Sort Modes:
     * - ASCENDING_SORTING: A → Z by song name (case-insensitive)
     * - DESCENDING_SORTING: Z → A by song name (case-insensitive)
     * - DATE_ADDED_SORTING: Oldest → Newest by playedAt timestamp
     * - DEFAULT_SORTING: Newest → Oldest by playedAt timestamp (most recent first)
     *
     * Why normalize to lowercase?
     * - Ensures consistent sort order regardless of capitalization
     * - "Apple" and "apple" sort together
     *
     * @param list Filtered list of history entries
     * @return Sorted list according to current sort mode
     */
    private fun sortHistory(list: List<HistoryEntry>): List<HistoryEntry> = when (historySorting) {
        GoConstants.ASCENDING_SORTING -> list.sortedBy { it.music.toName()?.lowercase(Locale.getDefault()) }
        GoConstants.DESCENDING_SORTING -> list.sortedByDescending { it.music.toName()?.lowercase(Locale.getDefault()) }
        GoConstants.DATE_ADDED_SORTING -> list.sortedBy { it.playedAt }
        else -> list.sortedByDescending { it.playedAt }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // UI State Updates
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Updates empty state view based on current list state.
     *
     * Empty State Logic:
     * - If sorted list is empty:
     *   - Show emptyHistory TextView
     *   - Hide RecyclerView
     *   - Display context-aware message:
     *     • "No playback history yet" (if historyEntries is also empty)
     *     • "No results found" (if searching and no matches)
     * - If sorted list has items:
     *   - Show RecyclerView
     *   - Hide emptyHistory TextView
     *
     * Why two messages?
     * - Helps user understand why list is empty
     * - "No history" → User hasn't played songs yet
     * - "No results" → Search query is too restrictive
     *
     * @param sorted Currently displayed (filtered + sorted) entries
     */
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

    /**
     * Updates Extended FAB visibility and badge count.
     *
     * FAB Behavior:
     * - Visible: Only when sorted list is not empty
     * - Badge: Shows count of displayed songs (e.g., "42")
     * - Action: Shuffle plays all displayed songs
     *
     * Why hide when empty?
     * - Prevents confusion (can't shuffle empty list)
     * - Cleaner UI when no songs to play
     *
     * @param sorted Currently displayed (filtered + sorted) entries
     */
    private fun updateFab(sorted: List<HistoryEntry>) {
        binding?.shuffleFab?.apply {
            isVisible = sorted.isNotEmpty()
            text = sorted.size.toString()
        }
    }

    /**
     * Updates sleep timer icon tint in toolbar.
     * Called externally by MainActivity when sleep timer state changes.
     *
     * @param enabled Whether sleep timer is currently active
     */
    fun tintSleepTimerIcon(enabled: Boolean) {
        binding?.searchToolbar?.let { Theming.tintSleepTimerMenuItem(it, enabled) }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // SearchView Callbacks
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Called when search query text changes.
     *
     * Behavior:
     * - Trims whitespace from query
     * - Updates historyQuery state
     * - Triggers immediate filter re-application (no debounce)
     * - Hides clear action during search
     *
     * Why no debounce?
     * - List is typically small (few hundred entries max)
     * - Filtering is fast (simple string contains check)
     * - Instant feedback improves UX
     *
     * @param newText Current search query text (can be null)
     * @return true to indicate query was handled
     */
    override fun onQueryTextChange(newText: String?): Boolean {
        historyQuery = newText?.trim().orEmpty()
        applyHistoryFilters()
        binding?.searchToolbar?.menu?.findItem(R.id.action_clear_history)?.isVisible =
            historyEntries.isNotEmpty() && historyQuery.isBlank()
        return true
    }

    /**
     * Called when user submits search query (presses Enter/Search button).
     * Not used in this implementation (filtering happens on text change).
     *
     * @param query Submitted query text
     * @return false to let system handle event (e.g., close keyboard)
     */
    override fun onQueryTextSubmit(query: String?): Boolean = false

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Fragment Cleanup
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Cleans up view resources to prevent memory leaks.
     *
     * Cleanup Steps:
     * 1. Clear RecyclerView adapter (prevents leaked context)
     * 2. Nullify binding (releases view references)
     * 3. Call super.onDestroyView()
     *
     * Why clear adapter?
     * - RecyclerView.Adapter holds reference to fragment (inner class)
     * - If not cleared, adapter → fragment → activity leak
     */
    override fun onDestroyView() {
        binding?.allMusicRv?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        /**
         * Factory method for creating new HistoryFragment instances.
         * Follows fragment best practice (no-arg constructor + factory method).
         *
         * @return New HistoryFragment instance
         */
        @JvmStatic
        fun newInstance() = HistoryFragment()
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // RecyclerView Adapter & ViewHolder
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * RecyclerView adapter for displaying history entries with efficient updates.
     *
     * Key Features:
     * - Extends ListAdapter (automatic DiffUtil calculation)
     * - Inner class (has access to fragment methods and interfaces)
     * - Handles item click, long-click, and overflow menu interactions
     *
     * Why ListAdapter over RecyclerView.Adapter?
     * - Automatic background diff calculation
     * - Built-in animations for insertions/removals/moves
     * - Less boilerplate (no manual notifyDataSetChanged)
     */
    private inner class HistoryAdapter :
        ListAdapter<HistoryEntry, HistoryAdapter.HistoryHolder>(HistoryDiffCallback) {

        /**
         * Creates ViewHolder with inflated item layout.
         *
         * @param parent RecyclerView parent
         * @param viewType Item view type (unused, all items same type)
         * @return New HistoryHolder instance
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder {
            val binding = HistoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return HistoryHolder(binding)
        }

        /**
         * Binds history data to ViewHolder.
         *
         * Uses absoluteAdapterPosition instead of position:
         * - position can be stale during async updates
         * - absoluteAdapterPosition is always current
         *
         * @param holder ViewHolder to bind
         * @param position Item position in adapter
         */
        override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
            holder.bind(getItem(holder.absoluteAdapterPosition))
        }

        /**
         * ViewHolder for individual history items.
         *
         * Responsibilities:
         * - Display song metadata (title, artist, album)
         * - Show relative timestamp (e.g., "2 hours ago")
         * - Load album cover asynchronously
         * - Handle click interactions (play, overflow menu)
         */
        inner class HistoryHolder(private val itemBinding: HistoryItemBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            /**
             * Binds history entry data to item views.
             *
             * Data Binding:
             * - historyTitle: Song name (title or displayName)
             * - historySubtitle: "Artist • Album" format
             * - historyPlayedAt: Relative time (e.g., "2 hours ago")
             * - historyCover: Album artwork (if enabled in preferences)
             *
             * Interactions:
             * - Click: Play song with full history context
             * - Long-click: Show context menu popup
             * - Overflow: Show additional song options
             *
             * @param entry History entry containing song + timestamp
             */
            fun bind(entry: HistoryEntry) {
                val music = entry.music
                with(itemBinding) {
                    // Display song title and subtitle
                    historyTitle.text = music.toName()
                    historySubtitle.text = getString(
                        R.string.artist_and_album,
                        music.artist,
                        music.album
                    )

                    // Display relative timestamp (e.g., "2 hours ago", "Yesterday")
                    historyPlayedAt.text = DateUtils.getRelativeTimeSpanString(
                        entry.playedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE
                    )

                    // Album cover handling (respects user preference)
                    val showCovers = GoPreferences.getPrefsInstance().isCovers
                    historyCover.handleViewVisibility(show = showCovers)
                    if (showCovers) {
                        // Set alpha for visual consistency
                        historyCover.background.alpha = Theming.getAlbumCoverAlpha(requireContext())

                        // Async load album cover with fallback
                        music.albumId?.waitForCover(requireContext()) { bmp, error ->
                            historyCover.loadWithError(bmp, error, R.drawable.ic_music_note_cover_alt)
                        } ?: historyCover.loadWithError(
                            null,
                            true,
                            R.drawable.ic_music_note_cover_alt
                        )
                    }

                    // Click to play song
                    root.setOnClickListener {
                        // Clear FM station if active
                        with(MediaPlayerHolder.getInstance()) {
                            if (isCurrentSongFM) currentSongFM = null
                        }
                        // Play song with full displayed history as playlist context
                        mMediaControlInterface.onSongSelected(
                            music,
                            displayedEntries.map { it.music },
                            music.launchedBy
                        )
                    }

                    // Overflow menu button
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

                    // Long-click for context menu
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

    /**
     * DiffUtil callback for efficient RecyclerView updates.
     *
     * Comparison Strategy:
     * - areItemsTheSame: Compares music.id AND playedAt timestamp
     *   (same song played at different times = different items)
     * - areContentsTheSame: Full equality check on HistoryEntry
     *
     * Why compare both id and timestamp?
     * - User can play same song multiple times
     * - Each playback creates separate history entry
     * - Need to differentiate "Song A at 2pm" vs "Song A at 3pm"
     *
     * DiffUtil Benefits:
     * - Only rebinds changed items
     * - Automatic animations for list changes
     * - Better performance than notifyDataSetChanged()
     */
    private object HistoryDiffCallback : DiffUtil.ItemCallback<HistoryEntry>() {
        override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean =
            oldItem.music.id == newItem.music.id && oldItem.playedAt == newItem.playedAt

        override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean =
            oldItem == newItem
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Summary
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //
    // This fragment demonstrates a complete playback history feature, including:
    //
    // ✅ Real-time data observation with Kotlin Flow
    // ✅ Efficient list updates with ListAdapter + DiffUtil
    // ✅ Advanced search and sorting capabilities
    // ✅ Album cover loading with error handling
    // ✅ Context-aware empty states
    // ✅ Rich user interactions (play, shuffle, clear)
    // ✅ Proper lifecycle management and memory cleanup
    //
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
}
