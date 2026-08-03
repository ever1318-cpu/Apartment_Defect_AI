package com.axlife.pinset.vision

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReferenceEntry(
    val file: String,       // relative to assets/reference/
    val roomId: String,     // e.g. "livingroom"
    val roomLabel: String   // e.g. "거실"
)

@Serializable
data class ReferenceIndex(val entries: List<ReferenceEntry>)

@Serializable
data class FloorplanRoomAnchor(
    val id: String,
    val label: String,
    val cx: Float,          // 0..1 normalized
    val cy: Float,
    val bbox: List<Float>   // [x0, y0, x1, y1] normalized
)

@Serializable
data class FloorplanEntrance(
    val cx: Float,
    val cy: Float,
    val label: String = "현관"
)

@Serializable
data class FloorplanMeta(
    val id: String,
    val label: String,
    val imageFile: String,       // relative to assets/floorplans/
    val rooms: List<FloorplanRoomAnchor>,
    val entrance: FloorplanEntrance? = null,
    /** Optional normalized crop [left, top, right, bottom] used when the
     * supplied source is a brochure page rather than a floorplan-only image. */
    val displayCrop: List<Float>? = null
)

@Serializable
data class FloorplanCatalogEntry(
    val id: String,
    val label: String,
    val areaPyeong: Int = 0,
    val default: Boolean = false,
    val siteMapFile: String? = null
)

@Serializable
data class FloorplanCatalog(val floorplans: List<FloorplanCatalogEntry>)

/**
 * Loads reference photo index and floorplan metadata from app assets.
 * Not thread-safe — call from a single scope.
 */
class ReferenceDb(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private var references: List<ReferenceEntry>? = null

    fun index(): List<ReferenceEntry> {
        references?.let { return it }
        val fromAssets = try {
            val raw = context.assets.open("reference/index.json").bufferedReader().use { it.readText() }
            json.decodeFromString(ReferenceIndex.serializer(), raw).entries
        } catch (_: Throwable) { emptyList() }
        val userDir = java.io.File(context.filesDir, "reference")
        val fromUser = if (userDir.exists()) {
            userDir.listFiles()?.filter { it.isDirectory }?.flatMap { roomDir ->
                roomDir.listFiles()?.filter { it.isFile && it.extension in setOf("jpg", "jpeg", "png") }?.map { f ->
                    ReferenceEntry(
                        file = "user::${roomDir.name}::${f.name}",
                        roomId = roomDir.name,
                        roomLabel = ""  // resolved from floorplan meta at match time
                    )
                } ?: emptyList()
            } ?: emptyList()
        } else emptyList()
        val all = fromAssets + fromUser
        references = all
        return all
    }

    fun invalidateIndex() { references = null }

    fun loadReferenceBitmap(entry: ReferenceEntry): android.graphics.Bitmap? {
        return try {
            if (entry.file.startsWith("user::")) {
                val parts = entry.file.removePrefix("user::").split("::")
                val f = java.io.File(context.filesDir, "reference/${parts[0]}/${parts[1]}")
                BitmapFactory.decodeFile(f.absolutePath)
            } else {
                context.assets.open("reference/${entry.file}").use { BitmapFactory.decodeStream(it) }
            }
        } catch (_: Throwable) { null }
    }

    fun floorplan(assetId: String): FloorplanMeta {
        val raw = context.assets.open("floorplans/$assetId.json").bufferedReader().use { it.readText() }
        return json.decodeFromString(FloorplanMeta.serializer(), raw)
    }

    /**
     * Enumerate available floorplans from assets/floorplans/catalog.json.
     * Falls back to a single 101동 1502호 entry if the catalog is missing —
     * old builds without the file still work.
     */
    fun catalog(): List<FloorplanCatalogEntry> = try {
        val raw = context.assets.open("floorplans/catalog.json").bufferedReader().use { it.readText() }
        json.decodeFromString(FloorplanCatalog.serializer(), raw).floorplans
    } catch (_: Throwable) {
        listOf(FloorplanCatalogEntry("apt_101_1502", "101동 1502호", 32, true, null))
    }

    fun defaultFloorplanId(): String =
        catalog().firstOrNull { it.default }?.id
            ?: catalog().firstOrNull()?.id
            ?: "apt_101_1502"

    fun loadFloorplanBitmap(assetId: String, meta: FloorplanMeta): android.graphics.Bitmap {
        val source = context.assets.open("floorplans/${meta.imageFile}").use {
            BitmapFactory.decodeStream(it)
        }
        val crop = meta.displayCrop
        if (crop == null || crop.size != 4) return source
        val left = (source.width * crop[0]).toInt().coerceIn(0, source.width - 1)
        val top = (source.height * crop[1]).toInt().coerceIn(0, source.height - 1)
        val right = (source.width * crop[2]).toInt().coerceIn(left + 1, source.width)
        val bottom = (source.height * crop[3]).toInt().coerceIn(top + 1, source.height)
        return android.graphics.Bitmap.createBitmap(source, left, top, right - left, bottom - top)
            .also { if (it !== source) source.recycle() }
    }

    fun loadSiteMapBitmap(fileName: String): android.graphics.Bitmap =
        context.assets.open("floorplans/$fileName").use { BitmapFactory.decodeStream(it) }
}
