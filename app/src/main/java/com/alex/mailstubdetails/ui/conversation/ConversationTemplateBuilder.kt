package com.alex.mailstubdetails.ui.conversation

import com.alex.mailstubdetails.model.EmailThread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises an [EmailThread] into the JSON payload consumed by
 * `renderThread(...)` in `assets/conversation.js`.
 *
 * The payload shape is:
 * ```
 * {
 *   "messages": [
 *     { "id": "m1", "html": "<p>...</p>", "expanded": true  },
 *     { "id": "m2", "html": "<p>...</p>", "expanded": false }
 *   ]
 * }
 * ```
 *
 * HTML is passed through as-is; sanitisation happens on the JS side via
 * DOMPurify. That keeps sanitisation logic co-located with the DOM that
 * consumes it, and avoids double-work.
 */
object ConversationTemplateBuilder {

    const val BASE_URL: String = "file:///android_asset/"
    const val TEMPLATE_PATH: String = "conversation_template.html"
    const val TEMPLATE_URL: String = BASE_URL + TEMPLATE_PATH

    fun buildPayload(
        thread: EmailThread,
        expandedIds: Set<String>,
        loadedIds: Set<String>
    ): String {
        val messages = JSONArray()
        thread.messages.forEach { msg ->
            val isLoaded = msg.id in loadedIds
            val obj = JSONObject().apply {
                put("id", msg.id)
                put("expanded", msg.id in expandedIds)
                put("loaded", isLoaded)
                // Only send HTML for already-loaded messages. Skeleton
                // rendering doesn't need the real content, and this makes
                // "not loaded yet" impossible to accidentally leak into
                // the DOM on the JS side.
                if (isLoaded) put("html", msg.htmlBody)
            }
            messages.put(obj)
        }
        return JSONObject().apply {
            put("threadId", thread.id)
            put("subject", thread.subject)
            put("messages", messages)
        }.toString()
    }

    /**
     * Default expansion: only the first message. Matches Gmail/Outlook default.
     */
    fun defaultExpandedIds(thread: EmailThread): Set<String> =
        if (thread.messages.isEmpty()) emptySet()
        else setOf(thread.messages.first().id)
}
