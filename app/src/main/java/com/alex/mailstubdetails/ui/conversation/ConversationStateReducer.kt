package com.alex.mailstubdetails.ui.conversation

/**
 * Pure-Kotlin reducer for the conversation expansion / load state — no
 * Compose, no coroutines, no Android. The rules:
 *
 * • **Expand** a collapsed message → append to `expanded`. If we haven't
 *   loaded its body yet AND no load is already in flight, request a load.
 * • **Collapse** an expanded message → remove from `expanded`. The
 *   `loaded` set is deliberately NOT cleared so re-expanding is instant
 *   (matches Gmail/Outlook's "already fetched" behavior).
 * • Any in-flight load left over from a previous expand keeps running;
 *   when it completes, calling [markLoaded] moves the id into `loaded`
 *   and drops it from `pending`. If the user has collapsed the message
 *   in the meantime, that's fine — next expand will find it already
 *   loaded and skip the fake fetch.
 *
 * Extracted from `ConversationScreen` so the state transitions can be
 * unit-tested without Compose runtime.
 */
object ConversationStateReducer {

    /**
     * The result of a [toggle]: the new state plus a boolean the caller
     * must observe to decide whether to kick off a fake-load coroutine.
     */
    data class ToggleResult(
        val expanded: Set<String>,
        val pending: Set<String>,
        val shouldStartLoad: Boolean
    )

    fun toggle(
        msgId: String,
        expanded: Set<String>,
        loaded: Set<String>,
        pending: Set<String>
    ): ToggleResult {
        return if (msgId in expanded) {
            // Collapse — keep loaded/pending unchanged.
            ToggleResult(expanded - msgId, pending, shouldStartLoad = false)
        } else {
            val newExpanded = expanded + msgId
            val shouldLoad = msgId !in loaded && msgId !in pending
            val newPending = if (shouldLoad) pending + msgId else pending
            ToggleResult(newExpanded, newPending, shouldStartLoad = shouldLoad)
        }
    }

    /**
     * Apply the result of a completed fake-load. Returns the new
     * `loaded` and `pending` sets.
     */
    data class LoadCompleteResult(
        val loaded: Set<String>,
        val pending: Set<String>
    )

    fun markLoaded(
        msgId: String,
        loaded: Set<String>,
        pending: Set<String>
    ): LoadCompleteResult =
        LoadCompleteResult(
            loaded = loaded + msgId,
            pending = pending - msgId
        )
}
