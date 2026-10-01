package com.alex.mailstubdetails.perf

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition-scoped access to the singleton [PerfCollector] installed on
 * the activity window. Composable screens and gesture-owning views (like
 * [com.alex.mailstubdetails.ui.conversation.ConversationContainer]) tag
 * frames through this handle so JankStats summaries can be sliced by
 * state ("screen=thread gesture=pinch").
 *
 * `null` in previews / tests where no collector is installed — call sites
 * MUST no-op when it's absent.
 */
val LocalPerfCollector = staticCompositionLocalOf<PerfCollector?> { null }
