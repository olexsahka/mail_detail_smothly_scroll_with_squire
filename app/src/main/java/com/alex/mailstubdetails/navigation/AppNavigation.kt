package com.alex.mailstubdetails.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alex.mailstubdetails.data.MOCK_THREADS
import com.alex.mailstubdetails.perf.LocalPerfCollector
import com.alex.mailstubdetails.perf.PerfKeys
import com.alex.mailstubdetails.ui.screen.ComposeScreen
import com.alex.mailstubdetails.ui.screen.ConversationScreen
import com.alex.mailstubdetails.ui.screen.InboxScreen

object Routes {
    const val INBOX = "inbox"
    const val THREAD = "thread/{threadId}"
    const val COMPOSE = "compose"

    fun thread(threadId: String) = "thread/$threadId"
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.INBOX) {

        composable(Routes.INBOX) {
            TagScreenForPerf("inbox")
            InboxScreen(
                threads = MOCK_THREADS,
                onThreadClick = { thread -> nav.navigate(Routes.thread(thread.id)) },
                onCompose = { nav.navigate(Routes.COMPOSE) }
            )
        }

        composable(
            route = Routes.THREAD,
            arguments = listOf(navArgument("threadId") { type = NavType.StringType })
        ) { backStack ->
            val threadId = backStack.arguments?.getString("threadId") ?: return@composable
            val thread = MOCK_THREADS.find { it.id == threadId } ?: return@composable
            TagScreenForPerf("thread")
            ConversationScreen(
                thread = thread,
                onBack = { nav.popBackStack() },
                onReply = { nav.navigate(Routes.COMPOSE) }
            )
        }

        composable(Routes.COMPOSE) {
            TagScreenForPerf("compose")
            ComposeScreen(onBack = { nav.popBackStack() })
        }
    }
}

/**
 * Sets the JankStats "screen" state tag while the enclosing composable is
 * on the back stack. Uses [DisposableEffect] so the tag flips atomically on
 * back-nav (previous screen's onDispose fires before the new screen's key
 * effect runs — Nav Compose disposes exiting entries before composing
 * entering ones).
 */
@Composable
private fun TagScreenForPerf(name: String) {
    val collector = LocalPerfCollector.current
    DisposableEffect(collector, name) {
        collector?.putState(PerfKeys.SCREEN, name)
        onDispose { collector?.removeState(PerfKeys.SCREEN) }
    }
}
