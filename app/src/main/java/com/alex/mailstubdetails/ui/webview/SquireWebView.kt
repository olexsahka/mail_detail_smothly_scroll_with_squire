package com.alex.mailstubdetails.ui.webview

import android.annotation.SuppressLint
import android.util.Base64
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

enum class SquireMode { READONLY, EDITABLE }

/**
 * Single Compose component for the Squire.js WebView.
 * Inflated from [R.layout.squire_webview] — no custom View subclass.
 *
 * Scroll contract:
 *   • The outer Compose [Column]/[LazyColumn] owns all vertical scrolling.
 *   • This component self-measures its height via the JS bridge and exposes
 *     that height to Compose, so it sits as a fixed-height block in the scroll.
 *   • [addOnScrollChangeListener] resets the WebView scroll offset to (0,0)
 *     on every frame — no internal scroll, no subclassing needed.
 *
 * Zoom contract:
 *   • A [ScaleGestureDetector] attached via [setOnTouchListener] detects pinch.
 *   • During pinch: [requestDisallowInterceptTouchEvent](true) — WebView handles zoom.
 *   • Single touch:  [requestDisallowInterceptTouchEvent](false) — Compose scroll intercepts.
 *   • After zoom: [WebViewClient.onScaleChanged] recalculates height → recomposition → resize.
 *
 * @param html         HTML to display/edit (pass empty string for blank compose).
 * @param mode         [SquireMode.READONLY] or [SquireMode.EDITABLE].
 * @param minHeight    Minimum height while content is loading.
 * @param onWebViewReady Optional callback that receives the [WebView] instance once
 *                     Squire is initialised — use this to call JS formatting commands
 *                     from a toolbar in edit mode.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun SquireWebViewContainer(
    html: String,
    mode: SquireMode = SquireMode.READONLY,
    modifier: Modifier = Modifier,
    minHeight: Dp = 200.dp,
    onWebViewReady: ((WebView) -> Unit)? = null
) {
    val density = LocalDensity.current

    // Height is driven by JS-reported scrollHeight × current zoom scale.
    // Changing it triggers recomposition → AndroidView resizes.
    var heightDp by remember { mutableStateOf(minHeight) }

    // rememberUpdatedState: bridge lambdas always read latest values
    // without being re-created on every recomposition.
    val latestHtml = rememberUpdatedState(html)
    val latestMode = rememberUpdatedState(mode)

    // Tracks the last html pushed via evaluateJavascript so `update` skips no-ops.
    val pushedHtml = remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(heightDp),
        factory = { ctx ->
            val webView = WebView(ctx).apply {
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }

            // ── Pinch zoom ↔ scroll delegation ───────────────────────────
            val scaleDetector = ScaleGestureDetector(
                ctx,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                        webView.parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }

                    @Suppress("DEPRECATION")
                    override fun onScaleEnd(detector: ScaleGestureDetector) {
                        webView.parent?.requestDisallowInterceptTouchEvent(false)
                        webView.post {
                            heightDp = SquireHeightMath.heightDp(
                                contentPx = webView.contentHeight,
                                scale = webView.scale,
                                density = density.density,
                                minHeightDp = minHeight.value
                            ).dp
                        }
                    }
                }
            )

            webView.setOnTouchListener { v, event ->
                scaleDetector.onTouchEvent(event)
                when {
                    scaleDetector.isInProgress -> {
                        // Pinch in progress — WebView handles zoom, block outer scroll.
                    }
                    event.actionMasked == MotionEvent.ACTION_DOWN -> {
                        // Single-touch start: allow outer Compose scroll to intercept moves.
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL -> {
                        // Touch ended: reset any accidental internal scroll offset.
                        if (v.scrollY != 0) v.scrollTo(v.scrollX, 0)
                    }
                }
                false // WebView still processes the event (links, focus, zoom)
            }

            // ── WebView settings ─────────────────────────────────────────
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                // allowFileAccess is needed to load our bundled assets via
                // file:///android_asset/. We do NOT enable
                // allowUniversalAccessFromFileURLs / allowFileAccessFromFileURLs —
                // that would let any script running in the file:// origin
                // XHR-fetch arbitrary local files, which combined with the
                // JavascriptInterface below would be a full RCE canal on
                // CDN compromise. Squire loads bundled locally, no cross-
                // origin XHR is required.
                allowFileAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
            }

            // ── JS → Kotlin bridge ────────────────────────────────────────
            webView.addJavascriptInterface(
                object : Any() {
                    @JavascriptInterface
                    fun requestHtml(): String = latestHtml.value

                    @JavascriptInterface
                    fun requestReadOnly(): Boolean =
                        latestMode.value == SquireMode.READONLY

                    @JavascriptInterface
                    @Suppress("DEPRECATION")
                    fun onHeightChanged(cssHeight: Int) {
                        webView.post {
                            heightDp = SquireHeightMath.heightDp(
                                contentPx = cssHeight,
                                scale = webView.scale,
                                density = density.density,
                                minHeightDp = minHeight.value
                            ).dp
                        }
                    }

                    @JavascriptInterface
                    fun onReady() {
                        webView.post { onWebViewReady?.invoke(webView) }
                    }
                },
                "Bridge"
            )

            // ── WebViewClient ─────────────────────────────────────────────
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ) = true // block all navigation inside email HTML

                override fun onScaleChanged(
                    view: WebView,
                    oldScale: Float,
                    newScale: Float
                ) {
                    view.post {
                        heightDp = SquireHeightMath.heightDp(
                            contentPx = view.contentHeight,
                            scale = newScale,
                            density = density.density,
                            minHeightDp = minHeight.value
                        ).dp
                    }
                }
            }

            webView.loadUrl("file:///android_asset/squire_editor.html")
            webView
        },
        update = { webView ->
            // Push new HTML via base64 when caller's state changes.
            // base64 avoids all JS string-escaping edge cases.
            val current = latestHtml.value
            if (pushedHtml.value != current) {
                pushedHtml.value = current
                val b64 = Base64.encodeToString(
                    current.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                webView.evaluateJavascript("setHtmlBase64('$b64')", null)
            }
        },
        onRelease = { webView ->
            // Compose disposal doesn't tear down AndroidView children on its
            // own — WebView keeps a reference to the JavascriptInterface
            // "Bridge" (which closes over Compose state) and to its native
            // renderer, leaking the Activity. Remove the interface and call
            // destroy() explicitly.
            webView.removeJavascriptInterface("Bridge")
            webView.stopLoading()
            webView.webChromeClient = null
            webView.setOnTouchListener(null)
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    )
}
