package com.axlife.pinset.vision

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Placeholder AR tracker.
 *
 * ARCore requires exclusive access to the camera stream, which conflicts with
 * the app's Camera2 dual-preview session. Enabling ARCore here would require
 * moving to the Shared Camera API, which does not currently support the
 * simultaneous multi-lens capture we rely on.
 *
 * This class keeps the public surface stable (available, tracking, hasAnchor,
 * currentRelativePose, setAnchorHere, tick, tryStart, stop, release) so the
 * rest of the app can call into it without conditional code. All methods
 * either no-op or return the "not available" answer. When a future release
 * wires in a real ARCore session, only this file changes.
 */
class ArTracker(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _tracking = MutableStateFlow(false)
    val tracking: StateFlow<Boolean> = _tracking.asStateFlow()

    fun tryStart(): Boolean = false
    fun stop() { /* no-op */ }
    fun release() { /* no-op */ }
    fun tick(): Any? = null
    fun setAnchorHere(): Boolean = false
    fun hasAnchor(): Boolean = false
    fun clearAnchor() { /* no-op */ }
    fun currentRelativePose(): FloatArray? = null
}
