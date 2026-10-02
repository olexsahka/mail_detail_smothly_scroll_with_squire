package com.alex.mailstubdetails

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import com.alex.mailstubdetails.navigation.AppNavigation
import com.alex.mailstubdetails.perf.LocalPerfCollector
import com.alex.mailstubdetails.perf.PerfCollector
import com.alex.mailstubdetails.ui.theme.MailStubTheme

// Extends AppCompatActivity so AppCompatDelegate.setLocalNightMode is
// available (see ConversationScreen's dark toggle). The manifest has
// android:configChanges="uiMode|..." so the night-mode flip reaches us
// via onConfigurationChanged instead of recreating the Activity — critical
// for keeping scroll state, WebView content, and in-flight loaders stable
// when the user taps the toolbar toggle.
class MainActivity : AppCompatActivity() {

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
