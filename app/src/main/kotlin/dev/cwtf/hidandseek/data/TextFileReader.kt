package dev.cwtf.hidandseek.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_TEXT_FILE_BYTES = 256 * 1024

data class ImportedTextFile(val name: String, val text: String)

class TextFileImportException(message: String) : IllegalArgumentException(message)

/** Reads a user-selected document without retaining a permission or making a private copy. */
class TextFileReader(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    suspend fun read(uri: Uri): Result<ImportedTextFile> = withContext(Dispatchers.IO) {
        runCatching {
            val metadata = runCatching { metadata(uri) }.getOrDefault(FileMetadata())
            if (metadata.size != null && metadata.size > MAX_TEXT_FILE_BYTES) {
                throw TextFileImportException("File is larger than the 256 KiB limit")
            }

            val bytes = resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_TEXT_FILE_BYTES) {
                        throw TextFileImportException("File is larger than the 256 KiB limit")
                    }
                    output.write(chunk, 0, read)
                }
                output.toByteArray()
            } ?: throw TextFileImportException("Could not open that file")

            ImportedTextFile(
                name = metadata.name.cleanFileName(uri),
                text = TextFileDecoder.decode(bytes),
            )
        }
    }

    private fun metadata(uri: Uri): FileMetadata {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use FileMetadata()
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            FileMetadata(
                name = nameIndex.takeIf { it >= 0 }?.let(cursor::getString),
                size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
            )
        } ?: FileMetadata()
    }

    private fun String?.cleanFileName(uri: Uri): String = this
        ?.filterNot(Char::isISOControl)
        ?.take(120)
        ?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')?.take(120)
        ?: "text file"

    private data class FileMetadata(val name: String? = null, val size: Long? = null)
}

/** Strict decoding keeps binary files from becoming a screenful of unsafe keystrokes. */
internal object TextFileDecoder {

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) throw TextFileImportException("File is empty")
        if (bytes.size > MAX_TEXT_FILE_BYTES) {
            throw TextFileImportException("File is larger than the 256 KiB limit")
        }

        val text = try {
            when {
                bytes.startsWith(UTF8_BOM) -> decode(bytes, UTF8_BOM.size, Charsets.UTF_8)
                bytes.startsWith(UTF16_LE_BOM) ->
                    decode(bytes, UTF16_LE_BOM.size, Charsets.UTF_16LE)
                bytes.startsWith(UTF16_BE_BOM) ->
                    decode(bytes, UTF16_BE_BOM.size, Charsets.UTF_16BE)
                else -> decode(bytes, 0, Charsets.UTF_8)
            }
        } catch (_: CharacterCodingException) {
            throw TextFileImportException("File is not valid UTF-8 or BOM-marked UTF-16 text")
        }

        if (text.isEmpty()) throw TextFileImportException("File is empty")
        if (text.any(::isBinaryControl)) {
            throw TextFileImportException("File contains binary control data")
        }
        return text
    }

    private fun decode(bytes: ByteArray, offset: Int, charset: java.nio.charset.Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun isBinaryControl(char: Char): Boolean =
        (char.code in 0..8) || (char.code in 11..12) || (char.code in 14..31) || char.code == 127

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}