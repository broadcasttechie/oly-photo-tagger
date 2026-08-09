package com.olyphototagger.app.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Persists user-entered settings — nothing here is hardcoded. The Dawarich base URL and
 * API token live in DataStore (app-sandboxed, not backed up by default — see
 * android:allowBackup in the manifest — but not encrypted at rest; revisit with
 * EncryptedSharedPreferences/Tink if the threat model calls for it).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DAWARICH_BASE_URL = stringPreferencesKey("dawarich_base_url")
        val DAWARICH_API_TOKEN = stringPreferencesKey("dawarich_api_token")
    }

    val dawarichConfig: Flow<DawarichConfig?> = context.settingsDataStore.data.map { prefs ->
        val baseUrl = prefs[Keys.DAWARICH_BASE_URL]
        val apiToken = prefs[Keys.DAWARICH_API_TOKEN]
        if (baseUrl.isNullOrBlank() || apiToken.isNullOrBlank()) null
        else DawarichConfig(baseUrl, apiToken)
    }

    suspend fun saveDawarichConfig(baseUrl: String, apiToken: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DAWARICH_BASE_URL] = BaseUrlNormalizer.normalize(baseUrl)
            prefs[Keys.DAWARICH_API_TOKEN] = apiToken.trim()
        }
    }

    suspend fun clearDawarichConfig() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.DAWARICH_BASE_URL)
            prefs.remove(Keys.DAWARICH_API_TOKEN)
        }
    }
}
