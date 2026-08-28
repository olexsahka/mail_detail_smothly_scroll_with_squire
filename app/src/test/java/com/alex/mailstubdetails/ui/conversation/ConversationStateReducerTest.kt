package com.alex.mailstubdetails.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateReducerTest {

    // ─── Expand ──────────────────────────────────────────────────────────

    @Test
    fun `expanding an unloaded message adds it to expanded and pending and requests a load`() {
        val r = ConversationStateReducer.toggle(
            msgId = "m1",
            expanded = emptySet(),
            loaded = emptySet(),
            pending = emptySet()
        )
        assertEquals(setOf("m1"), r.expanded)
        assertEquals(setOf("m1"), r.pending)
        assertTrue(r.shouldStartLoad)
    }

    @Test
    fun `expanding an already-loaded message does not request another load`() {
        val r = ConversationStateReducer.toggle(
            msgId = "m1",
            expanded = emptySet(),
            loaded = setOf("m1"),
            pending = emptySet()
        )
        assertEquals(setOf("m1"), r.expanded)
        assertTrue("pending must stay empty when body is already loaded", r.pending.isEmpty())
        assertFalse(r.shouldStartLoad)
    }

    @Test
    fun `expanding while a load is already in-flight does not duplicate the load`() {
        val r = ConversationStateReducer.toggle(
            msgId = "m1",
            expanded = emptySet(),
            loaded = emptySet(),
            pending = setOf("m1")
        )
        assertEquals(setOf("m1"), r.expanded)
        assertEquals(setOf("m1"), r.pending)
        assertFalse("duplicate load must be suppressed", r.shouldStartLoad)
    }

    @Test
    fun `expanding does not disturb other expanded messages`() {
        val r = ConversationStateReducer.toggle(
            msgId = "m3",
            expanded = setOf("m1", "m2"),
            loaded = setOf("m1"),
            pending = emptySet()
        )
        assertEquals(setOf("m1", "m2", "m3"), r.expanded)
    }

    // ─── Collapse ────────────────────────────────────────────────────────

    @Test
    fun `collapsing removes from expanded and never requests a load`() {
        val r = ConversationStateReducer.toggle(
            msgId = "m1",
            expanded = setOf("m1", "m2"),
            loaded = setOf("m1"),
            pending = emptySet()
        )
        assertEquals(setOf("m2"), r.expanded)
        assertFalse(r.shouldStartLoad)
    }

    @Test
    fun `collapsing preserves loaded set so re-expanding is instant`() {
        val expanded = setOf("m1")
        val loaded = setOf("m1")

        val collapsed = ConversationStateReducer.toggle("m1", expanded, loaded, emptySet())
        assertFalse("m1" in collapsed.expanded)

        val reExpanded = ConversationStateReducer.toggle(
            msgId = "m1",
            expanded = collapsed.expanded,
            loaded = loaded,   // caller keeps loaded set intact across collapse
            pending = collapsed.pending
        )
        assertFalse(
            "re-expanding a cached message must not fire another fake-load",
            reExpanded.shouldStartLoad
        )
    }

    @Test
    fun `collapsing an in-flight message keeps the load running`() {
        val r = ConversationStateReducer.toggle(
            msgId = "m1",
            expanded = setOf("m1"),
            loaded = emptySet(),
            pending = setOf("m1")
        )
        assertFalse("m1" in r.expanded)
        assertTrue(
            "the in-flight load must survive collapse — otherwise a fast " +
                "collapse + expand would restart the fake fetch each time",
            "m1" in r.pending
        )
    }

    // ─── markLoaded ──────────────────────────────────────────────────────

    @Test
    fun `markLoaded moves the id from pending to loaded`() {
        val r = ConversationStateReducer.markLoaded(
            msgId = "m1",
            loaded = emptySet(),
            pending = setOf("m1")
        )
        assertEquals(setOf("m1"), r.loaded)
        assertTrue(r.pending.isEmpty())
    }

    @Test
    fun `markLoaded is idempotent — replaying it does not break state`() {
        val once = ConversationStateReducer.markLoaded("m1", emptySet(), setOf("m1"))
        val twice = ConversationStateReducer.markLoaded("m1", once.loaded, once.pending)
        assertEquals(setOf("m1"), twice.loaded)
        assertTrue(twice.pending.isEmpty())
    }

    @Test
    fun `markLoaded on an unknown pending id still marks it loaded`() {
        // Defensive: if collapse cleared pending prematurely, a late-firing
        // coroutine should still register the load so re-expand is instant.
        val r = ConversationStateReducer.markLoaded(
            msgId = "m1",
            loaded = emptySet(),
            pending = emptySet()
        )
        assertEquals(setOf("m1"), r.loaded)
        assertTrue(r.pending.isEmpty())
    }

    // ─── End-to-end scripted sequence ────────────────────────────────────

    @Test
    fun `full cycle - expand load collapse re-expand does not refetch`() {
        var expanded: Set<String> = emptySet()
        var loaded: Set<String> = emptySet()
        var pending: Set<String> = emptySet()

        // 1. Expand → load requested.
        val t1 = ConversationStateReducer.toggle("m1", expanded, loaded, pending)
        expanded = t1.expanded; pending = t1.pending
        assertTrue(t1.shouldStartLoad)

        // 2. Load completes.
        val done = ConversationStateReducer.markLoaded("m1", loaded, pending)
        loaded = done.loaded; pending = done.pending
        assertTrue("m1" in loaded)
        assertTrue(pending.isEmpty())

        // 3. Collapse.
        val t2 = ConversationStateReducer.toggle("m1", expanded, loaded, pending)
        expanded = t2.expanded
        assertFalse("m1" in expanded)
        assertTrue("loaded persists after collapse", "m1" in loaded)

        // 4. Re-expand — no reload.
        val t3 = ConversationStateReducer.toggle("m1", expanded, loaded, pending)
        expanded = t3.expanded
        assertTrue("m1" in expanded)
        assertFalse("body is cached, no fake-load should fire", t3.shouldStartLoad)
    }
}
