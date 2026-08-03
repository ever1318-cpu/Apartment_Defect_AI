package com.axlife.pinset.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Draws a compact anchor symbol for the one-time inspection origin.
 * Defect pins stay red; the anchor stays teal so the two cannot be confused.
 */
fun DrawScope.drawAnchorMarker(
    center: Offset,
    radius: Float = 13f,
    color: Color = Color(0xFF087F8C)
) {
    // Two high-contrast beacon rings ensure the anchor is visibly different
    // from red defect pins even on a dense floorplan thumbnail.
    drawCircle(color.copy(alpha = 0.14f), radius = radius + 8f, center = center)
    drawCircle(
        color = color.copy(alpha = 0.88f),
        radius = radius + 5f,
        center = center,
        style = Stroke(width = (radius * 0.12f).coerceAtLeast(1.8f))
    )
    drawCircle(Color.White.copy(alpha = 0.96f), radius = radius + 3f, center = center)
    drawCircle(color.copy(alpha = 0.18f), radius = radius + 1f, center = center)

    val top = center.y - radius * 0.68f
    val bottom = center.y + radius * 0.62f
    drawCircle(
        color = color,
        radius = radius * 0.22f,
        center = Offset(center.x, top),
        style = Stroke(width = (radius * 0.14f).coerceAtLeast(1.5f))
    )
    drawLine(
        color = color,
        start = Offset(center.x, top + radius * 0.22f),
        end = Offset(center.x, bottom),
        strokeWidth = radius * 0.16f,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(center.x - radius * 0.42f, center.y - radius * 0.12f),
        end = Offset(center.x + radius * 0.42f, center.y - radius * 0.12f),
        strokeWidth = radius * 0.14f,
        cap = StrokeCap.Round
    )
    val arms = Path().apply {
        moveTo(center.x - radius * 0.78f, center.y + radius * 0.18f)
        cubicTo(
            center.x - radius * 0.64f, bottom,
            center.x - radius * 0.28f, center.y + radius * 0.78f,
            center.x, bottom
        )
        cubicTo(
            center.x + radius * 0.28f, center.y + radius * 0.78f,
            center.x + radius * 0.64f, bottom,
            center.x + radius * 0.78f, center.y + radius * 0.18f
        )
    }
    drawPath(
        path = arms,
        color = color,
        style = Stroke(width = radius * 0.16f, cap = StrokeCap.Round)
    )
}
