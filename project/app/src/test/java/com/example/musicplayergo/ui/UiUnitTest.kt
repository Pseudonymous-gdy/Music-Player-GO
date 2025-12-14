package com.example.musicplayergo.ui

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SingleClickHelperTest {

    private val lastClickField = SingleClickHelper::class.java.getDeclaredField("sLastClickTime").apply {
        isAccessible = true
    }
    private val minIntervalMillis = (SingleClickHelper::class.java.getDeclaredField("MIN_CLICK_INTERVAL").apply {
        isAccessible = true
    }.get(null) as Int).toLong()

    @Before
    fun resetState() {
        setLastClickTime(0L)
    }

    @Test
    fun `first click is not blocked and stores timestamp`() {
        val blocked = SingleClickHelper.isBlockingClick()

        assertFalse(blocked)
        assertTrue(getLastClickTime() > 0)
    }

    @Test
    fun `rapid second click is blocked to prevent double tapping`() {
        val now = System.currentTimeMillis()
        setLastClickTime(now)

        val blocked = SingleClickHelper.isBlockingClick()

        assertTrue(blocked)
        assertEquals("Last click time should not change on blocked clicks", now, getLastClickTime())
    }

    @Test
    fun `click after interval is allowed and updates last click time`() {
        val previous = System.currentTimeMillis() - minIntervalMillis - 5
        setLastClickTime(previous)

        val beforeCall = System.currentTimeMillis()
        val blocked = SingleClickHelper.isBlockingClick()

        assertFalse(blocked)
        assertTrue("Last click time should be refreshed after an allowed click", getLastClickTime() >= beforeCall)
    }

    private fun setLastClickTime(value: Long) {
        lastClickField.setLong(null, value)
    }

    private fun getLastClickTime(): Long = lastClickField.getLong(null)
}

@RunWith(RobolectricTestRunner::class)
class ItemTouchCallbackTest {

    @Test
    fun `onItemMove rearranges collection and notifies adapter`() {
        val items = mutableListOf("A", "B", "C")
        val observer = MoveRecordingObserver()
        val adapter = RecordingAdapter().apply {
            registerAdapterDataObserver(observer)
        }
        val callback = ItemTouchCallback(items, isActiveTabs = false)

        invokeOnItemMove(callback, 0, 2, adapter)

        assertEquals(listOf("B", "C", "A"), items)
        assertEquals(0 to 2, observer.lastMove)
    }

    @Test
    fun `onItemMove ignores attempts to move pinned last item on active tabs`() {
        val items = mutableListOf("A", "B", "C")
        val observer = MoveRecordingObserver()
        val adapter = RecordingAdapter().apply {
            registerAdapterDataObserver(observer)
        }
        val callback = ItemTouchCallback(items, isActiveTabs = true)

        invokeOnItemMove(callback, 0, 2, adapter)

        assertEquals(listOf("A", "B", "C"), items)
        assertNull("No adapter notification expected when move is blocked", observer.lastMove)
    }

    @Test
    fun `onItemMove moves items upward inside active tabs`() {
        val items = mutableListOf("A", "B", "C", "D")
        val observer = MoveRecordingObserver()
        val adapter = RecordingAdapter().apply {
            registerAdapterDataObserver(observer)
        }
        val callback = ItemTouchCallback(items, isActiveTabs = true)

        invokeOnItemMove(callback, 2, 0, adapter)

        assertEquals(listOf("C", "A", "B", "D"), items)
        assertEquals(2 to 0, observer.lastMove)
    }

    private fun invokeOnItemMove(
        callback: ItemTouchCallback<String>,
        from: Int,
        to: Int,
        adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
    ): Boolean {
        val method = ItemTouchCallback::class.java.getDeclaredMethod(
            "onItemMove",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            RecyclerView.Adapter::class.java
        )
        method.isAccessible = true
        return method.invoke(callback, from, to, adapter) as Boolean
    }

    private class RecordingAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            throw UnsupportedOperationException("ViewHolder creation not needed for this test")
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

        override fun getItemCount(): Int = 0
    }

    private class MoveRecordingObserver : RecyclerView.AdapterDataObserver() {
        var lastMove: Pair<Int, Int>? = null

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            lastMove = fromPosition to toPosition
        }
    }
}
