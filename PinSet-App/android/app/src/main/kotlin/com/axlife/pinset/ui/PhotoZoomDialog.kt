package com.axlife.pinset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** Single-item interface consumed by the carousel dialog. */
data class CarouselItem(
    val filePath: String,
    val caption: String,
    val isPlaceholder: Boolean = false,
    /** Normalized Y coordinate of the detected ceiling/wall or wall/floor boundary. */
    val boundaryYNorm: Float? = null,
    val boundaryLabel: String = ""
)

/**
 * Fullscreen swipe-through gallery. Swipe left/right to change photos,
 * pinch to zoom (1..8x), drag to pan when zoomed, double-tap to reset,
 * tap the X to dismiss.
 */
@Composable
fun PhotoCarouselDialog(
    items: List<CarouselItem>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.lastIndex)) { items.size }
    val pagerScope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ZoomablePage(item = items[page])
            }
            if (items.size > 1 && pagerState.currentPage > 0) {
                IconButton(
                    onClick = { pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                    modifier = Modifier.align(Alignment.CenterStart).padding(8.dp)
                ) {
                    Surface(color = Color.Black.copy(alpha = 0.55f), shape = CircleShape) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "?? ??", tint = Color.White,
                            modifier = Modifier.padding(7.dp))
                    }
                }
            }
            if (items.size > 1 && pagerState.currentPage < items.lastIndex) {
                IconButton(
                    onClick = { pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp)
                ) {
                    Surface(color = Color.Black.copy(alpha = 0.55f), shape = CircleShape) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "?? ??", tint = Color.White,
                            modifier = Modifier.padding(7.dp))
                    }
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(20.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "닫기", tint = Color.White,
                        modifier = Modifier.padding(6.dp))
                }
            }
            // Caption + page indicator
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            append(items[pagerState.currentPage].caption)
                            items[pagerState.currentPage].boundaryYNorm?.let {
                                if (items[pagerState.currentPage].boundaryLabel.isNotBlank()) {
                                    append(" \u00b7 ").append(items[pagerState.currentPage].boundaryLabel).append(" \uc2e4\uc81c \uacbd\uacc4")
                                }
                            }
                        },
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items.indices.forEach { i ->
                            Box(
                                Modifier
                                    .size(if (i == pagerState.currentPage) 8.dp else 6.dp)
                                    .background(
                                        if (i == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.35f),
                                        CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePage(item: CarouselItem) {
    var scale by remember { mutableStateOf(1f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }

    val zoomModifier = if (scale > 1.01f) {
        Modifier.pointerInput(scale) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 8f)
                if (scale > 1.01f) { offX += pan.x; offY += pan.y }
                else { offX = 0f; offY = 0f }
            }
        }
    } else Modifier

    Box(
        Modifier
            .fillMaxSize()
            // Do not register a base-scale child gesture here. It previously
            // consumed horizontal drags before HorizontalPager could page.
            .then(zoomModifier),
        contentAlignment = Alignment.Center
    ) {
        if (item.isPlaceholder) {
            Surface(color = Color(0xFF263238), shape = RoundedCornerShape(12.dp)) {
                Text(
                    item.caption + "\n" + "사진 없음",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(horizontal = 34.dp, vertical = 24.dp)
                )
            }
        } else {
            AsyncImage(
                model = item.filePath,
                contentDescription = item.caption,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offX,
                        translationY = offY
                    )
            )
            BoundaryEvidenceLine(
                yNorm = item.boundaryYNorm,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offX,
                        translationY = offY
                    )
            )
        }
    }
}

@Composable
private fun BoundaryEvidenceLine(yNorm: Float?, modifier: Modifier = Modifier) {
    // A line is rendered only when the on-device image pass found a strong
    // horizontal edge. It is evidence for a ceiling-wall or wall-floor junction,
    // not a generic camera-angle guide.
    if (yNorm == null) return
    Canvas(modifier = modifier) {
        val y = size.height * yNorm.coerceIn(0.05f, 0.95f)
        val color = Color(0xFFFFB300)
        drawLine(color = color, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 5f)
        drawCircle(color = color, radius = 9f, center = Offset(size.width * 0.08f, y))
        drawCircle(color = color, radius = 9f, center = Offset(size.width * 0.92f, y))
    }
}
