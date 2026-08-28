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
 * ### Invariant (Gmail-style: static overlays, compressed chains)
 * Overlays keep their density-only Compose size at every pinch factor —
 * no visual scaling, no DOM reflow during pinch. To hide the per-spacer
 * "overshoot" (spacer_device = M*P grows past the fixed overlay height
 * M when zoomed in), [positionOverlays] walks overlays in DOM order and
 * "compresses" each contiguous chain: within a chain of adjacent
 * spacers (no body content between), each overlay is placed right after
 * the previous overlay's bottom, so they stay flush regardless of zoom.
 * The accumulated overshoot spills out below the chain (either into an
 * empty area before the next body, or scrollable empty space at the
 * end of the thread — visible only if the user scrolls there).
 *
 * We deliberately do NOT mutate spacer CSS heights during pinch. Trying
 * that raced the WebView compositor's raster pipeline and produced
 * visible content flicker (bug reported 2026-08-27).
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

    /**
     * Current best estimate of `visualViewport.pageTop` in CSS px.
     *
     * Two sources feed this field:
     *  1. JS `onViewport(scale, pageTopCss)` — **atomic** snapshot of the
     *     compositor state. This is the only correct value during pinch,
     *     when `bridgeScale` is also changing per frame.
     *  2. Native `scrollListener` — predicts `pageTopCss = scrollY /
     *     effectiveScale` from the freshly-updated native `scrollY`.
     *     Used when **not** pinching so the value doesn't lag by 1 frame
     *     while the compositor scrolls between JS reports.
     *
     * Both writers hit the main thread, so last-write-wins per frame.
     * We guard prediction behind `!pinchActive` so we never overwrite the
     * atomic JS snapshot with a stale-scale prediction mid-pinch.
     */
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
            // Predict bridgePageTopCss from the fresh native scrollY when
            // NOT pinching. Two purposes:
            //   • Between JS visualViewport events the compositor keeps
            //     scrolling, and bridgePageTopCss from the last JS report
            //     would lag by up to 1 frame → visible ~1-3 px overlay
            //     wobble against DOM content. Prediction closes that gap.
            //   • Uses the last known bridgeScale, so the single bridge
            //     formula `(topCss - bridgePageTopCss) * effectiveScale`
            //     stays valid at any zoom level, not just 1×.
            // Skip during pinch — JS fires per-frame with an atomic
            // (scale, pageTopCss) snapshot, and a native prediction using
            // the previous-frame scale would break atomicity and re-
            // introduce drift.
            if (!pinchActive && bridgeHasValue) {
                val initial = webView.initialScale.takeIf { it > 0f } ?: 1f
                val effectiveScale = bridgeScale * initial
                if (effectiveScale > 0f) {
                    bridgePageTopCss = newY / effectiveScale
                }
            }
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
     * Replace the current set of overlays with [items]. The resulting
     * iteration order of the internal [overlays] LinkedHashMap **exactly**
     * matches [items] — that order must mirror the DOM order of the
     * `[data-overlay]` spacers, because [positionOverlays] walks the map
     * once per frame and uses `prev` for chain-compression contiguity
     * checks (`gap = curr.topCss - (prev.topCss + prev.heightCss)`). If
     * the walk order didn't match DOM order, `prev` would be a wrong
     * neighbor, and a negative `gap` (curr is above prev in DOM) would
     * trip the `gap <= 0.5` check and pull the current overlay to sit
     * flush under a much lower element — visible symptom: overlays that
     * "disappear" past the bottom of the viewport after a mid-thread
     * expand.
     *
     * Preserves per-overlay state (`topCss`, `heightCss`, `positioned`,
     * `lastSentSpacerCssPx`) when the same `(id, view)` pair reappears.
     */
    fun setOverlays(items: List<Pair<String, View>>) {
        val currentIds = overlays.keys.toList()
        val newIds = items.map { it.first }
        val sameOrder = currentIds == newIds
        val sameViews = sameOrder &&
            items.all { (id, view) -> overlays[id]?.view === view }
        if (sameViews) return

        val newIdSet = newIds.toHashSet()
        val toRemove = overlays.keys.filter { it !in newIdSet }
        for (id in toRemove) {
            overlays.remove(id)?.let { removeView(it.view) }
        }

        // Rebuild in items order. Existing Overlay objects are reused
        // (state preserved). New/replaced views trigger addView.
        val rebuilt = LinkedHashMap<String, Overlay>(items.size)
        for ((id, view) in items) {
            val existing = overlays[id]
            val overlay = if (existing != null && existing.view === view) {
                existing
            } else {
                existing?.let { removeView(it.view) }
                addView(
                    view,
                    LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                    )
                )
                Overlay(id = id, view = view)
            }
            rebuilt[id] = overlay
        }
        overlays.clear()
        overlays.putAll(rebuilt)
        requestLayout()
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
        // Rewrite bridgePageTopCss to what the fresh native scrollY
        // implies at the current bridgeScale — same math as the
        // scrollListener's non-pinch prediction. This aligns the bridge
        // value (which was last set by the JS atomic snapshot on the
        // last pinch frame) with what the compositor has actually
        // settled on, so the overlay position stays continuous across
        // the pinch → scroll boundary without any decay/blend.
        if (bridgeHasValue) {
            val initial = webView.initialScale.takeIf { it > 0f } ?: 1f
            val effectiveScale = bridgeScale * initial
            if (effectiveScale > 0f) {
                bridgePageTopCss = webView.scrollY.toFloat() / effectiveScale
            }
        }
        // Flush the scrollY we suppressed during the pinch so the outer
        // CompactAppBar swap threshold catches up to the settled state.
        onScrollChanged(webView.scrollY)
        positionOverlays()
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
     * Places every overlay at its target device-px position, with per-chain
     * compression so contiguous spacers stay flush at any zoom.
     *
     * Two positioning modes, decided per overlay by walking in DOM order:
     * ```
     *   naturalTopPx    = (topCss - pageTopCss) * effectiveScale
     *   compressedTopPx = prev.translationY + prev.measuredHeight
     * ```
     * An overlay is *contiguous* with the previous one when its spacer's
     * top CSS matches the previous spacer's bottom (i.e., there's no body
     * content in DOM between them). Contiguous → compressed (chain), else
     * → natural. The accumulated overshoot inside a chain (each spacer
     * grew by `M*(P-1)` in device px) piles up below the chain's last
     * overlay: either into empty space before the next body, or as
     * trailing scrollable emptiness at the end of the thread.
     *
     * Body content stays at its natural DOM position (zoomed by the
     * compositor). Because we place chain overlays *above* their natural
     * DOM position, they cover the top portion of the DOM spacer chain —
     * the excess DOM spacer area falls below the chain and shows as an
     * empty region.
     *
     * Before the JS bridge fires once we fall back to WebView's own
     * `currentScale` / `scrollY` so the first paint isn't blank.
     *
     * A single formula covers all cases:
     * ```
     *   scrollOffsetPx = bridgePageTopCss * effectiveScale
     *   naturalTopPx   = topCss * effectiveScale - scrollOffsetPx
     * ```
     * `bridgePageTopCss` is kept in sync by two writers (see its field
     * KDoc): JS atomic snapshots during pinch, native scroll-based
     * prediction otherwise. That removes the source-switching that
     * previously caused a small overlay snap at the pinch → scroll
     * boundary.
     */
    fun positionOverlays() {
        val initial = webView.initialScale.takeIf { it > 0f } ?: 1f
        val pinchFactor: Float = if (bridgeHasValue) {
            bridgeScale
        } else {
            val current = webView.currentScale.takeIf { it > 0f } ?: 1f
            current / initial
        }
        val effectiveScale = pinchFactor * initial
        val scrollOffsetPx = bridgePageTopCss * effectiveScale
        val viewportH = height
        // Sub-CSS-px tolerance when deciding whether two spacers are
        // adjacent in DOM. JS reports floats from getBoundingClientRect
        // and 0.5px rounding drift is normal.
        val contiguityEpsilon = 0.5f
        var prev: Overlay? = null
        var prevTopPx = 0f
        for (o in overlays.values) {
            val naturalTopPx = o.topCss * effectiveScale - scrollOffsetPx
            val topPx: Float = if (prev != null && prev.positioned && o.positioned) {
                val gapCss = o.topCss - (prev.topCss + prev.heightCss)
                // Require |gap| within epsilon. A strongly-negative gap
                // means the walk order and DOM order disagree, and
                // compressing there would drag the current overlay far
                // below its real DOM position — visible symptom: an
                // overlay "disappears" beyond the viewport bottom after
                // a mid-thread expand. setOverlays keeps the map in DOM
                // order to prevent this; this check forgives any future
                // ordering slip cheaply.
                if (gapCss in -contiguityEpsilon..contiguityEpsilon) {
                    // Contiguous chain: stack directly under previous overlay,
                    // absorbing this spacer's pinch overshoot into the chain.
                    prevTopPx + prev.view.measuredHeight
                } else {
                    // Body content separates the chains — fall back to natural
                    // DOM position so we don't cover the zoomed body.
                    naturalTopPx
                }
            } else {
                naturalTopPx
            }
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
            prev = o
            prevTopPx = topPx
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
