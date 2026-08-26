package com.alex.mailstubdetails.ui.conversation

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import org.json.JSONObject

/**
 * ViewGroup that hosts a single [ConversationWebView] plus N native overlay
 * Views positioned on top of it — the AOSP UnifiedEmail pattern.
 *
 * ### Ownership
 * * The WebView owns all vertical scrolling. It fills the container.
 * * Overlays are laid out at (0, 0) and moved by [View.setTranslationY]
 *   to their target position each frame the WebView reports a scroll or a
 *   scale change.
 *
 * ### Coordinate spaces
 * * **CSS px** — what JS reports (`getBoundingClientRect().top`).
 * * **Device px** — CSS px × [ConversationWebView.currentScale].
 * * **Screen px** — device px − `webView.scrollY`.
 *
 * ### Flow
 * ```
 *   caller ─setOverlays()─▶ container (measure) ─setSpacerHeight(id,cssPx)─▶ WebView JS
 *                                                                             │
 *   container ◀────────── onGeometry(json) via @JavascriptInterface ◀─── measurePositions()
 *                                     │
 *                                     ▼
 *                                positionOverlays()
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

    private class Overlay(
        val id: String,
        val view: View,
        var topCss: Float = 0f,
        var heightCss: Float = 0f,
        var lastSentSpacerCssPx: Int = -1,
        /** True once JS has reported a real geometry for this overlay. */
        var positioned: Boolean = false
    )

    companion object {
        const val APP_BAR_OVERLAY_ID: String = "app-bar"
    }

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
     * True while ≥2 pointers are down anywhere in the container — treated as
     * an in-progress pinch. [positionOverlays] early-returns while this is set
     * so overlays stay frozen at their pre-pinch screen positions; on the
     * final UP we unfreeze and snap to the new content-anchored positions.
     */
    private var isPinching: Boolean = false

    init {
        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        webView.scrollListener = ConversationWebView.ScrollListener { newY, _ ->
            positionOverlays()
            onScrollChanged(newY)
        }
        webView.scaleListener = ConversationWebView.ScaleListener {
            // Overlays scaleX/scaleY track webView.currentScale (see
            // positionOverlays), so their visual size matches the DOM spacer
            // at every pinch frame — no need to push a new spacer css height.
            positionOverlays()
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
        // Skip requestLayout when nothing changed. Compose recompositions
        // (fired on every scroll delta during pinch) can otherwise trigger
        // repeated re-measure passes that make overlays wobble visually.
        if (changed) requestLayout()
    }

    /**
     * Bridge callback — invoked from JS with the full geometry payload.
     * Runs on the WebView's binder thread; posts to the UI thread.
     */
    fun onGeometryJson(payloadJson: String) {
        post { applyGeometry(payloadJson) }
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
            positionOverlays()
            // The DOM now definitely contains our spacer elements — push the
            // measured overlay heights so JS reserves the right amount of
            // room and re-reports positions.
            pushSpacerHeights()
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
        val pinchFactor = currentPinchFactor()
        for (o in overlays.values) {
            if (!o.positioned || o.view.visibility != View.VISIBLE) continue
            val topDev = o.view.translationY
            val botDev = topDev + o.view.measuredHeight * pinchFactor
            // Overlays are full-width; only y needs bounds testing.
            if (y in topDev..botDev) return true
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
        pushSpacerHeights()
        publishAppBarHeightIfChanged()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        webView.layout(0, 0, w, h)
        for (o in overlays.values) {
            val ow = o.view.measuredWidth
            val oh = o.view.measuredHeight
            o.view.layout(0, 0, ow, oh)
        }
        positionOverlays()
    }

    /* ── Coordinate math ────────────────────────────────────────────────── */

    private fun currentPinchFactor(): Float {
        // webView.currentScale = density × pinch (≈3.0 on a 3x device at rest).
        // Only the pinch part should visually resize overlays; the density
        // part is already baked into their measuredHeight (device px).
        val scale = webView.currentScale.takeIf { it > 0f } ?: 1f
        val initial = webView.initialScale.takeIf { it > 0f } ?: 1f
        return scale / initial
    }

    fun positionOverlays() {
        val scale = webView.currentScale.takeIf { it > 0f } ?: 1f
        val pinchFactor = currentPinchFactor()
        val scrollY = webView.scrollY
        val viewportH = height
        for (o in overlays.values) {
            val topDevice = (o.topCss * scale) - scrollY
            // scaleX/Y = pinch only, pivot top-left so overlays grow down/right
            // from their content anchor and stay flush with their DOM spacer at
            // every zoom level — instead of the overlay keeping its unscaled
            // device size while the spacer around it grows with the pinch.
            o.view.pivotX = 0f
            o.view.pivotY = 0f
            o.view.scaleX = pinchFactor
            o.view.scaleY = pinchFactor
            o.view.translationY = topDevice
            val scaledHeight = o.view.measuredHeight * pinchFactor
            val bottomDevice = topDevice + scaledHeight
            val onScreen = bottomDevice > 0f && topDevice < viewportH
            // Suppress a newly-added overlay until JS reports its actual
            // position — otherwise it briefly renders at translationY=0
            // (on top of the app bar).
            val desired = if (o.positioned && onScreen) View.VISIBLE else View.INVISIBLE
            if (o.view.visibility != desired) o.view.visibility = desired
        }
    }

    private fun pushSpacerHeights() {
        // Spacer device height = cssPx × webView.currentScale (density × pinch).
        // Overlay visual device height = measuredHeight × pinchFactor.
        // For them to match at every pinch: cssPx = measuredHeight / initialScale
        // — a constant, so no need to re-push during pinch.
        val initial = webView.initialScale.takeIf { it > 0f } ?: 1f
        for (o in overlays.values) {
            val cssPx = (o.view.measuredHeight / initial).toInt()
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
