package com.alex.mailstubdetails.ui.conversation

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * ViewGroup that hosts a single [ConversationWebView] plus N native overlay
 * Views positioned on top of it — the AOSP UnifiedEmail pattern.
 *
 * ### Invariant (Gmail-style: static overlays over a zooming body)
 * Overlays keep their density-only Compose size at every pinch factor —
 * they do NOT visually scale with the WebView compositor. Only their
 * *top* is anchored to the top of the HTML spacer they sit on. As the
 * user zooms in, the spacer's device height grows past the overlay's
 * fixed device height, creating a visible gap between the overlay's
 * bottom and the DOM content that follows — this is the same "extra
 * space during pinch" Gmail exposes. It disappears again at zoom = 1.
 *
 * Spacer CSS heights are pushed as `measuredHeight / initialScale` — a
 * *constant* number of CSS px, invariant to user pinch. Pushing a new
 * spacer height per pinch tick would force a JS reflow every frame,
 * mid-pinch, producing visible flicker in the WebView and jumpy overlay
 * positions.
 *
 * ### Realtime viewport (JS `visualViewport` → `Bridge.onViewport`)
 * `WebViewClient.onScaleChanged` is sparse and lags the compositor by 1-2
 * frames — using it directly as the source of truth for overlay position
 * produced visible per-frame jerks during pinch. We instead listen to the
 * DOM `visualViewport` events in `conversation.js` and forward `{scale,
 * pageTop}` to [onViewportUpdate] every frame the compositor moves. That
 * feeds [positionOverlays] with the *actual* current compositor state, so
 * overlays track the DOM without lag and no damping is required.
 *
 * ### Ownership
 * * The WebView owns all vertical scrolling. It fills the container.
 * * Overlays are laid out via [View.layout] to their target device-px
 *   position each frame that a scroll, scale, or overlay-measurement change
 *   is scheduled through [requestGeometryUpdate].
 *
 * ### Coordinate spaces
 * * **CSS px** — what JS reports (`getBoundingClientRect().top`).
 * * **Device px** — CSS px × [ConversationWebView.currentScale].
 * * **Screen px (container-local)** — device px − `webView.scrollY`.
 *
 * ### Flow
 * ```
 *   caller ─setOverlays()─▶ container (measure) ─setSpacerHeight(id,cssPx)─▶ WebView JS
 *                                                                             │
 *   container ◀────────── onGeometry(json) via @JavascriptInterface ◀─── measurePositions()
 *                                     │
 *                                     ▼
 *                       requestGeometryUpdate() → 1 layout pass per frame
 * ```
 */
class ConversationContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    val webView: ConversationWebView = ConversationWebView(context)

    private val overlays: LinkedHashMap<String, Overlay> = LinkedHashMap()

    /** Total document height in CSS px, most recently reported by JS. */
    var contentHeightCss: Float = 0f
        private set

    /**
     * Invoked from [ConversationWebView.ScrollListener] with the WebView's
     * current scrollY (device px). Consumers use it to drive UI state like
     * the compact app bar swap.
     */
    var onScrollChanged: (scrollY: Int) -> Unit = {}

    /**
     * Measured device-px height of the overlay whose id is
     * [APP_BAR_OVERLAY_ID], or 0 if no such overlay is registered / it
     * hasn't been measured yet. Fires whenever the value changes so the
     * screen can compute the compact-bar swap threshold exactly.
     */
    var onAppBarHeightChanged: (heightPx: Int) -> Unit = {}

    private var appBarHeightPx: Int = 0

    /** Set by [requestGeometryUpdate]; cleared inside the postOnAnimation runnable. */
    private var geometryUpdatePosted: Boolean = false

    private class Overlay(
        val id: String,
        val view: View,
        var topCss: Float = 0f,
        var heightCss: Float = 0f,
        var lastSentSpacerCssPx: Int = -1,
        /** True once JS has reported a real geometry for this overlay. */
        var positioned: Boolean = false
    )

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var gestureInitialX: Float = 0f
    private var gestureInitialY: Float = 0f
    private var gestureClaimed: Boolean = false
    /**
     * True only when the DOWN of the current gesture landed on a native
     * overlay. When the DOWN was on the bare WebView, we don't intercept —
     * the WebView already handles scroll and pinch itself, and stealing
     * mid-gesture would cancel its in-flight handling.
     */
    private var interceptForCurrentGesture: Boolean = false

    /**
     * True while ≥ 2 pointers are down (i.e. a pinch is in progress on
     * the WebView's native compositor).
     *
     * Overlays follow the pinch every frame via [onViewportUpdate], so no
     * damping / freeze is needed — they stay flush with their spacer at
     * any scale.
     *
     * The flag still gates scrollY propagation to the outer CompactAppBar
     * swap threshold — WebView adjusts scrollY per pinch tick to keep the
     * focal point stable, and those transients would flap the compact-bar
     * reveal.
     */
    private var pinchActive: Boolean = false

    /* ── Realtime viewport state (JS bridge → [onViewportUpdate]) ───────── */

    /** Latest `visualViewport.scale` reported from JS, or 1 if none yet. */
    private var bridgeScale: Float = 1f

    /** Latest `visualViewport.pageTop` (CSS px) reported from JS. */
    private var bridgePageTopCss: Float = 0f

    /** True after the JS reporter has fired at least once. */
    private var bridgeHasValue: Boolean = false

    companion object {
        const val APP_BAR_OVERLAY_ID: String = "app-bar"
    }

    init {
        // Defensive clipping: overlays live at (0,0,w,h) with negative
        // translationY when scrolled off-top. We must NOT let them bleed
        // above the container's bounds (which sit below the status-bar
        // inset once Scaffold applies contentWindowInsets), otherwise
        // native compose content shows in the status-bar area.
        clipChildren = true
        clipToPadding = true
        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        webView.scrollListener = ConversationWebView.ScrollListener { newY, _ ->
            // Reposition SYNCHRONOUSLY in the same frame the WebView reports
            // its new scrollY. Routing through postOnAnimation would defer by
            // one frame and produce a visible lag between the HTML content
            // (already at new scrollY) and the overlay (still at old y).
            positionOverlays()
            // During pinch the WebView adjusts scrollY per-frame to keep the
            // focal point stable. Propagating those transients would make the
            // compact-app-bar swap threshold flap and the CompactAppBar
            // animate in/out mid-gesture. Freeze the outer scrollY state
            // until pinch ends; setJsPinchActive(false) re-fires it once.
            if (!pinchActive) onScrollChanged(newY)
        }
        webView.scaleListener = ConversationWebView.ScaleListener {
            // Same reasoning as scroll: apply in the same frame the WebView
            // committed the new scale.
            positionOverlays()
            // No spacer push here — spacer CSS is invariant to zoom (see
            // pushSpacerHeights). Pushing per pinch tick was the previous
            // behavior and caused visible WebView reflow flicker.
        }
    }

    /* ── Public API ─────────────────────────────────────────────────────── */

    /**
     * Replace the current set of overlays. Order is preserved (LinkedHashMap).
     * A subsequent [requestLayout] will measure overlays and push spacer
     * heights to the WebView.
     */
    fun setOverlays(items: List<Pair<String, View>>) {
        var changed = false
        val newIds = items.mapTo(mutableSetOf()) { it.first }
        val toRemove = overlays.keys - newIds
        for (id in toRemove) {
            val o = overlays.remove(id) ?: continue
            removeView(o.view)
            changed = true
        }
        for ((id, view) in items) {
            if (overlays[id]?.view === view) continue
            overlays[id]?.let { removeView(it.view) }
            overlays[id] = Overlay(id = id, view = view)
            addView(
                view,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
            changed = true
        }
        if (changed) requestLayout()
    }

    /**
     * Bridge callback — invoked from JS with the full geometry payload.
     * Runs on the WebView's binder thread; posts to the UI thread.
     */
    fun onGeometryJson(payloadJson: String) {
        post { applyGeometry(payloadJson) }
    }

    /**
     * Bridge callback — invoked from JS `visualViewport` scroll/resize
     * events with the compositor's current [scale] (pinch factor, 1.0 =
     * no zoom) and [pageTopCss] (CSS px scrolled from top of document).
     *
     * Runs on the WebView's binder thread; posts to the UI thread and
     * repositions overlays synchronously so they track the DOM without
     * waiting for the sparse `WebViewClient.onScaleChanged` callback.
     */
    fun onViewportUpdate(scale: Float, pageTopCss: Float) {
        post {
            bridgeScale = scale
            bridgePageTopCss = pageTopCss
            bridgeHasValue = true
            positionOverlays()
        }
    }

    private fun applyGeometry(payloadJson: String) {
        try {
            val json = JSONObject(payloadJson)
            contentHeightCss = json.optDouble("contentHeight", 0.0).toFloat()
            val arr = json.optJSONArray("overlays") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                val ov = overlays[id] ?: continue
                ov.topCss = o.optDouble("top", 0.0).toFloat()
                ov.heightCss = o.optDouble("height", 0.0).toFloat()
                ov.positioned = true
            }
            // (Re-)push spacer heights: applyGeometry is the first callback
            // after renderThread() rebuilds the DOM, so any push we tried
            // earlier hit a non-existent element. Dedup by lastSentSpacerCssPx
            // prevents an infinite JS ↔ native ping-pong when heights match.
            pushSpacerHeights()
            // topCss changed — reposition synchronously so the UI doesn't
            // flicker one frame behind the DOM update.
            positionOverlays()
        } catch (_: Exception) {
            // Malformed payloads are non-fatal — next measure will retry.
        }
    }

    /**
     * Reset the "last spacer height sent" cache. Call this before
     * [ConversationWebView.evaluateJavascript]-ing a new `renderThread(...)`
     * because the DOM is fully replaced and prior spacer height writes are
     * lost.
     */
    fun resetSpacerHeightCache() {
        for (o in overlays.values) o.lastSentSpacerCssPx = -1
    }

    /* ── Touch: forward vertical drag & pinch from overlays to the WebView ─ */

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Track pinch state here (not in onInterceptTouchEvent) because once
        // we've intercepted a gesture, onInterceptTouchEvent stops being
        // called — but a user can still put a second finger down mid-scroll
        // to start a pinch, and we need to see it.
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2) pinchActive = true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // pointerCount includes the pointer being lifted. When
                // dropping back to 1 finger, the pinch is over.
                if (ev.pointerCount <= 2) endPinch()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endPinch()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun endPinch() {
        if (!pinchActive) return
        pinchActive = false
        // Flush the scrollY we suppressed during the pinch so the outer
        // CompactAppBar swap threshold catches up to the settled state.
        onScrollChanged(webView.scrollY)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureInitialX = ev.x
                gestureInitialY = ev.y
                gestureClaimed = false
                interceptForCurrentGesture = isTouchOnOverlay(ev.x, ev.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 2nd finger on an overlay → user is starting a pinch on top
                // of a native view. Steal so the WebView, not the overlay,
                // gets both pointers. If DOWN was on the WebView, it's already
                // handling multi-touch itself — leave it alone.
                if (interceptForCurrentGesture && !gestureClaimed) {
                    claimGesture(ev)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!interceptForCurrentGesture) return false
                if (gestureClaimed) return true
                val dy = abs(ev.y - gestureInitialY)
                val dx = abs(ev.x - gestureInitialX)
                if (dy > touchSlop && dy > dx) {
                    claimGesture(ev)
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                gestureClaimed = false
                interceptForCurrentGesture = false
            }
        }
        return false
    }

    private fun isTouchOnOverlay(x: Float, y: Float): Boolean {
        // Overlays are laid out at (0, 0, w, h), moved via translationY, and
        // kept at density size (scaleY = 1) regardless of pinch — see
        // [positionOverlays].
        for (o in overlays.values) {
            if (!o.positioned || o.view.visibility != View.VISIBLE) continue
            val top = o.view.translationY
            val bottom = top + o.view.measuredHeight
            if (y >= top && y <= bottom) return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Once we've intercepted (scroll or pinch), pipe every event through
        // to the WebView so its scroller / ScaleGestureDetector see the whole
        // stream. Also forward the terminal UP/CANCEL for clean gesture end.
        return webView.dispatchTouchEvent(ev)
    }

    private fun claimGesture(currentEv: MotionEvent) {
        gestureClaimed = true
        // WebView never saw the ACTION_DOWN because a child overlay claimed it.
        // Synthesize one at the original press location so the WebView's touch
        // handlers (scroll anchor, ScaleGestureDetector) have a starting point.
        val down = MotionEvent.obtain(
            currentEv.downTime,
            currentEv.eventTime,
            MotionEvent.ACTION_DOWN,
            gestureInitialX,
            gestureInitialY,
            currentEv.metaState
        )
        webView.dispatchTouchEvent(down)
        down.recycle()
    }

    /* ── ViewGroup lifecycle ────────────────────────────────────────────── */

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChild(webView, widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val overlayWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val overlayHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (o in overlays.values) {
            o.view.measure(overlayWidthSpec, overlayHeightSpec)
        }
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(MeasureSpec.getSize(heightMeasureSpec), heightMeasureSpec)
        )
        publishAppBarHeightIfChanged()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        webView.layout(0, 0, w, h)
        // Overlays are laid out ONCE at (0, 0, ow, oh). Their screen y is
        // driven by View.translationY in positionOverlays() so that scroll
        // and pinch reposition in a single draw pass with no layout/measure
        // invalidation — the cheapest possible per-frame movement.
        for (o in overlays.values) {
            val ow = o.view.measuredWidth
            val oh = o.view.measuredHeight
            o.view.layout(0, 0, ow, oh)
        }
        positionOverlays()
        // Measurement may have produced a new measuredHeight → the CSS
        // spacer needs a fresh height. Batched via the coordinator because
        // this only fires on layout, not on scroll/pinch frames.
        requestGeometryUpdate()
    }

    /* ── Geometry coordinator ───────────────────────────────────────────── */

    /**
     * Coalesces scroll / scale / measurement change events into a single
     * geometry recomputation per animation frame. Every source that would
     * otherwise call [positionOverlays] or [pushSpacerHeights] directly
     * routes through here so a pinch + scroll + AppBar swap firing in the
     * same frame produces one consistent layout pass instead of three
     * competing ones with different state snapshots.
     */
    private fun requestGeometryUpdate() {
        if (geometryUpdatePosted) return
        geometryUpdatePosted = true
        postOnAnimation {
            geometryUpdatePosted = false
            pushSpacerHeights()
            positionOverlays()
        }
    }

    /* ── Coordinate math ────────────────────────────────────────────────── */

    /**
     * Moves every overlay so its top is anchored to the top of its HTML
     * spacer in device px. Overlays keep their density-only measured size —
     * they do NOT scale with pinch; only translationY moves them.
     *
     * Position rule (CSS-px offsets, converted at the end):
     * ```
     *   pinchFactor    = visualViewport.scale (from JS bridge)
     *   effectiveScale = pinchFactor * initialScale
     *   screenY        = (topCss - pageTopCss) * effectiveScale
     * ```
     * Both `topCss` and `pageTopCss` come from the same CSS-px coordinate
     * space (the layout viewport), so subtracting them stays consistent
     * regardless of what pinch factor the compositor is applying — no
     * cross-source lag between "how zoomed" and "how scrolled".
     *
     * Before the bridge fires we fall back to the WebView's native
     * `currentScale`/`scrollY` so the first frame after page load isn't
     * blank. Once JS reports once, we stay on the bridge path.
     */
    fun positionOverlays() {
        val initial = webView.initialScale.takeIf { it > 0f } ?: 1f
        val pinchFactor: Float
        val pageTopCss: Float
        if (bridgeHasValue) {
            pinchFactor = bridgeScale
            pageTopCss = bridgePageTopCss
        } else {
            val current = webView.currentScale.takeIf { it > 0f } ?: 1f
            pinchFactor = current / initial
            // webView.scrollY is device px; convert back to CSS px so the
            // math below is uniform.
            pageTopCss = if (current > 0f) webView.scrollY / current else 0f
        }
        val effectiveScale = pinchFactor * initial
        val viewportH = height
        for (o in overlays.values) {
            val topPx = (o.topCss - pageTopCss) * effectiveScale
            // Overlays stay at density size — no visual scaling. Reset
            // defensively in case a previous frame left them scaled.
            if (o.view.scaleX != 1f) o.view.scaleX = 1f
            if (o.view.scaleY != 1f) o.view.scaleY = 1f
            // translationY takes a float — no roundToInt() so we don't
            // introduce ±1px jitter on each frame from subpixel scale
            // values. The renderer snaps to device pixels at draw time.
            if (o.view.translationY != topPx) o.view.translationY = topPx
            val visualH = o.view.measuredHeight
            val onScreen = topPx + visualH > 0f && topPx < viewportH
            // Suppress a newly-added overlay until JS reports its actual
            // position — otherwise it briefly renders at translationY=0
            // (on top of the app bar).
            val desired = if (o.positioned && onScreen) View.VISIBLE else View.INVISIBLE
            if (o.view.visibility != desired) o.view.visibility = desired
        }
    }

    /**
     * Push each overlay's spacer CSS-px height into the HTML DOM.
     *
     * `cssPx = measuredHeight / initialScale` — a **constant** number of
     * CSS px, invariant to user pinch. At any zoom, the WebView compositor
     * scales the spacer to `measuredHeight × pinchFactor` device px, and
     * [positionOverlays] scales the overlay by the same `pinchFactor`, so
     * the two stay flush without any per-pinch reflow.
     *
     * Called from [requestGeometryUpdate] (layout / bridge-report paths).
     * We deliberately do NOT push on scale changes — a JS reflow per
     * pinch tick would stall the WebView compositor and produce visible
     * flicker (bug reported 2026-08-26).
     */
    private fun pushSpacerHeights() {
        val initial = webView.initialScale
        // WebView reports 1.0 before onPageFinished sets the density-scale.
        // Skip pushing until we have a real value; onPageFinished re-fires
        // scaleListener → coordinator → this method with the correct scale.
        if (initial <= 0f) return
        for (o in overlays.values) {
            val measured = o.view.measuredHeight
            if (measured <= 0) continue
            val cssPx = (measured / initial).roundToInt()
            if (cssPx <= 0 || cssPx == o.lastSentSpacerCssPx) continue
            o.lastSentSpacerCssPx = cssPx
            val id = o.id.replace("'", "\\'")
            webView.evaluateJavascript("setSpacerHeight('$id', $cssPx)", null)
        }
    }

    private fun publishAppBarHeightIfChanged() {
        val h = overlays[APP_BAR_OVERLAY_ID]?.view?.measuredHeight ?: 0
        if (h != appBarHeightPx) {
            appBarHeightPx = h
            onAppBarHeightChanged(h)
        }
    }
}
