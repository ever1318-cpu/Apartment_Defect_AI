package com.axlife.pinset.data

import android.content.Context
import com.axlife.pinset.BuildConfig
import com.axlife.pinset.ai.AiHttpTransport
import com.axlife.pinset.ai.UrlConnectionAiTransport
import com.axlife.pinset.ai.validatedAiApiUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Server-owned defect taxonomy with a last-known-good offline cache. */
data class FieldTaxonomyDetail(val code: String, val label: String, val tradeLabel: String)
data class FieldTaxonomyCatalog(
    val floorplanType: String,
    val roomCode: String,
    val surfaceCode: String,
    val details: List<FieldTaxonomyDetail>,
    val source: String,
    val cached: Boolean
)

class FieldTaxonomyRepository(
    private val context: Context,
    private val transport: AiHttpTransport = UrlConnectionAiTransport()
) {
    private val prefs = context.getSharedPreferences("field_taxonomy", Context.MODE_PRIVATE)

    suspend fun load(
        floorplanType: String,
        roomCode: String,
        surfaceCode: String
    ): FieldTaxonomyCatalog? {
        val key = cacheKey(floorplanType, roomCode, surfaceCode)
        val baseUrl = FieldEndpointPrefs.load(context)
        if (baseUrl.isNotBlank()) {
            val allowLocalHttp = BuildConfig.DEBUG && baseUrl.startsWith("http://")
            val result = runCatching {
                val root = validatedAiApiUrl(baseUrl, allowLocalHttp)
                val query = "floorplan_type=${encode(floorplanType)}&room_code=${encode(roomCode)}&surface_code=${encode(surfaceCode)}"
                val response = transport.request("GET", "$root/v2/field/taxonomy?$query", emptyMap(), null)
                require(response.status in 200..299) { "taxonomy HTTP ${response.status}" }
                parse(response.body, roomCode, surfaceCode, cached = false)
            }.getOrNull()
            if (result != null) {
                prefs.edit().putString(key, result.second).apply()
                return result.first
            }
        }
        return prefs.getString(key, null)?.let { raw ->
            runCatching { parse(raw, roomCode, surfaceCode, cached = true).first }.getOrNull()
        }
    }

    private fun parse(raw: String, roomCode: String, surfaceCode: String, cached: Boolean): Pair<FieldTaxonomyCatalog, String> {
        val body = JSONObject(raw)
        val details = body.optJSONArray("details")
        val values = buildList {
            if (details != null) for (index in 0 until details.length()) {
                val item = details.optJSONObject(index) ?: continue
                add(FieldTaxonomyDetail(
                    code = item.optString("code"),
                    label = item.optString("label"),
                    tradeLabel = item.optString("trade_label")
                ))
            }
        }.filter { it.label.isNotBlank() }.take(5)
        return FieldTaxonomyCatalog(
            floorplanType = body.optString("floorplan_type"),
            roomCode = roomCode,
            surfaceCode = surfaceCode,
            details = values,
            source = body.optString("source", "postgres"),
            cached = cached
        ) to raw
    }

    private fun cacheKey(floorplanType: String, roomCode: String, surfaceCode: String) =
        "catalog:${floorplanType}:${roomCode}:${surfaceCode}"

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}