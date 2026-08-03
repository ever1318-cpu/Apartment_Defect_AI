package com.axlife.pinset

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.axlife.pinset.camera.CaptureResult
import com.axlife.pinset.data.AppDatabase
import com.axlife.pinset.data.repo.DefectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class InspectionAccessMode { UNVERIFIED, AUTHENTICATED, DEMO }

class PinSetApplication : Application() {
    val database by lazy { AppDatabase.get(this) }
    val repository by lazy {
        DefectRepository(
            database.sessionDao(),
            database.defectDao(),
            database.defectPhotoDao(),
            database.syncQueueDao()
        )
    }

    val fieldTaxonomyRepository by lazy { com.axlife.pinset.data.FieldTaxonomyRepository(this) }

    val syncManager by lazy {
        com.axlife.pinset.sync.OfflineSyncManager(this, database) { createDefectSyncUploader() }
    }

    private fun createDefectSyncUploader(): com.axlife.pinset.sync.DefectSyncUploader? {
        val baseUrl = com.axlife.pinset.data.FieldEndpointPrefs.load(this)
        // Debug builds may reach a developer PC over the private LAN.
        // Release builds still require HTTPS through validatedAiApiUrl().
        val allowLocalHttp = BuildConfig.DEBUG && baseUrl.startsWith("http://")
        val uploader = if (baseUrl.isBlank()) {
            null
        } else {
            val media = com.axlife.pinset.ai.RealAiMediaUploader(
                baseUrl = baseUrl,
                allowLocalHttp = allowLocalHttp
            )
            com.axlife.pinset.sync.RealDefectSyncUploader(
                baseUrl = baseUrl,
                deviceId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ).orEmpty().ifBlank { "pinset-unknown-device" },
                mediaUploader = media,
                allowLocalHttp = allowLocalHttp
            )
        }
        return uploader
    }

    val sessionCompletionUploader: com.axlife.pinset.sync.SessionCompletionUploader?
        get() {
        val baseUrl = com.axlife.pinset.data.FieldEndpointPrefs.load(this)
        return if (baseUrl.isBlank()) null else com.axlife.pinset.sync.SessionCompletionUploader(
            baseUrl = baseUrl,
            allowLocalHttp = BuildConfig.DEBUG && baseUrl.startsWith("http://")
        )
    }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repository.normalizeLocalPhotoFileNames()
            syncManager.start()
        }
    }

    /** Temporary hand-off from CameraScreen → PinPlacementScreen. */
    @Volatile
    var pendingCapture: CaptureResult? = null

    @Volatile
    var pendingAiLocationHint: String? = null

    /** Non-null while CameraScreen is adding evidence to an existing defect. */
    @Volatile
    var pendingAdditionalPhotoDefectId: Long? = null

    /** Runtime gate selected by the intro login dialog. DEMO never syncs to the server. */
    var inspectionAccessMode by mutableStateOf(InspectionAccessMode.UNVERIFIED)
}
