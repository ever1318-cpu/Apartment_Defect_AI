package com.axlife.pinset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small compass overlay. The needle rotates opposite to `headingDeg` so the
 * red tip always points to true north. N/E/S/W labels stay upright thanks
 * to the fixed outer ring.
 *
 * Usage: place in a top-right corner or next to a sensor readout.
 */
@Composable
fun CompassIcon(
    headingDeg: Float,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    onDark: Boolean = true
) {
    val bgColor = if (onDark) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.85f)
    val ringColor = if (onDark) Color.White else Color(0xFF37474F)
    val northColor = Color(0xFFE53935)   // red north
    val southColor = if (onDark) Color.White else Color(0xFF37474F)
    val labelColor = if (onDark) Color.White else Color(0xFF37474F)

    // Two-part layout: an "N" label ABOVE the dial (not inside it) so the
    // needle can point straight up without ever occluding the label.
    // Total height = size + label height.
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "N",
            color = northColor,
            fontSize = (size.value * 0.32f).sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = (size.value * 0.32f).sp
        )
        Spacer(Modifier.height(1.dp))
        Box(
            modifier = Modifier
                .size(size)
                .background(bgColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(size * 0.85f)) {
                val w = this.size.width
                val h = this.size.height
                val cx = w / 2f
                val cy = h / 2f
                drawCircle(
                    color = ringColor.copy(alpha = 0.4f),
                    radius = kotlin.math.min(w, h) / 2f,
                    style = Stroke(width = 1.5f)
                )
                rotate(degrees = -headingDeg, pivot = Offset(cx, cy)) {
                    val needleLen = kotlin.math.min(w, h) / 2f - 3f
                    val halfWidth = needleLen * 0.22f
                    val northPath = Path().apply {
                        moveTo(cx, cy - needleLen)
                        lineTo(cx - halfWidth, cy)
                        lineTo(cx + halfWidth, cy)
                        close()
                    }
                    val southPath = Path().apply {
                        moveTo(cx, cy + needleLen)
                        lineTo(cx - halfWidth, cy)
                        lineTo(cx + halfWidth, cy)
                        close()
                    }
                    drawPath(southPath, color = southColor.copy(alpha = 0.85f))
                    drawPath(northPath, color = northColor)
                }
                drawCircle(color = ringColor, radius = 2.5f, center = Offset(cx, cy))
            }
        }
    }
}
