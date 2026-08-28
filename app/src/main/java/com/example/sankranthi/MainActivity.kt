package com.example.sankranthi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.sankranthi.data.ServiceLocator
import com.example.sankranthi.ui.nav.SankranthiApp
import com.example.sankranthi.ui.theme.SankranthiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            SankranthiTheme {
                SankranthiApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
