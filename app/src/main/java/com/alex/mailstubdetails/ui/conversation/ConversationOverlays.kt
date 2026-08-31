package com.alex.mailstubdetails.ui.conversation

import androidx.compose.runtime.Composable
import com.alex.mailstubdetails.model.EmailThread

/**
 * Resolver — dispatches an [OverlayDescriptor] to the right overlay
 * composable. Pass this (curried with the thread + callbacks) as the
 * `overlayContent` argument of [ConversationView].
 *
 * Handling missing message IDs: if a descriptor references a message not
 * found in the thread we skip rendering. This should never fire under
 * normal operation — [buildDescriptors] iterates the same thread — but
 * we're defensive against a stale JS-side geometry callback arriving
 * after the thread has changed.
 */
@Composable
fun ConversationOverlaySlot(
    descriptor: OverlayDescriptor,
    thread: EmailThread,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onReply: (msgId: String) -> Unit,
    onReplyAll: (msgId: String) -> Unit,
    onForward: (msgId: String) -> Unit,
    onToggleMessage: (msgId: String) -> Unit,
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {}
) {
    when (descriptor.kind) {
        OverlayKind.APP_BAR -> LargeAppBarOverlay(
            subject = thread.subject,
            messageCount = thread.messageCount,
            onBack = onBack,
            onMore = onMore,
            hasPrev = hasPrev,
            hasNext = hasNext,
            onPrev = onPrev,
            onNext = onNext
        )
        OverlayKind.MESSAGE_HEADER -> {
            val msg = thread.messages.firstOrNull { it.id == descriptor.msgId } ?: return
            MessageHeaderOverlay(
                message = msg,
                expanded = descriptor.expanded,
                highlighted = descriptor.highlighted,
                onToggle = { onToggleMessage(msg.id) }
            )
        }
        OverlayKind.MESSAGE_BODY_LOADER -> MessageBodyLoaderOverlay()
        OverlayKind.MESSAGE_FOOTER -> {
            val msg = thread.messages.firstOrNull { it.id == descriptor.msgId } ?: return
            MessageFooterOverlay(
                onReply = { onReply(msg.id) },
                onReplyAll = { onReplyAll(msg.id) },
                onForward = { onForward(msg.id) }
            )
        }
    }
}
