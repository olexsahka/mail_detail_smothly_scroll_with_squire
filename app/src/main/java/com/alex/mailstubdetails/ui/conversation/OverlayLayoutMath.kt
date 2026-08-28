package com.alex.mailstubdetails.ui.conversation

/**
 * Pure math for the chain-compressed overlay positioning in
 * [ConversationContainer.positionOverlays]. Extracted so it can be unit-
 * tested without an Android view hierarchy.
 *
 * Coordinate spaces:
 *  • **CSS px** — reported by JS (`getBoundingClientRect().top`).
 *  • **Device px** — CSS px × [effectiveScale].
 *  • **Container-local px** — device px − scroll offset. Same units as
 *    [OverlayGeometry.measuredHeightPx] and [OverlayPlacement.translationYPx].
 *
 * The single positioning formula:
 * ```
 *   effectiveScale  = pinchFactor * initialScale
 *   scrollOffsetPx  = bridgePageTopCss * effectiveScale
 *   naturalTopPx    = topCss * effectiveScale - scrollOffsetPx
 * ```
 *
 * Within a contiguous chain (spacers touching in DOM), overlays are
 * *compressed*: placed flush under the previous overlay's bottom instead
 * of at their natural DOM position. The accumulated per-spacer overshoot
 * (spacer_device = M × pinchFactor grows past overlay height M when
 * zoomed in) spills out below the chain rather than each overlay creeping
 * out of position. See ConversationContainer.positionOverlays KDoc for
 * the full rationale.
 */
object OverlayLayoutMath {

    /**
     * Sub-CSS-px tolerance when deciding whether two spacers are adjacent
     * in DOM. JS `getBoundingClientRect` reports floats and 0.5px
     * rounding drift is normal.
     */
    const val CONTIGUITY_EPSILON_CSS: Float = 0.5f

    /**
     * Per-overlay input. [topCss] / [heightCss] come from the JS geometry
     * bridge; [measuredHeightPx] from [android.view.View.getMeasuredHeight];
     * [positioned] is false until the first JS geometry report arrives.
     */
    data class OverlayGeometry(
        val id: String,
        val topCss: Float,
        val heightCss: Float,
        val measuredHeightPx: Int,
        val positioned: Boolean
    )

    /**
     * Where and whether to draw a given overlay.
     *
     * [translationYPx] is a float on purpose — no [Float.roundToInt] so we
     * don't introduce ±1 px jitter per frame from sub-pixel scale values.
     * The renderer snaps to device pixels at draw time.
     */
    data class OverlayPlacement(
        val id: String,
        val translationYPx: Float,
        /**
         * `true` iff the overlay is positioned AND its rect intersects the
         * container's viewport. Newly-added overlays whose geometry hasn't
         * been reported by JS yet are hidden to prevent them briefly
         * rendering at translationY = 0 (on top of the app bar).
         */
        val visible: Boolean
    )

    /**
     * Compute placements for every overlay in one pass.
     *
     * Preconditions: [initialScale] > 0, [pinchFactor] > 0. Callers guard
     * against pre-`onPageFinished` state where initialScale would be 0.
     */
    fun layout(
        overlays: List<OverlayGeometry>,
        initialScale: Float,
        pinchFactor: Float,
        bridgePageTopCss: Float,
        viewportHeightPx: Int
    ): List<OverlayPlacement> {
        val effectiveScale = pinchFactor * initialScale
        val scrollOffsetPx = bridgePageTopCss * effectiveScale
        val out = ArrayList<OverlayPlacement>(overlays.size)
        var prev: OverlayGeometry? = null
        var prevTopPx = 0f
        for (o in overlays) {
            val naturalTopPx = o.topCss * effectiveScale - scrollOffsetPx
            val topPx: Float = if (prev != null && prev.positioned && o.positioned) {
                val gapCss = o.topCss - (prev.topCss + prev.heightCss)
                if (gapCss in -CONTIGUITY_EPSILON_CSS..CONTIGUITY_EPSILON_CSS) {
                    // Contiguous chain → stack under previous overlay.
                    prevTopPx + prev.measuredHeightPx
                } else {
                    naturalTopPx
                }
            } else {
                naturalTopPx
            }
            val onScreen = topPx + o.measuredHeightPx > 0f && topPx < viewportHeightPx
            out += OverlayPlacement(
                id = o.id,
                translationYPx = topPx,
                visible = o.positioned && onScreen
            )
            prev = o
            prevTopPx = topPx
        }
        return out
    }
}
