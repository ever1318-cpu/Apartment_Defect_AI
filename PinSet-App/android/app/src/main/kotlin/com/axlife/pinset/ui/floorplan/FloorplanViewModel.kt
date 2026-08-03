package com.axlife.pinset.ui.floorplan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.repo.DefectRepository
import com.axlife.pinset.util.FloorplanStore
import com.axlife.pinset.vision.FloorplanMeta
import com.axlife.pinset.vision.ReferenceDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FloorplanState(
    val floorplan: FloorplanMeta? = null,
    val floorplanBitmap: Bitmap? = null,
    val defects: List<Defect> = emptyList(),
    val session: com.axlife.pinset.data.entity.Session? = null,
    /** True if the loaded bitmap came from a user-imported image (not assets). */
    val customFloorplan: Boolean = false
)

class FloorplanViewModel(private val app: PinSetApplication) : ViewModel() {
    private val repo: DefectRepository = app.repository
    private val db = ReferenceDb(app)

    private val _state = MutableStateFlow(FloorplanState())
    val state: StateFlow<FloorplanState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            reload()
            repo.observeDefects(repo.activeSession(app).id).collect { defects ->
                val refreshed = repo.currentSession().first() ?: return@collect
                _state.update {
                    it.copy(
                        defects = defects,
                        session = refreshed
                    )
                }
            }
        }
    }

    /**
     * Re-read the session and its floorplan bitmap. Called on init and after
     * the user imports a new custom floorplan so the tab shows the change
     * immediately.
     */
    private suspend fun reload() {
        val session = repo.activeSession(app)
        val meta = withContext(Dispatchers.IO) { db.floorplan(session.floorplanAssetId) }
        val custom = session.customFloorplanPath
        val bmp: Bitmap? = withContext(Dispatchers.IO) {
            if (custom != null && File(custom).exists()) {
                runCatching { BitmapFactory.decodeFile(custom) }.getOrNull()
            } else {
                runCatching { db.loadFloorplanBitmap(session.floorplanAssetId, meta) }.getOrNull()
            }
        }
        _state.update {
            it.copy(
                floorplan = meta,
                floorplanBitmap = bmp,
                session = session,
                customFloorplan = custom != null
            )
        }
    }

    /**
     * Copy [uri] (picked via a gallery intent) into the app-private
     * floorplans directory and attach the new path to the active session.
     */
    fun importCustomFloorplan(uri: Uri) {
        viewModelScope.launch {
            val session = repo.activeSession(app)
            val path = withContext(Dispatchers.IO) {
                FloorplanStore.importFromUri(app, session.id, uri)
            } ?: return@launch
            repo.setCustomFloorplan(session.id, path)
            reload()
        }
    }

    /** Update a defect in place — used by the expanded-map inline editor. */
    fun updateDefect(d: Defect) {
        viewModelScope.launch { repo.updateDefect(d) }
    }

    /** Blocking-style helper for UI: return the 1x (slot A) photo path if any. */
    suspend fun primaryPhotoPath(defectId: Long): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.database.defectPhotoDao().getByDefect(defectId)
                .firstOrNull { it.slot == com.axlife.pinset.data.entity.SlotRole.A }
                ?.filePath
        }

    /** Revert to the session's built-in asset floorplan. */
    fun clearCustomFloorplan() {
        viewModelScope.launch {
            val session = repo.activeSession(app)
            repo.setCustomFloorplan(session.id, null)
            reload()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PinSetApplication
                FloorplanViewModel(app)
            }
        }
    }
}
