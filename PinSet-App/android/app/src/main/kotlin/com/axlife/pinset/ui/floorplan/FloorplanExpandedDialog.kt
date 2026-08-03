package com.axlife.pinset.ui.floorplan

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.Session
import com.axlife.pinset.ui.pinset.ArrowPin
import com.axlife.pinset.ui.pinset.CroppedFloorplan
import com.axlife.pinset.ui.pinset.DefectLabel
import com.axlife.pinset.ui.pinset.PinTagSheet
import com.axlife.pinset.ui.pinset.PlainFloorplan
import com.axlife.pinset.ui.pinset.TagSubmission
import com.axlife.pinset.vision.clusterDefects

/**
 * Full-screen zoomable floorplan. Pinch to zoom (1..5x), drag to pan,
 * double-tap to reset.
 *
 * As the user zooms in, defect labels progressively reveal more information:
 *   scale <= 1.4  →  arrows only (no text)
 *   scale <= 2.5  →  "#N · type" compact
 *   scale >  2.5  →  full multi-line label (room, area, trade, heading, note)
 *
 * Tapping an arrow opens the tag sheet in edit mode so the user can adjust
 * fields right on the zoomed map.
 */
@Composable
fun FloorplanExpandedDialog(
    bitmap: Bitmap,
    session: Session?,
    defects: List<Defect>,
    isCustom: Boolean = false,
    /**
     * Optional lookup: given a defect id, return the file path of its slot-A
     * (main / 1x) photo. Used by the on-map editor to show the reference shot
     * at the bottom of the screen while the user edits the pin.
     */
    photoForDefect: (Long) -> String? = { null },
    onDismiss: () -> Unit,
    onUpdateDefect: (Defect) -> Unit = {}
) {
    val clusters = clusterDefects(defects, threshold = 0.02f)
    var scale by remember { mutableStateOf(1f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    var editing by remember { mutableStateOf<Defect?>(null) }

    val labelTier = when {
        scale <= 1.4f -> 1     // arrows only
        scale <= 2.5f -> 2     // compact
        else          -> 3     // full detail
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offX = 0f; offY = 0f })
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1.01f) { offX += pan.x; offY += pan.y }
                        else { offX = 0f; offY = 0f }
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offX, translationY = offY
                    )
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val sourceAspect =
                        bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                    val availableAspect =
                        maxWidth.value / maxHeight.value.coerceAtLeast(1f)
                    val fittedWidth =
                        if (availableAspect > sourceAspect) maxHeight * sourceAspect else maxWidth

                    PlainFloorplan(
                        bitmap = bitmap,
                        modifier = Modifier.fillMaxWidth(fittedWidth / maxWidth)
                    ) { innerW, innerH ->
                        NavigationTrail(
                            session = session,
                            defects = defects,
                            parentWidthDp = innerW,
                            parentHeightDp = innerH
                        )
                        clusters.forEach { c ->
                            ArrowPin(
                                xNorm = c.xNorm,
                                yNorm = c.yNorm,
                                parentWidthDp = innerW,
                                parentHeightDp = innerH,
                                index = c.lead.defectIndex,
                                severity = c.lead.severity,
                                headingDeg = c.lead.imuHeadingDeg,
                                clusterCount = c.count,
                                onClick = { editing = c.lead }
                            )
                            DefectLabel(
                                defect = c.lead,
                                tier = labelTier,
                                parentWidthDp = innerW,
                                parentHeightDp = innerH
                            )
                        }
                    }
                }
            }

            // Fixed overlay: zoom badge + close button, unaffected by graphicsLayer.
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "${String.format("%.1f", scale)}x  ·  " + tierLabel(labelTier),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(20.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "닫기",
                        tint = Color.White,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
            // Bottom photo strip — visible only while a defect is being
            // edited on-map. Shows the primary 1x reference shot so the user
            // can see what they're editing.
            editing?.let { d ->
                val path = photoForDefect(d.id)
                if (path != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 12.dp, start = 12.dp, end = 12.dp)
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(6.dp)
                            ) {
                                coil.compose.AsyncImage(
                                    model = path,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .background(Color.Black, RoundedCornerShape(8.dp))
                                )
                                Text(
                                    "  #${d.defectIndex} · 기본 1x 사진",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Tag sheet for on-map editing — same PinTagSheet used elsewhere.
    editing?.let { d ->
        PinTagSheet(
            title = "하자 정보 (#${d.defectIndex})",
            submitLabel = "수정 저장",
            showFinalize = true,
            initial = TagSubmission(
                type = d.defectType,
                severity = d.severity,
                trade = d.trade,
                surface = d.surface,
                areaDetail = d.areaDetail,
                note = d.note,
                finalize = d.status == com.axlife.pinset.data.entity.DefectStatus.DONE
            ),
            onDismiss = { editing = null },
            onSubmit = { sub ->
                val updated = d.copy(
                    defectType = sub.type,
                    severity = sub.severity,
                    trade = sub.trade,
                    surface = sub.surface,
                    areaDetail = sub.areaDetail,
                    note = sub.note,
                    status = if (sub.finalize)
                        com.axlife.pinset.data.entity.DefectStatus.DONE
                    else
                        com.axlife.pinset.data.entity.DefectStatus.PENDING
                )
                onUpdateDefect(updated)
                editing = null
            }
        )
    }
}

private fun tierLabel(t: Int) = when (t) {
    1 -> "화살표만"
    2 -> "간략 라벨"
    else -> "상세 라벨"
}
