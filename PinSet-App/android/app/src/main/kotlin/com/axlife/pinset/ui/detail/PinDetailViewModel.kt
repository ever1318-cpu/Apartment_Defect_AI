package com.axlife.pinset.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectPhoto
import com.axlife.pinset.data.entity.SyncQueueItem
import com.axlife.pinset.data.repo.DefectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PinDetailState(
    val defect: Defect? = null,
    val photos: List<DefectPhoto> = emptyList(),
    val sync: SyncQueueItem? = null
)

class PinDetailViewModel(
    private val repo: DefectRepository,
    private val defectId: Long,
    private val triggerSync: () -> Unit
) : ViewModel() {

    private val _state = MutableStateFlow(PinDetailState())
    val state: StateFlow<PinDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repo.observeDefect(defectId),
                repo.observePhotos(defectId),
                repo.observeSync(defectId)
            ) { d, p, sync -> Triple(d, p, sync) }
                .collect { (d, p, sync) ->
                    _state.update { it.copy(defect = d, photos = p, sync = sync) }
                }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val d = _state.value.defect ?: return
        viewModelScope.launch {
            repo.deleteDefect(d)
            onDeleted()
        }
    }

    fun update(patch: Defect) {
        viewModelScope.launch {
            repo.updateDefect(patch)
            triggerSync()
            _state.update { it.copy(defect = patch) }
        }
    }

    fun retrySync() {
        viewModelScope.launch {
            repo.requestSync(defectId)
            triggerSync()
        }
    }

    class Factory(private val defectId: Long) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PinSetApplication
            @Suppress("UNCHECKED_CAST")
            return PinDetailViewModel(app.repository, defectId, app.syncManager::trigger) as T
        }
    }
}
