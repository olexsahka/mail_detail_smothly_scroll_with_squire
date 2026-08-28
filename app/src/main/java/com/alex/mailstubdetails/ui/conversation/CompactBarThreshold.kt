package com.alex.mailstubdetails.ui.conversation

/**
 * Threshold rule for swapping the [LargeAppBarOverlay] out for a compact,
 * pinned `TopAppBar` as the conversation scrolls up.
 *
 * The large bar scrolls with the content (it's an overlay bound to a
 * `y=0` CSS spacer). Once only *compact-bar-worth* of it remains visible
 * — i.e. the scroll offset has consumed everything except the compact
 * bar's height — the compact bar slides down to replace it.
 *
 * Extracted from `ConversationScreen` so the transition point can be
 * unit-tested without Compose runtime.
 */
object CompactBarThreshold {

    /**
     * Returns `true` when the compact `TopAppBar` should be visible.
     *
     * `false` until the app bar's measured height is known
     * ([appBarHeightPx] == 0): during the first frame after navigation
     * the overlay hasn't been measured yet, and computing a threshold
     * from zero would flap the compact bar in and immediately out.
     */
    fun shouldShowCompact(
        scrollYPx: Int,
        appBarHeightPx: Int,
        compactBarHeightPx: Int
    ): Boolean {
        if (appBarHeightPx <= 0) return false
        // coerceAtLeast(0): if the compact bar is somehow taller than the
        // large one, threshold falls to 0 and the compact bar shows as
        // soon as any scroll happens — the swap still makes sense.
        val threshold = (appBarHeightPx - compactBarHeightPx).coerceAtLeast(0)
        return scrollYPx > threshold
    }
}
