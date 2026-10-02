package com.alex.mailstubdetails.ui.screen

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.mailstubdetails.model.EmailThread
import com.alex.mailstubdetails.ui.conversation.AppBarTitleFadeProgress
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

// Vertical distance the pinned title slides while fading in — chosen to
// look like it rises "from under the bar" without covering the icons.
// Value in dp so it stays constant on all densities.
private val TITLE_SLIDE_DISTANCE = 12.dp

/**
 * The AOSP-style conversation screen. Layout:
 *
 * ```
 *  Scaffold (system-bar insets)
 *   └ Box
 *      ├ ConversationView                 (fills)
 *      └ CompactAppBar (statically pinned, subject fades in on scroll)
 * ```
 *
 * The pinned bar sits inside the Box (not `Scaffold.topBar`) so it always
 * paints on top of `LargeAppBarOverlay` without reflowing the WebView. At
 * scrollY = 0 the pinned bar covers the hero overlay's own icons exactly —
 * they're intentionally duplicated so a fresh scroll never reveals a bare
 * top edge, and the visual is a single icon row. Only the subject text
 * cross-fades in as the hero subject travels under the pinned bar (see
 * [AppBarTitleFadeProgress]). The bar's height is measured at layout time
 * — no Material3 spec constants baked in.
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

    // Message-body dark toggle. Only inverts the HTML body (via `.dark-body`
    // CSS — see conversation.js#setBodyDarkMode). Native overlays
    // (headers/footers/bars) keep following the global MailStubTheme.
    // Initial value defaults to the system theme so a white message body
    // doesn't clash with dark Compose chrome on first open; the toolbar
    // icon lets the user override it for the current session.
    val systemDark = isSystemInDarkTheme()
    var bodyDarkMode by remember { mutableStateOf(systemDark) }

    var scrollY by remember { mutableIntStateOf(0) }
    // Subject Text top / bottom Y offset in device px, relative to top of
    // the hero overlay — reported by
    // `LargeAppBarOverlay.onSubjectBoundsChanged`. The two edges define the
    // scroll range over which the pinned title cross-fades in.
    var heroSubjectTopPx by remember(thread.id) { mutableIntStateOf(0) }
    var heroSubjectBottomPx by remember(thread.id) { mutableIntStateOf(0) }

    // Measured height of the pinned bar (device px). Fires once at layout
    // and on configuration changes — not per frame — so no impact on
    // scroll smoothness. Feeds both the focus threshold (header sliding
    // under the bar becomes "current") and the subject-fade rule (bar's
    // bottom edge = fade completion line). Zero before the first layout;
    // callers are all safe against a zero value.
    var staticBarHeightPx by remember { mutableIntStateOf(0) }
    val titleFadeProgress = AppBarTitleFadeProgress.progress(
        scrollYPx = scrollY,
        heroSubjectTopPx = heroSubjectTopPx,
        heroSubjectBottomPx = heroSubjectBottomPx,
        staticBarHeightPx = staticBarHeightPx
    )

    // ── Prev/next navigation state ──────────────────────────────────────
    // Currently-focused message = last header that has scrolled at or above
    // the compact bar (container fires this on scroll/pinch). Defaults to
    // the first message so prev/next work even before the first frame.
    var focusedMsgId by remember(thread.id) {
        mutableStateOf(thread.messages.first().id)
    }

    val controller = rememberConversationController()

    val currentIndex = thread.messages.indexOfFirst { it.id == focusedMsgId }
        .let { if (it < 0) 0 else it }
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex in 0 until thread.messages.lastIndex

    // Expand + kick off fake-load for a message if currently collapsed.
    // Same path as a header tap, minus the collapse branch — prev/next
    // must never collapse the target.
    val expandIfCollapsed: (String) -> Unit = { msgId ->
        if (msgId !in expandedIds) {
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
        }
    }

    fun jumpTo(targetIndex: Int) {
        val target = thread.messages.getOrNull(targetIndex) ?: return
        controller.scrollToMessage(target.id)
        expandIfCollapsed(target.id)
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
                focusThresholdPx = staticBarHeightPx,
                bodyDarkMode = bodyDarkMode,
                modifier = Modifier.fillMaxSize(),
                onScrollChanged = { scrollY = it },
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
                        onNext = onNext,
                        bodyDarkMode = bodyDarkMode,
                        onToggleBodyDarkMode = { bodyDarkMode = !bodyDarkMode },
                        onHeroSubjectBoundsChanged = { top, bottom ->
                            heroSubjectTopPx = top
                            heroSubjectBottomPx = bottom
                        }
                    )
                }
            )

            CompactAppBar(
                subject = thread.subject,
                titleFadeProgress = titleFadeProgress,
                onBack = onBack,
                onMore = {},
                hasPrev = hasPrev,
                hasNext = hasNext,
                onPrev = onPrev,
                onNext = onNext,
                showNav = thread.messageCount > 1,
                bodyDarkMode = bodyDarkMode,
                onToggleBodyDarkMode = { bodyDarkMode = !bodyDarkMode },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { staticBarHeightPx = it.height }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactAppBar(
    subject: String,
    titleFadeProgress: Float,
    onBack: () -> Unit,
    onMore: () -> Unit,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    showNav: Boolean,
    bodyDarkMode: Boolean,
    onToggleBodyDarkMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        title = {
            // Scroll-linked cross-fade of the subject. `progress` is
            // exactly the fraction of the hero subject that has already
            // slid under the bar (see [AppBarTitleFadeProgress]) — so as
            // the last pixel of the hero title leaves the viewport, the
            // pinned title has just reached full opacity and its natural
            // position. `translationYPx` in `graphicsLayer` moves *paint
            // only* (no relayout), so we can drive it every frame without
            // causing measurement churn in the toolbar.
            val translationYPx = with(LocalDensity.current) {
                TITLE_SLIDE_DISTANCE.toPx()
            } * (1f - titleFadeProgress)
            Text(
                text = subject,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    alpha = titleFadeProgress
                    translationY = translationYPx
                }
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
            IconButton(onClick = onToggleBodyDarkMode) {
                Icon(
                    imageVector = if (bodyDarkMode) {
                        Icons.Default.LightMode
                    } else {
                        Icons.Default.DarkMode
                    },
                    contentDescription = if (bodyDarkMode) {
                        "Switch message body to light theme"
                    } else {
                        "Switch message body to dark theme"
                    }
                )
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
        }
    )
}
