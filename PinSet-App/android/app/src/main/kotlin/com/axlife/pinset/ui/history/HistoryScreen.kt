package com.axlife.pinset.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectStatus
import com.axlife.pinset.data.entity.DefectType
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.data.entity.SlotRole
import com.axlife.pinset.data.entity.Trade
import com.axlife.pinset.ui.Routes
import com.axlife.pinset.ui.theme.Danger
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.ui.theme.Success
import com.axlife.pinset.ui.theme.TextSub
import com.axlife.pinset.ui.theme.Warning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SortBy(val label: String) { INDEX("번호순"), SEVERITY("심각도순"), TRADE("공종순"), ROOM("방순"), TIME("최신순") }
enum class StatusFilter(val label: String) { ALL("전체"), PENDING("미확인"), DONE("완료") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavController) {
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
    val state by vm.state.collectAsState()
    var sortBy by remember { mutableStateOf(SortBy.INDEX) }
    var statusFilter by remember { mutableStateOf(StatusFilter.ALL) }
    var severityFilter by remember { mutableStateOf<Severity?>(null) }

    val filtered = state.defects
        .filter { statusFilter == StatusFilter.ALL || it.status == statusFilter.toEntity() }
        .filter { severityFilter == null || it.severity == severityFilter }
        .let { list -> when (sortBy) {
            SortBy.INDEX    -> list.sortedByDescending { it.defectIndex }
            SortBy.SEVERITY -> list.sortedByDescending { it.severity.rank() }
            SortBy.TRADE    -> list.sortedBy { it.trade.name }
            SortBy.ROOM     -> list.sortedBy { it.roomLabel }
            SortBy.TIME     -> list.sortedByDescending { it.createdAt }
        } }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryDark, titleContentColor = Color.White
                ),
                title = { Text("하자 목록 (전체 ${state.defects.size}건)", color = Color.White, fontSize = 16.sp) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterBar(
                sortBy = sortBy, onSort = { sortBy = it },
                status = statusFilter, onStatus = { statusFilter = it },
                sev = severityFilter, onSev = { severityFilter = it }
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "조건에 맞는 하자가 없습니다.",
                            color = TextSub, fontSize = 13.sp,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
                items(filtered) { d ->
                    DefectRow(d, onToggle = { vm.toggleStatus(d) }, onClick = { nav.navigate(Routes.pinDetail(d.id)) })
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    sortBy: SortBy, onSort: (SortBy) -> Unit,
    status: StatusFilter, onStatus: (StatusFilter) -> Unit,
    sev: Severity?, onSev: (Severity?) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            StatusFilter.values().forEach { s ->
                Chip(s.label, status == s, PrimaryDark) { onStatus(s) }
            }
            Spacer(Modifier.width(6.dp))
            Text("|", color = TextSub, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.width(6.dp))
            Chip("중대", sev == Severity.MAJOR, Danger) { onSev(if (sev == Severity.MAJOR) null else Severity.MAJOR) }
            Chip("보통", sev == Severity.NORMAL, Warning) { onSev(if (sev == Severity.NORMAL) null else Severity.NORMAL) }
            Chip("경미", sev == Severity.MINOR, Success) { onSev(if (sev == Severity.MINOR) null else Severity.MINOR) }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            Text("정렬:", color = TextSub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            SortBy.values().forEach { s ->
                Chip(s.label, sortBy == s, PrimaryDark) { onSort(s) }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tint: Color, onClick: () -> Unit) {
    Surface(
        color = if (selected) tint else Color(0xFFECEFF1),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            color = if (selected) Color.White else Color.DarkGray,
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun DefectRow(d: Defect, onToggle: () -> Unit, onClick: () -> Unit) {
    // Single-line row — half the previous vertical padding so the list is
    // dense enough to scan a few dozen defects at a glance.
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (d.status == DefectStatus.DONE) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (d.status == DefectStatus.DONE) Success else TextSub,
            modifier = Modifier
                .padding(end = 6.dp)
                .clickable { onToggle() }
        )
        Text(
            "#${d.defectIndex}",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.width(32.dp)
        )
        // Everything condensed into a single line: type · trade · room · areaDetail · date.
        Text(
            buildString {
                append(koType(d.defectType)); append(" · ")
                append(koTrade(d.trade)); append(" · ")
                append(d.roomLabel)
                d.areaDetail.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
                append("  ")
                append(SimpleDateFormat("MM/dd", Locale.KOREAN).format(Date(d.createdAt)))
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Surface(color = severityColor(d.severity), shape = RoundedCornerShape(6.dp)) {
            Text(
                koSeverity(d.severity),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

private fun StatusFilter.toEntity() = when (this) {
    StatusFilter.PENDING -> DefectStatus.PENDING
    StatusFilter.DONE -> DefectStatus.DONE
    StatusFilter.ALL -> null
}
private fun Severity.rank() = when (this) { Severity.MAJOR -> 3; Severity.NORMAL -> 2; Severity.MINOR -> 1 }
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
