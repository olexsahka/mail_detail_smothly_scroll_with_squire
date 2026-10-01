package com.alex.mailstubdetails.perf

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Window
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.FrameDataApi24
import androidx.metrics.performance.FrameDataApi31
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Frame-metrics collector for MailStub.
 *
 * Wraps `androidx.metrics:metrics-performance` (JankStats) — the AndroidX
 * equivalent of the HWUI "Profile GPU rendering" bars. Emits several
 * record kinds to logcat under [TAG]:
 *
 *  - **Per-jank line** (WARN): every janky frame, with UI/CPU/GPU/total
 *    duration breakdown when available (API 24+/31+) plus the state
 *    tags active on that frame ("screen=thread gesture=pinch").
 *  - **Rolling summary per gesture** (INFO): emitted when a per-bucket
 *    window fills (every [SUMMARY_WINDOW] frames). Buckets are keyed
 *    by the active `gesture` state (plus `idle` for frames with no
 *    gesture tag). Each line includes `gesture=<name>` so you can
 *    compare pinch vs. scroll vs. idle directly.
 *  - **Bridge rate line** (INFO, 1 Hz): count of JS→Kotlin bridge
 *    calls per second — see [recordBridgeCall]. Lets you see whether
 *    `visualViewport` reports are keeping up with refresh rate during
 *    pinch.
 *  - **JS metric lines** (INFO/WARN): forwarded from `conversation.js`
 *    via [recordJsLongtask] / [recordJsFrameStats] — covers jank
 *    happening *inside* the WebView compositor/JS that JankStats
 *    cannot see.
 *
 * All numeric formatting uses [Locale.ROOT] so decimal separators stay
 * `.` even on locales like ru_RU — otherwise the skill parser breaks.
 */
class PerfCollector(
    private val window: Window,
    stateOwner: View,
) {
    private val holder: PerformanceMetricsState.Holder =
        PerformanceMetricsState.getHolderForHierarchy(stateOwner)

    private var jankStats: JankStats? = null

    private val buckets: MutableMap<String, Bucket> = HashMap()

    /** 1 Hz tick for bridge rate + rAF-style aggregates. */
    private val handler = Handler(Looper.getMainLooper())
    private var bridgeViewportCalls = 0
    private var bridgeGeometryCalls = 0
    private val rateTicker = object : Runnable {
        override fun run() {
            flushBridgeRate()
            handler.postDelayed(this, BRIDGE_RATE_INTERVAL_MS)
        }
    }

    fun start() {
        if (jankStats != null) return
        jankStats = JankStats.createAndTrack(window) { data -> onFrame(data) }
        handler.postDelayed(rateTicker, BRIDGE_RATE_INTERVAL_MS)
    }

    fun stop() {
        jankStats?.isTrackingEnabled = false
        jankStats = null
        handler.removeCallbacks(rateTicker)
    }

    /** Tag every following frame with [key]=[value] until [removeState]. */
    fun putState(key: String, value: String) {
        holder.state?.putState(key, value)
    }

    /** Tag only the next frame — good for one-shot events. */
    fun putSingleFrameState(key: String, value: String) {
        holder.state?.putSingleFrameState(key, value)
    }

    fun removeState(key: String) {
        holder.state?.removeState(key)
    }

    /**
     * Count one JS→Kotlin bridge call. [kind] must be one of
     * [BRIDGE_KIND_VIEWPORT] / [BRIDGE_KIND_GEOMETRY]. Called from the
     * bridge interface methods in `ConversationView` to measure how
     * often the WebView compositor reports back during interaction.
     *
     * Thread-safety note: bridge calls arrive on the WebView binder
     * thread, so increments use a single volatile int per kind and
     * are flushed on the main thread by [rateTicker]. Races on
     * increment are acceptable — we care about rates, not exact
     * counts.
     */
    fun recordBridgeCall(kind: String) {
        when (kind) {
            BRIDGE_KIND_VIEWPORT -> bridgeViewportCalls++
            BRIDGE_KIND_GEOMETRY -> bridgeGeometryCalls++
        }
    }

    /**
     * Record a JS long-task (>50 ms) reported by `PerformanceObserver`
     * inside the conversation WebView. These are tasks that *didn't*
     * necessarily cause a dropped native frame (WebView may have its
     * own compositor thread), but still indicate work that could
     * block user input or JS-driven callbacks like `visualViewport`.
     */
    fun recordJsLongtask(durationMs: Double, name: String) {
        Log.w(
            TAG,
            String.format(Locale.ROOT, "js-longtask dur=%.1fms name=%s", durationMs, name)
        )
    }

    /**
     * Record a 1 Hz slice of `requestAnimationFrame` statistics from
     * the WebView: [fps] = rAF callbacks fired this second, [longFrames]
     * = number of rAF deltas that exceeded the frame budget. Lets you
     * see whether the WebView's internal frame cadence matches the
     * display rate during pinch.
     */
    fun recordJsFrameStats(fps: Int, longFrames: Int) {
        Log.i(
            TAG,
            String.format(Locale.ROOT, "js-frames fps=%d long=%d", fps, longFrames)
        )
    }

    private fun onFrame(data: FrameData) {
        val uiMs = data.frameDurationUiNanos.toMs()
        val gesture = data.states.firstOrNull { it.key == PerfKeys.GESTURE }?.value
            ?: BUCKET_IDLE
        val bucket = buckets.getOrPut(gesture) { Bucket() }
        bucket.record(uiMs, data.isJank)

        if (data.isJank) {
            Log.w(TAG, formatJankLine(data, uiMs))
        }

        if (bucket.filled()) {
            Log.i(TAG, bucket.formatSummary(gesture))
            bucket.reset()
        }
    }

    private fun flushBridgeRate() {
        val vp = bridgeViewportCalls
        val geo = bridgeGeometryCalls
        bridgeViewportCalls = 0
        bridgeGeometryCalls = 0
        if (vp == 0 && geo == 0) return
        Log.i(
            TAG,
            String.format(Locale.ROOT, "bridge-rate viewport=%d/s geometry=%d/s", vp, geo)
        )
    }

    private fun formatJankLine(data: FrameData, uiMs: Float): String {
        val builder = StringBuilder("jank ui=")
            .append(String.format(Locale.ROOT, "%.1f", uiMs)).append("ms")
        if (data is FrameDataApi24) {
            builder.append(" cpu=")
                .append(String.format(Locale.ROOT, "%.1f", data.frameDurationCpuNanos.toMs()))
                .append("ms")
        }
        if (data is FrameDataApi31) {
            builder.append(" total=")
                .append(String.format(Locale.ROOT, "%.1f", data.frameDurationTotalNanos.toMs()))
                .append("ms")
            builder.append(" overrun=")
                .append(String.format(Locale.ROOT, "%.1f", data.frameOverrunNanos.toMs()))
                .append("ms")
        }
        if (data.states.isNotEmpty()) {
            builder.append(' ')
            data.states.joinTo(builder, separator = " ") { "${it.key}=${it.value}" }
        }
        return builder.toString()
    }

    private fun Long.toMs(): Float = this / NANOS_PER_MS

    /**
     * One rolling window of frame durations for a single `gesture`
     * state. Reset after each [formatSummary] emission.
     */
    private class Bucket {
        private val durationsMs = FloatArray(SUMMARY_WINDOW)
        private var frames = 0
        private var jank = 0

        fun record(uiMs: Float, isJank: Boolean) {
            durationsMs[frames] = uiMs
            frames++
            if (isJank) jank++
        }

        fun filled(): Boolean = frames >= SUMMARY_WINDOW

        fun reset() {
            frames = 0
            jank = 0
        }

        fun formatSummary(gesture: String): String {
            val sorted = durationsMs.copyOf(frames).also { it.sort() }
            val p50 = percentile(sorted, 0.50f)
            val p90 = percentile(sorted, 0.90f)
            val p99 = percentile(sorted, 0.99f)
            val jankPct = 100f * jank / frames
            val apiTag = if (Build.VERSION.SDK_INT >= 31) "api31+" else "api${Build.VERSION.SDK_INT}"
            return String.format(
                Locale.ROOT,
                "summary[%s] gesture=%s frames=%d jank=%d (%.1f%%) p50=%.1fms p90=%.1fms p99=%.1fms",
                apiTag, gesture, frames, jank, jankPct, p50, p90, p99
            )
        }

        private fun percentile(sortedAscMs: FloatArray, q: Float): Float {
            if (sortedAscMs.isEmpty()) return 0f
            val idx = ((sortedAscMs.size - 1) * q).toInt().coerceIn(0, sortedAscMs.size - 1)
            return sortedAscMs[idx]
        }
    }

    companion object {
        const val TAG: String = "MailStubPerf"

        /** Bucket name used when the frame has no `gesture` state tag. */
        const val BUCKET_IDLE: String = "idle"

        const val BRIDGE_KIND_VIEWPORT: String = "viewport"
        const val BRIDGE_KIND_GEOMETRY: String = "geometry"

        /** Per-bucket window size — one summary line every N frames of that gesture. */
        private const val SUMMARY_WINDOW: Int = 120

        /** Bridge rate aggregation period. */
        private const val BRIDGE_RATE_INTERVAL_MS: Long = 1000L

        private val NANOS_PER_MS: Float =
            TimeUnit.MILLISECONDS.toNanos(1).toFloat()
    }
}
