package com.axlife.pinset.ui.camera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.axlife.pinset.data.SlotPrefs
import com.axlife.pinset.camera.FlashMode
import com.axlife.pinset.ui.CompassIcon
import com.axlife.pinset.ui.InspectionStepBar
import com.axlife.pinset.ui.Routes
import com.axlife.pinset.ui.theme.Secondary
import com.axlife.pinset.ui.theme.Success
import com.axlife.pinset.ui.theme.Warning
import coil.compose.AsyncImage

@Composable
fun CameraScreen(nav: NavController, anchorMode: Boolean = false) {
    val vm: CameraViewModel = viewModel(factory = CameraViewModel.Factory)
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(state.result, anchorMode) {
        val result = state.result
        if (!anchorMode && result != null && result.shots.isNotEmpty()) {
            val app = ctx.applicationContext as com.axlife.pinset.PinSetApplication
            if (app.pendingAdditionalPhotoDefectId == null) {
                vm.storeAndAdvance {
                    nav.navigate(Routes.PIN_PLACEMENT) { popUpTo(Routes.HOME) }
                }
            }
        }
    }

    LaunchedEffect(state.skipToOpinion, anchorMode) {
        if (!anchorMode && state.skipToOpinion) {
            kotlinx.coroutines.delay(1_500L)
            nav.navigate(Routes.PIN_PLACEMENT) { popUpTo(Routes.HOME) }
        }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasPermission = it }
    )
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val activity = ctx as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.resumePreview()
                Lifecycle.Event.ON_PAUSE -> vm.pausePreview()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            vm.pausePreview()
        }
    }

    var showSettings by remember { mutableStateOf(false) }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("카메라 권한이 필요합니다", color = Color.White)
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            SingleLineTopBar(
                anchorMode = anchorMode,
                flashMode = state.flashMode,
                onClose = {
                    val app = ctx.applicationContext as com.axlife.pinset.PinSetApplication
                    app.pendingAdditionalPhotoDefectId = null
                    nav.popBackStack()
                },
                onSettings = { showSettings = true },
                onFlash = vm::cycleFlashMode
            )
            InspectionStepBar(
                currentStep = if (anchorMode) 0 else 1,
                darkBackground = true,
                onStepSelected = { step ->
                    when (step) {
                        0 -> nav.navigate(Routes.HOME) { launchSingleTop = true }
                        1 -> if (anchorMode) nav.navigate(Routes.CAMERA)
                        2 -> {
                            nav.navigate(Routes.PIN_PLACEMENT)
                        }
                    }
                }
            )
            // Preview grows to fill leftover vertical space (weight = 1) so
            // the shutter row below is ALWAYS visible regardless of screen
            // height. Aspect ratio is no longer forced — the surface adapts
            // to whatever height it gets.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                DualPreview(
                    dualActive = state.dualPreviewActive,
                    teleZoom = state.telePreviewZoom,
                    ultraZoom = state.ultraPreviewZoom,
                    onSurfaces = { teleSv, ultraSv -> vm.setPreviewSurfaces(teleSv, ultraSv) },
                    onSurfaceDestroyed = vm::onPreviewSurfaceDestroyed,
                    onPinch = { factor -> vm.pinchPreviewZoom(factor) }
                )
                // Rule-of-thirds grid + centered defect-region ring overlay.
                // Both live above the SurfaceView so composition ordering
                // handles them correctly.
                PreviewMarkerOverlay(
                    showGrid = state.showGrid,
                    markerFrac = state.markerRadiusFrac,
                    anchorMode = anchorMode,
                    modifier = Modifier.fillMaxSize()
                )
                if (state.precisionMeasurementEnabled && !anchorMode) {
                    androidx.compose.material3.Surface(
                        color = Color(0xCC6D4C41),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    ) {
                        Text(
                            "\uc815\ubc00 \ud2c8\uc0c8 \uce21\uc815 \ucf1c\uc9d0",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
                CompassIcon(
                    headingDeg = state.imuHeadingDeg,
                    size = 44.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
            // 촬영은 라이브 프리뷰 바로 아래의 중앙 제어영역에 둔다. 하단의 평면도·설정과
            // 분리하여 현장에서 손가락 이동을 줄인다.

            if (!anchorMode) {
                // Both sliders on ONE row so the preview above keeps its
                // vertical room. Lens + Mark share space 60:40 — the lens
                // slider gets the wider half since its range is bigger.
                CombinedControlRow(
                    zoom = state.previewZoom,
                    zoomMin = state.previewZoomMin,
                    zoomMax = state.previewZoomMax,
                    onZoomChange = vm::setPreviewZoom,
                    markerFrac = state.markerRadiusFrac,
                    onMarkerChange = vm::setMarkerRadius
                )
            } else {
                // Anchor mode: lens slider only, location picker below.
                ZoomSlider(
                    value = state.previewZoom,
                    min = state.previewZoomMin,
                    max = state.previewZoomMax,
                    onChange = vm::setPreviewZoom
                )
                AnchorLocationPicker(
                    selected = state.anchorLocationLabel,
                    onSelect = vm::setAnchorLocationLabel
                )
            }
            BottomBar(
                capturing = state.capturing,
                shutterSoundEnabled = state.shutterSoundEnabled,
                onShutter = { vm.capture(ctx) },
                onToggleShutterSound = vm::toggleShutterSound,
                onSwitch = { showSettings = true }
            )            // Skip the mini-map entirely in anchor mode — the user is at the
            // entrance, hasn't set an anchor yet, so PDR projections aren't
            // meaningful. Frees vertical space for the preview + shutter.
            if (!anchorMode) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MiniMapOverlay(
                        bitmap = state.liveFloorplan,
                        session = state.activeSession,
                        defects = state.liveDefects,
                        liveXNorm = state.liveXNorm,
                        liveYNorm = state.liveYNorm,
                        headingDeg = state.imuHeadingDeg,
                        modifier = Modifier.fillMaxWidth(0.72f)
                    )
                }
            }

        }
        if (state.capturing) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Secondary)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        state.captureStatus.ifBlank { "촬영 중입니다. 카메라를 고정하세요." },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        if (state.recoveringPreview && !state.capturing) {
            Surface(
                color = Color.Black.copy(alpha = 0.76f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable { vm.restartPreview() }
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text("카메라 복구 중 · 눌러서 다시 연결", color = Color.White)
                }
            }
        }
        state.error?.let { msg ->
            Surface(
                color = Color(0xFFB00020),
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
            ) { Text(msg, color = Color.White, modifier = Modifier.padding(12.dp), fontSize = 12.sp) }
        }
    }

    state.result?.takeIf {
        it.shots.isNotEmpty() && (
            anchorMode ||
                (ctx.applicationContext as com.axlife.pinset.PinSetApplication)
                    .pendingAdditionalPhotoDefectId != null
            )
    }?.let { result ->
        CaptureReviewDialog(
            imagePath = result.primary?.filePath ?: result.shots.first().filePath,
            anchorLocationLabel = state.anchorLocationLabel,
            distanceLabel = result.pose.focusDistanceM?.let { String.format("%.1fm", it) } ?: "측정값 없음",
            headingDeg = result.pose.imuHeadingDeg,
            onRetake = vm::discardCapture,
            onUse = {
                if (anchorMode) {
                    vm.storeAnchorAndAdvance {
                        nav.navigate(Routes.HOME) {
                            popUpTo(Routes.INTRO) { inclusive = false }
                        }
                    }
                } else {
                    val app = ctx.applicationContext as com.axlife.pinset.PinSetApplication
                    val additionalDefectId = app.pendingAdditionalPhotoDefectId
                    if (additionalDefectId != null) {
                        vm.storeAdditionalPhotosAndReturn(additionalDefectId) {
                            nav.popBackStack()
                        }
                    } else {
                        vm.storeAndAdvance {
                            nav.navigate(Routes.PIN_PLACEMENT) { popUpTo(Routes.HOME) }
                        }
                    }
                }
            }
        )
    }

    if (showSettings) {
        SlotSettingsDialog(
            initial = state.slotPrefs,
            showGrid = state.showGrid,
            defectPhotoMode = state.defectPhotoMode.takeUnless { anchorMode },
            precisionMeasurementEnabled = state.precisionMeasurementEnabled,
            onPrecisionMeasurementChange = vm::setPrecisionMeasurementEnabled,
            referenceMarkerEnabled = state.referenceMarkerEnabled,
            onReferenceMarkerChange = vm::setReferenceMarkerEnabled,
            onToggleGrid = { vm.toggleGrid() },
            onDismiss = { showSettings = false },
            onDefectPhotoModeChange = vm::setDefectPhotoMode,
            onSave = { prefs -> vm.updateSlots(prefs); showSettings = false }
        )
    }
}

@Composable
private fun CaptureReviewDialog(
    imagePath: String,
    anchorLocationLabel: String,
    distanceLabel: String,
    headingDeg: Float,
    onRetake: () -> Unit,
    onUse: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("촬영 이미지 판별 확인", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                    AsyncImage(
                        model = java.io.File(imagePath),
                        contentDescription = "촬영한 하자 이미지",
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
                Surface(
                    color = Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "사진앵커 : ${koreanDirection(headingDeg)} " +
                            "${cardinalDirection(headingDeg)}${normalizedHeading(headingDeg)}도 / $distanceLabel",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B3A2A),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Text("AI 또는 점검자가 하자를 분간하기 어렵다면 즉시 재촬영하세요.")
                Text(
                    "확인 후 사용을 선택해야 위치·하자 의견 입력으로 넘어갑니다.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(onClick = onUse) { Text("이미지 사용") }
        },
        dismissButton = {
            OutlinedButton(onClick = onRetake) { Text("재촬영") }
        }
    )
}

/**
 * Compact single-line top bar for the camera screen. Replaces the old
 * multi-badge TopBar so the header title never wraps to a second line —
 * matches the design v1.0 requirement.
 *
 * v1.1 revision: the right-side zoom-number pill is gone (the slider under
 * the preview already carries the current ratio). A settings gear stays so
 * slot preferences remain one tap away.
 */
@Composable
private fun SingleLineTopBar(
    anchorMode: Boolean,
    flashMode: FlashMode,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onFlash: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, null, tint = Color.White) }
        val title = if (anchorMode) "최초 위치 앵커 촬영" else "LIVE DETECTION"
        Text(
            title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onFlash) {
            Icon(
                imageVector = when (flashMode) {
                    FlashMode.AUTO -> Icons.Filled.FlashAuto
                    FlashMode.ON -> Icons.Filled.FlashOn
                    FlashMode.OFF -> Icons.Filled.FlashOff
                },
                contentDescription = "플래시 ${flashMode.label}",
                tint = if (flashMode == FlashMode.OFF) Color.Gray else Color(0xFFFFD54F)
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, null, tint = Color.White)
        }
    }
}

@Composable
private fun TopBar(
    onClose: () -> Unit,
    locating: Boolean,
    onSettings: () -> Unit,
    slotPrefs: SlotPrefs,
    previewZoom: Float,
    arAvailable: Boolean,
    arTracking: Boolean,
    arAnchorSet: Boolean,
    onSetAnchor: () -> Unit,
    onClearAnchor: () -> Unit,
    imuPitch: Float
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, null, tint = Color.White) }
        // AR anchor status + surface hint
        if (arAvailable) {
            val anchorLabel = when {
                arAnchorSet && arTracking -> "AR: 앵커 · 추적중"
                arAnchorSet -> "AR: 앵커 · 추적끊김"
                arTracking -> "AR: 앵커설정"
                else -> "AR 준비중"
            }
            val anchorTint = when {
                arAnchorSet && arTracking -> Success
                arTracking -> Warning
                else -> Color.Gray
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.clickable {
                    if (arAnchorSet) onClearAnchor() else onSetAnchor()
                }
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.LocationOn, null, tint = anchorTint, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(anchorLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.6f)) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.GpsFixed, null, tint = if (locating) Warning else Success, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (locating) "위치 파악 중" else formatZoom(previewZoom), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier.clickable { onSettings() }
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Settings, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(slotPrefs.labels.joinToString("·"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Single fullscreen preview. The user's zoom slider drives CONTROL_ZOOM_RATIO
 * end-to-end so what shows here is exactly what gets saved as slot A. Slot B
 * (0.5x wide context) is captured in a second shutter tick right after.
 *
 * We still feed the VM through the two-surface entry point for API stability;
 * the "ultra" surface is a hidden 1x1 SurfaceView that never renders anything.
 */
@Composable
private fun DualPreview(
    dualActive: Boolean,
    teleZoom: Float,
    ultraZoom: Float,
    onSurfaces: (SurfaceView, SurfaceView) -> Unit,
    onSurfaceDestroyed: (SurfaceView) -> Unit,
    onPinch: (Float) -> Unit
) {
    var tele by remember { mutableStateOf<SurfaceView?>(null) }
    var ultra by remember { mutableStateOf<SurfaceView?>(null) }
    LaunchedEffect(tele, ultra) {
        val t = tele; val u = ultra
        if (t != null && u != null) onSurfaces(t, u)
    }
    Box(
        Modifier
            .fillMaxSize()
            .border(2.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .background(Color.Black, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) onPinch(zoom)
                }
            }
    ) {
        PreviewPane(
            // Short single-line label — no longer describes the workflow;
            // the top bar carries "현관문 앵커" vs "하자 촬영" context.
            label = "라이브",
            labelBg = Secondary,
            modifier = Modifier.fillMaxSize(),
            onSurface = { tele = it },
            onSurfaceDestroyed = onSurfaceDestroyed
        )
        // Invisible carrier surface kept only so the VM's two-surface API
        // still receives its second handle. Camera2 never draws to it.
        AndroidView(
            factory = { context ->
                SurfaceView(context).also { sv ->
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { ultra = sv }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            ultra = null
                            onSurfaceDestroyed(sv)
                        }
                    })
                }
            },
            modifier = Modifier.size(1.dp)
        )
    }
}

@Composable
private fun PreviewPane(
    label: String,
    labelBg: Color,
    modifier: Modifier = Modifier,
    onSurface: (SurfaceView) -> Unit,
    onSurfaceDestroyed: (SurfaceView) -> Unit
) {
    Box(modifier.fillMaxWidth()) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).also { sv ->
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { onSurface(sv) }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            onSurfaceDestroyed(sv)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Surface(
            color = labelBg,
            shape = RoundedCornerShape(bottomEnd = 12.dp, topStart = 12.dp),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Text(
                label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 3×3 rule-of-thirds grid + centered circular defect-region ring, drawn on
 * top of the live preview. Anchor mode also keeps a focus ring and center dot
 * visible so the first reference frame has an explicit aiming point.
 */
@Composable
private fun PreviewMarkerOverlay(
    showGrid: Boolean,
    markerFrac: Float,
    anchorMode: Boolean,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height

        if (showGrid) {
            // Two-pass grid: dark drop-shadow underneath, bright yellow line
            // on top. Makes the grid readable on both dark and light scenes
            // without a background overlay.
            val shadow = Color.Black.copy(alpha = 0.55f)
            val line = Color(0xFFFFEB3B).copy(alpha = 0.9f)
            val shadowStroke = 4.5f
            val lineStroke = 2.2f
            fun pair(x0: Float, y0: Float, x1: Float, y1: Float) {
                drawLine(shadow,
                    androidx.compose.ui.geometry.Offset(x0, y0),
                    androidx.compose.ui.geometry.Offset(x1, y1), shadowStroke)
                drawLine(line,
                    androidx.compose.ui.geometry.Offset(x0, y0),
                    androidx.compose.ui.geometry.Offset(x1, y1), lineStroke)
            }
            pair(w / 3f, 0f, w / 3f, h)
            pair(w * 2f / 3f, 0f, w * 2f / 3f, h)
            pair(0f, h / 3f, w, h / 3f)
            pair(0f, h * 2f / 3f, w, h * 2f / 3f)
        }

        run {
            val cx = w / 2f
            val cy = h / 2f
            val shortSide = kotlin.math.min(w, h)
            val r = if (anchorMode) {
                shortSide * 0.14f
            } else {
                shortSide * markerFrac.coerceIn(0.05f, 0.45f)
            }
            val stroke = shortSide * 0.010f
            val markerColor = if (anchorMode) Color(0xFF00E5FF) else Color(0xFFE53935)

            // Hollow ring — no fill so the defect area under the marker is
            // never obscured. White backing + red main + tiny crosshair.
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = r,
                center = androidx.compose.ui.geometry.Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke * 1.7f)
            )
            drawCircle(
                color = markerColor,
                radius = r,
                center = androidx.compose.ui.geometry.Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            // Tiny center dot instead of the full crosshair — a single pixel
            // of reference without covering the target.
            drawCircle(
                markerColor,
                radius = shortSide * 0.009f,
                center = androidx.compose.ui.geometry.Offset(cx, cy)
            )
        }
    }
}

/**
 * Combined single-row control for lens zoom + defect-mark radius. Labels
 * are shortened to "렌즈"/"마크" and each slider takes half the row so both
 * fit on one line. Frees an entire vertical row for the preview above.
 */
@Composable
private fun CombinedControlRow(
    zoom: Float,
    zoomMin: Float,
    zoomMax: Float,
    onZoomChange: (Float) -> Unit,
    markerFrac: Float,
    onMarkerChange: (Float) -> Unit
) {
    // Two equal halves — each control gets exactly 50% of the row width so
    // the label + slider stack in a balanced 1:1 layout.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔍", fontSize = 10.sp)
            Spacer(Modifier.width(2.dp))
            Text(
                "렌즈",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = zoom,
                onValueChange = onZoomChange,
                valueRange = zoomMin..zoomMax,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            Text(
                formatZoom(zoom),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⬤", color = Color(0xFFE53935), fontSize = 10.sp)
            Spacer(Modifier.width(2.dp))
            Text(
                "마크",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = markerFrac,
                onValueChange = onMarkerChange,
                valueRange = 0.05f..0.45f,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            Text(
                "${(markerFrac * 100).toInt()}%",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Anchor-location chip row + free-text field, shown in anchor mode below
 * the zoom slider. Presets cover the common starting spots; the text field
 * lets the operator override with anything (e.g. a specific room name).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AnchorLocationPicker(selected: String, onSelect: (String) -> Unit) {
    val presets = listOf("현관문", "거실", "베란다", "주방", "기타")
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📍 촬영위치 기준",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                "· 인트로 위치 유지 · 필요시 재지정",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            presets.forEach { p ->
                val active = selected == p ||
                    (p == "기타" && selected !in presets.dropLast(1))
                Surface(
                    color = if (active) Color(0xFF00897B) else Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.clickable { onSelect(p) }
                ) {
                    Text(
                        p,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        // Free-text override — becomes the label when non-empty.
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.OutlinedTextField(
            value = if (selected in presets.dropLast(1)) "" else selected,
            onValueChange = { onSelect(it.ifBlank { "기타" }) },
            placeholder = {
                Text("직접 입력 (예: 안방 창가)",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp)
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White, fontSize = 12.sp
            ),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00897B),
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                cursorColor = Color.White,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ZoomSlider(value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔍", fontSize = 10.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            "렌즈 배율",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(6.dp))
        Text(formatZoom(min), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
        )
        Text(formatZoom(max), color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
    }
}

private fun formatZoom(v: Float): String {
    val i = v.toInt()
    return if (kotlin.math.abs(v - i) < 0.05f) "${i}x" else String.format("%.1fx", v)
}

private fun cardinalDirection(headingDeg: Float): String {
    val normalized = ((headingDeg % 360f) + 360f) % 360f
    val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return labels[((normalized + 22.5f) / 45f).toInt() % 8]
}

private fun koreanDirection(headingDeg: Float): String {
    val normalized = ((headingDeg % 360f) + 360f) % 360f
    val labels = arrayOf("북향", "북동향", "동향", "남동향", "남향", "남서향", "서향", "북서향")
    return labels[((normalized + 22.5f) / 45f).toInt() % 8]
}

private fun normalizedHeading(headingDeg: Float): Int =
    (((headingDeg % 360f) + 360f) % 360f).toInt()

private fun compactAnchorLabel(value: String): String =
    value.replace("침실 ", "침실").replace("방 ", "방").trim().let {
        if (it.length <= 8) it else it.take(7) + "…"
    }

private fun surfaceLabelFromPitch(pitchDeg: Float): String = when {
    pitchDeg > 30f  -> "천장"
    pitchDeg < -30f -> "바닥"
    else            -> "벽"
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

/** One-line live sensor readout under the camera preview. */
@Composable
private fun LiveImuLine(
    pitchDeg: Float,
    headingDeg: Float,
    distanceLabel: String
) {
    val text = "기울기 ${pitchDeg.toInt()}°  ·  방향 ${headingDeg.toInt()}° ${compassLabel(headingDeg)}  ·  거리 $distanceLabel"
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

/**
 * Left-aligned variant: zoom badge + sensor readout on the LEFT side of the
 * screen so the composition matches design v1.0 (miniMap goes below to the
 * center, ratio/tilt info stays out of the way).
 */
@Composable
private fun LeftAlignedImuLine(
    pitchDeg: Float,
    headingDeg: Float,
    distanceLabel: String,
    zoom: Float
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Color(0xFFF57C00),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                formatZoom(zoom),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "기울기 ${pitchDeg.toInt()}° · 방향 ${headingDeg.toInt()}° ${compassLabel(headingDeg)} · 거리 $distanceLabel",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BottomBar(
    capturing: Boolean,
    shutterSoundEnabled: Boolean,
    onShutter: () -> Unit,
    onToggleShutterSound: () -> Unit,
    onSwitch: () -> Unit
) {
    // Pill-shaped shutter — half the vertical footprint of the old 72dp
    // circle so the preview above can breathe.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = if (shutterSoundEnabled) Color(0xFF455A64) else Color(0xFF263238),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.clickable { onToggleShutterSound() }
        ) {
            Text(
                if (shutterSoundEnabled) "촬영음" else "무음",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
            )
        }
        Box(
            Modifier
                .weight(1f)
                .height(44.dp)
                .background(Color.White, RoundedCornerShape(22.dp))
                .clickable(enabled = !capturing) { onShutter() },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, null, tint = Secondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "촬영",
                    color = Secondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
        IconButton(onClick = onSwitch) {
            Icon(Icons.Filled.SwapHoriz, null, tint = Color.White)
        }
    }
}
