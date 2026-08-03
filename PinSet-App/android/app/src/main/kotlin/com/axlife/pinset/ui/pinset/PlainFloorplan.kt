package com.axlife.pinset.ui.pinset

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders a user-imported floorplan image as-is (no crop, no shift). The bitmap
 * is drawn to fit inside a rounded container whose aspect ratio matches the
 * source image, so pin coordinates (xNorm, yNorm) map 1:1 to what the user sees.
 */
@Composable
fun PlainFloorplan(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    content: @Composable (innerWidth: Dp, innerHeight: Dp) -> Unit
) {
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(Color.White, RoundedCornerShape(12.dp))
    ) {
        val innerW = maxWidth
        val innerH = maxHeight
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
        content(innerW, innerH)
    }
}
