package com.qiuji.codemeter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.qiuji.codemeter.ui.CodeMeterTheme
import com.qiuji.codemeter.ui.AppScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CodeMeterTheme {
                AppScreen()
            }
        }
    }
}
