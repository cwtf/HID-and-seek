package dev.cwtf.hidandseek

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.cwtf.hidandseek.ui.HidAndSeekApp
import dev.cwtf.hidandseek.ui.theme.HidAndSeekTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val controller = (application as HidAndSeekApplication).hidController

        setContent {
            HidAndSeekTheme {
                HidAndSeekApp(controller)
            }
        }
    }
}
