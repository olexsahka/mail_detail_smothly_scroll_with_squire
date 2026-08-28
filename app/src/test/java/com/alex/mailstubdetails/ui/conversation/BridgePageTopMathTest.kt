package com.alex.mailstubdetails.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgePageTopMathTest {

    // ─── Nominal ────────────────────────────────────────────────────────

    @Test
    fun `divides scrollY by effective scale (bridgeScale × initialScale)`() {
        // effectiveScale = 1.0 * 2.0 = 2 → 400 / 2 = 200 CSS px
        val result = BridgePageTopMath.predict(
            scrollYPx = 400,
            bridgeScale = 1f,
            initialScale = 2f
        )
        assertEquals(200f, result!!, EPS)
    }

    @Test
    fun `zoomed-in pinch reduces predicted CSS scroll`() {
        // Same scrollY at 2× pinch → CSS scroll is half of what it'd be at 1×.
        val at1x = BridgePageTopMath.predict(1000, 1f, 2f)
        val at2x = BridgePageTopMath.predict(1000, 2f, 2f)
        assertEquals(500f, at1x!!, EPS)
        assertEquals(250f, at2x!!, EPS)
    }

    @Test
    fun `zero scrollY yields zero CSS`() {
        assertEquals(0f, BridgePageTopMath.predict(0, 1f, 2f)!!, EPS)
    }

    // ─── initialScale fallback (pre-onPageFinished) ─────────────────────

    @Test
    fun `uninitialised initialScale falls back to 1`() {
        // Pre-onPageFinished, WebView reports scale = 0 or negative.
        // Formula must still produce a valid prediction rather than fail —
        // that matches the pre-extraction guard.
        val result = BridgePageTopMath.predict(
            scrollYPx = 300,
            bridgeScale = 1f,
            initialScale = 0f
        )
        assertEquals(
            "with initialScale fallback to 1, 300 / (1*1) = 300",
            300f, result!!, EPS
        )
    }

    @Test
    fun `negative initialScale also falls back to 1`() {
        val result = BridgePageTopMath.predict(
            scrollYPx = 500,
            bridgeScale = 1f,
            initialScale = -3f
        )
        assertEquals(500f, result!!, EPS)
    }

    // ─── Non-positive scale product → null (caller keeps previous value) ─

    @Test
    fun `null when bridgeScale is zero`() {
        assertNull(
            "effectiveScale = 0 * anything → 0; predicting would divide by zero",
            BridgePageTopMath.predict(scrollYPx = 100, bridgeScale = 0f, initialScale = 2f)
        )
    }

    @Test
    fun `null when bridgeScale is negative`() {
        assertNull(
            BridgePageTopMath.predict(scrollYPx = 100, bridgeScale = -1f, initialScale = 2f)
        )
    }

    @Test
    fun `null when both scales are zero`() {
        // initialScale=0 falls back to 1, but bridgeScale=0 zeroes the product.
        assertNull(
            BridgePageTopMath.predict(scrollYPx = 100, bridgeScale = 0f, initialScale = 0f)
        )
    }

    companion object {
        private const val EPS = 0.0001f
    }
}
