package com.alex.mailstubdetails.perf

/**
 * State-tag keys attached to JankStats frames. Keep this set small — every
 * active key shows up on every jank line and in every summary bucket, so
 * a broad key produces noise, not signal.
 */
object PerfKeys {
    /** Which top-level route is composed: "inbox", "thread", "compose". */
    const val SCREEN: String = "screen"

    /**
     * Interaction currently driving the ConversationContainer: one of
     * `ConversationContainer.GESTURE_*`. Absent when idle.
     */
    const val GESTURE: String = "gesture"
}
