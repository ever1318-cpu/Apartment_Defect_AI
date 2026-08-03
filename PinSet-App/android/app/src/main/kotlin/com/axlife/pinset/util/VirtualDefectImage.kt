package com.axlife.pinset.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectType
import java.io.FileOutputStream

/**
 * Creates a clearly labelled *reference illustration* when an inspector saves
 * an opinion without a camera image.  It is evidence of the entered opinion,
 * never evidence of the physical condition: the watermark makes that boundary
 * explicit after local storage, upload, and gallery retrieval.
 */
object VirtualDefectImage {
    fun create(context: Context, defect: Defect): String {
        val file = ImageStore.newCaptureFile(context, "virtual_reference")
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(239, 241, 244))

        paint.color = Color.rgb(35, 48, 65)
        canvas.drawRect(0f, 0f, 1200f, 130f, paint)
        paint.color = Color.WHITE
        paint.textSize = 42f
        paint.isFakeBoldText = true
        canvas.drawText("가상 참고 이미지 · 실제 촬영 없음", 55f, 80f, paint)
        paint.isFakeBoldText = false

        paint.color = Color.rgb(220, 224, 229)
        canvas.drawRoundRect(110f, 190f, 1090f, 725f, 24f, 24f, paint)
        drawDefectSymbol(canvas, paint, defect.defectType)

        paint.color = Color.rgb(35, 48, 65)
        paint.textSize = 38f
        canvas.drawText("${defect.roomLabel} · ${defect.surface.name}", 80f, 790f, paint)
        paint.textSize = 28f
        val opinion = defect.residentOpinion.ifBlank { defect.finalPathText }.take(44)
        canvas.drawText(if (opinion.isBlank()) "점검자 의견 기반 분류" else opinion, 80f, 840f, paint)

        FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun drawDefectSymbol(canvas: Canvas, paint: Paint, type: DefectType) {
        when (type) {
            DefectType.CRACK -> {
                paint.color = Color.rgb(83, 71, 63)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 18f
                val crack = Path().apply {
                    moveTo(250f, 480f)
                    lineTo(400f, 360f)
                    lineTo(520f, 470f)
                    lineTo(670f, 305f)
                    lineTo(830f, 445f)
                    lineTo(960f, 340f)
                }
                canvas.drawPath(crack, paint)
                paint.style = Paint.Style.FILL
            }
            DefectType.LEAK -> {
                paint.color = Color.rgb(71, 145, 211)
                canvas.drawCircle(600f, 445f, 145f, paint)
                val drop = Path().apply {
                    moveTo(600f, 235f)
                    lineTo(485f, 440f)
                    quadTo(600f, 610f, 715f, 440f)
                    close()
                }
                canvas.drawPath(drop, paint)
            }
            DefectType.FINISH -> {
                paint.color = Color.rgb(181, 137, 90)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 10f
                repeat(4) { row -> repeat(6) { col ->
                    canvas.drawRect(260f + col * 115f, 290f + row * 105f, 360f + col * 115f, 380f + row * 105f, paint)
                } }
                paint.style = Paint.Style.FILL
            }
            DefectType.OTHER -> {
                paint.color = Color.rgb(221, 120, 45)
                val sign = Path().apply {
                    moveTo(600f, 260f); lineTo(850f, 630f); lineTo(350f, 630f); close()
                }
                canvas.drawPath(sign, paint)
                paint.color = Color.WHITE
                paint.textSize = 180f
                canvas.drawText("!", 565f, 570f, paint)
            }
        }
    }
}
