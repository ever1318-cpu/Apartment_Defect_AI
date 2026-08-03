package com.axlife.pinset.ui.pinset

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The bundled apt_101_1502.png is a mobile UX mockup: its top band is a header
 * and its bottom third is a "current pin" card. This composable hides both by
 * clipping to a wide-ish aspect ratio and shifting the raw image up.
 *
 * Pin (x, y) normalized coordinates refer to the ORIGINAL source image, so pins
 * render inside the inner container which carries the same shift.
 *
 * The `content` lambda receives the inner container's Dp width and height so
 * callers can position pins using those units.
 */
@Composable
fun CroppedFloorplan(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    outerAspect: Float = 1f / 0.75f,   // width / height of the visible frame
    innerAspect: Float = 9f / 16f,     // width / height of the source image
    verticalShiftPct: Float = -0.26f,  // fraction of container width to shift up
    content: @Composable (innerWidth: Dp, innerHeight: Dp) -> Unit
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(outerAspect)
            .background(Color.White, RoundedCornerShape(12.dp))
    ) {
        val containerW = maxWidth
        val innerW = containerW
        val innerH = containerW / innerAspect
        val shift = containerW * verticalShiftPct
        Box(
            Modifier
                .offset(x = 0.dp, y = shift)
                .fillMaxWidth()
                .aspectRatio(innerAspect)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxWidth().aspectRatio(innerAspect)
            )
            content(innerW, innerH)
        }
    }
}
