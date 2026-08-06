package dev.cwtf.hidandseek

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cwtf.hidandseek.ui.HidAndSeekApp
import dev.cwtf.hidandseek.ui.theme.HidAndSeekTheme

/** Something shared into the app from elsewhere. */
sealed interface SharedContent {
    data class Image(val uri: Uri) : SharedContent
    data class Text(val text: String) : SharedContent
}

class MainActivity : ComponentActivity() {

    private var shared by mutableStateOf<SharedContent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as HidAndSeekApplication).container
        shared = readShare(intent)

        setContent {
            val settings by container.settings.collectAsState()

            HidAndSeekTheme(appearance = settings.appearance) {
                HidAndSeekApp(
                    container = container,
                    sharedContent = shared,
                    onSharedContentConsumed = { shared = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)?.let { shared = it }
    }

    private fun readShare(intent: Intent?): SharedContent? {
        if (intent?.action != Intent.ACTION_SEND) return null

        intent.imageUri()?.let { return SharedContent.Image(it) }
        intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?.let { return SharedContent.Text(it) }
        return null
    }

    @Suppress("DEPRECATION")
    private fun Intent.imageUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
}
