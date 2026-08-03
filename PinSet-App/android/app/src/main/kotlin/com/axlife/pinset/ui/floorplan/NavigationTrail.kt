package com.axlife.pinset.ui.floorplan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.Session
import com.axlife.pinset.ui.drawAnchorMarker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the user's inspection path over the floorplan.
 *
 *   ★ entrance anchor          (session.startXNorm/Y)
 *   ● capture waypoint         (each defect's xNorm/yNorm, in createdAt order)
 *   ─ dashed line              (visit order 1 → 2 → 3 …)
 *   → short arrow at each dot  (camera heading recorded at capture time)
 *
 * All coordinates are in normalized floorplan space (0..1) — the parent
 * container tells us its Dp size so we scale to pixels here.
 */
@Composable
fun NavigationTrail(
    session: Session?,
    defects: List<Defect>,
    parentWidthDp: Dp,
    parentHeightDp: Dp,
    modifier: Modifier = Modifier
) {
    if (defects.isEmpty() && session?.startXNorm == null) return

    val sorted = defects.sortedBy { it.createdAt }
    val trailColor = Color(0xFF1565C0)              // primary blue
    val arrowColor = Color(0xFFE91E63)              // secondary pink
    val entranceColor = Color(0xFF2E7D32)           // success green

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        fun px(xNorm: Float, yNorm: Float) =
            androidx.compose.ui.geometry.Offset(xNorm * w, yNorm * h)

        // Waypoints — including the entrance if we have one.
        val points = buildList {
            session?.let { s ->
                if (s.startXNorm != null && s.startYNorm != null) {
                    add(px(s.startXNorm, s.startYNorm))
                }
            }
            sorted.forEach { add(px(it.xNorm, it.yNorm)) }
        }

        // Dashed connecting path.
        if (points.size >= 2) {
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            }
            drawPath(
                path = path,
                color = trailColor.copy(alpha = 0.6f),
                style = Stroke(
                    width = 4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                )
            )
        }

        // Entrance star.
        session?.let { s ->
            if (s.startXNorm != null && s.startYNorm != null) {
                val e = px(s.startXNorm, s.startYNorm)
                drawAnchorMarker(
                    center = e,
                    radius = 12.dp.toPx(),
                    color = entranceColor
                )
            }
        }

        // Each defect: heading arrow + dot.
        sorted.forEach { d ->
            val c = px(d.xNorm, d.yNorm)
            drawCircle(color = trailColor, radius = 5.dp.toPx(), center = c)
            drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = c)
            // Arrow — length 16dp in the heading direction.
            val headingRad = d.imuHeadingDeg.toDouble() * PI / 180.0
            // Screen: heading 0° (north) points UP → negative y.
            val dx = sin(headingRad).toFloat() * 20.dp.toPx()
            val dy = -cos(headingRad).toFloat() * 20.dp.toPx()
            val tip = androidx.compose.ui.geometry.Offset(c.x + dx, c.y + dy)
            drawLine(
                color = arrowColor,
                start = c, end = tip,
                strokeWidth = 3.dp.toPx()
            )
            // Small triangular head at tip
            val backX = c.x + dx * 0.7f
            val backY = c.y + dy * 0.7f
            val perpX = -dy * 0.35f
            val perpY = dx * 0.35f
            val head = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(backX + perpX, backY + perpY)
                lineTo(backX - perpX, backY - perpY)
                close()
            }
            drawPath(head, color = arrowColor)
        }
    }
}

@Composable
fun FloorplanAnchorOverlay(
    session: Session?,
    modifier: Modifier = Modifier
) {
    val x = session?.startXNorm ?: return
    val y = session.startYNorm ?: return
    Canvas(modifier.fillMaxSize()) {
        drawAnchorMarker(
            center = androidx.compose.ui.geometry.Offset(x * size.width, y * size.height),
            radius = 12.dp.toPx(),
            color = Color(0xFF2E7D32)
        )
    }
}
