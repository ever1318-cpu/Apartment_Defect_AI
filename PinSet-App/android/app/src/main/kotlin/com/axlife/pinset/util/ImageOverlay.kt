package com.axlife.pinset.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageOverlay {
    private const val TAG = "ImageOverlay"

    /**
     * Bake sensor metadata onto a saved JPEG so it survives outside the app.
     *
     *   - Top band: single-line readout of tilt / heading / distance.
     *   - Top-right corner: small compass rose whose red needle points to true
     *     north (rotated by the negation of headingDeg).
     *
     * Original dimensions are preserved. Best-effort — errors are logged and
     * swallowed so a stamp failure never blocks the capture pipeline.
     */
    fun stampSensorLine(
        file: File,
        pitchDeg: Float,
        headingDeg: Float,
        anchorDistance: String,
        rotationDeg: Int = 90
    ): Boolean {
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        return try {
            // Sensor pixels come in landscape; rotate them upright first so
            // the stamped band and compass badge land at the visual top of
            // the saved image (independent of EXIF).
            val rotated = if (rotationDeg % 360 != 0) {
                val m = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                Bitmap.createBitmap(
                    original, 0, 0, original.width, original.height, m, true
                )
            } else original
            val out = rotated.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)

            drawTopBand(c, out.width, out.height, pitchDeg, headingDeg, anchorDistance)
            drawCompassBadge(c, out.width, headingDeg)

            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            out.recycle()
            if (rotated !== original) rotated.recycle()
            original.recycle()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "stampSensorLine failed: ${t.message}")
            false
        }
    }

    private fun drawTopBand(
        c: Canvas, w: Int, h: Int,
        pitchDeg: Float, headingDeg: Float, distance: String
    ) {
        val bandH = (h * 0.055f).coerceAtLeast(48f)
        val bandPaint = Paint().apply {
            color = Color.argb(160, 0, 0, 0)
            style = Paint.Style.FILL
        }
        c.drawRect(0f, 0f, w.toFloat(), bandH, bandPaint)
        val textPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textSize = bandH * 0.55f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val line = "기울기 ${pitchDeg.toInt()}°  ·  방향 ${headingDeg.toInt()}° ${compass(headingDeg)}  ·  거리 $distance"
        val bounds = Rect()
        textPaint.getTextBounds(line, 0, line.length, bounds)
        // Leave room on the right for the compass badge.
        val margin = w * 0.14f
        val availableW = w - margin
        val x = (availableW - bounds.width()) / 2f
        val y = bandH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        c.drawText(line, x.coerceAtLeast(8f), y, textPaint)
    }

    /**
     * Compact compass rose at the top-right. Fixed N label at the top so the
     * card stays readable; the needle rotates so its red tip points to real
     * north given the phone's heading at capture time.
     */
    private fun drawCompassBadge(c: Canvas, imgW: Int, headingDeg: Float) {
        val diameter = imgW * 0.11f
        val cx = imgW - diameter / 2f - imgW * 0.02f
        val cy = diameter / 2f + imgW * 0.02f
        val r = diameter / 2f

        val bg = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        c.drawCircle(cx, cy, r, bg)

        val ring = Paint().apply {
            color = Color.argb(200, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = r * 0.08f
            isAntiAlias = true
        }
        c.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)

        // "N" label — fixed at top.
        val labelPaint = Paint().apply {
            color = Color.WHITE
            textSize = r * 0.55f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("N", cx, cy - r * 0.55f, labelPaint)

        // Rotate the needle -headingDeg so a heading of 0 points straight up.
        c.save()
        c.rotate(-headingDeg, cx, cy)

        val needleLen = r * 0.75f
        val needleHalf = needleLen * 0.22f

        val northPaint = Paint().apply {
            color = Color.rgb(229, 57, 53)   // red
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val southPaint = Paint().apply {
            color = Color.argb(220, 240, 240, 240)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val north = Path().apply {
            moveTo(cx, cy - needleLen)
            lineTo(cx - needleHalf, cy)
            lineTo(cx + needleHalf, cy)
            close()
        }
        val south = Path().apply {
            moveTo(cx, cy + needleLen)
            lineTo(cx - needleHalf, cy)
            lineTo(cx + needleHalf, cy)
            close()
        }
        c.drawPath(south, southPaint)
        c.drawPath(north, northPaint)

        c.restore()

        // Pivot cap.
        c.drawCircle(cx, cy, r * 0.09f, ring)
    }

    /**
     * Draw a red circular defect-region marker on top of an existing JPEG.
     * Called AFTER [stampSensorLine] has re-oriented pixels into portrait
     * (so we operate on width/height in visual orientation, not raw sensor
     * axes). Marker is always centered on the frame; radius scales with the
     * user-chosen fraction of the shorter side.
     *
     *   - Solid red ring, ~2% of the shorter side.
     *   - Small red crosshair at the exact center for precision.
     *   - Semi-transparent red fill inside the ring so the region is
     *     obvious even at thumbnail size.
     */
    /**
     * Bake a hollow RED DASHED ring onto the JPEG (no fill, no crosshair).
     * Marks the operator-selected defect region at capture time.
     *
     * Uses android.graphics.DashPathEffect on a Path so the dash spacing
     * scales with radius — the ring stays legible whether the operator
     * chose a tiny or a large region.
     */
    fun stampDefectMarker(
        file: File,
        radiusFraction: Float
    ): Boolean {
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        return try {
            val out = original.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            val w = out.width.toFloat()
            val h = out.height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val shortSide = kotlin.math.min(w, h)
            val r = shortSide * radiusFraction.coerceIn(0.05f, 0.45f)
            val stroke = shortSide * 0.010f
            val dash = shortSide * 0.022f
            val gap = shortSide * 0.014f

            val ringPath = Path().apply {
                addCircle(cx, cy, r, Path.Direction.CW)
            }

            // White backing so the dashed red stays visible on both bright
            // and dark scenes. Slightly wider than the red pass.
            val backing = Paint().apply {
                color = Color.argb(230, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = stroke * 1.8f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(
                    floatArrayOf(dash, gap), 0f
                )
            }
            c.drawPath(ringPath, backing)

            // Main red dashed ring.
            val red = Paint().apply {
                color = Color.rgb(229, 57, 53)
                style = Paint.Style.STROKE
                strokeWidth = stroke
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(
                    floatArrayOf(dash, gap), 0f
                )
            }
            c.drawPath(ringPath, red)

            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            out.recycle()
            original.recycle()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "stampDefectMarker failed: ${t.message}")
            false
        }
    }

    /**
     * Adds a persistent inspection label after the operator has confirmed the
     * room and detailed part.  This intentionally happens at opinion-save
     * time, rather than shutter time, because detailed defect information is
     * not known yet while taking the picture.
     */
    fun stampInspectionLabel(
        file: File,
        unitLabel: String,
        roomLabel: String,
        detailLabel: String,
        capturedAtMillis: Long
    ): Boolean {
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        return try {
            val out = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val bandH = (out.height * 0.072f).coerceAtLeast(58f)
            val top = out.height - bandH
            val band = Paint().apply { color = Color.argb(188, 0, 0, 0) }
            canvas.drawRect(0f, top, out.width.toFloat(), out.height.toFloat(), band)
            val stamp = SimpleDateFormat("MMdd-HH-mm-ss", Locale.US)
                .format(Date(capturedAtMillis))
            val line = "$unitLabel | $roomLabel | $detailLabel | $stamp"
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = bandH * 0.45f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val maxWidth = out.width * 0.95f
            while (text.measureText(line) > maxWidth && text.textSize > 16f) {
                text.textSize *= 0.92f
            }
            val x = (out.width - text.measureText(line)) / 2f
            val y = top + bandH / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(line, x.coerceAtLeast(8f), y, text)
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            out.recycle()
            original.recycle()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "stampInspectionLabel failed: ${t.message}")
            false
        }
    }

    /**
     * Marks the one-time inspection origin directly on both anchor photos.
     * It is intentionally distinct from a defect label: the unit, the word
     * "앵커", selected location and capture time remain visible after export.
     */
    fun stampAnchorLabel(
        file: File,
        unitLabel: String,
        locationLabel: String,
        capturedAtMillis: Long
    ): Boolean {
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        return try {
            val out = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val bandH = (out.height * 0.078f).coerceAtLeast(64f)
            val top = out.height - bandH
            canvas.drawRect(
                0f, top, out.width.toFloat(), out.height.toFloat(),
                Paint().apply { color = Color.argb(202, 0, 77, 86) }
            )
            val stamp = SimpleDateFormat("MMdd-HH-mm-ss", Locale.US)
                .format(Date(capturedAtMillis))
            val line = "$unitLabel | 앵커 | $locationLabel | $stamp"
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = bandH * 0.46f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val maxWidth = out.width * 0.94f
            while (text.measureText(line) > maxWidth && text.textSize > 16f) {
                text.textSize *= 0.92f
            }
            val x = (out.width - text.measureText(line)) / 2f
            val y = top + bandH / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(line, x.coerceAtLeast(8f), y, text)
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            out.recycle()
            original.recycle()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "stampAnchorLabel failed: ${t.message}")
            false
        }
    }

    private fun compass(deg: Float): String {
        val d = ((deg % 360f) + 360f) % 360f
        return when {
            d < 22.5f || d >= 337.5f -> "N"
            d < 67.5f  -> "NE"
            d < 112.5f -> "E"
            d < 157.5f -> "SE"
            d < 202.5f -> "S"
            d < 247.5f -> "SW"
            d < 292.5f -> "W"
            else       -> "NW"
        }
    }
}
