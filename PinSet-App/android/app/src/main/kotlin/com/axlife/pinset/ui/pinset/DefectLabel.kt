package com.axlife.pinset.ui.pinset

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectType
import com.axlife.pinset.data.entity.Trade

/**
 * Rendered next to an ArrowPin on the floorplan. The amount of information
 * shown scales with the caller-provided zoom tier:
 *
 *   tier = 1  →  hidden (arrow only)
 *   tier = 2  →  "#3 · 균열"        (compact)
 *   tier = 3  →  full multi-line label with:
 *                #index · type
 *                room · areaDetail
 *                trade · surface
 *                heading (nesw)
 *                note (if any)
 *
 * `xNorm/yNorm` position the label just below-right of the pin. The parent
 * BoxWithConstraints supplies `parentWidthDp/parentHeightDp` for the math.
 */
@Composable
fun DefectLabel(
    defect: Defect,
    tier: Int,
    parentWidthDp: Dp,
    parentHeightDp: Dp
) {
    if (tier <= 1) return
    val meta = tradeShort(defect.trade)
    val typeKo = typeShort(defect.defectType)
    val compass = compass8(defect.imuHeadingDeg)

    val offsetX = (defect.xNorm * parentWidthDp.value + 14).dp
    val offsetY = (defect.yNorm * parentHeightDp.value + 4).dp

    Box(
        Modifier
            .offset(x = offsetX, y = offsetY)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        if (tier == 2) {
            Text(
                "#${defect.defectIndex} · $typeKo",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        } else {
            Column {
                Text(
                    "#${defect.defectIndex} · $typeKo",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${defect.roomLabel}${if (defect.areaDetail.isNotBlank()) " · ${defect.areaDetail}" else ""}",
                    color = Color(0xFFFFEB3B),
                    fontSize = 8.sp
                )
                Text(
                    "$meta · ${surfaceShort(defect.surface)} · ${defect.imuHeadingDeg.toInt()}°$compass",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 8.sp
                )
                if (defect.note.isNotBlank()) {
                    Text(
                        defect.note.take(30) + if (defect.note.length > 30) "…" else "",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 7.sp
                    )
                }
            }
        }
    }
}

private fun typeShort(t: DefectType) = when (t) {
    DefectType.CRACK -> "균열"; DefectType.LEAK -> "누수"
    DefectType.FINISH -> "마감"; DefectType.OTHER -> "기타"
}
private fun tradeShort(t: Trade) = when (t) {
    Trade.WALL -> "벽체"; Trade.WALLPAPER -> "도배"; Trade.TILE -> "타일"
    Trade.FLOOR -> "바닥"; Trade.WINDOW -> "창호"; Trade.ELECTRIC -> "전기"
    Trade.PLUMBING -> "배관"; Trade.OTHER -> "기타"
}
private fun surfaceShort(s: com.axlife.pinset.data.entity.Surface) = when (s) {
    com.axlife.pinset.data.entity.Surface.CEILING -> "천장"
    com.axlife.pinset.data.entity.Surface.WALL -> "벽"
    com.axlife.pinset.data.entity.Surface.FLOOR -> "바닥"
}
private fun compass8(deg: Float): String {
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
