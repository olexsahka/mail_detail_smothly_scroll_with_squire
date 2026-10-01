package com.alex.mailstubdetails

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.alex.mailstubdetails.navigation.AppNavigation
import com.alex.mailstubdetails.perf.LocalPerfCollector
import com.alex.mailstubdetails.perf.PerfCollector
import com.alex.mailstubdetails.ui.theme.MailStubTheme

class MainActivity : ComponentActivity() {

    private lateinit var perfCollector: PerfCollector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        perfCollector = PerfCollector(window, window.decorView).also { it.start() }
        setContent {
            MailStubTheme {
                CompositionLocalProvider(LocalPerfCollector provides perfCollector) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onDestroy() {
        perfCollector.stop()
        super.onDestroy()
    }
}
