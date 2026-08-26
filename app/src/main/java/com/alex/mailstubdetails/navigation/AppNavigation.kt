package com.alex.mailstubdetails.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alex.mailstubdetails.data.MOCK_THREADS
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
            ConversationScreen(
                thread = thread,
                onBack = { nav.popBackStack() },
                onReply = { nav.navigate(Routes.COMPOSE) }
            )
        }

        composable(Routes.COMPOSE) {
            ComposeScreen(onBack = { nav.popBackStack() })
        }
    }
}
