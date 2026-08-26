package com.alex.mailstubdetails.ui.conversation

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The single scrollable WebView that owns the conversation surface.
 *
 * Contract:
 *   • Callers listen to scroll deltas via [scrollListener] and reposition
 *     native overlays on top of the WebView accordingly.
 *   • The current pinch-zoom scale is cached in [currentScale] — the CSS px
 *     positions reported from JS multiply by this value to get device px.
 *   • Callers may install their own [WebViewClient] via [clientDelegate];
 *     the internal client wraps it so scale tracking is never skipped.
 */
class ConversationWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    fun interface ScrollListener {
        fun onScrolled(newY: Int, oldY: Int)
    }

    fun interface ScaleListener {
        fun onScaleChanged(newScale: Float)
    }

    var scrollListener: ScrollListener? = null
    var scaleListener: ScaleListener? = null

    /** Cached WebView zoom scale. Updated from [WebViewClient.onScaleChanged]. */
    var currentScale: Float = 1f
        private set

    /**
     * The WebView's scale at page load with no user pinch applied — i.e. the
     * density-only baseline (≈ resources.displayMetrics.density on standard
     * viewports). Callers derive the pure pinch factor as
     * `currentScale / initialScale`.
     */
    var initialScale: Float = 1f
        private set

    private var initialScaleSet: Boolean = false

    /**
     * Optional delegate for additional [WebViewClient] callbacks
     * (`onPageFinished`, `shouldOverrideUrlLoading`, ...).
     * Scale tracking always runs regardless of the delegate.
     */
    var clientDelegate: WebViewClient? = null

    init {
        @SuppressLint("SetJavaScriptEnabled")
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = false
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
        }
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false

        super.setWebViewClient(InternalClient())
    }

    override fun setWebViewClient(client: WebViewClient) {
        // Intercept: keep our internal client, forward everything else via delegate.
        clientDelegate = client
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        scrollListener?.onScrolled(t, oldt)
    }

    private inner class InternalClient : WebViewClient() {
        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
            currentScale = newScale
            scaleListener?.onScaleChanged(newScale)
            clientDelegate?.onScaleChanged(view, oldScale, newScale)
        }

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: android.graphics.Bitmap?
        ) {
            clientDelegate?.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            @Suppress("DEPRECATION")
            val s = view.scale.takeIf { it > 0f } ?: 1f
            currentScale = s
            if (!initialScaleSet) {
                initialScale = s
                initialScaleSet = true
            }
            clientDelegate?.onPageFinished(view, url)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean =
            clientDelegate?.shouldOverrideUrlLoading(view, request) ?: false

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? =
            clientDelegate?.shouldInterceptRequest(view, request)
    }
}
