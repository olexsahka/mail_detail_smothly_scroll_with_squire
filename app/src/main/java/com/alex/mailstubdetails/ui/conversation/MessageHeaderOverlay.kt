package com.alex.mailstubdetails.ui.conversation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.mailstubdetails.model.EmailMessage

/**
 * Overlay drawn on top of the WebView's `.msg-header-spacer` area for a
 * single message. Opaque background so the (blank) spacer beneath is hidden.
 *
 * @param expanded    Whether the message body is currently expanded in the
 *                    thread. Drives layout: expanded shows a "details" chevron
 *                    that toggles a To/Cc/Bcc/Date block; collapsed shows a
 *                    one-line preview and inline date.
 * @param highlighted Transient "you just jumped here via prev/next" flag.
 * @param onToggle    Called when the row is tapped — expected to flip the
 *                    message's expansion state in the caller.
 *
 * Details block: local state (per-message, per-composition). When [expanded]
 * flips off the header rewinds to collapsed details on next re-expand — this
 * matches Gmail's behaviour and keeps the header short by default.
 *
 * Smoothness notes:
 *   • Details expand/collapse via [AnimatedVisibility] with a tween of
 *     ~220 ms (expand) / ~180 ms (shrink) plus a 150 ms fade. Faster than
 *     the default spring, no bounce — bounce would race the DOM spacer
 *     height push (setSpacerHeight) and produce visible content jitter
 *     below the header.
 *   • ConversationContainer.onMeasure re-runs each animation frame; the
 *     lastSentSpacerCssPx cache in pushSpacerHeights dedupes identical CSS
 *     pixel heights so we only cross the JS bridge when the CSS rounding
 *     actually changes.
 */
@Composable
fun MessageHeaderOverlay(
    message: EmailMessage,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false
) {
    val borderColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = if (highlighted) 150 else 800),
        label = "headerHighlightBorder"
    )

    // Local, per-message state. Survives config change so a rotation while
    // details are open doesn't snap them shut mid-animation. Reset whenever
    // the whole message collapses (see the `if (!expanded)` reset below is
    // implicit: we don't render the toggle at all when expanded==false, so
    // detailsExpanded is a no-op until re-expand).
    var detailsExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (detailsExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
        label = "detailsChevronRotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(2.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(message.fromName)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.fromName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (expanded) {
                    Text(
                        text = "to ${message.toList.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = message.plainPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = message.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(4.dp))
            if (expanded) {
                // Details chevron. Standalone clickable — consumes its own
                // touch so the outer Column's onToggle doesn't also fire
                // (otherwise a tap here would collapse the body AND toggle
                // details in the same gesture).
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = { detailsExpanded = !detailsExpanded }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (detailsExpanded) {
                            "Hide message details"
                        } else {
                            "Show message details"
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(chevronRotation)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = "Expand message",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Details block (From / To / Cc / Bcc / Date / attachments).
        //
        // Deliberately NO Compose enter/exit animation. Compose size or
        // fade animation would keep the overlay's measuredHeight
        // interpolating for the whole duration → onMeasure fires each
        // frame → pushSpacerHeights sends 13+ evaluateJavascript calls at
        // 60fps × 220ms, each triggering browser reflow. Symptom is
        // "jerky" spacer growth and content-below jitter.
        //
        // Instead: content mounts/unmounts instantly (one Compose frame),
        // overlay measuredHeight jumps → ONE setSpacerHeight() bridge call
        // → CSS `transition: height` on the spacer (see
        // conversation.js#animateSpacerHeight + conversation_template.html
        // ".animating-height") animates the layout shift smoothly in the
        // browser. Overlay has opaque `surface` background so the
        // transient overlap during animation (overlay bottom > body top
        // until CSS transition completes) looks like normal layout.
        if (expanded && detailsExpanded) {
            MessageDetailsBlock(message)
        }

        if (expanded && !detailsExpanded && message.hasAttachment) {
            // Attachment hint only visible in "quick" state — once the user
            // opens details, the attachment info moves into the block.
            Spacer(Modifier.height(6.dp))
            Text(
                text = "📎 1 attachment",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 52.dp)
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun MessageDetailsBlock(message: EmailMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, start = 52.dp, end = 4.dp, bottom = 2.dp)
    ) {
        DetailRow("from", "${message.fromName} <${message.fromEmail}>")
        DetailRow("to", message.toList.joinToString(", "))
        if (message.ccList.isNotEmpty()) {
            DetailRow("cc", message.ccList.joinToString(", "))
        }
        if (message.bccList.isNotEmpty()) {
            DetailRow("bcc", message.bccList.joinToString(", "))
        }
        DetailRow("date", message.date)
        if (message.hasAttachment) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "📎 1 attachment",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Avatar(name: String) {
    val initial = name.firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
