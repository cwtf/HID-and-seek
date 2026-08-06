package dev.cwtf.hidandseek.data.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import dev.cwtf.hidandseek.data.AttachmentSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** An image ready to send: on disk, EXIF-free, and sized for a model. */
data class ProcessedImage(
    val id: String,
    val file: File,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val byteSize: Long,
) {
    /** Rough token cost, for the composer's estimate. */
    val estimatedTokens: Int get() = (widthPx * heightPx / 750).coerceAtLeast(85)
}

/** Scaling maths, kept pure so it can be tested without an Android runtime. */
object ImageScaling {

    /**
     * Longest-edge-limited dimensions, preserving aspect ratio.
     *
     * Images smaller than the limit are left alone — upscaling costs tokens and
     * adds nothing a model can use.
     */
    fun fit(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Image has no size" }
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height

        val scale = maxEdge.toDouble() / longest
        return (width * scale).toInt().coerceAtLeast(1) to
            (height * scale).toInt().coerceAtLeast(1)
    }

    /** The power-of-two `inSampleSize` for decoding without loading full size. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }
}

/**
 * Prepares images for sending.
 *
 * The EXIF strip is the part that matters: metadata is removed by decoding to a
 * bitmap and re-encoding, so there is no tag list to keep up to date and
 * nothing can survive by being unrecognised. Orientation is read first and
 * applied as a rotation, because that is the one tag whose loss would be
 * visible.
 *
 * **GPS coordinates never leave the device.** A photo of a desk should not
 * carry a home address to a third-party API, so this is unconditional and has
 * no setting.
 */
class ImageProcessor(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, "attachments").apply { mkdirs() }

    suspend fun process(uri: Uri, settings: AttachmentSettings): Result<ProcessedImage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                } ?: error("Could not read the image")

                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not an image" }

                val rotation = readOrientation(uri)

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = ImageScaling.sampleSize(
                        bounds.outWidth,
                        bounds.outHeight,
                        settings.maxEdgePx,
                    )
                }
                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                } ?: error("Could not decode the image")

                val rotated = applyRotation(decoded, rotation)
                val (targetWidth, targetHeight) = ImageScaling.fit(
                    rotated.width,
                    rotated.height,
                    settings.maxEdgePx,
                )
                val scaled = if (targetWidth == rotated.width && targetHeight == rotated.height) {
                    rotated
                } else {
                    rotated.scale(targetWidth, targetHeight)
                }

                val bytes = compress(scaled, settings)
                val id = UUID.randomUUID().toString()
                val file = File(directory, "$id.jpg").apply { writeBytes(bytes) }

                ProcessedImage(
                    id = id,
                    file = file,
                    mimeType = "image/jpeg",
                    widthPx = scaled.width,
                    heightPx = scaled.height,
                    byteSize = bytes.size.toLong(),
                )
            }
        }

    /**
     * Compresses, dropping quality if the payload ceiling is exceeded.
     *
     * Degrading quality is better than silently sending something a provider
     * will reject, and better than mangling the image without saying so —
     * the caller is told the final size.
     */
    private fun compress(bitmap: Bitmap, settings: AttachmentSettings): ByteArray {
        var quality = settings.jpegQuality
        while (true) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val bytes = stream.toByteArray()
            if (bytes.size <= settings.maxPayloadBytes || quality <= 40) return bytes
            quality -= 15
        }
    }

    private fun readOrientation(uri: Uri): Int =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)

    private fun applyRotation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun Bitmap.scale(width: Int, height: Int): Bitmap =
        Bitmap.createScaledBitmap(this, width, height, true)

    /** Inlined into the request; images are never uploaded to an intermediary. */
    fun toDataUri(image: ProcessedImage): String {
        val encoded = Base64.encodeToString(image.file.readBytes(), Base64.NO_WRAP)
        return "data:${image.mimeType};base64,$encoded"
    }

    fun delete(image: ProcessedImage) {
        runCatching { image.file.delete() }
    }

    fun deleteAll() {
        runCatching { directory.listFiles()?.forEach { it.delete() } }
    }

    fun storageBytes(): Long =
        directory.listFiles()?.sumOf { it.length() } ?: 0L
}
