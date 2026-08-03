package com.axlife.pinset.ui.camera

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.Session
import com.axlife.pinset.data.entity.Severity

/**
 * Live HUD overlay for the camera screen. Draws the session floorplan with:
 *   - green dot at the anchor (entrance)
 *   - severity-colored dots at every saved defect
 *   - yellow ringed dot at the current PDR-estimated position
 *
 * Tap-through — parent screen owns interaction.
 */
@Composable
fun MiniMapOverlay(
    bitmap: Bitmap?,
    session: Session?,
    defects: List<Defect>,
    liveXNorm: Float?,
    liveYNorm: Float?,
    headingDeg: Float = 0f,
    modifier: Modifier = Modifier
) {
    if (bitmap == null) return
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(aspect)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
        session?.let { s ->
            if (s.startXNorm != null && s.startYNorm != null) {
                Dot(s.startXNorm, s.startYNorm, Color(0xFF2E7D32), 10.dp)
            }
        }
        defects.forEach { d ->
            Dot(d.xNorm, d.yNorm, severityColor(d.severity), 8.dp)
        }
        if (liveXNorm != null && liveYNorm != null) {
            LiveRipple(xNorm = liveXNorm, yNorm = liveYNorm)
        }
        Surface(
            color = Color.White.copy(alpha = 0.88f),
            shape = CircleShape,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
        ) {
            Text(
                text = "N\n↑",
                color = Color(0xFFB71C1C),
                fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                fontSize = 9.sp,
                lineHeight = 9.sp,
                modifier = Modifier
                    .graphicsLayer(rotationZ = -headingDeg)
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Radar-style ripple marker for the operator's live PDR position: three
 * concentric rings expand and fade outward from a solid yellow dot at the
 * center. Repeats indefinitely so the marker is always animated even when
 * the user is standing still.
 */
@Composable
private fun BoxWithConstraintsScope.LiveRipple(
    xNorm: Float,
    yNorm: Float
) {
    val transition = rememberInfiniteTransition(label = "ripple")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple-phase"
    )
    // A generous hit-box centered on the point.
    val hitSize = 44.dp
    val hitOffX = (xNorm * maxWidth.value).dp - hitSize / 2
    val hitOffY = (yNorm * maxHeight.value).dp - hitSize / 2
    Canvas(
        modifier = Modifier
            .offset(x = hitOffX, y = hitOffY)
            .size(hitSize)
    ) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f
        // Three staggered rings — phase 0, 0.33, 0.66 offset.
        for (i in 0 until 3) {
            val local = (phase + i / 3f) % 1f
            val radius = maxRadius * local
            val alpha = (1f - local).coerceIn(0f, 1f) * 0.75f
            drawCircle(
                color = Color(0xFFFFEB3B).copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Stroke(width = 2.5f)
            )
        }
        // Solid core dot.
        drawCircle(
            color = Color(0xFFFFC107),
            radius = maxRadius * 0.16f,
            center = center
        )
        drawCircle(
            color = Color.White,
            radius = maxRadius * 0.16f,
            center = center,
            style = Stroke(width = 2f)
        )
    }
}

@Composable
private fun BoxWithConstraintsScope.Dot(
    xNorm: Float,
    yNorm: Float,
    color: Color,
    outer: Dp,
    withRing: Boolean = false
) {
    val offX = (xNorm * maxWidth.value).dp - outer / 2
    val offY = (yNorm * maxHeight.value).dp - outer / 2
    Box(
        modifier = Modifier
            .offset(x = offX, y = offY)
            .size(outer)
            .background(color, CircleShape)
    )
    if (withRing) {
        val ringSize = outer + 6.dp
        val ringOffX = (xNorm * maxWidth.value).dp - ringSize / 2
        val ringOffY = (yNorm * maxHeight.value).dp - ringSize / 2
        Box(
            modifier = Modifier
                .offset(x = ringOffX, y = ringOffY)
                .size(ringSize)
                .border(2.dp, Color.White, CircleShape)
        )
    }
}

private fun severityColor(s: Severity): Color = when (s) {
    Severity.MAJOR -> Color(0xFFD32F2F)
    Severity.NORMAL -> Color(0xFFF57F17)
    Severity.MINOR -> Color(0xFF90A4AE)
}
