package com.axlife.pinset.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aiDataStore by preferencesDataStore(name = "ai_prefs")

/**
 * Preferences for the cloud AI (Gemini Vision) classifier. Stored via
 * DataStore so they persist across launches without a full Room migration.
 *
 * The API key lives here — NEVER commit it to source. Users paste their own
 * key in the reference-photos screen's settings section. Keeps us out of the
 * "shipping-a-secret" trap and lets each operator use their own quota.
 */
object AiPrefs {
    private val KEY_ENABLED = booleanPreferencesKey("gemini_enabled")
    private val KEY_API_KEY = stringPreferencesKey("gemini_api_key")
    private val KEY_MODEL = stringPreferencesKey("gemini_model")

    private const val DEFAULT_MODEL = "gemini-2.0-flash"

    data class Snapshot(
        val enabled: Boolean,
        val apiKey: String,
        val model: String
    ) {
        val isConfigured: Boolean get() = enabled && apiKey.isNotBlank()
    }

    fun observe(context: Context): Flow<Snapshot> =
        context.aiDataStore.data.map { p ->
            Snapshot(
                enabled = p[KEY_ENABLED] ?: false,
                apiKey = p[KEY_API_KEY].orEmpty(),
                model = p[KEY_MODEL] ?: DEFAULT_MODEL
            )
        }

    suspend fun save(context: Context, enabled: Boolean, apiKey: String, model: String) {
        context.aiDataStore.edit { p ->
            p[KEY_ENABLED] = enabled
            p[KEY_API_KEY] = apiKey.trim()
            p[KEY_MODEL] = model.ifBlank { DEFAULT_MODEL }
        }
    }
}
