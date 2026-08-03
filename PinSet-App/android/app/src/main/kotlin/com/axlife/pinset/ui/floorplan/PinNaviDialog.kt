package com.axlife.pinset.ui.floorplan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectType
import com.axlife.pinset.data.entity.Session
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.data.entity.Surface
import com.axlife.pinset.data.entity.Trade
import com.axlife.pinset.ui.theme.Danger
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.ui.theme.Success
import com.axlife.pinset.ui.theme.Warning
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Dialog shown when the user taps an arrow pin. It summarises where this
 * defect sits on the floorplan relative to the session's entrance anchor —
 * a small "navigation card" so the reader knows where to walk to find it.
 *
 *   방  · 표면
 *   #번호 · 유형(심각도) · 공종
 *   현관에서 X방향 Y m
 *   촬영 당시 기울기 · 방향
 */
@Composable
fun PinNaviDialog(
    defect: Defect,
    session: Session?,
    onDismiss: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val bearing = bearingFromEntrance(defect, session)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    "#${defect.defectIndex} · ${koType(defect.defectType)}",
                    fontWeight = FontWeight.Bold, fontSize = 18.sp
                )
                Row {
                    Chip(koSeverity(defect.severity), sevColor(defect.severity))
                    Spacer(Modifier.width(4.dp))
                    Chip(koTrade(defect.trade), PrimaryDark)
                    Spacer(Modifier.width(4.dp))
                    Chip(koSurface(defect.surface), Color(0xFF6A1B9A))
                }
            }
        },
        text = {
            Column {
                InfoRow("위치", defect.roomLabel + if (defect.areaDetail.isNotBlank()) " · ${defect.areaDetail}" else "")
                if (bearing != null) {
                    InfoRow("현관에서", "${bearing.compass}방향 · 약 ${String.format("%.1fm", bearing.metersEstimate)}")
                }
                InfoRow("촬영 방향", "${defect.imuHeadingDeg.toInt()}° ${compass(defect.imuHeadingDeg)}")
                InfoRow("촬영 기울기", "${defect.imuPitchDeg.toInt()}°")
                defect.focusDistanceM?.let {
                    InfoRow("피사체 거리", String.format("%.1fm", it))
                }
                if (defect.arWorldX != null && defect.arWorldZ != null) {
                    val d = hypot(defect.arWorldX.toDouble(), defect.arWorldZ.toDouble()).toFloat()
                    InfoRow("앵커거리(AR)", String.format("%.1fm", d))
                }
                if (defect.note.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("메모: ${defect.note}", fontSize = 12.sp, color = Color.Gray)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenDetail,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
            ) { Text("상세 보기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

private data class Bearing(val compass: String, val metersEstimate: Float)

/**
 * Rough bearing from the session's entrance anchor to the defect, converting
 * normalized coordinates to meters using a typical 12x9 m apartment scale.
 */
private fun bearingFromEntrance(defect: Defect, session: Session?): Bearing? {
    val ex = session?.startXNorm ?: return null
    val ey = session.startYNorm ?: return null
    val dx = defect.xNorm - ex
    val dy = defect.yNorm - ey
    if (kotlin.math.abs(dx) < 0.005f && kotlin.math.abs(dy) < 0.005f) return null
    // Screen-space bearing: +x right, +y down. We report in compass terms
    // relative to "up = away from entrance towards inside".
    val angleDeg = (atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / PI).toFloat()
    val meters = hypot(dx.toDouble() * 12.0, dy.toDouble() * 9.0).toFloat()
    return Bearing(compass(angleDeg), meters)
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

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(80.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(10.dp)) {
        Text(
            text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
        )
    }
}

private fun koType(t: DefectType) = when (t) {
    DefectType.CRACK -> "균열"; DefectType.LEAK -> "누수"
    DefectType.FINISH -> "마감 불량"; DefectType.OTHER -> "기타"
}
private fun koSeverity(s: Severity) = when (s) {
    Severity.MAJOR -> "중대"; Severity.NORMAL -> "보통"; Severity.MINOR -> "경미"
}
private fun koTrade(t: Trade) = when (t) {
    Trade.WALL -> "벽체"; Trade.WALLPAPER -> "도배"; Trade.TILE -> "타일"
    Trade.FLOOR -> "바닥"; Trade.WINDOW -> "창호"; Trade.ELECTRIC -> "전기"
    Trade.PLUMBING -> "배관"; Trade.OTHER -> "기타"
}
private fun koSurface(s: Surface) = when (s) {
    Surface.CEILING -> "천장"; Surface.WALL -> "벽"; Surface.FLOOR -> "바닥"
}
private fun sevColor(s: Severity) = when (s) {
    Severity.MAJOR -> Danger; Severity.NORMAL -> Warning; Severity.MINOR -> Success
}
