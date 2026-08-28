package com.alex.mailstubdetails.ui.conversation

import com.alex.mailstubdetails.ui.conversation.OverlayLayoutMath.OverlayGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutMathTest {

    private fun geom(
        id: String,
        topCss: Float,
        heightCss: Float,
        measuredPx: Int = 100,
        positioned: Boolean = true
    ) = OverlayGeometry(id, topCss, heightCss, measuredPx, positioned)

    // ─── Single-overlay natural placement ───────────────────────────────

    @Test
    fun `single overlay at zoom 1 places at topCss minus scroll`() {
        val out = OverlayLayoutMath.layout(
            overlays = listOf(geom("a", topCss = 100f, heightCss = 50f)),
            initialScale = 1f,
            pinchFactor = 1f,
            bridgePageTopCss = 30f,
            viewportHeightPx = 800
        )
        // topCss=100, scroll=30, effectiveScale=1 → 100 - 30 = 70
        assertEquals(70f, out.single().translationYPx, EPS)
    }

    @Test
    fun `initialScale is baked into the effective scale`() {
        val out = OverlayLayoutMath.layout(
            overlays = listOf(geom("a", topCss = 100f, heightCss = 50f)),
            initialScale = 2f,
            pinchFactor = 1f,
            bridgePageTopCss = 0f,
            viewportHeightPx = 800
        )
        // topCss * (pinch * initial) = 100 * (1 * 2) = 200
        assertEquals(200f, out.single().translationYPx, EPS)
    }

    @Test
    fun `pinch factor scales positions and scroll offset uniformly`() {
        val out = OverlayLayoutMath.layout(
            overlays = listOf(geom("a", topCss = 100f, heightCss = 50f)),
            initialScale = 1f,
            pinchFactor = 2f,
            bridgePageTopCss = 30f,
            viewportHeightPx = 800
        )
        // effective = 2, scrollOffset = 30 * 2 = 60, topPx = 100*2 - 60 = 140
        assertEquals(140f, out.single().translationYPx, EPS)
    }

    // ─── Chain compression ─────────────────────────────────────────────

    @Test
    fun `two contiguous overlays stack flush regardless of zoom`() {
        // Spacer A: [0, 40) CSS.  Spacer B starts exactly at 40 → contiguous.
        val overlays = listOf(
            geom("a", topCss = 0f, heightCss = 40f, measuredPx = 100),
            geom("b", topCss = 40f, heightCss = 60f, measuredPx = 120)
        )
        val out = OverlayLayoutMath.layout(
            overlays = overlays,
            initialScale = 1f,
            pinchFactor = 3f,  // strong zoom-in
            bridgePageTopCss = 0f,
            viewportHeightPx = 2000
        )
        // A: natural = 0 * 3 - 0 = 0
        // B: contiguous → compressed = a.translationY + a.measuredHeight = 0 + 100 = 100
        assertEquals(0f, out[0].translationYPx, EPS)
        assertEquals(100f, out[1].translationYPx, EPS)
    }

    @Test
    fun `non-contiguous overlays use natural DOM position`() {
        // Spacer A ends at CSS 40; Spacer B starts at CSS 200 → gap = 160 CSS px of body.
        val overlays = listOf(
            geom("a", topCss = 0f, heightCss = 40f, measuredPx = 100),
            geom("b", topCss = 200f, heightCss = 60f, measuredPx = 120)
        )
        val out = OverlayLayoutMath.layout(
            overlays = overlays,
            initialScale = 1f,
            pinchFactor = 2f,
            bridgePageTopCss = 0f,
            viewportHeightPx = 2000
        )
        // A: 0 * 2 = 0
        // B: not contiguous → natural = 200 * 2 = 400
        assertEquals(0f, out[0].translationYPx, EPS)
        assertEquals(400f, out[1].translationYPx, EPS)
    }

    @Test
    fun `sub-pixel gap within epsilon is treated as contiguous`() {
        // Spacer B starts 0.3 CSS px after A → within CONTIGUITY_EPSILON_CSS = 0.5
        val overlays = listOf(
            geom("a", topCss = 0f, heightCss = 40f, measuredPx = 100),
            geom("b", topCss = 40.3f, heightCss = 60f, measuredPx = 120)
        )
        val out = OverlayLayoutMath.layout(
            overlays = overlays,
            initialScale = 1f,
            pinchFactor = 1f,
            bridgePageTopCss = 0f,
            viewportHeightPx = 2000
        )
        // Contiguous → b stacks under a (100), not natural (40.3).
        assertEquals(100f, out[1].translationYPx, EPS)
    }

    @Test
    fun `negative gap beyond epsilon falls back to natural`() {
        // Reversed order: b sits above a in CSS. gap = -160 CSS px, well past epsilon.
        val overlays = listOf(
            geom("a", topCss = 200f, heightCss = 40f, measuredPx = 100),
            geom("b", topCss = 30f, heightCss = 60f, measuredPx = 120)
        )
        val out = OverlayLayoutMath.layout(
            overlays = overlays,
            initialScale = 1f,
            pinchFactor = 1f,
            bridgePageTopCss = 0f,
            viewportHeightPx = 2000
        )
        // b uses natural 30, not compressed (which would drag it below a).
        assertEquals(30f, out[1].translationYPx, EPS)
    }

    @Test
    fun `chain of three overlays compresses cumulatively`() {
        val overlays = listOf(
            geom("a", topCss = 0f, heightCss = 40f, measuredPx = 100),
            geom("b", topCss = 40f, heightCss = 40f, measuredPx = 120),
            geom("c", topCss = 80f, heightCss = 40f, measuredPx = 80)
        )
        val out = OverlayLayoutMath.layout(
            overlays = overlays,
            initialScale = 1f,
            pinchFactor = 4f,
            bridgePageTopCss = 0f,
            viewportHeightPx = 2000
        )
        // a: 0, b: a+100=100, c: b+120=220
        assertEquals(0f, out[0].translationYPx, EPS)
        assertEquals(100f, out[1].translationYPx, EPS)
        assertEquals(220f, out[2].translationYPx, EPS)
    }

    // ─── Visibility ─────────────────────────────────────────────────────

    @Test
    fun `overlay above viewport top is invisible`() {
        val out = OverlayLayoutMath.layout(
            overlays = listOf(geom("a", topCss = 0f, heightCss = 50f, measuredPx = 100)),
            initialScale = 1f,
            pinchFactor = 1f,
            bridgePageTopCss = 500f,  // scrolled far down
            viewportHeightPx = 800
        )
        // topPx = -500, bottom = -400 → entirely above viewport
        assertFalse(out.single().visible)
    }

    @Test
    fun `overlay below viewport bottom is invisible`() {
        val out = OverlayLayoutMath.layout(
            overlays = listOf(geom("a", topCss = 10000f, heightCss = 50f)),
            initialScale = 1f,
            pinchFactor = 1f,
            bridgePageTopCss = 0f,
            viewportHeightPx = 800
        )
        // topPx = 10000 > 800 → below viewport
        assertFalse(out.single().visible)
    }

    @Test
    fun `overlay partially on-screen is visible`() {
        val out = OverlayLayoutMath.layout(
            overlays = listOf(geom("a", topCss = 0f, heightCss = 50f, measuredPx = 100)),
            initialScale = 1f,
            pinchFactor = 1f,
            bridgePageTopCss = 20f,  // top clipped by 20 px
            viewportHeightPx = 800
        )
        // topPx = -20, bottom = 80 → intersects viewport
        assertTrue(out.single().visible)
    }

    @Test
    fun `unpositioned overlays are hidden even when they intersect viewport`() {
        val out = OverlayLayoutMath.layout(
            overlays = listOf(
                geom("a", topCss = 100f, heightCss = 50f, positioned = false)
            ),
            initialScale = 1f,
            pinchFactor = 1f,
            bridgePageTopCss = 0f,
            viewportHeightPx = 800
        )
        assertFalse(
            "overlay must stay hidden until JS reports its geometry — otherwise " +
                "it would briefly render at translationY=0 on top of the app bar",
            out.single().visible
        )
    }

    @Test
    fun `unpositioned overlays do not anchor the next overlay's compression`() {
        // If A is not positioned yet, B must fall back to natural placement
        // rather than compressing against A's (stale) translation.
        val overlays = listOf(
            geom("a", topCss = 0f, heightCss = 40f, measuredPx = 100, positioned = false),
            geom("b", topCss = 40f, heightCss = 60f, measuredPx = 120, positioned = true)
        )
        val out = OverlayLayoutMath.layout(
            overlays = overlays,
            initialScale = 1f,
            pinchFactor = 2f,
            bridgePageTopCss = 0f,
            viewportHeightPx = 2000
        )
        // b: natural = 40 * 2 = 80 (not compressed against a's 0)
        assertEquals(80f, out[1].translationYPx, EPS)
    }

    // ─── Sanity ─────────────────────────────────────────────────────────

    @Test
    fun `empty input produces empty output`() {
        val out = OverlayLayoutMath.layout(
            overlays = emptyList(),
            initialScale = 1f,
            pinchFactor = 1f,
            bridgePageTopCss = 0f,
            viewportHeightPx = 800
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `output preserves input order and ids`() {
        val overlays = listOf(
            geom("x", topCss = 0f, heightCss = 10f),
            geom("y", topCss = 100f, heightCss = 10f),
            geom("z", topCss = 200f, heightCss = 10f)
        )
        val out = OverlayLayoutMath.layout(overlays, 1f, 1f, 0f, 800)
        assertEquals(listOf("x", "y", "z"), out.map { it.id })
    }

    companion object {
        private const val EPS = 0.0001f
    }
}
