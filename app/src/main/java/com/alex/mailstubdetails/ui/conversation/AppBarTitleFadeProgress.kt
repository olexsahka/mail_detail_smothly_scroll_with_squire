package com.alex.mailstubdetails.ui.conversation

/**
 * Scroll-linked fade-in progress for the statically pinned bar's title.
 *
 * The static bar is drawn on top of [LargeAppBarOverlay] and is always
 * visible (icons only, no title at scroll = 0). As the hero subject
 * scrolls up under the bar, we cross-fade the same subject into the
 * pinned bar's center — but *coupled to the scroll position*, not to a
 * discrete trigger. That means the pinned title starts fading in the
 * moment the hero subject's *top* passes under the bar's bottom edge and
 * completes exactly when the hero subject's *bottom* has also passed
 * under — no visible gap between "hero title disappeared" and "static
 * title appeared".
 *
 * `heroSubjectTopPx` / `heroSubjectBottomPx` are Y offsets in device px
 * relative to the top of the hero overlay (reported by
 * `LargeAppBarOverlay` via `onSubjectBoundsChanged`).
 *
 * Screen-space equivalents (hero overlay is bound to `y=0` CSS spacer,
 * so its top on screen ≈ `-scrollY`):
 * ```
 *   subjectTop_screen    = heroSubjectTopPx    − scrollY
 *   subjectBottom_screen = heroSubjectBottomPx − scrollY
 * ```
 * Fade-in span in scrollY:
 * ```
 *   fadeStart = heroSubjectTopPx    − staticBarHeightPx   (subj top just crossed bar bottom)
 *   fadeEnd   = heroSubjectBottomPx − staticBarHeightPx   (subj bottom just crossed bar bottom)
 * ```
 *
 * Extracted as a pure function so the mapping can be unit-tested.
 */
object AppBarTitleFadeProgress {

    /**
     * Returns fade progress in [0f, 1f].
     * - `0f` = pinned title fully hidden (hero subject still fully visible below the bar).
     * - `1f` = pinned title fully visible (hero subject fully under the bar).
     *
     * Also returns `0f` before the hero overlay has been measured
     * (`heroSubjectBottomPx <= heroSubjectTopPx`): during the first
     * frame after navigation the geometry hasn't been reported yet, and
     * any non-zero progress computed from unmeasured state would flash
     * the title in.
     */
    fun progress(
        scrollYPx: Int,
        heroSubjectTopPx: Int,
        heroSubjectBottomPx: Int,
        staticBarHeightPx: Int
    ): Float {
        val span = heroSubjectBottomPx - heroSubjectTopPx
        if (span <= 0) return 0f
        val fadeStart = heroSubjectTopPx - staticBarHeightPx
        val raw = (scrollYPx - fadeStart).toFloat() / span.toFloat()
        return raw.coerceIn(0f, 1f)
    }
}
