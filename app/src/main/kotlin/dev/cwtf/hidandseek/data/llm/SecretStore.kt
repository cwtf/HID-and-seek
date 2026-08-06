package dev.cwtf.hidandseek.data.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys, held apart from everything else.
 *
 * Backed by an Android Keystore AES-GCM master key. Keys never enter DataStore,
 * the chat database, exports, or logs — only the alias that points here does,
 * so anything that serialises a provider record carries no secret.
 */
class SecretStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(alias: String): String? = prefs.getString(alias, null)?.takeIf { it.isNotBlank() }

    fun put(alias: String, secret: String) {
        prefs.edit().apply {
            if (secret.isBlank()) remove(alias) else putString(alias, secret)
        }.apply()
    }

    fun remove(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    fun has(alias: String): Boolean = get(alias) != null

    private companion object {
        // Excluded from backup by dataExtractionRules.
        const val FILE_NAME = "hid_and_seek_secrets"
    }
}
