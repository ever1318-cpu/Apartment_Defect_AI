package com.axlife.pinset.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageStore {
    fun captureDir(context: Context): File =
        File(context.filesDir, "captures").also { it.mkdirs() }

    fun newCaptureFile(context: Context, lensTag: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(captureDir(context), "cap_${stamp}_${lensTag}.jpg")
    }

    /**
     * Placeholder for EXIF metadata writing. Disabled until the
     * androidx.exifinterface dependency is confirmed present in Gradle sync.
     * Signature kept so callers don't need to change.
     */
    fun writeExifMeta(
        file: File,
        deviceLabel: String,
        slot: String,
        zoomRatio: Float,
        isDigital: Boolean
    ) {
        // no-op — enable once ExifInterface is resolved
    }
}
