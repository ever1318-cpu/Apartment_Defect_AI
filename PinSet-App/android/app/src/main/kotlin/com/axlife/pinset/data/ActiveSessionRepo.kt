package com.axlife.pinset.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.activeSessionStore by preferencesDataStore("active_session")

/**
 * Persists which Session row is currently "active" — the one new captures
 * attach to and the home screen summarizes.
 */
object ActiveSessionRepo {
    private val KEY = longPreferencesKey("active_id")

    fun observe(context: Context): Flow<Long?> =
        context.activeSessionStore.data.map { p -> p[KEY]?.takeIf { it > 0 } }

    suspend fun set(context: Context, sessionId: Long) {
        context.activeSessionStore.edit { p -> p[KEY] = sessionId }
    }

    suspend fun clear(context: Context) {
        context.activeSessionStore.edit { p -> p.remove(KEY) }
    }
}
