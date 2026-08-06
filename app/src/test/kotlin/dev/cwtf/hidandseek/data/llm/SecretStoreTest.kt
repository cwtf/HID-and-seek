package dev.cwtf.hidandseek.data.llm

import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class SecretStoreTest {

    private val key = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }

    @Test
    fun `encrypted secret round trips`() {
        val encoded = SecretCipher.encrypt(key, "provider-key", "sk-secret")

        assertEquals("sk-secret", SecretCipher.decrypt(key, "provider-key", encoded))
    }

    @Test
    fun `encryption uses a fresh IV for identical values`() {
        val first = SecretCipher.encrypt(key, "provider-key", "sk-secret")
        val second = SecretCipher.encrypt(key, "provider-key", "sk-secret")

        assertNotEquals(first, second)
    }

    @Test
    fun `ciphertext is bound to its alias`() {
        val encoded = SecretCipher.encrypt(key, "provider-key", "sk-secret")

        assertFails { SecretCipher.decrypt(key, "different-key", encoded) }
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        val encoded = SecretCipher.encrypt(key, "provider-key", "sk-secret")

        assertFalse(encoded.contains("sk-secret"))
    }
}