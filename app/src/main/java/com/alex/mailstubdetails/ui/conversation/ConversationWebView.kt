package com.alex.mailstubdetails.ui.conversation

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

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
            // Native WebView pinch — same model as Gmail (Android) /
            // AOSP UnifiedEmail. WebView's compositor scales the rendered
            // bitmap visually; HTML layout is NOT reflowed, so tables and
            // wide email content survive intact. Zoom is shared across the
            // whole conversation (single WebView = single currentScale).
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

    /** Last applied dark-mode flag; dedup for [setBodyDarkMode]. */
    private var lastDarkMode: Boolean? = null

    /**
     * Flip the WebView's dark-mode state via WebSettingsCompat. Three
     * gated API calls, same shape as the legacy pattern that worked
     * pre-targetSdk-T:
     *   • setForceDark — honored on old Chromium / pre-T apps, no-op on T+.
     *   • setForceDarkStrategy — same gating; picks USER_AGENT_DARKENING_ONLY
     *     because our HTML has no dark CSS theme to prefer over invert.
     *   • setAlgorithmicDarkeningAllowed — the post-T knob. Only takes
     *     effect when the host Activity's resources config is in
     *     UI_MODE_NIGHT_YES — that's why ConversationScreen pairs this
     *     call with AppCompatDelegate.setLocalNightMode(MODE_NIGHT_YES).
     *     Just wrapping our local Context in a night-mode
     *     createConfigurationContext() isn't enough: Chromium walks up
     *     to Activity resources for the night-mode check.
     *
     * Also flips the background color — avoids a flash of the opposite
     * palette between a DOM rebuild and the compositor's first frame
     * under the new theme.
     */
    fun setBodyDarkMode(darkMode: Boolean) {
        if (lastDarkMode == darkMode) return
        lastDarkMode = darkMode

        setBackgroundColor(if (darkMode) Color.BLACK else Color.WHITE)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(
                settings,
                if (darkMode) WebSettingsCompat.FORCE_DARK_ON
                else WebSettingsCompat.FORCE_DARK_OFF
            )
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDarkStrategy(
                settings,
                WebSettingsCompat.DARK_STRATEGY_USER_AGENT_DARKENING_ONLY
            )
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkMode)
        }

        invalidate()
    }

    /**
     * Reports whether the Activity hosting this WebView currently has
     * its uiMode set to UI_MODE_NIGHT_YES. Useful for diagnostics /
     * sanity-checks when debugging the dark-mode toggle.
     */
    @Suppress("unused")
    val isInNightModeContext: Boolean
        get() = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

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
            // WebView does not fire onScaleChanged for the initial density
            // scale — notify listeners here so the container's geometry
            // coordinator can push spacer heights with the real scale
            // instead of the placeholder 1.0.
            scaleListener?.onScaleChanged(s)
            clientDelegate?.onPageFinished(view, url)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            // Delegate first — a caller may want custom routing (e.g., open
            // an in-app browser Compose screen for internal links).
            clientDelegate?.shouldOverrideUrlLoading(view, request)?.let {
                if (it) return true
            }
            // Route external schemes to the system handler. Without this,
            // taps on http/https/mailto/tel links from email HTML load
            // inside the conversation WebView, which both breaks navigation
            // and is a phishing surface (the URL bar isn't visible).
            val uri: Uri = request.url ?: return false
            return when (uri.scheme?.lowercase()) {
                "http", "https", "mailto", "tel", "sms", "geo" -> {
                    launchExternal(view.context, uri)
                    true
                }
                // file:// / about: / data: — internal / template loads.
                else -> false
            }
        }

        private fun launchExternal(context: Context, uri: Uri) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // No app can handle this URL — silently drop rather than crash.
            }
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? =
            clientDelegate?.shouldInterceptRequest(view, request)
    }
}
