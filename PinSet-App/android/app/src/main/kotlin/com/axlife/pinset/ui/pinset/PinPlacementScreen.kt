package com.axlife.pinset.ui.pinset

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.axlife.pinset.camera.CapturedShot
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.data.entity.SlotRole
import com.axlife.pinset.data.entity.Surface
import com.axlife.pinset.ui.PhotoCarouselDialog
import com.axlife.pinset.ui.CompassIcon
import com.axlife.pinset.ui.InspectionStepBar
import com.axlife.pinset.ui.drawAnchorMarker
import com.axlife.pinset.ui.Routes
import com.axlife.pinset.ui.theme.AutoPinRed
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.ui.theme.Secondary
import com.axlife.pinset.ui.theme.Success
import com.axlife.pinset.ui.theme.Warning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinPlacementScreen(nav: NavController) {
    val vm: PinPlacementViewModel = viewModel(factory = PinPlacementViewModel.Factory)
    val state by vm.state.collectAsState()

    var showTagSheet by remember { mutableStateOf(false) }
    var autoOpenedOpinion by remember { mutableStateOf(false) }
    var pendingPinMove by remember { mutableStateOf<PinPos?>(null) }
    var carouselIndex by remember { mutableStateOf<Int?>(null) }
    var savedNotice by remember { mutableStateOf<String?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(state.matching, state.capture) {
        if (!state.matching && state.capture != null && !autoOpenedOpinion) {
            autoOpenedOpinion = true
            showTagSheet = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHost) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            InspectionStepBar(
                currentStep = 1,
                onStepSelected = { step ->
                    when (step) {
                        0 -> nav.navigate(Routes.HOME) { launchSingleTop = true }
                        1 -> nav.navigate(Routes.CAMERA) { launchSingleTop = true }
                        2 -> showTagSheet = true
                    }
                }
            )
            if (state.matching) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("위치 추정 중…")
                    }
                }
                return@Column
            }
            if (!showTagSheet) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("하자의견 입력창을 준비하고 있습니다.", color = PrimaryDark)
                }
                return@Column
            }
            state.error?.let { Text(it, color = Color.Red, modifier = Modifier.padding(16.dp)) }

            state.capture?.pose?.let { pose ->
                ImuInfoBar(
                    pitchDeg = pose.imuPitchDeg,
                    headingDeg = pose.imuHeadingDeg,
                    arX = pose.arWorldX,
                    arY = pose.arWorldY,
                    arZ = pose.arWorldZ,
                    focusDistanceM = pose.focusDistanceM
                )
            }

            SlotGallery(
                shots = state.capture?.shots.orEmpty(),
                onTap = { idx -> carouselIndex = idx },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            if (
                state.aiConfidence in 0f..0.54f ||
                state.aiPathText.isBlank() ||
                state.aiPathText.startsWith("❌")
            ) {
                Surface(
                    color = Color(0xFFFFF3E0),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        "AI 판별이 불명확할 수 있습니다. 점검자가 이미지를 확인하고 필요하면 ‘재촬영’을 선택하세요.",
                        color = Color(0xFF8A4B00),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            RoomChip(state.roomLabel, state.confidence, state.alternatives, vm::selectRoom)
            RoomZoomPanel(state, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp))
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                FloorplanWithPin(
                    state = state,
                    onPinMoveRequest = { x, y -> pendingPinMove = PinPos(x, y) },
                    onPinTap = { showTagSheet = true },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            LocationAiRecommendation(
                roomLabel = state.roomLabel,
                surface = state.autoSurface,
                surfaceBandLabel = state.autoSurfaceBand.label,
                positionDraft = state.positionDraft,
                clockwiseRoute = state.clockwiseRoute,
                aiPathText = state.aiPathText,
                aiConfidence = state.aiConfidence
            )
            // Bottom breathing room so the sticky button never overlaps the
            // last content row when the user scrolls to the end.
            Spacer(Modifier.height(24.dp))
        }
    }

    savedNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("하자 저장 완료") },
            text = { Text(notice) },
            confirmButton = {
                Button(onClick = {
                    savedNotice = null
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                }) { Text("확인") }
            }
        )
    }

    pendingPinMove?.let { requested ->
        AlertDialog(
            onDismissRequest = { pendingPinMove = null },
            title = { Text("위치 수정?") },
            text = { Text("자동으로 추정된 하자 핀을 선택한 위치로 이동하시겠습니까?") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.movePin(requested.x, requested.y)
                        pendingPinMove = null
                    }
                ) { Text("위치 수정") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingPinMove = null }) { Text("취소") }
            }
        )
    }

    if (showTagSheet) {
        PinTagSheet(
            initial = TagSubmission(
                type = com.axlife.pinset.data.entity.DefectType.OTHER,
                severity = com.axlife.pinset.data.entity.Severity.NORMAL,
                trade = com.axlife.pinset.data.entity.Trade.OTHER,
                surface = state.autoSurface,
                // The first server taxonomy candidate is preselected, while
                // remaining choices stay available in the detailed-part menu.
                areaDetail = state.suggestedDetails.firstOrNull().orEmpty(),
                note = "",
                // The image model's first-pass recommendation is carried
                // forward as the editable initial opinion. The exact text
                // finally submitted is retained separately as raw input in
                // Defect.residentOpinion; AI/final text never overwrites it.
                residentOpinion = "",
                aiPathText = state.aiPathText,
                aiConfidence = state.aiConfidence,
                finalPathText = ""   // start empty; user picks or types
            ),
            onDismiss = { showTagSheet = false },
            aiTextSuggest = { opinion ->
                if (opinion.isBlank()) null
                else vm.classifyOpinion(opinion)?.let {
                    com.axlife.pinset.ui.pinset.AiSuggestionUi(it.pathText, it.confidence, it.rationale)
                }
            },
            photoAiPath = state.aiPathText,
            photoAiConfidence = state.aiConfidence,
            suggestedDetailOptions = state.suggestedDetails,
            hierarchySuggestion = state.positionDraft,
            surfaceBandLabel = state.autoSurfaceBand.label,
            suggestedTradeLabel = state.suggestedTradeLabel,
            boundaryYNorm = state.surfaceBoundaryYNorm,
            boundaryLabel = state.autoSurfaceBand.label,
            aiHierarchySuggestion = state.aiPathText,
            precisionMeasurement = state.capture?.precisionMeasurement == true,
            referenceMarkerCaptured = state.capture?.referenceMarkerCaptured == true,
            defectPhotoPath = state.capture?.forSlot(com.axlife.pinset.data.entity.SlotRole.A)?.filePath
                ?: state.capture?.primary?.filePath.orEmpty(),
            widePhotoPath = state.capture?.forSlot(com.axlife.pinset.data.entity.SlotRole.B)?.filePath.orEmpty(),
            onStepSelected = { step ->
                when (step) {
                    0 -> {
                        showTagSheet = false
                        nav.navigate(Routes.HOME) { launchSingleTop = true }
                    }
                    1 -> {
                        showTagSheet = false
                        nav.navigate(Routes.CAMERA) { launchSingleTop = true }
                    }
                }
            },
            onSubmit = { sub ->
                showTagSheet = false
                vm.save(sub) { _, summary, deliveryNotice ->
                    savedNotice = "$summary\n\n$deliveryNotice"
                }
            }
        )
    }

    val shots = state.capture?.shots
    val idx = carouselIndex
    if (shots != null && idx != null) {
        PhotoCarouselDialog(
            items = shots.map { it.toCarouselItem() },
            initialIndex = idx,
            onDismiss = { carouselIndex = null }
        )
    }
}

private fun CapturedShot.toCarouselItem() = com.axlife.pinset.ui.CarouselItem(
    filePath = filePath,
    caption = when (slot) {
        SlotRole.A -> "기본"
        SlotRole.B -> "중간"
        SlotRole.C -> "광역"
    } + " · ${formatX(requestedZoom)}x" + if (isDigital) " (디지털)" else ""
)

@Composable
private fun SlotGallery(shots: List<CapturedShot>, onTap: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (shots.isEmpty()) return
    val ordered = listOf(SlotRole.A, SlotRole.B, SlotRole.C).mapNotNull { role ->
        shots.firstOrNull { it.slot == role }?.let { it to shots.indexOf(it) }
    }
    // Keep capture evidence compact: horizontal thumbnails replace the old
    // three full-width 4:3 tiles that forced excessive vertical scrolling.
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(ordered) { _, pair ->
            val (shot, idx) = pair
            SlotTile(
                shot = shot,
                onTap = { onTap(idx) },
                modifier = Modifier.width(150.dp)
            )
        }
    }
}

@Composable
private fun SlotTile(shot: CapturedShot, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val (label, tint) = when (shot.slot) {
        SlotRole.A -> "기본" to PrimaryDark
        SlotRole.B -> "중간" to Secondary
        SlotRole.C -> "광역" to Warning
    }
    val scale = if (shot.isDigital) ContentScale.Fit else ContentScale.Crop
    Box(
        modifier
            .aspectRatio(4f / 3f)
            .background(Color.Black, RoundedCornerShape(12.dp))
            .clickable { onTap() }
    ) {
        AsyncImage(
            model = shot.filePath,
            contentDescription = label,
            contentScale = scale,
            modifier = Modifier.fillMaxSize()
        )
        Surface(color = tint, shape = RoundedCornerShape(bottomEnd = 12.dp, topStart = 12.dp)) {
            Text(
                "$label · ${formatX(shot.requestedZoom)}x" + if (shot.isDigital) " *" else "",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun LocationAiRecommendation(
    roomLabel: String?,
    surface: Surface,
    surfaceBandLabel: String,
    positionDraft: String,
    clockwiseRoute: List<String>,
    aiPathText: String,
    aiConfidence: Float
) {
    val surfaceLabel = when (surface) {
        Surface.CEILING -> "천장"
        Surface.WALL -> "벽"
        Surface.FLOOR -> "바닥"
    }
    Surface(
        color = Color(0xFFF3E5F5),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "추천 부위  ${roomLabel ?: "위치 미지정"} · $surfaceLabel",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = PrimaryDark,
                    modifier = Modifier.weight(1f)
                )
                if (aiConfidence > 0f) {
                    Text(
                        "AI ${(aiConfidence * 100).toInt()}%",
                        color = Color(0xFF7B1FA2),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                aiPathText.ifBlank { "이미지 분석 의견을 준비하고 있습니다." },
                color = Color(0xFF6A1B9A),
                fontSize = 11.sp,
                maxLines = 2
            )
        }
    }
}

private fun buildInitialOpinion(
    roomLabel: String?,
    surface: Surface,
    aiPathText: String
): String {
    val surfaceLabel = when (surface) {
        Surface.CEILING -> "천장"
        Surface.WALL -> "벽"
        Surface.FLOOR -> "바닥"
    }
    val location = "${roomLabel ?: "위치 미지정"} $surfaceLabel"
    val recommendation = aiPathText
        .takeUnless { it.isBlank() || it.startsWith("AI 분석 중") || it.startsWith("❌") }
    return recommendation?.let { "${location}에서 촬영된 하자입니다. AI 이미지 분석 후보: $it" }
        ?: "${location}에서 촬영된 하자입니다."
}

internal fun formatX(v: Float): String {
    val i = v.toInt()
    return if (kotlin.math.abs(v - i) < 0.05f) "${i}" else String.format("%.1f", v)
}

@Composable
private fun ImuInfoBar(
    pitchDeg: Float,
    headingDeg: Float,
    arX: Float?,
    arY: Float?,
    arZ: Float?,
    focusDistanceM: Float?
) {
    val surface = when {
        pitchDeg > 30f  -> "천장"
        pitchDeg < -30f -> "바닥"
        else            -> "벽"
    }
    val compass = compassLabel(headingDeg)
    // Prefer LENS_FOCUS_DISTANCE (subject-to-lens) — the AR anchor distance
    // is different information (start-point-to-camera) and only shows up if
    // both AR and anchor were available.
    val distance = when {
        focusDistanceM != null -> String.format("%.1fm", focusDistanceM)
        arX != null && arZ != null -> {
            val d = kotlin.math.sqrt((arX * arX + arZ * arZ).toDouble()).toFloat()
            String.format("앵커 %.1fm", d)
        }
        else -> "—"
    }
    Surface(
        color = Color(0xFF5F6F82),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InfoCell("표면", surface)
            InfoDivider()
            InfoCell("기울기", "${pitchDeg.toInt()}°")
            InfoDivider()
            InfoCell(
                "방향",
                "${koreanDirectionLabel(headingDeg)}$compass${normalizedHeading(headingDeg)}"
            )
            InfoDivider()
            InfoCell("거리", distance)
        }
    }
}

@Composable
private fun InfoCell(label: String, value: String) {
    Column(Modifier.padding(horizontal = 6.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun InfoDivider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(28.dp)
            .background(Color.White.copy(alpha = 0.25f))
    )
}

private fun compassLabel(deg: Float): String {
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

private fun koreanDirectionLabel(deg: Float): String {
    val d = ((deg % 360f) + 360f) % 360f
    val labels = arrayOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
    return labels[((d + 22.5f) / 45f).toInt() % 8]
}

private fun normalizedHeading(deg: Float): Int =
    (((deg % 360f) + 360f) % 360f).toInt()

@Composable
private fun RoomChip(
    roomLabel: String?,
    confidence: Float,
    alternatives: List<Pair<String, Float>>,
    onPickRoom: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.LocationOn, null, tint = if (roomLabel != null) AutoPinRed else Color.Gray)
        Spacer(Modifier.width(8.dp))
        Text(
            if (roomLabel != null) "$roomLabel · 위치 추정 ${(confidence * 100).toInt()}%" else "방을 선택하세요",
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        alternatives.take(3).forEach { (label, score) ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (label == roomLabel) AutoPinRed else Color(0xFFECEFF1),
                modifier = Modifier.padding(start = 6.dp).clickable { onPickRoom(label) }
            ) {
                Text(
                    "$label ${(score * 100).toInt()}%",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    color = if (label == roomLabel) Color.White else Color.DarkGray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun RoomZoomPanel(state: PinPlacementState, modifier: Modifier = Modifier) {
    val bitmap = state.floorplanBitmap ?: return
    val room = state.floorplan?.rooms?.firstOrNull { it.id == state.roomId } ?: return
    val b = room.bbox.takeIf { it.size >= 4 } ?: return
    val padding = 0.035f
    val left = (b[0] - padding).coerceIn(0f, 1f)
    val top = (b[1] - padding).coerceIn(0f, 1f)
    val right = (b[2] + padding).coerceIn(left + 0.01f, 1f)
    val bottom = (b[3] + padding).coerceIn(top + 0.01f, 1f)
    val crop = remember(bitmap, room.id) {
        val x = (bitmap.width * left).toInt()
        val y = (bitmap.height * top).toInt()
        val w = ((right - left) * bitmap.width).toInt().coerceAtLeast(1)
        val h = ((bottom - top) * bitmap.height).toInt().coerceAtLeast(1)
        android.graphics.Bitmap.createBitmap(bitmap, x, y, w.coerceAtMost(bitmap.width - x), h.coerceAtMost(bitmap.height - y))
    }
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFF5F8FA), modifier = modifier) {
        Column(Modifier.padding(7.dp)) {
            Text("${room.label} 확대 위치도 · 파랑: 촬영 위치 · 빨강: 하자 위치", color = PrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().aspectRatio(crop.width.toFloat() / crop.height.toFloat())) {
                Image(crop.asImageBitmap(), contentDescription = "방 확대 위치도", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    fun drawMarker(pin: PinPos?, color: Color, radius: Float) {
                        pin ?: return
                        val x = ((pin.x - left) / (right - left)).coerceIn(0f, 1f) * size.width
                        val y = ((pin.y - top) / (bottom - top)).coerceIn(0f, 1f) * size.height
                        drawCircle(color.copy(alpha = 0.22f), radius * 2.2f, Offset(x, y))
                        drawCircle(color, radius, Offset(x, y))
                        drawCircle(Color.White, radius * 0.35f, Offset(x - radius * 0.25f, y - radius * 0.25f))
                    }
                    drawMarker(state.capturePin, Color(0xFF1565C0), 9f)
                    drawMarker(state.pin, Color(0xFFD32F2F), 11f)
                }
            }
        }
    }
}
@Composable
private fun FloorplanWithPin(
    state: PinPlacementState,
    onPinMoveRequest: (Float, Float) -> Unit,
    onPinTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = state.floorplanBitmap ?: return
    val density = LocalDensity.current
    val currentHeading = state.capture?.pose?.imuHeadingDeg ?: 0f

    // Pulsing halo for the new-defect pin — draws attention to the freshly
    // placed dot without being flashy enough to distract from the map.
    val pulse by rememberInfiniteTransition(label = "new-pin-pulse")
        .animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse-alpha"
        )

    val overlay: @Composable (innerW: androidx.compose.ui.unit.Dp, innerH: androidx.compose.ui.unit.Dp) -> Unit =
        { innerW, innerH ->
            val boxWPx = with(density) { innerW.toPx() }
            val boxHPx = with(density) { innerH.toPx() }

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            onPinMoveRequest(
                                (pos.x / boxWPx).coerceIn(0f, 1f),
                                (pos.y / boxHPx).coerceIn(0f, 1f)
                            )
                        }
                    }
            )

            // Background: navigation trail (anchor → current pin) + existing
            // defect pins, all drawn on a raw Canvas overlay so we don't
            // fight the ArrowPin composable layout.
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height

                // Nav trail: dashed teal line from entrance anchor to the
                // currently proposed pin. Only when both endpoints exist.
                val ax = state.anchorX
                val ay = state.anchorY
                val newPin = state.pin
                if (ax != null && ay != null && newPin != null) {
                    val startPx = Offset(ax * w, ay * h)
                    val endPx = Offset(newPin.x * w, newPin.y * h)
                    drawLine(
                        color = com.axlife.pinset.ui.theme.Anchor.copy(alpha = 0.6f),
                        start = startPx,
                        end = endPx,
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                    )
                    drawAnchorMarker(
                        center = startPx,
                        radius = 13f,
                        color = com.axlife.pinset.ui.theme.Anchor
                    )
                }

                // Existing defects — punchy red dots (dark halo + bright
                // core + specular highlight) so they read clearly against
                // any floorplan background.
                state.existingDefects.forEach { d ->
                    val cx = d.xNorm.coerceIn(0f, 1f) * w
                    val cy = d.yNorm.coerceIn(0f, 1f) * h
                    val alpha = if (d.status ==
                        com.axlife.pinset.data.entity.DefectStatus.DONE
                    ) 0.5f else 1f
                    drawCircle(
                        color = Color(0xFF7F0000).copy(alpha = alpha),
                        radius = 11f,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = Color(0xFFFF1744).copy(alpha = alpha),
                        radius = 8.5f,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = alpha * 0.9f),
                        radius = 2.2f,
                        center = Offset(cx - 2.4f, cy - 2.4f)
                    )
                }

                // Pulsing highlight around the new pin.
                newPin?.let { p ->
                    val cx = p.x * w
                    val cy = p.y * h
                    drawCircle(
                        color = com.axlife.pinset.ui.theme.Danger.copy(alpha = 0.15f * pulse),
                        radius = 34f,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = com.axlife.pinset.ui.theme.Danger.copy(alpha = 0.35f * pulse),
                        radius = 22f,
                        center = Offset(cx, cy)
                    )
                }
            }

            // Foreground: the standard ArrowPin composable for the new defect
            // sits on top so it's clickable and shows the heading arrow.
            state.pin?.let { pin ->
                ArrowPin(
                    xNorm = pin.x,
                    yNorm = pin.y,
                    parentWidthDp = innerW,
                    parentHeightDp = innerH,
                    index = state.existingDefects.size + 1,
                    severity = Severity.NORMAL,
                    headingDeg = currentHeading,
                    dragging = false,
                    onClick = onPinTap
                )
            }
            Box(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                CompassIcon(
                    headingDeg = currentHeading,
                    size = 34.dp,
                    onDark = false
                )
            }
        }

    // All current floorplan assets are clean 84A/84B plan images. Render at
    // the bitmap's native aspect ratio so displayed height can never exceed
    // the source ratio (and therefore remains well below the 130% limit).
    PlainFloorplan(bitmap = bitmap, modifier = modifier, content = overlay)
}
