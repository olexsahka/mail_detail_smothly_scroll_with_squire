package com.alex.mailstubdetails.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppBarTitleFadeProgressTest {

    private val staticBar = 200
    private val heroTop = 400
    private val heroBottom = 500 // span = 100

    // ─── Guards ───────────────────────────────────────────────────────

    @Test
    fun `zero span returns zero (subject not measured yet)`() {
        assertEquals(
            0f,
            AppBarTitleFadeProgress.progress(
                scrollYPx = 500,
                heroSubjectTopPx = 0,
                heroSubjectBottomPx = 0,
                staticBarHeightPx = staticBar
            ),
            0f
        )
    }

    @Test
    fun `inverted bounds return zero (defensive)`() {
        assertEquals(
            0f,
            AppBarTitleFadeProgress.progress(
                scrollYPx = 500,
                heroSubjectTopPx = 600,
                heroSubjectBottomPx = 500,
                staticBarHeightPx = staticBar
            ),
            0f
        )
    }

    // ─── Progress mapping ─────────────────────────────────────────────

    @Test
    fun `zero before subject top reaches bar bottom`() {
        // fadeStart = 400 - 200 = 200. scrollY = 199 → below start.
        assertEquals(
            0f,
            AppBarTitleFadeProgress.progress(
                scrollYPx = 199,
                heroSubjectTopPx = heroTop,
                heroSubjectBottomPx = heroBottom,
                staticBarHeightPx = staticBar
            ),
            0f
        )
    }

    @Test
    fun `zero exactly when subject top touches bar bottom`() {
        // fadeStart = 200. scrollY = 200 → progress = 0 (about to start).
        assertEquals(
            0f,
            AppBarTitleFadeProgress.progress(
                scrollYPx = 200,
                heroSubjectTopPx = heroTop,
                heroSubjectBottomPx = heroBottom,
                staticBarHeightPx = staticBar
            ),
            0f
        )
    }

    @Test
    fun `half progress at the midpoint of the fade span`() {
        // fadeStart=200, fadeEnd=300, span=100. scrollY=250 → 0.5.
        assertEquals(
            0.5f,
            AppBarTitleFadeProgress.progress(
                scrollYPx = 250,
                heroSubjectTopPx = heroTop,
                heroSubjectBottomPx = heroBottom,
                staticBarHeightPx = staticBar
            ),
            0.0001f
        )
    }

    @Test
    fun `one exactly when subject bottom touches bar bottom`() {
        // fadeEnd = 500 - 200 = 300. scrollY = 300 → progress = 1.
        assertEquals(
            1f,
            AppBarTitleFadeProgress.progress(
                scrollYPx = 300,
                heroSubjectTopPx = heroTop,
                heroSubjectBottomPx = heroBottom,
                staticBarHeightPx = staticBar
            ),
            0f
        )
    }

    @Test
    fun `clamps to one when scrolled far past fade end`() {
        assertEquals(
            1f,
            AppBarTitleFadeProgress.progress(
                scrollYPx = 10_000,
                heroSubjectTopPx = heroTop,
                heroSubjectBottomPx = heroBottom,
                staticBarHeightPx = staticBar
            ),
            0f
        )
    }

    // ─── Edge: bar taller than subject top ────────────────────────────

    @Test
    fun `bar taller than subject top starts fade at scroll zero`() {
        // subjectTop=100, bar=200 → fadeStart = -100. At scroll=0, raw = 100/100 = 1.
        // The static bar already fully covers the subject → title should be full-on
        // from the start.
        assertEquals(
            1f,
            AppBarTitleFadeProgress.progress(
                scrollYPx = 0,
                heroSubjectTopPx = 100,
                heroSubjectBottomPx = 200,
                staticBarHeightPx = staticBar
            ),
            0f
        )
    }
}
