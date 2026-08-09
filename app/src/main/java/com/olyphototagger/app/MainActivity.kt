package com.olyphototagger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.olyphototagger.app.ui.AppNavigation
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OlyPhotoTaggerTheme {
                AppNavigation()
            }
        }
    }
}
