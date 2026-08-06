package dev.cwtf.hidandseek.ui.chat

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where images can come from, other than the photo picker.
 *
 * None of these needs a runtime permission: capture is delegated to the system
 * camera app, and the clipboard is read only when the user asks.
 */
object ImageSources {

    /**
     * A destination for the camera app to write into.
     *
     * Capture goes through `ACTION_IMAGE_CAPTURE` rather than an in-app
     * viewfinder, which is what keeps `CAMERA` off the permission list — an app
     * only needs that permission if it declares it.
     */
    fun createCaptureUri(context: Context): Uri {
        val directory = File(context.filesDir, "captures").apply { mkdirs() }
        val file = File(directory, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    /** The image on the clipboard, or null if there isn't one. */
    fun clipboardImage(context: Context): Uri? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null

        val uri = clip.getItemAt(0).uri ?: return null
        val type = context.contentResolver.getType(uri)
        return if (type?.startsWith("image/") == true) uri else null
    }

    /** Removes capture files left behind by cancelled or failed captures. */
    fun cleanUpCaptures(context: Context) {
        runCatching {
            File(context.filesDir, "captures").listFiles()?.forEach { file ->
                if (file.length() == 0L) file.delete()
            }
        }
    }
}
