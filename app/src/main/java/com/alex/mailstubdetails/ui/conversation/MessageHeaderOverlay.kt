package com.alex.mailstubdetails.ui.conversation

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 *                    thread. Drives layout: expanded shows fromEmail + To/CC;
 *                    collapsed shows a one-line preview and inline date.
 * @param highlighted Transient "you just jumped here via prev/next" flag.
 *                    When true, the header shows an animated primary-color
 *                    border. Fade-in is quick (150ms) so the highlight
 *                    appears as soon as the smooth scroll starts; fade-out
 *                    is slower (800ms) so it dissolves gently once the
 *                    screen clears the flag ~1.5s later.
 * @param onToggle    Called when the row is tapped — expected to flip the
 *                    message's expansion state in the caller.
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
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse message" else "Expand message",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded && (message.ccList.isNotEmpty() || message.hasAttachment)) {
            Spacer(Modifier.height(6.dp))
            if (message.ccList.isNotEmpty()) {
                Text(
                    text = "cc ${message.ccList.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 52.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (message.hasAttachment) {
                Text(
                    text = "📎 1 attachment",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 52.dp, top = 2.dp)
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
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
