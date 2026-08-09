package com.olyphototagger.app.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Persists user-entered settings — nothing here is hardcoded. The Dawarich base URL is
 * plain DataStore; the API token is encrypted with [TokenCipher] before it's written, since
 * DataStore's own on-disk format is plaintext XML/proto.
 */
class SettingsRepository(
    private val context: Context,
    private val tokenCipher: TokenCipher = TokenCipher()
) {

    private object Keys {
        val DAWARICH_BASE_URL = stringPreferencesKey("dawarich_base_url")
        val DAWARICH_API_TOKEN_CIPHERTEXT = stringPreferencesKey("dawarich_api_token_ciphertext")
        val DAWARICH_API_TOKEN_IV = stringPreferencesKey("dawarich_api_token_iv")
    }

    val dawarichConfig: Flow<DawarichConfig?> = context.settingsDataStore.data.map { prefs ->
        val baseUrl = prefs[Keys.DAWARICH_BASE_URL]
        val ciphertext = prefs[Keys.DAWARICH_API_TOKEN_CIPHERTEXT]
        val iv = prefs[Keys.DAWARICH_API_TOKEN_IV]
        if (baseUrl.isNullOrBlank() || ciphertext.isNullOrBlank() || iv.isNullOrBlank()) {
            null
        } else {
            val apiToken = tokenCipher.decrypt(EncryptedValue(ciphertext, iv))
            apiToken?.let { DawarichConfig(baseUrl, it) }
        }
    }

    suspend fun saveDawarichConfig(baseUrl: String, apiToken: String) {
        val encrypted = tokenCipher.encrypt(apiToken.trim())
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DAWARICH_BASE_URL] = BaseUrlNormalizer.normalize(baseUrl)
            prefs[Keys.DAWARICH_API_TOKEN_CIPHERTEXT] = encrypted.ciphertext
            prefs[Keys.DAWARICH_API_TOKEN_IV] = encrypted.iv
        }
    }

    suspend fun clearDawarichConfig() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.DAWARICH_BASE_URL)
            prefs.remove(Keys.DAWARICH_API_TOKEN_CIPHERTEXT)
            prefs.remove(Keys.DAWARICH_API_TOKEN_IV)
        }
    }
}
