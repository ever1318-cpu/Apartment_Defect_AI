package com.axlife.pinset.ui.history

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectStatus
import com.axlife.pinset.data.entity.DefectType
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.data.entity.Trade
import com.axlife.pinset.ui.theme.Danger
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.ui.theme.Success
import com.axlife.pinset.ui.theme.TextSub
import com.axlife.pinset.ui.theme.Warning
import com.axlife.pinset.ui.home.HomeMiniFloorplan
import com.axlife.pinset.ui.Routes
import com.axlife.pinset.InspectionAccessMode
import com.axlife.pinset.PinSetApplication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(nav: NavController) {
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PinSetApplication
    var confirmFinish by remember { mutableStateOf(false) }
    var completionMessage by remember { mutableStateOf<String?>(null) }
    var closeBlockedMessage by remember { mutableStateOf<String?>(null) }

    val total = state.defects.size
    val pending = state.defects.count { it.status == DefectStatus.PENDING }
    val done = state.defects.count { it.status == DefectStatus.DONE }
    val text = buildReportText(state.defects, total, pending, done)

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryDark, titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = { Text("보고서", color = Color.White, fontSize = 16.sp) },
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "하자체크 보고서")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        ctx.startActivity(Intent.createChooser(intent, "보고서 공유"))
                    }) { Icon(Icons.Filled.Share, null) }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFF0F4F8))
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = PrimaryDark)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        SimpleDateFormat("yyyy.MM.dd", Locale.KOREAN).format(Date()),
                        color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp
                    )
                    Text("하자 점검 요약", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("전체", "$total")
                        Divider()
                        StatCell("미확인", "$pending")
                        Divider()
                        StatCell("완료", "$done")
                    }
                }
            }
            if (state.session != null && state.floorplanBitmap != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "최종 평면도 · 누적 하자 핀 ${total}건",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        HomeMiniFloorplan(
                            session = state.session,
                            bitmap = state.floorplanBitmap,
                            isCustom = state.floorplanIsCustom,
                            defects = state.defects,
                            liveXNorm = null,
                            liveYNorm = null,
                            modifier = Modifier.fillMaxWidth().height(210.dp)
                        )
                    }
                }
            }
            CompactStatistics(state.defects)
            Text("전체 목록", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            state.defects.sortedByDescending { it.defectIndex }.forEach { d ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { nav.navigate(Routes.pinDetail(d.id)) }
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row {
                            Text("#${d.defectIndex}", fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                            Text(
                                "${koType(d.defectType)} · ${koTrade(d.trade)} · ${d.roomLabel}",
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), fontSize = 13.sp
                            )
                            Surface(color = severityColor(d.severity), shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    koSeverity(d.severity), color = Color.White, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (d.areaDetail.isNotBlank() || d.note.isNotBlank()) {
                            Text(
                                buildString {
                                    if (d.areaDetail.isNotBlank()) append(d.areaDetail)
                                    if (d.note.isNotBlank()) { if (isNotEmpty()) append(" · "); append(d.note) }
                                },
                                fontSize = 11.sp, color = TextSub
                            )
                        }
                        Text(
                            "눌러서 상세 확인·수정",
                            fontSize = 10.sp,
                            color = PrimaryDark,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { nav.navigate(Routes.HOME) { launchSingleTop = true } },
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                ) { Text("계속 점검", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = {
                        vm.finishInspection(
                            onFinished = { message -> completionMessage = message },
                            onBlocked = { message -> closeBlockedMessage = message }
                        )
                    },
                    enabled = total > 0 && !state.isClosing &&
                        state.session?.done != true &&
                        app.inspectionAccessMode != InspectionAccessMode.DEMO,
                    modifier = Modifier.weight(1.2f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Success)
                ) { Text("전송 및 마감", fontWeight = FontWeight.Bold) }
            }
            Button(
                onClick = { confirmFinish = true },
                enabled = total > 0 && !state.isClosing &&
                    state.session?.done != true &&
                    app.inspectionAccessMode != InspectionAccessMode.DEMO,
                modifier = Modifier.fillMaxWidth().height(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Success)
            ) {
                Text(
                    when {
                        state.session?.done == true -> "점검 마감 완료"
                        app.inspectionAccessMode == InspectionAccessMode.DEMO -> "DEMO 모드 · 마감하려면 다시 로그인"
                        else -> "전체 확인 후 세대 점검 마감"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("세대 점검을 마감할까요?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "전체 ${total}건 · 미확인 ${pending}건 · 완료 ${done}건\n" +
                        "평면도 누적 핀과 전체 목록을 최종 확인했습니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    vm.finishInspection(
                        onFinished = { message -> completionMessage = message },
                        onBlocked = { message -> closeBlockedMessage = message }
                    )
                }) { Text("전송 및 마감", color = Success, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) { Text("계속 점검") }
            }
        )
    }

    completionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("세대 점검 마감 완료", fontWeight = FontWeight.Bold) },
            text = { Text(message + "\n다음 동·호수 초기 화면으로 이동합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    completionMessage = null
                    nav.navigate(Routes.INTRO) { popUpTo(Routes.HOME) { inclusive = true } }
                }) { Text("다음 세대 시작", color = Success, fontWeight = FontWeight.Bold) }
            }
        )
    }
    closeBlockedMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { closeBlockedMessage = null },
            title = { Text("전송 상태 확인", fontWeight = FontWeight.Bold) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { closeBlockedMessage = null }) { Text("확인") }
            }
        )
    }
}

@Composable
private fun StatCell(label: String, num: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        Text(num, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
    }
}
@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(40.dp).background(Color.White.copy(alpha = 0.25f)))
}

@Composable
private fun CompactStatistics(defects: List<Defect>) {
    val severity = listOf(Severity.MAJOR, Severity.NORMAL, Severity.MINOR)
        .joinToString(" · ") { "${koSeverity(it)} ${defects.count { d -> d.severity == it }}" }
    val trades = Trade.values().mapNotNull { trade ->
        defects.count { it.trade == trade }.takeIf { it > 0 }?.let { count ->
            val label = if (trade == Trade.OTHER) "\u00a0\u00a0${koTrade(trade)}" else koTrade(trade)
            "$label $count"
        }
    }.joinToString(" · ").ifBlank { "없음" }
    val rooms = defects.groupingBy { it.roomLabel }.eachCount()
        .entries.sortedByDescending { it.value }
        .joinToString(" · ") { "${it.key} ${it.value}" }.ifBlank { "없음" }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactStatColumn("심각도", severity, Modifier.weight(1f))
            CompactStatColumn("공종", trades, Modifier.weight(1f))
            CompactStatColumn("방", rooms, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompactStatColumn(title: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(
            title,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = PrimaryDark,
        )
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private fun buildReportText(defects: List<Defect>, total: Int, pending: Int, done: Int): String = buildString {
    appendLine("[하자체크 보고서]")
    appendLine(SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREAN).format(Date()))
    appendLine()
    appendLine("전체 ${total}건 · 미확인 ${pending}건 · 완료 ${done}건")
    appendLine()
    defects.sortedByDescending { it.defectIndex }.forEach { d ->
        appendLine("#${d.defectIndex} ${koType(d.defectType)} [${koSeverity(d.severity)}] " +
            "${koTrade(d.trade)} · ${d.roomLabel}" +
            (if (d.areaDetail.isNotBlank()) " · ${d.areaDetail}" else "") +
            (if (d.note.isNotBlank()) " · ${d.note}" else ""))
    }
}

private fun koType(t: DefectType) = when (t) {
    DefectType.CRACK -> "균열"; DefectType.LEAK -> "누수"
    DefectType.FINISH -> "마감 불량"; DefectType.OTHER -> "기타"
}
private fun koSeverity(s: Severity) = when (s) {
    Severity.MAJOR -> "중대"; Severity.NORMAL -> "보통"; Severity.MINOR -> "경미"
}
private fun koTrade(t: Trade) = when (t) {
    Trade.WALL -> "벽체"; Trade.WALLPAPER -> "도배"; Trade.TILE -> "타일"
    Trade.FLOOR -> "바닥"; Trade.WINDOW -> "창호"; Trade.ELECTRIC -> "전기"
    Trade.PLUMBING -> "배관"; Trade.OTHER -> "기타"
}
private fun severityColor(s: Severity) = when (s) {
    Severity.MAJOR -> Danger; Severity.NORMAL -> Warning; Severity.MINOR -> Success
}
