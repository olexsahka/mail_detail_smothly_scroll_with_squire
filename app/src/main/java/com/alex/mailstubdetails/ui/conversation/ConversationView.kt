package com.alex.mailstubdetails.ui.conversation

import android.annotation.SuppressLint
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alex.mailstubdetails.model.EmailThread

/* ── Public API surface for overlays (consumed by Phase C) ─────────────── */

enum class OverlayKind { APP_BAR, MESSAGE_HEADER, MESSAGE_FOOTER }

/**
 * Describes one native overlay that lives above the ConversationWebView.
 * The Compose caller receives these descriptors and returns the overlay
 * composable for each — see [ConversationView].
 */
data class OverlayDescriptor(
    val id: String,
    val kind: OverlayKind,
    val msgId: String?,
    val expanded: Boolean
)

/**
 * Compose entry point for the AOSP-style conversation surface.
 *
 * The [overlayContent] slot is invoked once per descriptor; Phase C fills
 * this with `MessageHeaderOverlay`, `MessageFooterOverlay`, and the large
 * app bar. For now we ship a placeholder so B3 can be verified visually.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ConversationView(
    thread: EmailThread,
    expandedIds: Set<String>,
    modifier: Modifier = Modifier,
    onScrollChanged: (scrollY: Int) -> Unit = {},
    onAppBarHeightChanged: (heightPx: Int) -> Unit = {},
    overlayContent: @Composable (OverlayDescriptor) -> Unit = { DefaultOverlayPlaceholder(it) }
) {
    val descriptors = remember(thread, expandedIds) { buildDescriptors(thread, expandedIds) }
    val latestOverlayContent = rememberUpdatedState(overlayContent)
    val latestOnScrollChanged = rememberUpdatedState(onScrollChanged)
    val latestOnAppBarHeightChanged = rememberUpdatedState(onAppBarHeightChanged)

    // Bridge/state that survives across recompositions and is written from
    // the WebView binder thread via post { } to the main thread.
    val bridgeState = remember { BridgeState() }
    val overlayCache = remember { mutableMapOf<String, ComposeView>() }
    // Skip setContent when the descriptor for a given id hasn't changed:
    // ComposeView.setContent recomposes on lambda-identity change, so
    // passing a fresh lambda every recomposition (fired constantly during
    // pinch) causes needless composition churn.
    val lastRenderedDescriptors = remember { mutableMapOf<String, OverlayDescriptor>() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val container = ConversationContainer(ctx)
            container.onScrollChanged = { scrollY -> latestOnScrollChanged.value(scrollY) }
            container.onAppBarHeightChanged = { h -> latestOnAppBarHeightChanged.value(h) }

            container.webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onReady() {
                        container.post {
                            bridgeState.ready.value = true
                            syncWebView(container, bridgeState)
                        }
                    }

                    @JavascriptInterface
                    fun onGeometry(payloadJson: String) {
                        container.onGeometryJson(payloadJson)
                    }
                },
                "Bridge"
            )

            container.webView.loadUrl(ConversationTemplateBuilder.TEMPLATE_URL)
            container
        },
        update = { container ->
            // ── Overlay Views (get-or-create, keyed by descriptor id) ────
            val ctx = container.context
            val items: List<Pair<String, View>> = descriptors.map { d ->
                val view = overlayCache.getOrPut(d.id) {
                    ComposeView(ctx).apply {
                        setViewCompositionStrategy(
                            ViewCompositionStrategy.DisposeOnDetachedFromWindow
                        )
                    }
                }
                if (lastRenderedDescriptors[d.id] != d) {
                    lastRenderedDescriptors[d.id] = d
                    view.setContent { latestOverlayContent.value(d) }
                }
                d.id to (view as View)
            }
            container.setOverlays(items)

            val liveIds = descriptors.mapTo(mutableSetOf()) { it.id }
            overlayCache.keys.retainAll { it in liveIds }
            lastRenderedDescriptors.keys.retainAll { it in liveIds }

            // ── JS sync: full render on thread change, deltas on expand ──
            bridgeState.pendingThread = thread
            bridgeState.pendingExpandedIds = expandedIds
            syncWebView(container, bridgeState)
        }
    )
}

/* ── Internal helpers ───────────────────────────────────────────────────── */

private class BridgeState {
    val ready = mutableStateOf(false)
    var pendingThread: EmailThread? = null
    var pendingExpandedIds: Set<String> = emptySet()
    var lastSentThreadId: String? = null
    var lastSentExpandedIds: Set<String> = emptySet()
}

private fun syncWebView(container: ConversationContainer, state: BridgeState) {
    if (!state.ready.value) return
    val thread = state.pendingThread ?: return
    val expanded = state.pendingExpandedIds

    if (state.lastSentThreadId != thread.id) {
        // Full render — new (or first) thread.
        val payload = ConversationTemplateBuilder.buildPayload(thread, expanded)
        val b64 = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // renderThread rebuilds the DOM from scratch — any previous
        // setSpacerHeight writes are lost, so drop the "already sent" cache.
        container.resetSpacerHeightCache()
        // Base64 sidesteps all JS string-escaping edge cases in the JSON.
        container.webView.evaluateJavascript(
            "renderThread(JSON.parse(atob('$b64')))",
            null
        )
        state.lastSentThreadId = thread.id
        state.lastSentExpandedIds = expanded
        return
    }

    if (state.lastSentExpandedIds != expanded) {
        // Delta path — mutate DOM in place via toggleExpanded(msgId).
        val added = expanded - state.lastSentExpandedIds
        val removed = state.lastSentExpandedIds - expanded
        for (id in added + removed) {
            val safe = id.replace("'", "\\'")
            container.webView.evaluateJavascript("toggleExpanded('$safe')", null)
        }
        state.lastSentExpandedIds = expanded
    }
}

private fun buildDescriptors(
    thread: EmailThread,
    expandedIds: Set<String>
): List<OverlayDescriptor> {
    val list = ArrayList<OverlayDescriptor>(1 + thread.messages.size * 2)
    list += OverlayDescriptor(
        id = "app-bar",
        kind = OverlayKind.APP_BAR,
        msgId = null,
        expanded = false
    )
    thread.messages.forEach { msg ->
        val expanded = msg.id in expandedIds
        list += OverlayDescriptor(
            id = "header:${msg.id}",
            kind = OverlayKind.MESSAGE_HEADER,
            msgId = msg.id,
            expanded = expanded
        )
        if (expanded) {
            list += OverlayDescriptor(
                id = "footer:${msg.id}",
                kind = OverlayKind.MESSAGE_FOOTER,
                msgId = msg.id,
                expanded = true
            )
        }
    }
    return list
}

/* ── Placeholder overlay (replaced by Phase C) ─────────────────────────── */

@Composable
private fun DefaultOverlayPlaceholder(descriptor: OverlayDescriptor) {
    val (label, tint, minHeight) = when (descriptor.kind) {
        OverlayKind.APP_BAR ->
            Triple("APP BAR", Color(0x1F1A73E8), 128.dp)
        OverlayKind.MESSAGE_HEADER ->
            Triple("HEADER · ${descriptor.msgId}", Color(0x1F34A853), 64.dp)
        OverlayKind.MESSAGE_FOOTER ->
            Triple("FOOTER · ${descriptor.msgId}", Color(0x1FFBBC04), 48.dp)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(minHeight)
            .background(tint)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
