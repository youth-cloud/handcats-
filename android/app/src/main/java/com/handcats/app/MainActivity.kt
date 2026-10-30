package com.handcats.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.handcats.app.navigation.HandCatsApp
import com.handcats.app.ui.theme.HandCatsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HandCatsTheme {
                HandCatsApp()
            }
        }
    }
}
