package dev.cwtf.hidandseek.data.chat

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device text extraction.
 *
 * Worth having for three reasons: it works with models that cannot read
 * images, it costs a fraction of the tokens, and the extracted text can be
 * typed straight to the connected device — retyping a long error code off a
 * screen is exactly the drudgery this app exists to remove.
 *
 * Runs entirely locally against the bundled model, so choosing this over
 * sending the image means the picture never leaves the phone.
 */
class TextRecognizer(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun extract(file: File): Result<String> = extract(Uri.fromFile(file))

    suspend fun extract(uri: Uri): Result<String> = runCatching {
        val image = InputImage.fromFilePath(context, uri)
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { continuation.resume(it.text) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }
}
