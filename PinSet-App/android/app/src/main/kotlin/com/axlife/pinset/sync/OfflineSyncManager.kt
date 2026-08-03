package com.axlife.pinset.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.axlife.pinset.data.AppDatabase
import com.axlife.pinset.data.entity.SyncQueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OfflineSyncManager(
    context: Context,
    private val database: AppDatabase,
    private val uploaderProvider: () -> DefectSyncUploader?
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var delayedRetry: Job? = null
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = trigger()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) trigger()
        }
    }

    fun start() {
        if (started) return
        started = true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        scope.launch {
            database.syncQueueDao().defectIdsWithoutQueue().forEach { defectId ->
                database.syncQueueDao().enqueue(SyncQueueItem(localDefectId = defectId))
            }
            // Do not strand a retry after the process was stopped during its
            // backoff window. Local records remain authoritative until this
            // delivery succeeds.
            database.syncQueueDao().makeRetriesDue()
            database.syncQueueDao().recoverInterruptedUploads(System.currentTimeMillis())
            trigger()
        }
    }

    fun trigger() {
        val app = appContext as? com.axlife.pinset.PinSetApplication
        if (app?.inspectionAccessMode == com.axlife.pinset.InspectionAccessMode.DEMO) return
        if (uploaderProvider() == null || !hasValidatedNetwork()) return
        scope.launch { drain() }
    }

    /** Runs one delivery pass so the close-session action can give a definite result. */
    suspend fun flush(): Boolean {
        val app = appContext as? com.axlife.pinset.PinSetApplication
        if (app?.inspectionAccessMode == com.axlife.pinset.InspectionAccessMode.DEMO) return false
        if (uploaderProvider() == null || !hasValidatedNetwork()) return false
        // A user explicitly pressing final close is a new delivery attempt.
        // Do not leave previously failed items behind an exponential-backoff
        // timer; otherwise the close screen appears to loop even after the
        // server and USB reverse connection have been restored.
        database.syncQueueDao().makeRetriesDue()
        drain()
        return true
    }

    private suspend fun drain() = mutex.withLock {
        val uploader = uploaderProvider() ?: return
        val syncDao = database.syncQueueDao()
        val now = System.currentTimeMillis()
        val items = syncDao.due(now)
        for (item in items) {
            if (!hasValidatedNetwork()) break
            val defect = database.defectDao().getById(item.localDefectId)
            val photos = database.defectPhotoDao().getByDefect(item.localDefectId)
            val session = defect?.let { database.sessionDao().getById(it.sessionId) }
            if (defect == null || session == null) continue
            syncDao.markUploading(item.operationId, System.currentTimeMillis())
            when (val result = uploader.upload(item, defect, photos, session)) {
                is SyncUploadResult.Applied -> syncDao.markCompleted(
                    item.operationId, result.serverRevision, System.currentTimeMillis()
                )
                is SyncUploadResult.Conflict -> syncDao.markConflict(
                    item.operationId, result.message, System.currentTimeMillis()
                )
                is SyncUploadResult.Retry -> {
                    val next = System.currentTimeMillis() +
                        SyncRetryPolicy.delayMs(item.attemptCount)
                    syncDao.markRetry(
                        item.operationId, result.message, next, System.currentTimeMillis()
                    )
                    schedule(next)
                }
            }
        }
    }

    private fun schedule(atMillis: Long) {
        delayedRetry?.cancel()
        delayedRetry = scope.launch {
            delay((atMillis - System.currentTimeMillis()).coerceAtLeast(1_000L))
            trigger()
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
