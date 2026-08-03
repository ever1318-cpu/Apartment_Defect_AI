package com.axlife.pinset.ui.home

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectStatus
import com.axlife.pinset.data.entity.Session
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.ui.pinset.CroppedFloorplan
import com.axlife.pinset.ui.pinset.PlainFloorplan
import com.axlife.pinset.ui.drawAnchorMarker
import com.axlife.pinset.ui.theme.Anchor
import com.axlife.pinset.ui.theme.Danger
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.ui.theme.Warning

/**
 * Miniature floorplan overview shown at the bottom-right of the home summary
 * card. Renders the ACTUAL floorplan bitmap (bundled asset OR user-imported)
 * with pins overlaid — no more schematic vector fallback for the primary path.
 *
 * The parent decides the size; typical usage is Modifier.fillMaxWidth(0.5f).
 */
@Composable
fun HomeMiniFloorplan(
    session: Session?,
    bitmap: Bitmap?,
    isCustom: Boolean,
    defects: List<Defect>,
    liveXNorm: Float?,
    liveYNorm: Float?,
    modifier: Modifier = Modifier
) {
    if (bitmap == null) {
        SchematicFallback(session, defects, liveXNorm, liveYNorm, modifier)
        return
    }
    // The mini view lives inside a fixed-size container (parent decides).
    // Render the bitmap with ContentScale.Fit so the WHOLE floorplan is
    // always visible, then overlay pins at the same normalized coordinate
    // system used everywhere else. This guarantees every saved defect pin
    // shows up regardless of the parent's aspect ratio.
    //
    // The compass marker and total-count badge used to live inside this box.
    // Per v1.3 design: badge is gone, compass moves to the parent card
    // (outside/above the floorplan) — so this composable stays visually
    // clean and pin-focused.
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        PinsOverlay(session, defects, liveXNorm, liveYNorm)
    }
}

/**
 * Tiny compass rose showing which direction is north on the floorplan. By
 * convention the top of the image is north, so the arrow points straight up.
 * Rotating the marker later when we support user-imported orientations is
 * a one-line change (rotate the containing modifier).
 */
@Composable
fun NorthMarker(modifier: Modifier = Modifier) {
    // "N" label sits ABOVE the dial in its own row so the needle can point
    // straight up (heading 0°) without ever occluding the letter. Caller
    // controls total container size via [modifier]; the internal Canvas
    // fills whatever space remains after the label.
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "N",
            color = Danger,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 9.sp
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.85f), CircleShape)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.minDimension / 2f - 1f
                drawCircle(
                    color = PrimaryDark,
                    radius = r,
                    center = Offset(cx, cy),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)
                )
                val len = r * 0.7f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx, cy - len)
                    lineTo(cx - 3f, cy)
                    lineTo(cx + 3f, cy)
                    close()
                }
                drawPath(path, Danger)
                val south = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx, cy + len)
                    lineTo(cx - 3f, cy)
                    lineTo(cx + 3f, cy)
                    close()
                }
                drawPath(south, Color(0xFFBDBDBD))
            }
        }
    }
}

@Composable
private fun PinsOverlay(
    session: Session?,
    defects: List<Defect>,
    liveXNorm: Float?,
    liveYNorm: Float?
) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height

        // Entrance anchor (teal so it never gets confused with defects).
        if (session?.startXNorm != null && session.startYNorm != null) {
            val ax = session.startXNorm * w
            val ay = session.startYNorm * h
            drawAnchorMarker(center = Offset(ax, ay), radius = 11f, color = Anchor)
        }

        // Saved defect pins — punchy red dots designed to stand out even on
        // busy floorplan backgrounds. Layered: dark red outer ring for
        // contrast, bright red core, small white specular highlight. Done
        // defects drop alpha so pending ones catch the eye first.
        defects.forEach { d ->
            val cx = d.xNorm.coerceIn(0.02f, 0.98f) * w
            val cy = d.yNorm.coerceIn(0.02f, 0.98f) * h
            val alpha = if (d.status == DefectStatus.DONE) 0.5f else 1f
            // Dark outer halo (helps against light floorplan backgrounds).
            drawCircle(
                Color(0xFF7F0000).copy(alpha = alpha),
                radius = 8f,
                center = Offset(cx, cy)
            )
            // Bright red body.
            drawCircle(
                Color(0xFFFF1744).copy(alpha = alpha),
                radius = 6.5f,
                center = Offset(cx, cy)
            )
            // Small white specular highlight top-left for a 3D pop.
            drawCircle(
                Color.White.copy(alpha = alpha * 0.9f),
                radius = 1.6f,
                center = Offset(cx - 1.8f, cy - 1.8f)
            )
        }

        // Live PDR marker.
        if (liveXNorm != null && liveYNorm != null) {
            val lx = liveXNorm.coerceIn(0f, 1f) * w
            val ly = liveYNorm.coerceIn(0f, 1f) * h
            drawCircle(Danger.copy(alpha = 0.35f), radius = 7f, center = Offset(lx, ly))
            drawCircle(Danger, radius = 3f, center = Offset(lx, ly))
        }
    }
}

/**
 * Blinking-entrance schematic shown before we have a floorplan bitmap OR
 * before the session has an anchor. Draws a stroke-only outline plus a
 * pulsing dot at the entrance so operators know the app is waiting for the
 * anchor shot to complete.
 */
@Composable
private fun SchematicFallback(
    session: Session?,
    defects: List<Defect>,
    liveXNorm: Float?,
    liveYNorm: Float?,
    modifier: Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "anchor-blink")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "anchor-pulse"
    )
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.92f))
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val w = size.width; val h = size.height

            drawRect(
                color = PrimaryDark,
                topLeft = Offset(0f, 0f),
                size = size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
            )
            drawLine(PrimaryDark, Offset(0f, h * 0.48f), Offset(w * 0.55f, h * 0.48f), 1f)
            drawLine(PrimaryDark, Offset(w * 0.55f, 0f), Offset(w * 0.55f, h), 1f)
            drawLine(PrimaryDark, Offset(w * 0.55f, h * 0.6f), Offset(w, h * 0.6f), 1f)
            drawLine(PrimaryDark, Offset(w * 0.26f, h * 0.48f), Offset(w * 0.26f, h), 1f)

            // Entrance opening — pulses if the anchor hasn't been set yet.
            val hasAnchor = session?.startXNorm != null
            val ax = session?.startXNorm?.let { it * w } ?: (w * 0.15f)
            val ay = session?.startYNorm?.let { it * h } ?: (h * 0.92f)
            if (!hasAnchor) {
                drawCircle(Anchor.copy(alpha = pulse * 0.5f), radius = 10f, center = Offset(ax, ay))
            }
            if (hasAnchor) {
                drawAnchorMarker(center = Offset(ax, ay), radius = 11f, color = Anchor)
            } else {
                drawCircle(Color.White, radius = 5f, center = Offset(ax, ay))
                drawCircle(Anchor, radius = 3.5f, center = Offset(ax, ay))
            }

            defects.forEach { d ->
                val cx = d.xNorm.coerceIn(0f, 1f) * w
                val cy = d.yNorm.coerceIn(0f, 1f) * h
                val alpha = if (d.status == DefectStatus.DONE) 0.5f else 1f
                drawCircle(Color(0xFF7F0000).copy(alpha = alpha), radius = 8f, center = Offset(cx, cy))
                drawCircle(Color(0xFFFF1744).copy(alpha = alpha), radius = 6.5f, center = Offset(cx, cy))
                drawCircle(Color.White.copy(alpha = alpha * 0.9f), radius = 1.6f,
                    center = Offset(cx - 1.8f, cy - 1.8f))
            }
            if (liveXNorm != null && liveYNorm != null) {
                val lx = liveXNorm.coerceIn(0f, 1f) * w
                val ly = liveYNorm.coerceIn(0f, 1f) * h
                drawCircle(Danger.copy(alpha = 0.35f), radius = 6f, center = Offset(lx, ly))
                drawCircle(Danger, radius = 2.4f, center = Offset(lx, ly))
            }
        }
    }
}
