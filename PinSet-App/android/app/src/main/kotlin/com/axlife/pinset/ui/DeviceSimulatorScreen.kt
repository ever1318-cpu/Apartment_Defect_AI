package com.axlife.pinset.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.ai.UrlConnectionAiTransport
import com.axlife.pinset.data.FieldEndpointPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private data class DeviceRunSnapshot(
    val loading: Boolean = true,
    val reachable: Boolean = false,
    val endpoint: String = "",
    val household: String = "확인 중",
    val defectCount: Int = 0,
    val unsentCount: Int = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSimulatorScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as PinSetApplication
    var refreshKey by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(DeviceRunSnapshot()) }
    var stage by remember { mutableIntStateOf(0) }
    val stages = listOf("초기환경", "하자사진", "하자의견", "로컬저장·전송")

    LaunchedEffect(refreshKey) {
        snapshot = snapshot.copy(loading = true)
        snapshot = withContext(Dispatchers.IO) {
            val endpoint = FieldEndpointPrefs.load(context)
            val reachable = endpoint.isNotBlank() && runCatching {
                UrlConnectionAiTransport(connectTimeoutMs = 3_000, readTimeoutMs = 4_000)
                    .request("GET", "$endpoint/health", emptyMap(), null).status in 200..299
            }.getOrDefault(false)
            val session = app.repository.activeSession(context)
            val defects = app.repository.observeDefects(session.id).first()
            DeviceRunSnapshot(
                loading = false, reachable = reachable, endpoint = endpoint,
                household = session.unitLabel, defectCount = defects.size,
                unsentCount = app.database.syncQueueDao().countOutstanding()
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("S25 실행상태 시뮬레이터", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "뒤로") } },
                actions = { IconButton(onClick = { refreshKey++ }) { Icon(Icons.Filled.Refresh, "상태 새로고침") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(color = Color(0xFFEAF3FB), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("실행 기기", color = Color(0xFF315B7D), fontSize = 12.sp)
                    Text("${Build.MANUFACTURER} ${Build.MODEL}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}", fontSize = 12.sp)
                }
            }
            Text("현장 업무 흐름", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                stages.forEachIndexed { index, label ->
                    if (index == stage) Button(onClick = { stage = index }, modifier = Modifier.weight(1f)) {
                        Text("${index + 1} $label", fontSize = 10.sp, maxLines = 1)
                    } else OutlinedButton(onClick = { stage = index }, modifier = Modifier.weight(1f)) {
                        Text("${index + 1} $label", fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            Text("현재 시뮬레이션: ${stages[stage]} · 단계 버튼은 실제 저장값을 변경하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("실제 실행 상태", fontWeight = FontWeight.Bold)
                    StatusRow("현재 세대", snapshot.household)
                    StatusRow("등록 하자", "${snapshot.defectCount}건")
                    StatusRow("로컬 미전송", "${snapshot.unsentCount}건")
                    StatusRow("VPN/서버", when {
                        snapshot.loading -> "확인 중"
                        snapshot.endpoint.isBlank() -> "서버 주소 없음"
                        snapshot.reachable -> "연결됨"
                        else -> "미연결 · 로컬 저장 유지"
                    })
                    if (snapshot.endpoint.isNotBlank()) Text(snapshot.endpoint, fontSize = 11.sp, color = Color(0xFF5B6B78), maxLines = 1)
                }
            }
            Button(onClick = { refreshKey++ }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("실제 상태 새로고침")
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold)
    }
}