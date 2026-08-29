package com.sankranthi.ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.sankranthi.ledger.data.ServiceLocator
import com.sankranthi.ledger.ui.nav.SankranthiApp
import com.sankranthi.ledger.ui.theme.SankranthiTheme

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
