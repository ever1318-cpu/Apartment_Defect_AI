package com.axlife.pinset.data

import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.slotStore by preferencesDataStore("slot_prefs")

/**
 * Zoom ratios for the two capture slots.
 *   A = top / detail  (default 20x — hardware may clamp to actual max)
 *   B = bottom / wide (default 0.5x)
 *
 * With hardware dual-preview these values are ignored in favor of the two
 * physical lens native zooms. They are still used by the single-preview
 * fallback and as user-facing defaults in the settings dialog.
 */
data class SlotPrefs(
    val a: Float = 20.0f,
    val b: Float = 0.5f
) {
    val list: List<Float> get() = listOf(a, b)
    val labels: List<String> get() = list.map { format(it) }
    companion object {
        fun format(v: Float): String {
            val i = v.toInt()
            return if (kotlin.math.abs(v - i) < 0.05f) "${i}x" else String.format("%.1fx", v)
        }
    }
}

object SlotPrefsRepo {
    private val KEY_A = floatPreferencesKey("slot_a")
    private val KEY_B = floatPreferencesKey("slot_b")

    fun observe(context: Context): Flow<SlotPrefs> =
        context.slotStore.data.map { p ->
            SlotPrefs(
                a = p[KEY_A] ?: 20.0f,
                b = p[KEY_B] ?: 0.5f
            )
        }

    suspend fun save(context: Context, prefs: SlotPrefs) {
        context.slotStore.edit { p ->
            p[KEY_A] = prefs.a
            p[KEY_B] = prefs.b
        }
    }
}
