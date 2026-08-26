package com.alex.mailstubdetails.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Overlay drawn on top of the WebView's `.msg-footer-spacer` area for an
 * expanded message. Opaque background — three action buttons in a row.
 */
@Composable
fun MessageFooterOverlay(
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FooterAction(
                icon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                label = "Reply",
                onClick = onReply,
                modifier = Modifier.weight(1f)
            )
            FooterAction(
                icon = { Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = null) },
                label = "Reply all",
                onClick = onReplyAll,
                modifier = Modifier.weight(1f)
            )
            FooterAction(
                icon = { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null) },
                label = "Forward",
                onClick = onForward,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FooterAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp, vertical = 8.dp
        )
    ) {
        icon()
        Spacer(Modifier.size(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
