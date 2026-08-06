package dev.cwtf.hidandseek

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.cwtf.hidandseek.ui.HidAndSeekApp
import dev.cwtf.hidandseek.ui.theme.HidAndSeekTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as HidAndSeekApplication).container

        setContent {
            val settings by container.settings.collectAsState()

            HidAndSeekTheme(appearance = settings.appearance) {
                HidAndSeekApp(container)
            }
        }
    }
}
