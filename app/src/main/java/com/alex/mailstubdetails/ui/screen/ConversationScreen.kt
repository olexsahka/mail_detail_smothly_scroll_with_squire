package com.alex.mailstubdetails.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.mailstubdetails.model.EmailThread
import com.alex.mailstubdetails.ui.conversation.ConversationOverlaySlot
import com.alex.mailstubdetails.ui.conversation.ConversationView

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
    var scrollY by remember { mutableIntStateOf(0) }
    var appBarHeightPx by remember { mutableIntStateOf(0) }

    // Compact bar appears when the large bar has scrolled such that only
    // compact-bar-worth of it remains visible: scrollY > largeH - compactH.
    // largeH is the measured app-bar overlay height reported by the container;
    // compactH is Material3's TopAppBar height (64dp).
    val density = LocalDensity.current
    val compactBarHeightPx = with(density) { 64.dp.toPx().toInt() }
    val showCompact = appBarHeightPx > 0 &&
        scrollY > (appBarHeightPx - compactBarHeightPx).coerceAtLeast(0)

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ConversationView(
                thread = thread,
                expandedIds = expandedIds,
                modifier = Modifier.fillMaxSize(),
                onScrollChanged = { scrollY = it },
                onAppBarHeightChanged = { appBarHeightPx = it },
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
                            expandedIds = if (msgId in expandedIds) {
                                expandedIds - msgId
                            } else {
                                expandedIds + msgId
                            }
                        }
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
                    onMore = {}
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
    onMore: () -> Unit
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
            IconButton(onClick = onMore) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
        }
    )
}
