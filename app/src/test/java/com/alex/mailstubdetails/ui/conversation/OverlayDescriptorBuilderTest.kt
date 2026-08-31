package com.alex.mailstubdetails.ui.conversation

import com.alex.mailstubdetails.model.EmailMessage
import com.alex.mailstubdetails.model.EmailThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayDescriptorBuilderTest {

    private fun msg(id: String) = EmailMessage(
        id = id,
        fromName = "n",
        fromEmail = "$id@e.com",
        toList = listOf("me@e.com"),
        subject = "s",
        date = "d",
        htmlBody = "<p>$id</p>",
        plainPreview = "p"
    )

    private fun thread(vararg ids: String) =
        EmailThread(id = "t", subject = "s", messages = ids.map(::msg))

    // ─── Shape ───────────────────────────────────────────────────────────

    @Test
    fun `always starts with app-bar overlay`() {
        val out = OverlayDescriptorBuilder.build(thread("a"), emptySet(), emptySet())
        assertEquals(OverlayDescriptorBuilder.APP_BAR_ID, out.first().id)
        assertEquals(OverlayKind.APP_BAR, out.first().kind)
    }

    @Test
    fun `empty thread produces only the app-bar overlay`() {
        val empty = EmailThread(id = "t", subject = "s", messages = emptyList())
        val out = OverlayDescriptorBuilder.build(empty, emptySet(), emptySet())
        assertEquals(1, out.size)
    }

    @Test
    fun `every message gets a header overlay regardless of expansion`() {
        val out = OverlayDescriptorBuilder.build(thread("a", "b", "c"), emptySet(), emptySet())
        val headers = out.filter { it.kind == OverlayKind.MESSAGE_HEADER }
        assertEquals(listOf("a", "b", "c"), headers.map { it.msgId })
    }

    // ─── Body loader visibility ─────────────────────────────────────────

    @Test
    fun `body loader appears only when expanded and not loaded`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a", "b", "c"),
            expandedIds = setOf("a", "b"),
            loadedIds = setOf("b")
        )
        val bodyIds = out.filter { it.kind == OverlayKind.MESSAGE_BODY_LOADER }.map { it.msgId }
        // a is expanded and NOT loaded → loader
        // b is expanded but loaded → no loader
        // c is not expanded → no loader
        assertEquals(listOf("a"), bodyIds)
    }

    @Test
    fun `body loader disappears once the message is loaded`() {
        val expanded = setOf("a")
        val before = OverlayDescriptorBuilder.build(thread("a"), expanded, emptySet())
        val after = OverlayDescriptorBuilder.build(thread("a"), expanded, setOf("a"))

        assertTrue(before.any { it.kind == OverlayKind.MESSAGE_BODY_LOADER })
        assertTrue(after.none { it.kind == OverlayKind.MESSAGE_BODY_LOADER })
    }

    // ─── Footer visibility ──────────────────────────────────────────────

    @Test
    fun `footer overlay appears only for expanded messages`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a", "b"),
            expandedIds = setOf("b"),
            loadedIds = emptySet()
        )
        val footerIds = out.filter { it.kind == OverlayKind.MESSAGE_FOOTER }.map { it.msgId }
        assertEquals(listOf("b"), footerIds)
    }

    @Test
    fun `footer overlay is present for loaded expanded messages`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a"),
            expandedIds = setOf("a"),
            loadedIds = setOf("a")
        )
        assertTrue(out.any { it.kind == OverlayKind.MESSAGE_FOOTER })
    }

    // ─── Ordering ───────────────────────────────────────────────────────

    @Test
    fun `expanded loading message emits header then loader then footer`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a"),
            expandedIds = setOf("a"),
            loadedIds = emptySet()
        )
        // out = [app-bar, header:a, body:a, footer:a]
        assertEquals(4, out.size)
        assertEquals(OverlayKind.APP_BAR, out[0].kind)
        assertEquals(OverlayKind.MESSAGE_HEADER, out[1].kind)
        assertEquals(OverlayKind.MESSAGE_BODY_LOADER, out[2].kind)
        assertEquals(OverlayKind.MESSAGE_FOOTER, out[3].kind)
    }

    @Test
    fun `expanded loaded message skips the loader`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a"),
            expandedIds = setOf("a"),
            loadedIds = setOf("a")
        )
        // out = [app-bar, header:a, footer:a]
        assertEquals(listOf(OverlayKind.APP_BAR, OverlayKind.MESSAGE_HEADER, OverlayKind.MESSAGE_FOOTER),
            out.map { it.kind })
    }

    @Test
    fun `messages retain thread order across the descriptor list`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a", "b", "c"),
            expandedIds = setOf("a", "b", "c"),
            loadedIds = setOf("a", "b", "c")
        )
        // Order in list: [app-bar, header:a, footer:a, header:b, footer:b, header:c, footer:c]
        val nonAppBarMsgIds = out.drop(1).map { it.msgId }
        assertEquals(listOf("a", "a", "b", "b", "c", "c"), nonAppBarMsgIds)
    }

    // ─── IDs ────────────────────────────────────────────────────────────

    @Test
    fun `header body and footer ids follow prefix-colon-msgId format`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("m1"),
            expandedIds = setOf("m1"),
            loadedIds = emptySet()
        )
        val byKind = out.associateBy { it.kind }
        assertEquals("header:m1", byKind[OverlayKind.MESSAGE_HEADER]?.id)
        assertEquals("body:m1", byKind[OverlayKind.MESSAGE_BODY_LOADER]?.id)
        assertEquals("footer:m1", byKind[OverlayKind.MESSAGE_FOOTER]?.id)
    }

    @Test
    fun `descriptor expanded flag mirrors expandedIds`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a", "b"),
            expandedIds = setOf("a"),
            loadedIds = emptySet()
        )
        val headers = out.filter { it.kind == OverlayKind.MESSAGE_HEADER }.associateBy { it.msgId }
        assertTrue(headers["a"]!!.expanded)
        assertTrue(!headers["b"]!!.expanded)
    }

    // ─── Highlight ──────────────────────────────────────────────────────

    @Test
    fun `highlight applies to collapsed target header only`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a", "b", "c"),
            expandedIds = setOf("a"),
            loadedIds = emptySet(),
            highlightedMsgId = "b"
        )
        val headers = out.filter { it.kind == OverlayKind.MESSAGE_HEADER }.associateBy { it.msgId }
        assertTrue(headers["b"]!!.highlighted)
        assertTrue(!headers["a"]!!.highlighted)
        assertTrue(!headers["c"]!!.highlighted)
    }

    @Test
    fun `highlight is suppressed when target message is expanded`() {
        // Body is visible when expanded, so no header border is drawn —
        // matches product decision 2026-08-28.
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a", "b"),
            expandedIds = setOf("b"),
            loadedIds = setOf("b"),
            highlightedMsgId = "b"
        )
        val headerB = out.first { it.kind == OverlayKind.MESSAGE_HEADER && it.msgId == "b" }
        assertTrue(!headerB.highlighted)
    }

    @Test
    fun `null highlightedMsgId leaves every header unhighlighted`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a", "b"),
            expandedIds = emptySet(),
            loadedIds = emptySet(),
            highlightedMsgId = null
        )
        val headers = out.filter { it.kind == OverlayKind.MESSAGE_HEADER }
        assertTrue(headers.none { it.highlighted })
    }

    @Test
    fun `highlight does not leak onto app-bar or body or footer overlays`() {
        val out = OverlayDescriptorBuilder.build(
            thread = thread("a"),
            expandedIds = setOf("a"),
            loadedIds = emptySet(),
            highlightedMsgId = "a"
        )
        val nonHeader = out.filter { it.kind != OverlayKind.MESSAGE_HEADER }
        assertTrue(nonHeader.none { it.highlighted })
    }
}
