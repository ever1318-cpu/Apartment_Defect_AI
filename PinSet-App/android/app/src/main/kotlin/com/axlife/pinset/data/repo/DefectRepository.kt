package com.axlife.pinset.data.repo

import android.content.Context
import com.axlife.pinset.data.ActiveSessionRepo
import com.axlife.pinset.data.dao.DefectDao
import com.axlife.pinset.data.dao.DefectPhotoDao
import com.axlife.pinset.data.dao.SessionDao
import com.axlife.pinset.data.dao.SyncQueueDao
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectPhoto
import com.axlife.pinset.data.entity.Session
import com.axlife.pinset.data.entity.SyncQueueItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DefectRepository(
    private val sessionDao: SessionDao,
    private val defectDao: DefectDao,
    private val photoDao: DefectPhotoDao,
    private val syncQueueDao: SyncQueueDao
) {
    fun currentSession(): Flow<Session?> = sessionDao.currentSession()
    fun allSessions(): Flow<List<Session>> = sessionDao.observeAll()

    /**
     * Returns the active session. If ActiveSessionRepo has a stored id, that
     * one is preferred; otherwise falls back to the most-recent session, or
     * creates a default one.
     */
    suspend fun activeSession(context: Context): Session {
        val activeId = ActiveSessionRepo.observe(context).first()
        if (activeId != null) {
            sessionDao.getById(activeId)?.let { return it }
        }
        sessionDao.observeAll().first().firstOrNull()?.let {
            ActiveSessionRepo.set(context, it.id)
            return it
        }
        // Pick the default floorplan from the catalog for the very first session.
        val defaultFloorplan = try {
            com.axlife.pinset.vision.ReferenceDb(context).defaultFloorplanId()
        } catch (_: Throwable) { "apt_101_1502" }
        val id = sessionDao.insert(Session(unitLabel = "샘플 호수", floorplanAssetId = defaultFloorplan))
        ActiveSessionRepo.set(context, id)
        return sessionDao.getById(id)!!
    }

    suspend fun ensureSession(unitLabel: String, floorplanAssetId: String): Session {
        sessionDao.currentSession().first()?.let { return it }
        val id = sessionDao.insert(Session(unitLabel = unitLabel, floorplanAssetId = floorplanAssetId))
        return sessionDao.getById(id)!!
    }

    suspend fun createSession(unitLabel: String, floorplanAssetId: String): Long =
        sessionDao.insert(Session(unitLabel = unitLabel, floorplanAssetId = floorplanAssetId))

    /** Creates a new amendment record without altering the completed original session. */
    suspend fun createAmendmentSession(
        unitLabel: String,
        floorplanAssetId: String,
        originalSessionId: Long,
        nextRevisionNo: Int
    ): Long = sessionDao.insert(
        Session(
            unitLabel = unitLabel,
            floorplanAssetId = floorplanAssetId,
            revisionNo = nextRevisionNo,
            amendedFromSessionId = originalSessionId,
            sessionMode = "AMENDMENT"
        )
    )

    suspend fun renameSession(id: Long, unitLabel: String) {
        sessionDao.getById(id)?.let { sessionDao.update(it.copy(unitLabel = unitLabel)) }
    }

    suspend fun finishSession(id: Long) {
        sessionDao.getById(id)?.let { sessionDao.update(it.copy(done = true)) }
    }

    /** Set the entrance anchor (called on first capture of the session). */
    suspend fun setStartAnchor(
        id: Long,
        xNorm: Float,
        yNorm: Float,
        headingDeg: Float,
        locationLabel: String? = null
    ) {
        sessionDao.getById(id)?.let {
            if (it.startXNorm == null) {
                sessionDao.update(it.copy(
                    startXNorm = xNorm,
                    startYNorm = yNorm,
                    startHeadingDeg = headingDeg,
                    anchorLocationLabel = locationLabel ?: it.anchorLocationLabel
                ))
            }
        }
    }

    /** Persist the operator-selected first inspection room as the session
     * anchor before camera launch. No compass heading is assigned because a
     * marketing floorplan is not guaranteed to be north-up. */
    suspend fun setInitialRoomAnchor(
        id: Long,
        xNorm: Float,
        yNorm: Float,
        roomLabel: String
    ) {
        sessionDao.getById(id)?.let {
            sessionDao.update(
                it.copy(
                    startXNorm = xNorm.coerceIn(0f, 1f),
                    startYNorm = yNorm.coerceIn(0f, 1f),
                    startHeadingDeg = null,
                    anchorLocationLabel = roomLabel
                )
            )
        }
    }

    suspend fun deleteSession(id: Long) {
        sessionDao.getById(id)?.let { sessionDao.delete(it) }
    }

    /** Record a user-imported floorplan image path against the session. */
    suspend fun setCustomFloorplan(id: Long, absolutePath: String?) {
        sessionDao.getById(id)?.let {
            sessionDao.update(it.copy(customFloorplanPath = absolutePath))
        }
    }

    /**
     * Save the two entrance-anchor photos + auto-derive the start pin from
     * the floorplan's entrance metadata. The anchor is confirmed only after
     * both files exist AND startXNorm/startYNorm are populated.
     */
    suspend fun setAnchorPhotos(
        id: Long,
        nearPath: String,
        farPath: String,
        headingDeg: Float,
        entranceX: Float,
        entranceY: Float,
        locationLabel: String = "현관문"
    ) {
        sessionDao.getById(id)?.let {
            sessionDao.update(
                it.copy(
                    anchorPhotoNearPath = nearPath,
                    anchorPhotoFarPath = farPath,
                    startXNorm = entranceX,
                    startYNorm = entranceY,
                    startHeadingDeg = headingDeg,
                    anchorLocationLabel = locationLabel
                )
            )
        }
    }

    fun observeDefects(sessionId: Long): Flow<List<Defect>> = defectDao.observeBySession(sessionId)
    fun countDefects(sessionId: Long): Flow<Int> = defectDao.countBySession(sessionId)
    fun observeDefect(id: Long): Flow<Defect?> = defectDao.observeById(id)
    fun observePhotos(defectId: Long): Flow<List<DefectPhoto>> = photoDao.observeByDefect(defectId)
    fun observeSync(defectId: Long): Flow<SyncQueueItem?> =
        syncQueueDao.observeForDefect(defectId)
    suspend fun requestSync(defectId: Long) = syncQueueDao.requestRetry(defectId)

    suspend fun incompleteSyncCount(sessionId: Long): Int =
        syncQueueDao.countIncompleteForSession(sessionId)

    suspend fun addDefect(defect: Defect, photos: List<DefectPhoto>): Long {
        val toInsert = if (defect.defectIndex <= 0) {
            val current = defectDao.observeBySession(defect.sessionId).first()
            val maxIdx = current.maxOfOrNull { it.defectIndex } ?: 0
            defect.copy(defectIndex = maxIdx + 1)
        } else defect
        return defectDao.insertOfflineBundle(
            defect = toInsert,
            photos = photos,
            operationId = java.util.UUID.randomUUID().toString(),
            entityId = java.util.UUID.randomUUID().toString()
        )
    }

    suspend fun updateDefect(defect: Defect) {
        defectDao.update(defect)
        val previous = syncQueueDao.getForDefect(defect.id)
        syncQueueDao.replace(
            SyncQueueItem(
                operationId = java.util.UUID.randomUUID().toString(),
                entityId = previous?.entityId ?: java.util.UUID.randomUUID().toString(),
                localDefectId = defect.id,
                serverRevision = previous?.serverRevision
            )
        )
    }

    /** Attach newly captured evidence to an existing defect and enqueue a
     * fresh idempotent sync operation without altering the defect metadata. */
    suspend fun addPhotos(defectId: Long, photos: List<DefectPhoto>) {
        val defect = defectDao.getById(defectId) ?: return
        photos.forEach { photoDao.insert(it.copy(id = 0, defectId = defectId)) }
        updateDefect(defect)
    }

    /** Rename local captures after final classification; server UUIDs remain unchanged. */
    suspend fun normalizeLocalPhotoFileNames(): Int {
        var renamed = 0
        photoDao.getAll().forEach { photo ->
            val source = File(photo.filePath)
            if (!source.isFile) return@forEach
            val defect = defectDao.getById(photo.defectId) ?: return@forEach
            val session = sessionDao.getById(defect.sessionId) ?: return@forEach
            val detail = defect.areaDetail.takeIf { it.isNotBlank() } ?: when (defect.surface) {
                com.axlife.pinset.data.entity.Surface.CEILING -> "천정"
                com.axlife.pinset.data.entity.Surface.FLOOR -> "바닥"
                com.axlife.pinset.data.entity.Surface.WALL -> "벽체"
            }
            val role = when (photo.slot) {
                com.axlife.pinset.data.entity.SlotRole.A -> "CLOSE"
                com.axlife.pinset.data.entity.SlotRole.B -> "WIDE"
                com.axlife.pinset.data.entity.SlotRole.C -> "EXTRA"
            }
            val stamp = SimpleDateFormat("MMdd-HH-mm-ss", Locale.US)
                .format(Date(source.lastModified().takeIf { it > 0L } ?: defect.createdAt))
            val extension = source.extension.ifBlank { "jpg" }.lowercase(Locale.US)
            val stem = listOf(
                safeFilePart(session.unitLabel), safeFilePart(defect.roomLabel),
                safeFilePart(detail), stamp, "D%03d".format(defect.defectIndex), role,
                if (source.name.contains("virtual_reference", ignoreCase = true)) "REFERENCE" else null,
                "P%05d".format(photo.id)
            ).filterNotNull().joinToString("_")
            val target = File(source.parentFile, "$stem.$extension")
            if (source.absolutePath != target.absolutePath && !target.exists() && source.renameTo(target)) {
                photoDao.update(photo.copy(filePath = target.absolutePath))
                renamed++
            }
        }
        return renamed
    }

    private fun safeFilePart(value: String): String = value
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|]+"), "-")
        .replace(Regex("\\s+"), "")
        .ifBlank { "unknown" }
    suspend fun deleteDefect(defect: Defect) = defectDao.delete(defect)

    suspend fun countBySessionNow(sessionId: Long): Int =
        defectDao.observeBySession(sessionId).first().size
}
