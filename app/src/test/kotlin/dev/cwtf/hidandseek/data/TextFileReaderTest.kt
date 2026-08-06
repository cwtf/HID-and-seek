package dev.cwtf.hidandseek.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextFileReaderTest {

    @Test
    fun `plain UTF-8 text is decoded unchanged`() {
        assertEquals("hello\nworld", TextFileDecoder.decode("hello\nworld".encodeToByteArray()))
    }

    @Test
    fun `UTF-8 BOM is removed`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "echo ok".encodeToByteArray()

        assertEquals("echo ok", TextFileDecoder.decode(bytes))
    }

    @Test
    fun `BOM-marked UTF-16 little endian is supported`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "hello".toByteArray(Charsets.UTF_16LE)

        assertEquals("hello", TextFileDecoder.decode(bytes))
    }

    @Test
    fun `BOM-marked UTF-16 big endian is supported`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "hello".toByteArray(Charsets.UTF_16BE)

        assertEquals("hello", TextFileDecoder.decode(bytes))
    }

    @Test
    fun `invalid UTF-8 is rejected`() {
        assertFailsWith<TextFileImportException> {
            TextFileDecoder.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun `binary control data is rejected`() {
        assertFailsWith<TextFileImportException> {
            TextFileDecoder.decode("hello\u0000world".encodeToByteArray())
        }
    }

    @Test
    fun `oversized files are rejected`() {
        assertFailsWith<TextFileImportException> {
            TextFileDecoder.decode(ByteArray(MAX_TEXT_FILE_BYTES + 1) { 'a'.code.toByte() })
        }
    }
}