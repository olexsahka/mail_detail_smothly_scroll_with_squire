package com.alex.mailstubdetails

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alex.mailstubdetails.navigation.AppNavigation
import com.alex.mailstubdetails.ui.theme.MailStubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MailStubTheme {
                AppNavigation()
            }
        }
    }
}
