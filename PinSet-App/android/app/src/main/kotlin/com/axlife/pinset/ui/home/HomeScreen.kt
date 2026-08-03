package com.axlife.pinset.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.axlife.pinset.data.entity.DefectStatus
import com.axlife.pinset.data.entity.Session
import com.axlife.pinset.ui.Routes
import com.axlife.pinset.ui.theme.Anchor
import com.axlife.pinset.ui.theme.Danger
import com.axlife.pinset.ui.theme.Pending
import com.axlife.pinset.ui.theme.Primary
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.ui.theme.PrimaryLight
import com.axlife.pinset.ui.theme.Success
import com.axlife.pinset.ui.theme.SurfaceColor
import com.axlife.pinset.ui.theme.TextSub
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val uiPrefs = remember { context.getSharedPreferences("home_ui_state", android.content.Context.MODE_PRIVATE) }
    var showSessionPicker by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var finishMessage by remember { mutableStateOf<String?>(null) }
    var showAiAssistantIntro by remember(state.activeSessionId) {
        mutableStateOf(
            state.activeSessionId?.let { sessionId ->
                !uiPrefs.getBoolean("ai_assistant_seen_$sessionId", false)
            } ?: false
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryDark,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Home, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("품질하자점검", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.REFERENCE) }) {
                        Icon(Icons.Filled.Settings, null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SummaryCard(
                    state = state,
                    onSwitchSession = { showSessionPicker = true }
                )
            }
            if (!state.anchorSet) {
                item {
                    AnchorBanner(state) {
                        nav.navigate(Routes.CAMERA_ANCHOR)
                    }
                }
            }
            item {
                StartCaptureButton(
                    enabled = state.anchorSet,
                    onClick = {
                        if (!state.anchorSet) {
                            // Force anchor first — the entrance point is what
                            // makes PDR / mini-map projections meaningful.
                            nav.navigate(Routes.CAMERA_ANCHOR)
                        } else {
                            nav.navigate(Routes.CAMERA)
                        }
                    }
                )
            }
            item {
                Button(
                    onClick = { confirmFinish = true },
                    enabled = state.total > 0 && state.activeSession?.done != true && !state.isClosing,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text(
                        if (state.activeSession?.done == true) {
                            "세대 점검 마감 완료"
                        } else {
                            if (state.isClosing) "전송 및 마감 중…" else "전송 및 마감"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            if (state.total == 0 && showAiAssistantIntro) {
                item {
                    AiInspectionEntryCard {
                        state.activeSessionId?.let { sessionId ->
                            uiPrefs.edit().putBoolean("ai_assistant_seen_$sessionId", true).apply()
                        }
                        showAiAssistantIntro = false
                        nav.navigate(Routes.AI_INSPECTION)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("최근 하자 목록", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "  (전체 ${state.total}건)",
                        fontSize = 12.sp, color = TextSub,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
            items(state.recent) { d ->
                DefectSingleRow(d) { nav.navigate(Routes.pinDetail(d.id)) }
            }
            if (state.recent.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceColor)) {
                        Text(
                            "아직 등록된 하자가 없습니다.\n'새 하자 입력'을 눌러 첫 촬영을 진행하세요.",
                            modifier = Modifier.padding(20.dp),
                            color = TextSub, fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    if (showSessionPicker) {
        SessionPickerDialog(
            sessions = state.sessions,
            activeId = state.activeSessionId,
            onDismiss = { showSessionPicker = false },
            onPick = { s -> vm.selectSession(s.id); showSessionPicker = false },
            onNew = { showSessionPicker = false; showCreate = true },
            onDelete = { s -> vm.deleteSession(s.id) }
        )
    }
    if (showCreate) {
        NewSessionDialog(
            onDismiss = { showCreate = false },
            onCreate = { label, floorplanId ->
                vm.createSession(label, floorplanId)
                showCreate = false
            }
        )
    }
    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("세대점검을 전송 및 마감할까요?") },
            text = { Text("최근 하자 목록의 ${state.total}건을 전송한 뒤 현재 세대 점검을 마감합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    vm.finishInspection(
                        onFinished = { finishMessage = it },
                        onBlocked = { finishMessage = it }
                    )
                }) { Text("전송 및 마감") }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("취소") } }
        )
    }
    finishMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { finishMessage = null },
            title = { Text("세대점검 상태") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    val completed = message.contains("마감이 완료")
                    finishMessage = null
                    if (completed) nav.navigate(Routes.INTRO) {
                        popUpTo(Routes.INTRO) { inclusive = true }
                    }
                }) { Text("확인") }
            }
        )
    }
}

@Composable
private fun AiInspectionEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = PrimaryLight),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = PrimaryDark)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("AI 하자분류 어시스턴트", fontWeight = FontWeight.Bold, color = PrimaryDark)
                Text(
                    "의견을 입력하고 추가 질문을 거쳐 표준 하자분류를 추천받습니다.",
                    fontSize = 12.sp,
                    color = TextSub
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    state: HomeState,
    onSwitchSession: () -> Unit
) {
    // Navy information hero. Orange is reserved for the new-defect CTA so the
    // field operator sees one unambiguous primary action.
    val gradient = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(PrimaryDark, Primary, Color(0xFF3C6FA7))
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.background(gradient).padding(16.dp)) {
            Column {
                // Header — unit + date on the left, compass on the right.
                // Align the row to the BOTTOM so the compass drops down to
                // the date line level (matches v1.5 tweak).
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSwitchSession() }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.unitLabel,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Icon(
                                Icons.Filled.ExpandMore, null,
                                tint = Color.White.copy(alpha = 0.85f)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CalendarToday, null,
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                state.dateText,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 11.sp
                            )
                        }
                    }
                    // Compass at same vertical position as the date row —
                    // small, top-aligned so it doesn't overlap the ring below.
                    NorthMarker(modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(10.dp))
                // Section label sits above the ring+stats+mini row so the
                // three columns stay compact and the numbers have room to
                // breathe without their "건" suffix.
                Text(
                    "품질하자접수 현황",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(6.dp))
                // Bottom row: ring + tight 3-line stats on the LEFT, mini
                // floorplan on the RIGHT.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(
                        done = state.done,
                        total = state.total,
                        modifier = Modifier.size(78.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    // Wrap-content column so the stats snap left and leave
                    // breathing room before the mini-map on the right.
                    Column {
                        // No "건" suffix — the "품질하자접수 현황" label above
                        // makes the unit implicit and frees horizontal space
                        // for the mini floorplan on the right.
                        StatLine("전체", "${state.total}", Color.White)
                        Spacer(Modifier.height(2.dp))
                        StatLine("미확인", "${state.pending}", Color(0xFFFFEB3B))
                        Spacer(Modifier.height(2.dp))
                        StatLine("완료", "${state.done}", Color(0xFF69F0AE))
                    }
                    // Flexible spacer eats the leftover width — visual gap
                    // between the stat block and the floorplan thumbnail.
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(width = 108.dp, height = 74.dp)
                    ) {
                        HomeMiniFloorplan(
                            session = state.activeSession,
                            bitmap = state.floorplanBitmap,
                            isCustom = state.floorplanIsCustom,
                            defects = state.defects,
                            liveXNorm = null,
                            liveYNorm = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineStats(total: Int, pending: Int, done: Int) {
    // Compressed one-liner: "건" only appears on the leading item, "완료"
    // label is dropped (the trailing "(33%)" makes it obvious), and the
    // completion pill on the right is gone.
    val ratio = if (total > 0) (done * 100f / total).toInt() else 0
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InlineStat(label = "전체", value = "${total}건", valueColor = Color.White)
        InlineDot()
        InlineStat(label = "미확인", value = "$pending", valueColor = Color(0xFFFFEB3B))
        InlineDot()
        Text(
            "$done ($ratio%)",
            color = Color(0xFF69F0AE),
            fontSize = 13.sp, fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun InlineStat(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun InlineDot() {
    Text(
        "  ·  ",
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun AnchorBanner(state: HomeState, onTap: () -> Unit) {
    // Small teal banner that lets the operator see anchor status. Includes
    // a mini thumbnail of the entrance-door capture when one exists — the
    // 20x close-up (SlotRole.A) is preferred so the "1503" plate is legible.
    val bg = androidx.compose.ui.graphics.Brush.horizontalGradient(
        colors = if (state.anchorSet)
            listOf(Anchor, Color(0xFF26A69A))
        else
            listOf(Color(0xFF757575), Color(0xFF9E9E9E))
    )
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
    ) {
        Row(
            Modifier.background(bg).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnchorThumbnail(
                photoPath = state.anchorPhotoPath,
                anchorSet = state.anchorSet
            )
            if (state.anchorSet) {
                Spacer(Modifier.width(5.dp))
                com.axlife.pinset.ui.CompassIcon(
                    headingDeg = state.anchorHeadingDeg ?: 0f,
                    size = 24.dp,
                    onDark = true
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // Header line — must fit on ONE line even for long labels
                // (e.g. free-text "안방 창가 옆"). Ellipsis at the end for safety.
                Text(
                    if (state.anchorSet)
                        "앵커 · ${state.anchorLocationLabel}"
                    else
                        "앵커 미설정",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                // Sub-line: 8-way direction · angle · distance · tilt.
                if (state.anchorSet) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                val heading = state.anchorHeadingDeg ?: 0f
                                append(homeCardinalDirection(heading))
                                append(" ")
                                append(normalizedHomeHeading(heading))
                                append("°")
                                state.anchorDistanceLabel?.let {
                                    append("  ·  거리 "); append(it)
                                }
                                state.anchorPitchDeg?.let {
                                    append("  ·  경사 "); append(it.toInt()); append("°")
                                }
                            },
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Text(
                        "촬영 시작 시 현관에서 자동 설정됩니다",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                color = Color.White.copy(alpha = 0.22f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    if (state.anchorSet) "✓ 설정됨" else "미설정",
                    color = Color.White,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

private fun homeCardinalDirection(headingDeg: Float): String {
    val normalized = ((headingDeg % 360f) + 360f) % 360f
    val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return labels[((normalized + 22.5f) / 45f).toInt() % 8]
}

private fun normalizedHomeHeading(headingDeg: Float): Int =
    (((headingDeg % 360f) + 360f) % 360f).toInt()

/**
 * 40dp square thumbnail that either shows the entrance-door capture (when
 * the anchor has been set) or a placeholder door icon on a slightly darker
 * background (when unset). Kept small so it doesn't dwarf the text label
 * next to it in the banner.
 */
@Composable
private fun AnchorThumbnail(photoPath: String?, anchorSet: Boolean) {
    Box(
        Modifier
            .size(40.dp)
            .background(
                if (anchorSet) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.2f),
                RoundedCornerShape(8.dp)
            )
    ) {
        if (anchorSet && photoPath != null) {
            coil.compose.AsyncImage(
                model = photoPath,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            // Placeholder: door emoji stand-in when no photo is captured yet.
            Text(
                "🚪",
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun ProgressRing(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    val ratio = if (total > 0) done.toFloat() / total else 0f
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 9f
            val diameter = kotlin.math.min(size.width, size.height) - stroke
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f
            )
            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            drawArc(
                color = Color(0xFF69F0AE),
                startAngle = -90f,
                sweepAngle = 360f * ratio,
                useCenter = false,
                topLeft = topLeft,
                size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(ratio * 100).toInt()}%",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text("완료율", color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp)
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.width(48.dp))
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StartCaptureButton(enabled: Boolean, onClick: () -> Unit) {
    // The single high-emphasis field CTA. All surrounding information uses
    // the navy palette so this orange capture block remains unmistakable.
    val grad = if (enabled) {
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(Color(0xFFFF8A00), Color(0xFFF4511E))
        )
    } else {
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(Color(0xFFBDBDBD), Color(0xFF9E9E9E))
        )
    }
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = if (enabled) 10.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (enabled) 2.dp else 0.dp,
                color = if (enabled) Color(0xFFFFC46B) else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
    ) {
        Row(
            Modifier.fillMaxSize().background(grad).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.22f),
                shape = RoundedCornerShape(50)
            ) {
                Icon(
                    Icons.Filled.FlashOn, null, tint = Color.White,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (enabled) "새 하자 입력 (사진, 의견)" else "새 하자 입력 (앵커 먼저 촬영)",
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                Text(
                    if (enabled) "셔터 1회 → 2장 · 의견 · 방향·거리 자동 기록"
                    else "먼저 현관 앞에서 앵커 촬영을 진행하세요",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp,
                    maxLines = 1
                )
            }
            if (enabled) {
                Surface(
                    color = Color.White.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        "주요 작업",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

/**
 * Single-line row for the recent-defect list. Layout (matches mockup v1.0):
 *   [ #012 ]  [ ⌛ ]  주방발코니 > 벽 > 도장 > 흠집  07-19 15:39  [ 접수중 ]
 *
 * — sequence number leads, status icon after, path text ellipsizes to fit,
 * time is dim gray, status chip pinned to the right.
 */
@Composable
private fun DefectSingleRow(item: RecentDefect, onClick: () -> Unit) {
    val (barColor, chipBg, chipFg, icon) = when (item.status) {
        DefectStatus.DONE -> StatusStyle(Color(0xFF9E9E9E), Color(0xFFF5F5F5), Color(0xFF616161), Icons.Filled.CheckCircle)
        DefectStatus.PENDING -> StatusStyle(Pending, Color(0xFFFFE0B2), PrimaryDark, Icons.Filled.HourglassEmpty)
    }
    Surface(
        color = SurfaceColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            Modifier
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0.0f to barColor,
                        0.008f to barColor,
                        0.009f to Color.Transparent,
                        1.0f to Color.Transparent
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "#${"%03d".format(item.index)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = PrimaryDark,
                modifier = Modifier.width(38.dp)
            )
            Icon(icon, null, tint = barColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                item.pathLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                item.timeText,
                fontSize = 10.sp,
                color = TextSub,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(6.dp))
            Surface(color = chipBg, shape = RoundedCornerShape(8.dp)) {
                Text(
                    item.statusLabel,
                    color = chipFg, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private data class StatusStyle(
    val bar: Color,
    val chipBg: Color,
    val chipFg: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
private fun SessionPickerDialog(
    sessions: List<Session>,
    activeId: Long?,
    onDismiss: () -> Unit,
    onPick: (Session) -> Unit,
    onNew: () -> Unit,
    onDelete: (Session) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("호수 선택", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                sessions.forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(s) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            (if (s.id == activeId) "● " else "○ ") + s.unitLabel,
                            fontWeight = if (s.id == activeId) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onDelete(s) }) {
                            Text("삭제", color = Danger, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) {
                Icon(Icons.Filled.Add, null, tint = PrimaryDark)
                Text("새 호수 추가", color = PrimaryDark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Composable
private fun NewSessionDialog(
    onDismiss: () -> Unit,
    onCreate: (label: String, floorplanId: String) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val db = remember { com.axlife.pinset.vision.ReferenceDb(ctx) }
    val catalog = remember { db.catalog() }
    var label by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(db.defaultFloorplanId()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 호수 만들기") },
        text = {
            Column {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("호수 (예: 102동 1503호)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("평면도 선택", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                catalog.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { picked = entry.id }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            (if (picked == entry.id) "● " else "○ ") + entry.label,
                            fontSize = 13.sp,
                            fontWeight = if (picked == entry.id) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (entry.areaPyeong > 0) {
                            Text("${entry.areaPyeong}평", color = TextSub, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (label.isNotBlank()) onCreate(label.trim(), picked) }) {
                Text("만들기", color = Success, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

fun today(): String = SimpleDateFormat("yyyy.MM.dd (E)", Locale.KOREAN).format(Date())

data class RecentDefect(
    val id: Long,
    val index: Int,
    val pathLabel: String,
    val statusLabel: String,
    val status: DefectStatus,
    val timeText: String
)

data class HomeState(
    val activeSessionId: Long? = null,
    val activeSession: Session? = null,
    val sessions: List<Session> = emptyList(),
    val unitLabel: String = "…",
    val dateText: String = today(),
    val total: Int = 0,
    val pending: Int = 0,
    val done: Int = 0,
    val anchorSet: Boolean = false,
    val anchorHeadingDeg: Float? = null,
    /** Human-facing label of where the anchor was captured (e.g. "현관문",
     *  "거실"). Empty when unset. */
    val anchorLocationLabel: String = "현관문",
    /** Rough distance readout captured with the anchor (e.g. "1.2m"). */
    val anchorDistanceLabel: String? = null,
    /** Tilt (IMU pitch, deg) at the moment the anchor was captured. */
    val anchorPitchDeg: Float? = null,
    /** Path to the entrance-door photo (20x close-up preferred). Null when
     *  the session hasn't captured an anchor yet. */
    val anchorPhotoPath: String? = null,
    val defects: List<com.axlife.pinset.data.entity.Defect> = emptyList(),
    val recent: List<RecentDefect> = emptyList(),
    val floorplanBitmap: android.graphics.Bitmap? = null,
    val floorplanIsCustom: Boolean = false,
    val isClosing: Boolean = false
)
