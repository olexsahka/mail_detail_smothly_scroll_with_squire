package com.alex.mailstubdetails.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class SquireHeightMathTest {

    // ─── Nominal ────────────────────────────────────────────────────────

    @Test
    fun `no zoom on 1x density passes contentPx through as dp`() {
        val dp = SquireHeightMath.heightDp(
            contentPx = 500,
            scale = 1f,
            density = 1f,
            minHeightDp = 100f
        )
        // 500 * 1 / 1 = 500 dp
        assertEquals(500f, dp, EPS)
    }

    @Test
    fun `2x density halves dp height for the same content px`() {
        val dp = SquireHeightMath.heightDp(
            contentPx = 800,
            scale = 1f,
            density = 2f,
            minHeightDp = 100f
        )
        // 800 * 1 / 2 = 400 dp
        assertEquals(400f, dp, EPS)
    }

    @Test
    fun `zoom-in doubles content px before density conversion`() {
        val dp = SquireHeightMath.heightDp(
            contentPx = 500,
            scale = 2f,
            density = 2f,
            minHeightDp = 100f
        )
        // 500 * 2 / 2 = 500 dp — zoom perfectly cancels density here
        assertEquals(500f, dp, EPS)
    }

    @Test
    fun `zoom-in at 3x density`() {
        val dp = SquireHeightMath.heightDp(
            contentPx = 900,
            scale = 2f,
            density = 3f,
            minHeightDp = 100f
        )
        // 900 * 2 / 3 = 600 dp
        assertEquals(600f, dp, EPS)
    }

    // ─── Min-height floor ───────────────────────────────────────────────

    @Test
    fun `content shorter than min is clamped up to minHeight`() {
        val dp = SquireHeightMath.heightDp(
            contentPx = 20,
            scale = 1f,
            density = 2f,
            minHeightDp = 200f
        )
        // 20 * 1 / 2 = 10 dp → clamps to 200
        assertEquals(200f, dp, EPS)
    }

    @Test
    fun `zero content px clamps to minHeight`() {
        val dp = SquireHeightMath.heightDp(
            contentPx = 0,
            scale = 1f,
            density = 2f,
            minHeightDp = 150f
        )
        assertEquals(150f, dp, EPS)
    }

    @Test
    fun `zoom-out below min still clamps up`() {
        val dp = SquireHeightMath.heightDp(
            contentPx = 400,
            scale = 0.5f,
            density = 2f,
            minHeightDp = 300f
        )
        // 400 * 0.5 / 2 = 100 dp → clamps to 300
        assertEquals(300f, dp, EPS)
    }

    @Test
    fun `content exactly equal to minHeight returns minHeight`() {
        val dp = SquireHeightMath.heightDp(
            contentPx = 100,
            scale = 1f,
            density = 1f,
            minHeightDp = 100f
        )
        assertEquals(100f, dp, EPS)
    }

    @Test
    fun `content just above minHeight is not clamped`() {
        val dp = SquireHeightMath.heightDp(
            contentPx = 101,
            scale = 1f,
            density = 1f,
            minHeightDp = 100f
        )
        assertEquals(101f, dp, EPS)
    }

    // ─── Fractional scale ───────────────────────────────────────────────

    @Test
    fun `fractional zoom preserves precision`() {
        val dp = SquireHeightMath.heightDp(
            contentPx = 1000,
            scale = 1.5f,
            density = 2f,
            minHeightDp = 0f
        )
        // 1000 * 1.5 / 2 = 750 dp
        assertEquals(750f, dp, EPS)
    }

    companion object {
        private const val EPS = 0.0001f
    }
}
