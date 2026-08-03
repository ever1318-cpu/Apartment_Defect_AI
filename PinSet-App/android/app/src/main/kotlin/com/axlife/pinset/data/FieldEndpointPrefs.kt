package com.axlife.pinset.data

import android.content.Context
import com.axlife.pinset.BuildConfig

/** User-configurable field API endpoint. It contains no credential. */
object FieldEndpointPrefs {
    private const val PREFS = "field_endpoint"
    private const val KEY_URL = "api_base_url"

    fun load(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_URL, null)
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() }
        ?: BuildConfig.AI_API_BASE_URL.trim().trimEnd('/')

    fun save(context: Context, rawUrl: String) {
        val value = rawUrl.trim().trimEnd('/')
        require(value.startsWith("https://") || (BuildConfig.DEBUG && value.startsWith("http://"))) {
            "운영 서버는 HTTPS 주소를 사용해야 합니다."
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, value).apply()
    }
}
