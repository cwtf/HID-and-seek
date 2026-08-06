package dev.cwtf.hidandseek.ui

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal fun plainTextClipEntry(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("HID & Seek", text))

internal fun ClipEntry?.firstTextOrNull(): String? {
    val data = this?.clipData ?: return null
    if (data.itemCount == 0) return null
    return data.getItemAt(0).text?.toString()
}