package com.axlife.pinset.ui.pinset

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.data.entity.Surface

/**
 * Thin solid arrow pin. Color = severity. Rotation = the compass heading at
 * capture time so the arrow tip points where the phone was aimed. The
 * floorplan's top edge is treated as magnetic north, so a heading of 0°
 * (north) rotates the arrow to point straight up on screen.
 *
 * The bare SVG points to the right (+x, i.e. east). To face north the arrow
 * must rotate -90°; from there we add the heading. In Compose, positive
 * rotation is clockwise which matches how a compass heading grows.
 *
 * Anchored so its center sits on (xNorm, yNorm) of the parent container.
 */
@Composable
fun ArrowPin(
    xNorm: Float,
    yNorm: Float,
    parentWidthDp: Dp,
    parentHeightDp: Dp,
    index: Int,
    severity: Severity,
    /** Compass heading in degrees (0 = north / floorplan top). */
    headingDeg: Float,
    /** Kept for backwards compatibility with earlier callers; not used. */
    surface: Surface = Surface.WALL,
    clusterCount: Int = 1,
    current: Boolean = false,
    dragging: Boolean = false,
    size: Dp = 44.dp,
    onClick: () -> Unit = {}
) {
    val rotation = headingDeg - 90f
    val (fill, stroke) = severityColors(severity)

    val scale = if (current || dragging) {
        val t = rememberInfiniteTransition(label = "arrow-pulse")
        val v by t.animateFloat(
            initialValue = 1f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (dragging) 350 else 700),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        v
    } else 1f

    val xPx = xNorm * parentWidthDp.value
    val yPx = yNorm * parentHeightDp.value
    val half = size.value / 2f

    Box(
        modifier = Modifier
            .offset(x = (xPx - half).dp, y = (yPx - half).dp)
            .size(size)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(size)
                .scale(scale)
                .rotate(rotation)
        ) {
            val w = this.size.width
            val h = this.size.height
            val sx = w / 100f
            val sy = h / 100f
            fun p(x: Float, y: Float) = Offset(x * sx, y * sy)

            val path = Path().apply {
                moveTo(p(15f, 44f).x, p(15f, 44f).y)
                lineTo(p(62f, 44f).x, p(62f, 44f).y)
                lineTo(p(62f, 30f).x, p(62f, 30f).y)
                lineTo(p(88f, 50f).x, p(88f, 50f).y)
                lineTo(p(62f, 70f).x, p(62f, 70f).y)
                lineTo(p(62f, 56f).x, p(62f, 56f).y)
                lineTo(p(15f, 56f).x, p(15f, 56f).y)
                close()
            }
            drawPath(path = path, color = fill)
            drawPath(
                path = path,
                color = stroke,
                style = Stroke(width = 2.5f * sx)
            )
        }
        if (index > 0) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-6).dp, y = (-6).dp)
                    .size(20.dp)
                    .background(stroke, CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(index.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }
        if (clusterCount > 1) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 6.dp, y = 4.dp)
                    .background(Color(0xFF263238), RoundedCornerShape(10.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(10.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "×$clusterCount",
                    color = Color(0xFFFFEB3B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }
    }
}

private fun severityColors(s: Severity): Pair<Color, Color> {
    // Unified punchy red for every arrow pin so operators can spot defect
    // locations at a glance regardless of severity. Severity is still
    // recorded on the defect (and shown in the list rows), just no longer
    // encoded in the arrow colour.
    val fill = Color(0xFFFF1744)
    val stroke = Color(0xFF7F0000)
    return fill to stroke
}
