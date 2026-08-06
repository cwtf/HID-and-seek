package dev.cwtf.hidandseek.data.llm

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys and sensitive snippets, held apart from everything else.
 *
 * Values are authenticated with AES-GCM using a non-exportable Android
 * Keystore key. Only opaque aliases and encrypted payloads reach preferences;
 * plaintext never enters DataStore, the chat database, exports, or logs.
 */
class SecretStore(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        getOrCreateMasterKey()
    }

    private val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).also(::migrateLegacy)
    }

    fun get(alias: String): String? = prefs.getString(alias, null)
        ?.let { encoded ->
            runCatching { SecretCipher.decrypt(masterKey, alias, encoded) }.getOrNull()
        }
        ?.takeIf { it.isNotBlank() }

    fun put(alias: String, secret: String) {
        if (secret.isBlank()) {
            remove(alias)
            return
        }
        prefs.edit()
            .putString(alias, SecretCipher.encrypt(masterKey, alias, secret))
            .apply()
    }

    fun remove(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    fun has(alias: String): Boolean = get(alias) != null

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    /** Moves pre-1.1 AndroidX-encrypted values without making users re-enter them. */
    private fun migrateLegacy(target: SharedPreferences) {
        if (target.getBoolean(MIGRATION_COMPLETE, false)) return

        val legacyFile = File(
            appContext.applicationInfo.dataDir,
            "shared_prefs/$LEGACY_FILE_NAME.xml",
        )
        if (!legacyFile.isFile) {
            target.edit().putBoolean(MIGRATION_COMPLETE, true).commit()
            return
        }

        val legacy = openLegacyPreferences() ?: return
        val migrated = runCatching {
            val editor = target.edit()
            legacy.all.forEach { (alias, value) ->
                val secret = value as? String ?: return@forEach
                editor.putString(alias, SecretCipher.encrypt(masterKey, alias, secret))
            }
            editor.putBoolean(MIGRATION_COMPLETE, true)
            check(editor.commit()) { "Could not commit migrated secrets" }
        }.isSuccess

        if (migrated) {
            legacy.edit().clear().commit()
            appContext.deleteSharedPreferences(LEGACY_FILE_NAME)
        }
    }

    /** Deprecated code is deliberately isolated to one-time compatibility migration. */
    @Suppress("DEPRECATION")
    private fun openLegacyPreferences(): SharedPreferences? = runCatching {
        val legacyMasterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            LEGACY_FILE_NAME,
            legacyMasterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hid_and_seek_secrets_v2_key"
        const val FILE_NAME = "hid_and_seek_secrets_v2"
        const val LEGACY_FILE_NAME = "hid_and_seek_secrets"
        const val MIGRATION_COMPLETE = "__legacy_migration_complete"
    }
}

/** Versioned payload format shared with host-side crypto regression tests. */
internal object SecretCipher {

    fun encrypt(key: SecretKey, alias: String, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(alias.encodeToByteArray())
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        val iv = cipher.iv
        require(iv.size <= 255)

        val payload = byteArrayOf(iv.size.toByte()) + iv + ciphertext
        return PREFIX + Base64.getEncoder().withoutPadding().encodeToString(payload)
    }

    fun decrypt(key: SecretKey, alias: String, encoded: String): String {
        require(encoded.startsWith(PREFIX)) { "Unknown secret payload version" }
        val payload = Base64.getDecoder().decode(encoded.removePrefix(PREFIX))
        require(payload.isNotEmpty()) { "Missing secret IV" }

        val ivSize = payload[0].toInt() and 0xff
        require(ivSize > 0 && payload.size > 1 + ivSize) { "Invalid secret payload" }
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val ciphertext = payload.copyOfRange(1 + ivSize, payload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(alias.encodeToByteArray())
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private const val PREFIX = "gcm1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
}