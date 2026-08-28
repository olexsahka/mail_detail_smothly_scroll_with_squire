package com.alex.mailstubdetails.ui.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactBarThresholdTest {

    // ─── Guard: unknown app-bar height ────────────────────────────────

    @Test
    fun `hidden when app bar height is not yet measured`() {
        assertFalse(
            "compact bar must not flap in when the large-bar geometry is unknown",
            CompactBarThreshold.shouldShowCompact(
                scrollYPx = 500,
                appBarHeightPx = 0,
                compactBarHeightPx = 200
            )
        )
    }

    @Test
    fun `hidden when app bar height is negative (defensive)`() {
        assertFalse(
            CompactBarThreshold.shouldShowCompact(
                scrollYPx = 500,
                appBarHeightPx = -10,
                compactBarHeightPx = 200
            )
        )
    }

    // ─── Threshold math (scroll < threshold → hidden) ─────────────────

    @Test
    fun `hidden at zero scroll`() {
        assertFalse(
            CompactBarThreshold.shouldShowCompact(
                scrollYPx = 0,
                appBarHeightPx = 400,
                compactBarHeightPx = 200
            )
        )
    }

    @Test
    fun `hidden just below threshold`() {
        // threshold = 400 - 200 = 200; scrollY = 200 → NOT strictly > threshold
        assertFalse(
            CompactBarThreshold.shouldShowCompact(
                scrollYPx = 200,
                appBarHeightPx = 400,
                compactBarHeightPx = 200
            )
        )
    }

    @Test
    fun `visible just above threshold`() {
        assertTrue(
            CompactBarThreshold.shouldShowCompact(
                scrollYPx = 201,
                appBarHeightPx = 400,
                compactBarHeightPx = 200
            )
        )
    }

    @Test
    fun `visible when fully scrolled past the large bar`() {
        assertTrue(
            CompactBarThreshold.shouldShowCompact(
                scrollYPx = 1000,
                appBarHeightPx = 400,
                compactBarHeightPx = 200
            )
        )
    }

    // ─── Edge: compact taller than large ──────────────────────────────

    @Test
    fun `compact taller than large bar clamps threshold to zero`() {
        // Degenerate config: swap the moment scroll exceeds 0.
        assertFalse(
            "at zero scroll, still no reason to show the compact bar",
            CompactBarThreshold.shouldShowCompact(
                scrollYPx = 0,
                appBarHeightPx = 100,
                compactBarHeightPx = 200
            )
        )
        assertTrue(
            CompactBarThreshold.shouldShowCompact(
                scrollYPx = 1,
                appBarHeightPx = 100,
                compactBarHeightPx = 200
            )
        )
    }
}
