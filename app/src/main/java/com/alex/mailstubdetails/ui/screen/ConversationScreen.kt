package com.alex.mailstubdetails.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.mailstubdetails.model.EmailThread
import com.alex.mailstubdetails.ui.conversation.CompactBarThreshold
import com.alex.mailstubdetails.ui.conversation.ConversationOverlaySlot
import com.alex.mailstubdetails.ui.conversation.ConversationStateReducer
import com.alex.mailstubdetails.ui.conversation.ConversationView
import com.alex.mailstubdetails.ui.conversation.rememberConversationController

// Fake body-fetch pacing. The jitter keeps repeated taps from feeling
// mechanical (every load takes the same time = uncanny), and lets the
// UI showcase the shimmer for a realistic-looking beat before content
// snaps in.
private const val FAKE_LOAD_DELAY_MS_MIN = 550L
private const val FAKE_LOAD_DELAY_MS_JITTER = 350L

// How long the primary-color highlight border stays visible after the user
// taps a prev/next arrow. Matches the "flash and fade" pattern from Gmail —
// long enough to notice, short enough not to feel sticky.
private const val JUMP_HIGHLIGHT_DURATION_MS = 1500L

/**
 * The AOSP-style conversation screen — replaces both the old
 * `MessageDetailScreen` (N=1) and `ThreadScreen` (N>1). Layout:
 *
 * ```
 *  Scaffold (system-bar insets)
 *   └ Box
 *      ├ ConversationView                            (fills)
 *      └ AnimatedVisibility(showCompact) CompactBar  (overlays top)
 * ```
 *
 * The compact bar overlays inside the Box rather than sitting in
 * Scaffold.topBar so its slide-in/out never reflows the WebView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    thread: EmailThread,
    onBack: () -> Unit,
    onReply: () -> Unit
) {
    var expandedIds by remember(thread.id) {
        mutableStateOf(setOf(thread.messages.first().id))
    }
    // Body content of the first message is treated as prefetched (matches
    // Gmail/Outlook: opening a thread shows the newest message immediately).
    // All others go through the fake-load path on tap.
    var loadedIds by remember(thread.id) {
        mutableStateOf(setOf(thread.messages.first().id))
    }
    // In-flight fake-load requests. Guards against spamming taps: if a
    // load is already scheduled for a message, further collapse+expand
    // toggles reuse the same coroutine's result.
    var pendingLoads by remember(thread.id) { mutableStateOf(emptySet<String>()) }
    val loadScope = rememberCoroutineScope()

    var scrollY by remember { mutableIntStateOf(0) }
    var appBarHeightPx by remember { mutableIntStateOf(0) }

    // Compact bar appears when the large bar has scrolled such that only
    // compact-bar-worth of it remains visible. largeH is the measured
    // app-bar overlay height reported by the container; compactH is
    // Material3's TopAppBar height (64dp).
    val density = LocalDensity.current
    val compactBarHeightPx = with(density) { 64.dp.toPx().toInt() }
    val showCompact = CompactBarThreshold.shouldShowCompact(
        scrollYPx = scrollY,
        appBarHeightPx = appBarHeightPx,
        compactBarHeightPx = compactBarHeightPx
    )

    // ── Prev/next navigation state ──────────────────────────────────────
    // Currently-focused message = last header that has scrolled at or above
    // the compact bar (container fires this on scroll/pinch). Defaults to
    // the first message so prev/next work even before the first frame.
    var focusedMsgId by remember(thread.id) {
        mutableStateOf(thread.messages.first().id)
    }
    // Transient "you just jumped here" marker for the header border. Set on
    // arrow tap, auto-cleared after JUMP_HIGHLIGHT_DURATION_MS.
    var highlightedMsgId by remember(thread.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedMsgId, thread.id) {
        if (highlightedMsgId != null) {
            delay(JUMP_HIGHLIGHT_DURATION_MS)
            highlightedMsgId = null
        }
    }

    val controller = rememberConversationController()

    val currentIndex = thread.messages.indexOfFirst { it.id == focusedMsgId }
        .let { if (it < 0) 0 else it }
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex in 0 until thread.messages.lastIndex

    fun jumpTo(targetIndex: Int) {
        val target = thread.messages.getOrNull(targetIndex) ?: return
        controller.scrollToMessage(target.id)
        highlightedMsgId = target.id
    }
    val onPrev: () -> Unit = { jumpTo(currentIndex - 1) }
    val onNext: () -> Unit = { jumpTo(currentIndex + 1) }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ConversationView(
                thread = thread,
                expandedIds = expandedIds,
                loadedIds = loadedIds,
                highlightedMsgId = highlightedMsgId,
                focusThresholdPx = compactBarHeightPx,
                modifier = Modifier.fillMaxSize(),
                onScrollChanged = { scrollY = it },
                onAppBarHeightChanged = { appBarHeightPx = it },
                onFocusedMessageChanged = { id -> if (id != null) focusedMsgId = id },
                controller = controller,
                overlayContent = { descriptor ->
                    ConversationOverlaySlot(
                        descriptor = descriptor,
                        thread = thread,
                        onBack = onBack,
                        onMore = {},
                        onReply = { onReply() },
                        onReplyAll = { onReply() },
                        onForward = { onReply() },
                        onToggleMessage = { msgId ->
                            val result = ConversationStateReducer.toggle(
                                msgId = msgId,
                                expanded = expandedIds,
                                loaded = loadedIds,
                                pending = pendingLoads
                            )
                            expandedIds = result.expanded
                            pendingLoads = result.pending
                            if (result.shouldStartLoad) {
                                loadScope.launch {
                                    delay(
                                        FAKE_LOAD_DELAY_MS_MIN +
                                            Random.nextLong(FAKE_LOAD_DELAY_MS_JITTER)
                                    )
                                    val done = ConversationStateReducer.markLoaded(
                                        msgId = msgId,
                                        loaded = loadedIds,
                                        pending = pendingLoads
                                    )
                                    loadedIds = done.loaded
                                    pendingLoads = done.pending
                                }
                            }
                        },
                        hasPrev = hasPrev,
                        hasNext = hasNext,
                        onPrev = onPrev,
                        onNext = onNext
                    )
                }
            )

            AnimatedVisibility(
                visible = showCompact,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it })
            ) {
                CompactAppBar(
                    subject = thread.subject,
                    onBack = onBack,
                    onMore = {},
                    hasPrev = hasPrev,
                    hasNext = hasNext,
                    onPrev = onPrev,
                    onNext = onNext,
                    showNav = thread.messageCount > 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactAppBar(
    subject: String,
    onBack: () -> Unit,
    onMore: () -> Unit,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    showNav: Boolean
) {
    TopAppBar(
        title = {
            Text(
                text = subject,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            if (showNav) {
                IconButton(onClick = onPrev, enabled = hasPrev) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous message")
                }
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next message")
                }
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
        }
    )
}
