package com.axlife.pinset.ui.intro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.core.content.ContextCompat
import com.axlife.pinset.ui.CompassIcon
import com.axlife.pinset.ui.Routes
import com.axlife.pinset.ui.theme.Primary
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.vision.FloorplanRoomAnchor
import com.axlife.pinset.InspectionAccessMode
import com.axlife.pinset.PinSetApplication
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdIntroScreen(nav: NavController) {
    val vm: HouseholdIntroViewModel = viewModel(factory = HouseholdIntroViewModel.Factory)
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as PinSetApplication
    var expandedDiagram by remember { mutableStateOf<String?>(null) }
    var loginRole by remember { mutableStateOf<String?>(null) }
    var loginId by remember { mutableStateOf("Master") }
    var loginPassword by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }
    var showDemoNotice by remember { mutableStateOf(false) }
    var showEndpointDialog by remember { mutableStateOf(false) }
    var showIntroSettings by remember { mutableStateOf(false) }
    var endpointDraft by remember { mutableStateOf(com.axlife.pinset.data.FieldEndpointPrefs.load(context)) }
    var endpointError by remember { mutableStateOf<String?>(null) }
    var showCommonAreaDialog by remember { mutableStateOf(false) }
    var commonAreaLocation by remember { mutableStateOf("공용부 추가") }
    var commonAreaMenu by remember { mutableStateOf(false) }
    var delayedAutoCallRequest by remember { mutableStateOf(0) }
    var showAmendmentConfirm by remember { mutableStateOf(false) }

    fun hasGpsReference(): Boolean = runCatching {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        manager.getProviders(true).any { manager.getLastKnownLocation(it) != null }
    }.getOrDefault(false)

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        vm.recommendNearbyHousehold(
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        )
    }

    fun requestNearbyHousehold() {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.recommendNearbyHousehold(hasGpsReference())
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    fun continueWithAccess(onGranted: () -> Unit) {
        if (app.inspectionAccessMode != InspectionAccessMode.UNVERIFIED) {
            onGranted()
        } else if (state.inspectorRole in setOf("서버관리자", "총괄매니저", "점검매니저")) {
            // Alpha-test manager accounts deliberately bypass the password
            // dialog so field capture can begin without keyboard input.
            app.inspectionAccessMode = InspectionAccessMode.AUTHENTICATED
            vm.selectInspectorRole("점검매니저")
            onGranted()
        } else {
            loginRole = state.inspectorRole
            loginId = "MManager"
            loginPassword = ""
            loginError = null
        }
    }

    LaunchedEffect(delayedAutoCallRequest) {
        if (delayedAutoCallRequest > 0) {
            delay(3_000)
            requestNearbyHousehold()
        }
    }
    LaunchedEffect(state.nearbyBuildingNo, state.nearbyUnitNo) {
        if (state.nearbyBuildingNo != null && state.nearbyUnitNo != null) {
            vm.applyNearbyHousehold()
        }
    }

    fun startHouseholdInput() {
        continueWithAccess {
            if (state.completedInspectionCount > 0) {
                showAmendmentConfirm = true
            } else {
                vm.beginDirectDefect(livingRoomAnchor = true) {
                    nav.navigate(Routes.CAMERA) { popUpTo(Routes.INTRO) { inclusive = false } }
                }
            }
        }
    }

    if (showAmendmentConfirm) {
        AlertDialog(
            onDismissRequest = { showAmendmentConfirm = false },
            title = { Text("\uae30\uc874 \uc810\uac80 \ub370\uc774\ud130 \ud655\uc778") },
            text = {
                Text("\uc774 \ub3d9\u00b7\ud638\uc218\ub294 \uc774\ubbf8 ${state.completedInspectionCount}\uac74\uc758 \uc810\uac80 \uae30\ub85d\uc774 \uc788\uc2b5\ub2c8\ub2e4. \uc6d0\ubcf8 \ub370\uc774\ud130\ub294 \ubcc4\ub3c4\ub85c \ubcf4\uad00\ud558\uace0, \uc774\ubc88 \uc785\ub825\uc740 \uc218\uc815\u00b7\ubcf4\uc644 \ubcf8(v${state.nextRevisionNo})\uc73c\ub85c \uc800\uc7a5\ub429\ub2c8\ub2e4. \uc9c4\ud589\ud560\uae4c\uc694?")
            },
            confirmButton = {
                Button(onClick = {
                    showAmendmentConfirm = false
                    vm.beginDirectDefect(livingRoomAnchor = true) {
                        nav.navigate(Routes.CAMERA) { popUpTo(Routes.INTRO) { inclusive = false } }
                    }
                }) { Text("\uc218\uc815\u00b7\ubcf4\uc644 \uc9c4\ud589") }
            },
            dismissButton = {
                TextButton(onClick = { showAmendmentConfirm = false }) { Text("\ucde8\uc18c") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryDark,
                    titleContentColor = Color.White
                ),
                title = {
                    Column {
                        Text(
                            buildAnnotatedString {
                                append("품질/하자 점검  ")
                                withStyle(SpanStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold)) {
                                    append("v1.0")
                                }
                            },
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 31.sp,
                            maxLines = 1
                        )
                        if (false) Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.dp)
                                .clickable { requestNearbyHousehold() },
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = Color(0xFFCFD8FF)
                            )
                            Text(
                                "동·호수 자동 부르기",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFCFD8FF),
                                modifier = Modifier.padding(start = 3.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showIntroSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "점검 설정", tint = Color.White)
                    }
                }
            )
        },
        bottomBar = {
            if (state.selected != null) {
                Surface(shadowElevation = 10.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (false && !state.anchorAlreadySet) {
                                OutlinedButton(
                                    enabled = state.selectedRoom != null,
                                    onClick = {
                                        continueWithAccess {
                                            vm.beginInspection {
                                                nav.navigate(Routes.CAMERA_ANCHOR) {
                                                    popUpTo(Routes.INTRO) { inclusive = false }
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp)
                                ) { Text("앵커 촬영", fontWeight = FontWeight.Bold) }
                            }
                            Button(
                                onClick = ::startHouseholdInput,
                                modifier = Modifier.weight(1f).height(54.dp)
                            ) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("하자입력(세대)", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showCommonAreaDialog = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("공용부/기타") }
                            OutlinedButton(
                                onClick = { nav.navigate(Routes.SERVER_GALLERY) },
                                modifier = Modifier.weight(1f)
                            ) { Text("하자DB") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "아이디: ${state.inspectorId}  ·  등급: ${state.inspectorRole}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            if (false && state.inspectorRole == "점검매니저") {
                item {
                    ManagerInspectionStatsCard(
                        stats = state.managerStats,
                        onRefresh = vm::refreshManagerStatistics,
                        onConfigure = {
                            endpointDraft = com.axlife.pinset.data.FieldEndpointPrefs.load(context)
                            endpointError = null
                            showEndpointDialog = true
                        }
                    )
                }
            }
            state.selected?.let { household ->
                item {
                    HouseholdHeader(
                        complexName = household.complexName,
                        unitLabel = household.unitLabel,
                        fallback = household.fallback,
                        floorplanLabel = household.floorplanLabel
                    )
                }
                if (state.completedInspectionCount > 0) {
                    item {
                        Surface(
                            color = Color(0xFFFFF3E0),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "\uae30\uc874 \uc138\ub300 \uc810\uac80 ${state.completedInspectionCount}\uac74 \ubcf4\uad00 \uc911 \u00b7 \uc0c8 \uc785\ub825\uc740 \uc218\uc815\u00b7\ubcf4\uc644\ubcf8(v${state.nextRevisionNo})\uc73c\ub85c \ubd84\ub9ac \uc800\uc7a5\ub429\ub2c8\ub2e4.",
                                modifier = Modifier.padding(12.dp),
                                color = Color(0xFF8A4B08),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            item {
                val recentPulse = rememberInfiniteTransition(label = "recentInspection")
                val recentPulseAlpha by recentPulse.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "recentInspectionAlpha"
                )
                var buildingMenuExpanded by remember { mutableStateOf(false) }
                var unitMenuExpanded by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = state.buildingNo,
                            onValueChange = vm::updateBuilding,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("동 번호") },
                            suffix = { Text("동") },
                            trailingIcon = {
                                IconButton(onClick = { buildingMenuExpanded = true }) {
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "동 번호 목록 열기")
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = buildingMenuExpanded,
                            onDismissRequest = { buildingMenuExpanded = false }
                        ) {
                            state.buildingOptions.forEach { building ->
                                DropdownMenuItem(
                                    text = { Text("${building}동") },
                                    onClick = {
                                        vm.updateBuilding(building)
                                        buildingMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = state.unitNo,
                            onValueChange = vm::updateUnit,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("호수") },
                            suffix = { Text("호") },
                            trailingIcon = {
                                if (state.loading) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(onClick = { unitMenuExpanded = true }) {
                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "호수 목록 열기")
                                    }
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = unitMenuExpanded,
                            onDismissRequest = { unitMenuExpanded = false }
                        ) {
                            state.unitOptions.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text("${unit}호") },
                                    onClick = {
                                        vm.updateUnit(unit)
                                        unitMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                state.recentCapturedHousehold?.let { unitLabel ->
                    Surface(
                        color = Color(0xFFFFF1F1),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).alpha(recentPulseAlpha)
                    ) {
                        Text(
                            "( \uc9c1\uc804 \uc810\uac80  $unitLabel )",
                            fontSize = 11.sp,
                            color = Color(0xFFB3261E),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
                if (false) state.nearbyUnitNo?.let { suggestedUnit ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "추천 ${state.nearbyBuildingNo}동 ${suggestedUnit}호",
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    state.nearbyRecommendationStatus.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(
                                onClick = vm::applyNearbyHousehold,
                                modifier = Modifier
                            ) { Text("적용") }
                        }
                    }
                }
            }
            if (state.matches.size > 1 && state.selected == null) {
                item { Text("조회된 세대를 선택하세요.", fontWeight = FontWeight.Bold) }
                items(state.matches) { match ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { vm.select(match) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Apartment, contentDescription = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text("${match.complexName} · ${match.unitLabel}", fontWeight = FontWeight.Bold)
                                Text("소유주 ${match.ownerMasked} · ${match.floorplanLabel}")
                            }
                        }
                    }
                }
            }
            if (state.searched && state.matches.isEmpty() && state.selected == null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("일치하는 세대를 찾지 못했습니다.", fontWeight = FontWeight.Bold)
                            Text("운영 DB 연결 전에는 내장 기본도면으로 점검 흐름을 확인할 수 있습니다.")
                            OutlinedButton(onClick = vm::useDefault, modifier = Modifier.fillMaxWidth()) {
                                Text("기본도면으로 계속")
                            }
                        }
                    }
                }
            }
            state.selected?.let { household ->
                if (state.siteMap != null) {
                    item {
                        val buildingNo = Regex("""(\d+)동""")
                            .find(household.unitLabel)?.groupValues?.getOrNull(1)
                        Text(
                            "단지 안내도 · 현재 ${buildingNo?.let { "${it}동" } ?: "동 위치"}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.clickable { expandedDiagram = "site" }
                        ) {
                            SiteMapWithBuildingMarker(
                                bitmap = state.siteMap!!,
                                buildingNo = buildingNo,
                                modifier = Modifier.fillMaxWidth().height(176.dp).padding(8.dp)
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "\uc138\ub300 \ud3c9\uba74\ub3c4",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        state.floorplanTypes.forEach { type ->
                            FilterChip(
                                selected = household.floorplanId == type.id,
                                onClick = { vm.selectFloorplanType(type.id) },
                                label = { Text(type.label, fontWeight = FontWeight.Bold) }
                            )
                        }
                    }
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.clickable(enabled = state.floorplan != null) {
                            expandedDiagram = "floorplan"
                        }
                    ) {
                        FloorplanWithMarker(
                            bitmap = state.floorplan,
                            unitLabel = household.unitLabel,
                            room = state.rooms.firstOrNull { it.id == "entrance" } ?: state.selectedRoom,
                            headingDeg = floorplanNorthHeading(household.floorplanId),
                            loading = state.loading,
                            modifier = Modifier.fillMaxWidth().height(250.dp)
                        )
                    }
                }
                if (state.anchorAlreadySet) {
                    item {
                        Text(
                            "앵커: ${state.anchorLocationLabel ?: state.selectedRoom?.label.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryDark
                        )
                    }
                } else if (false) {
                    item {
                        Text(
                            "1. 최초 사진 위치(앵커) 선택",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "앵커: 현관문 앞"
                                ?: "첫 사진을 촬영할 공간을 선택하세요.",
                            color = if (state.selectedRoom == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                Color(0xFFD50000)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (state.selectedRoom == null) FontWeight.Normal else FontWeight.Bold
                        )
                    }
                    val perRow = ((state.rooms.size + 1) / 2).coerceAtLeast(1)
                    items(state.rooms.chunked(perRow)) { roomRow ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            roomRow.forEach { room ->
                                FilterChip(
                                    selected = state.selectedRoom?.id == room.id,
                                    onClick = { vm.selectRoom(room) },
                                    label = { Text(room.label, fontSize = 10.sp, maxLines = 1) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(perRow - roomRow.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    state.selected?.let { household ->
        val buildingNo = Regex("""(\d+)동""")
            .find(household.unitLabel)?.groupValues?.getOrNull(1)
        if (expandedDiagram == "site" && state.siteMap != null) {
            FullscreenDiagramDialog(
                title = "단지 안내도 · 현재 ${buildingNo?.let { "${it}동" } ?: "동 위치"}",
                initialScale = 1f,
                onDismiss = { expandedDiagram = null }
            ) {
                SiteMapWithBuildingMarker(
                    bitmap = state.siteMap!!,
                    buildingNo = buildingNo,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
            }
        }
        if (expandedDiagram == "floorplan" && state.floorplan != null) {
            FullscreenDiagramDialog(
                title = "세대 평면도 · ${household.floorplanLabel}평형",
                initialScale = 0.9f,
                onDismiss = { expandedDiagram = null }
            ) {
                FloorplanWithMarker(
                    bitmap = state.floorplan,
                    unitLabel = household.unitLabel,
                    room = state.rooms.firstOrNull { it.id == "entrance" } ?: state.selectedRoom,
                    headingDeg = floorplanNorthHeading(household.floorplanId),
                    loading = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    loginRole?.let { role ->
        val todayPassword = LocalDate.now()
            .format(DateTimeFormatter.ofPattern("MMdd")) + "2587"
        AlertDialog(
            onDismissRequest = { loginRole = null },
            title = { Text("$role 로그인", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("점검용 아이디와 숫자 비밀번호를 입력하세요. 오늘: $todayPassword")
                    OutlinedTextField(
                        value = loginId,
                        onValueChange = {
                            loginId = it
                            loginError = null
                        },
                        label = { Text("아이디") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = loginPassword,
                        onValueChange = {
                            loginPassword = it
                            loginError = null
                        },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    loginError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "인증이 일치하지 않으면 DEMO 모드로 실행되며 서버 저장·전송은 차단됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val authenticated =
                            loginId.trim() == "MManager" &&
                                loginPassword == todayPassword
                        vm.selectInspectorRole(role)
                        if (authenticated) {
                            app.inspectionAccessMode = InspectionAccessMode.AUTHENTICATED
                            loginRole = null
                        } else {
                            app.inspectionAccessMode = InspectionAccessMode.DEMO
                            loginRole = null
                            showDemoNotice = true
                        }
                    }
                ) { Text("확인") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        app.inspectionAccessMode = InspectionAccessMode.DEMO
                        loginRole = null
                        showDemoNotice = true
                    }) { Text("DEMO로 계속") }
                    OutlinedButton(onClick = { loginRole = null }) { Text("취소") }
                }
            }
        )
    }
    if (showDemoNotice) {
        AlertDialog(
            onDismissRequest = { showDemoNotice = false },
            title = { Text("DEMO 모드", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text("아이디 또는 비밀번호가 일치하지 않습니다. 화면과 촬영 기능은 체험할 수 있지만 서버 저장·전송은 차단됩니다.")
            },
            confirmButton = {
                Button(onClick = { showDemoNotice = false }) { Text("DEMO로 계속") }
            }
        )
    }
    if (showIntroSettings) {
        AlertDialog(
            onDismissRequest = { showIntroSettings = false },
            title = { Text("점검 설정", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.inspectorRole in setOf("서버관리자", "총괄매니저", "점검매니저")) {
                        Text(
                            state.managerStats?.let {
                                "점검현황 · 시작 ${it.startDate ?: "-"} / 누적 ${it.totalHouseholds}세대 / 오늘 ${it.todayHouseholds}세대"
                            } ?: "점검현황을 불러오는 중입니다.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = { vm.refreshManagerStatistics() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("점검현황 새로고침") }
                    }
                    OutlinedButton(
                        onClick = {
                            showIntroSettings = false
                            nav.navigate(Routes.DEVICE_SIMULATOR)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("S25 실행상태 시뮬레이터") }
                    OutlinedButton(
                        onClick = {
                            endpointDraft = com.axlife.pinset.data.FieldEndpointPrefs.load(context)
                            endpointError = null
                            showIntroSettings = false
                            showEndpointDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("서버·VPN 설정") }
                    OutlinedButton(
                        onClick = {
                            showIntroSettings = false
                            delayedAutoCallRequest += 1
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("동호수 자동부르기 (3초 후 적용)") }
                    if (state.selected != null && !state.anchorAlreadySet) {
                        OutlinedButton(
                            enabled = state.selectedRoom != null,
                            onClick = {
                                continueWithAccess {
                                    vm.beginInspection {
                                        showIntroSettings = false
                                        nav.navigate(Routes.CAMERA_ANCHOR)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("최초 사진 앵커 선택·촬영") }
                    }
                }
            },
            confirmButton = { Button(onClick = { showIntroSettings = false }) { Text("닫기") } }
        )
    }
    if (showEndpointDialog) {
        AlertDialog(
            onDismissRequest = { showEndpointDialog = false },
            title = { Text("전송 서버 설정", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("현장 Wi‑Fi·VPN은 http://PC주소:8001, 외부망 운영은 HTTPS 주소를 입력합니다.")
                    OutlinedTextField(
                        value = endpointDraft,
                        onValueChange = { endpointDraft = it },
                        label = { Text("API 주소") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    endpointError?.let { Text(it, color = Color(0xFFB3261E), fontSize = 12.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    runCatching {
                        com.axlife.pinset.data.FieldEndpointPrefs.save(context, endpointDraft)
                    }.onSuccess {
                        showEndpointDialog = false
                        app.syncManager.trigger()
                        vm.refreshManagerStatistics()
                    }.onFailure { endpointError = it.message ?: "서버 주소를 확인해 주세요." }
                }) { Text("저장 및 연결") }
            },
            dismissButton = {
                TextButton(onClick = { showEndpointDialog = false }) { Text("취소") }
            }
        )
    }

    if (showCommonAreaDialog) {
        AlertDialog(
            onDismissRequest = { showCommonAreaDialog = false },
            title = { Text("공용부·기타 하자 입력", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("동·호와 무관한 공용부 또는 기타 장소를 입력한 뒤 사진 촬영과 하자의견 기록을 시작합니다.")
                    OutlinedTextField(
                        value = commonAreaLocation,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("발생 위치") },
                        placeholder = { Text("예: 106동 지하주차장, 단지 보도, 관리동") },
                        trailingIcon = {
                            IconButton(onClick = { commonAreaMenu = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "발생 위치 선택")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1
                    )
                    DropdownMenu(
                        expanded = commonAreaMenu,
                        onDismissRequest = { commonAreaMenu = false }
                    ) {
                        listOf(
                            "공용부 추가", "기타 하자", "지하주차장", "공동현관", "복도·계단",
                            "승강기홀", "옥상·외벽", "단지 보도·조경", "관리동·커뮤니티",
                            "기계·전기·소방시설"
                        ).forEach { location ->
                            DropdownMenuItem(
                                text = { Text(location) },
                                onClick = {
                                    commonAreaLocation = location
                                    commonAreaMenu = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = commonAreaLocation.isNotBlank(),
                    onClick = {
                        continueWithAccess {
                            vm.beginCommonArea(commonAreaLocation) {
                                showCommonAreaDialog = false
                                commonAreaLocation = "공용부 추가"
                                nav.navigate(Routes.CAMERA) {
                                    popUpTo(Routes.INTRO) { inclusive = false }
                                }
                            }
                        }
                    }
                ) { Text("사진 입력") }
            },
            dismissButton = { TextButton(onClick = { showCommonAreaDialog = false }) { Text("취소") } }
        )
    }
}

@Composable
private fun ManagerInspectionStatsCard(
    stats: ManagerInspectionStats?,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F4F2)),
        modifier = Modifier.fillMaxWidth().clickable { onRefresh() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("점검 현황", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                Text(
                    if (stats == null) "서버 점검 현황을 불러오는 중…" else
                        "시작 ${stats.startDate ?: "-"} · 누적 ${stats.totalHouseholds}세대 · 오늘 ${stats.todayHouseholds}세대",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF315B57)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("새로고침", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    "서버 설정",
                    color = Primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onConfigure() }.padding(top = 3.dp)
                )
            }
        }
    }

}

@Composable
private fun FullscreenDiagramDialog(
    title: String,
    initialScale: Float,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "확대 화면 닫기")
                    }
                }
                var scale by remember(initialScale) { mutableStateOf(initialScale) }
                var offsetX by remember { mutableStateOf(0f) }
                var offsetY by remember { mutableStateOf(0f) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFF5F7FA))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(initialScale, 5f)
                                if (scale == initialScale) {
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                }
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                    contentAlignment = Alignment.Center
                ) {
                    content()
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scale = initialScale
                            offsetX = 0f
                            offsetY = 0f
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("화면 맞춤") }
                    OutlinedButton(
                        onClick = { scale = (scale - 0.25f).coerceAtLeast(initialScale) }
                    ) { Text("－") }
                    Text("${(scale * 100).toInt()}%", fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = { scale = (scale + 0.25f).coerceAtMost(5f) }
                    ) { Text("＋") }
                    OutlinedButton(onClick = onDismiss) { Text("닫기") }
                }
            }
        }
    }
}

@Composable
private fun FloorplanWithMarker(
    bitmap: android.graphics.Bitmap?,
    unitLabel: String,
    room: FloorplanRoomAnchor?,
    headingDeg: Float,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.background(Color(0xFFF5F7FA)),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "$unitLabel 세대 평면도",
                modifier = Modifier.fillMaxSize().padding(12.dp),
                contentScale = ContentScale.Fit
            )
        } ?: if (loading) {
            CircularProgressIndicator()
        } else {
            Text("도면 미리보기 없음")
        }
        room?.let {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val c = androidx.compose.ui.geometry.Offset(
                    it.cx.coerceIn(0f, 1f) * size.width,
                    it.cy.coerceIn(0f, 1f) * size.height
                )
                drawCircle(Color(0x55FF1744), radius = 25f, center = c)
                drawCircle(Color.White, radius = 13f, center = c)
                drawCircle(Color(0xFFFF1744), radius = 9f, center = c)
            }
        }
        CompassIcon(
            headingDeg = headingDeg,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
            size = 34.dp,
            onDark = false
        )
    }
}

/** North rotation for each bundled marketing floorplan. */
private fun floorplanNorthHeading(floorplanId: String): Float = when (floorplanId) {
    "ulsan_down_84a", "ulsan_down_84b" -> 0f
    else -> 0f
}

@Composable
private fun SiteMapWithBuildingMarker(
    bitmap: android.graphics.Bitmap,
    buildingNo: String?,
    modifier: Modifier = Modifier
) {
    val normalized = buildingNo?.let(::buildingPosition)
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "단지 안내도 현재 동 위치",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        if (normalized != null) {
            Canvas(Modifier.fillMaxSize()) {
                val imageAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                val canvasAspect = size.width / size.height.coerceAtLeast(1f)
                val drawWidth: Float
                val drawHeight: Float
                val left: Float
                val top: Float
                if (canvasAspect > imageAspect) {
                    drawHeight = size.height
                    drawWidth = drawHeight * imageAspect
                    left = (size.width - drawWidth) / 2f
                    top = 0f
                } else {
                    drawWidth = size.width
                    drawHeight = drawWidth / imageAspect
                    left = 0f
                    top = (size.height - drawHeight) / 2f
                }
                val c = androidx.compose.ui.geometry.Offset(
                    left + normalized.first * drawWidth,
                    top + normalized.second * drawHeight
                )
                drawCircle(Color(0x55FF0000), radius = 28f, center = c)
                drawCircle(Color.White, radius = 15f, center = c)
                drawCircle(Color.Red, radius = 11f, center = c)
            }
        }
    }
}

/** Normalized centers measured from the bundled Ulsan Down site map. */
private fun buildingPosition(buildingNo: String): Pair<Float, Float>? = mapOf(
    "101" to (.29f to .16f), "102" to (.29f to .29f),
    "103" to (.29f to .43f), "104" to (.29f to .57f),
    "105" to (.32f to .72f), "106" to (.35f to .86f),
    "107" to (.45f to .82f), "108" to (.48f to .67f),
    "109" to (.47f to .54f), "110" to (.50f to .42f),
    "111" to (.50f to .28f), "112" to (.59f to .54f),
    "113" to (.61f to .69f), "114" to (.70f to .60f),
    "115" to (.72f to .46f), "116" to (.72f to .33f),
    "117" to (.80f to .45f), "118" to (.82f to .28f),
    "119" to (.84f to .14f), "120" to (.92f to .26f)
)[buildingNo]

@Composable
private fun HouseholdHeader(
    complexName: String,
    unitLabel: String,
    fallback: Boolean,
    floorplanLabel: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (fallback) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = if (fallback) Primary else PrimaryDark)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    complexName,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                Text(
                    unitLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}
