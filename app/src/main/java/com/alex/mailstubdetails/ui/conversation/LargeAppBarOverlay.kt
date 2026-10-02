package com.alex.mailstubdetails.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Large "hero" app bar drawn on top of the `app-bar-spacer` at the top of
 * the conversation. Because it's an overlay bound to a spacer at y=0 in
 * CSS px, it scrolls up with the content — no pinning, no continuous
 * collapse. A statically pinned bar sits on top of this one at all times;
 * as the hero scrolls up, the subject fades into the static bar's title
 * slot at the exact moment the hero subject's bottom passes under it (see
 * [AppBarTitleFadeThreshold] and [onSubjectBottomChanged]).
 *
 * The icons row (back / prev-next / more) is intentionally duplicated
 * between here and the static pinned bar: at scrollY = 0 the static bar
 * covers this row exactly, so the visual is a single icon row; when the
 * user scrolls, the hero icons travel up with the content while the
 * pinned copy stays put. Cheaper than trying to hide either one during
 * transient scroll frames.
 *
 * @param onSubjectBoundsChanged Reports the subject Text's top and bottom
 *   Y offset in device px, both relative to this overlay's top. Fires only
 *   on change. The consumer uses the [top, bottom] band to drive a scroll-
 *   linked cross-fade of the static bar's title — starts fading in when
 *   the subject's top passes under the bar, completes when the bottom
 *   passes under. Passing both edges (rather than a single trigger point)
 *   removes the animation gap between "hero title disappeared" and
 *   "static title appeared".
 */
@Composable
fun LargeAppBarOverlay(
    subject: String,
    messageCount: Int,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    bodyDarkMode: Boolean = false,
    onToggleBodyDarkMode: () -> Unit = {},
    onSubjectBoundsChanged: (topPx: Int, bottomPx: Int) -> Unit = { _, _ -> }
) {
    val showNav = messageCount > 1
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Spacer(Modifier.size(1.dp).weight(1f))
            if (showNav) {
                IconButton(onClick = onPrev, enabled = hasPrev) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Previous message"
                    )
                }
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Next message"
                    )
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = subject,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.onGloballyPositioned { coords ->
                    // positionInRoot() is relative to this ComposeView's
                    // top-left — which is exactly the overlay's top edge
                    // in device px (ConversationContainer lays overlays
                    // out at (0,0,w,h) and drives their y via translationY).
                    val topPx = coords.positionInRoot().y.roundToInt()
                    val bottomPx = topPx + coords.size.height
                    onSubjectBoundsChanged(topPx, bottomPx)
                }
            )
            if (messageCount > 1) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "$messageCount messages",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
