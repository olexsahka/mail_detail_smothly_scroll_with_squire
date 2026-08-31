package com.alex.mailstubdetails.ui.conversation

import com.alex.mailstubdetails.model.EmailThread

/**
 * Pure-Kotlin descriptor builder — no Android, no Compose. Given a thread
 * and the current expansion / load state, returns the ordered list of
 * overlays that must be rendered on top of the WebView.
 *
 * Order (matches DOM order in `conversation.js` — see
 * [ConversationContainer.setOverlays] for why order matters):
 *
 * ```
 *   app-bar
 *   for each message:
 *     header
 *     [body loader]   ← only if expanded and NOT yet loaded
 *     [footer]        ← only if expanded
 * ```
 *
 * Extracted from [ConversationView] so it can be unit-tested and reused
 * across projects without pulling in Compose or WebView dependencies.
 */
object OverlayDescriptorBuilder {

    const val APP_BAR_ID: String = "app-bar"

    /**
     * @param highlightedMsgId Id of the message the user just jumped to via
     *  the prev/next arrows in the app bar. The matching header descriptor
     *  gets `highlighted = true` **only when the message is currently
     *  collapsed** — when expanded, the body is visible so no separate
     *  highlight is needed (per product decision 2026-08-28).
     */
    fun build(
        thread: EmailThread,
        expandedIds: Set<String>,
        loadedIds: Set<String>,
        highlightedMsgId: String? = null
    ): List<OverlayDescriptor> {
        val list = ArrayList<OverlayDescriptor>(1 + thread.messages.size * 3)
        list += OverlayDescriptor(
            id = APP_BAR_ID,
            kind = OverlayKind.APP_BAR,
            msgId = null,
            expanded = false
        )
        thread.messages.forEach { msg ->
            val expanded = msg.id in expandedIds
            val loaded = msg.id in loadedIds
            list += OverlayDescriptor(
                id = "header:${msg.id}",
                kind = OverlayKind.MESSAGE_HEADER,
                msgId = msg.id,
                expanded = expanded,
                highlighted = msg.id == highlightedMsgId && !expanded
            )
            if (expanded && !loaded) {
                list += OverlayDescriptor(
                    id = "body:${msg.id}",
                    kind = OverlayKind.MESSAGE_BODY_LOADER,
                    msgId = msg.id,
                    expanded = true
                )
            }
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
}
