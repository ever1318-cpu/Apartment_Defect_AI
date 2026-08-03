package com.axlife.pinset.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FloorplanStore {
    private fun dir(context: Context): File =
        File(context.filesDir, "floorplans").also { it.mkdirs() }

    /**
     * Copy the image behind [uri] into the app's private storage, keyed to
     * this [sessionId], and return the absolute path. Overwrites any prior
     * custom floorplan for the same session so the DB never accumulates
     * orphaned files.
     */
    fun importFromUri(context: Context, sessionId: Long, uri: Uri): String? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir(context), "session_${sessionId}_$stamp.jpg")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            // Purge older files for the same session so we don't leak storage.
            dir(context).listFiles { f ->
                f.name.startsWith("session_${sessionId}_") && f.absolutePath != out.absolutePath
            }?.forEach { it.delete() }
            out.absolutePath
        } catch (_: Throwable) { null }
    }
}
