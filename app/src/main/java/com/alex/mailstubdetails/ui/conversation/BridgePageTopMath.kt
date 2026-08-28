package com.alex.mailstubdetails.ui.conversation

/**
 * Predict `bridgePageTopCss` (the current CSS-px scroll offset used by
 * [OverlayLayoutMath]) from a fresh native `WebView.scrollY` sample.
 *
 * Two callers in [ConversationContainer]:
 *  1. `scrollListener` when NOT pinching — closes the ~1-frame gap between
 *     the compositor's actual scroll and the last JS `visualViewport`
 *     report. Without this, overlays visibly lag DOM content by a few px.
 *  2. `endPinch` — aligns the bridge value (last set by JS during pinch)
 *     with the compositor's settled scrollY, so overlay position stays
 *     continuous across the pinch → scroll boundary.
 *
 * The single formula:
 * ```
 *   effectiveScale = bridgeScale * initialScale
 *   pageTopCss     = scrollYPx / effectiveScale
 * ```
 *
 * Returns `null` when the scale product is non-positive — callers keep
 * their previous `bridgePageTopCss` in that case (matches the guard in
 * the pre-extraction code).
 */
object BridgePageTopMath {

    fun predict(
        scrollYPx: Int,
        bridgeScale: Float,
        initialScale: Float
    ): Float? {
        // Match the pre-extraction fallback: an uninitialised initialScale
        // (<=0 before onPageFinished sets it) is treated as 1.0 rather
        // than making prediction fail.
        val initial = initialScale.takeIf { it > 0f } ?: 1f
        val effectiveScale = bridgeScale * initial
        if (effectiveScale <= 0f) return null
        return scrollYPx / effectiveScale
    }
}
