package com.alex.mailstubdetails.ui.webview

import kotlin.math.max

/**
 * Height clamp for the Squire [SquireWebViewContainer]:
 *
 * ```
 *   heightDp = max((contentPx × scale) / density, minHeightDp)
 * ```
 *
 * Called from three sites in `SquireWebView.kt`, one per height source:
 *  • pinch-end               — [contentPx] = `webView.contentHeight`, [scale] = `webView.scale`
 *  • JS onHeightChanged      — [contentPx] = CSS scrollHeight,        [scale] = `webView.scale`
 *  • WebViewClient.onScaleChanged — [contentPx] = `view.contentHeight`, [scale] = `newScale`
 *
 * Extracted so the (contentPx × scale ÷ density) → dp conversion can be
 * unit-tested without a Compose `Density` receiver.
 *
 * Assumes `density > 0` — the display-metrics density is always positive
 * on any real device, so we don't guard here.
 */
object SquireHeightMath {

    /**
     * @param contentPx measured content height in device px, before zoom.
     * @param scale current WebView zoom scale (1.0 = no user zoom).
     * @param density `Resources.displayMetrics.density` — px-per-dp ratio.
     * @param minHeightDp minimum height in dp while content is still loading.
     * @return final Compose height in dp.
     */
    fun heightDp(
        contentPx: Int,
        scale: Float,
        density: Float,
        minHeightDp: Float
    ): Float {
        val heightPx = contentPx * scale
        val heightDp = heightPx / density
        return max(heightDp, minHeightDp)
    }
}
