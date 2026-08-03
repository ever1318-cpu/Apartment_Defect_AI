package com.axlife.pinset.ui.floorplan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.ui.Routes
import com.axlife.pinset.ui.pinset.ArrowPin
import com.axlife.pinset.ui.pinset.CroppedFloorplan
import com.axlife.pinset.ui.pinset.PlainFloorplan
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.vision.DefectCluster
import com.axlife.pinset.vision.clusterDefects

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloorplanScreen(nav: NavController) {
    val vm: FloorplanViewModel = viewModel(factory = FloorplanViewModel.Factory)
    val state by vm.state.collectAsState()
    var openCluster by remember { mutableStateOf<DefectCluster?>(null) }
    var naviPin by remember { mutableStateOf<Defect?>(null) }
    var showTrail by remember { mutableStateOf(true) }
    var showExpanded by remember { mutableStateOf(false) }
    val clusters = clusterDefects(state.defects)

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryDark,
                    titleContentColor = Color.White
                ),
                title = { Text("평면도 상세보기 · 총 ${state.defects.size}건", color = Color.White) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                state.floorplanBitmap?.let { bmp ->
                    FloorplanCanvas(
                        bitmap = bmp,
                        clusters = clusters,
                        showTrail = showTrail,
                        isCustom = state.customFloorplan,
                        session = state.session,
                        defects = state.defects,
                        onClusterTap = { c ->
                            if (c.count > 1) openCluster = c
                            else naviPin = c.lead
                        }
                    )
                } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.session != null) "평면도를 불러오세요 (우측 이미지 아이콘)"
                        else "평면도를 불러오는 중…"
                    )
                }
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    FilledIconButton(
                        onClick = { nav.navigate(Routes.CAMERA) },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White)
                    ) {
                        Icon(Icons.Filled.Add, null, tint = PrimaryDark)
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    FilledIconButton(
                        onClick = { showExpanded = true },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White)
                    ) {
                        Icon(Icons.Filled.ZoomIn, null, tint = PrimaryDark)
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.FilterChip(
                        selected = showTrail,
                        onClick = { showTrail = !showTrail },
                        label = { Text("이동 표시", fontSize = 11.sp) }
                    )
                    if (state.customFloorplan) {
                        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.TextButton(
                            onClick = { vm.clearCustomFloorplan() }
                        ) {
                            Text("기본 도면", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
    openCluster?.let { c ->
        AlertDialog(
            onDismissRequest = { openCluster = null },
            title = { Text("이 위치의 하자 ${c.count}건") },
            text = {
                Column {
                    c.members.forEach { d ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable {
                                    openCluster = null
                                    naviPin = d
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("#${d.defectIndex}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                            Text(
                                "${koType(d.defectType.name)} · ${koSev(d.severity)}",
                                modifier = Modifier.weight(1f)
                            )
                            Text("›", color = Color.Gray)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { openCluster = null }) { Text("닫기") }
            }
        )
    }
    naviPin?.let { pin ->
        PinNaviDialog(
            defect = pin,
            session = state.session,
            onDismiss = { naviPin = null },
            onOpenDetail = {
                naviPin = null
                nav.navigate(Routes.pinDetail(pin.id))
            }
        )
    }
    if (showExpanded) {
        state.floorplanBitmap?.let { bmp ->
            // Prefetch each defect's slot-A photo so the expanded editor can
            // show it synchronously. Small map — one entry per defect.
            val photoPaths = androidx.compose.runtime.produceState(
                initialValue = emptyMap<Long, String>(),
                key1 = state.defects
            ) {
                val map = mutableMapOf<Long, String>()
                state.defects.forEach { d ->
                    vm.primaryPhotoPath(d.id)?.let { map[d.id] = it }
                }
                value = map
            }
            FloorplanExpandedDialog(
                bitmap = bmp,
                session = state.session,
                defects = state.defects,
                isCustom = state.customFloorplan,
                photoForDefect = { id -> photoPaths.value[id] },
                onDismiss = { showExpanded = false },
                onUpdateDefect = { d -> vm.updateDefect(d) }
            )
        }
    }
}

@Composable
private fun FloorplanCanvas(
    bitmap: Bitmap,
    clusters: List<DefectCluster>,
    showTrail: Boolean,
    isCustom: Boolean,
    session: com.axlife.pinset.data.entity.Session?,
    defects: List<Defect>,
    onClusterTap: (DefectCluster) -> Unit
) {
    val pinsAndTrail: @Composable (androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp) -> Unit =
        { innerW, innerH ->
            if (showTrail) {
                NavigationTrail(
                    session = session,
                    defects = defects,
                    parentWidthDp = innerW,
                    parentHeightDp = innerH,
                    modifier = Modifier
                )
            } else {
                FloorplanAnchorOverlay(session = session)
            }
            clusters.forEach { c ->
                ArrowPin(
                    xNorm = c.xNorm,
                    yNorm = c.yNorm,
                    parentWidthDp = innerW,
                    parentHeightDp = innerH,
                    index = c.lead.defectIndex,
                    severity = c.lead.severity,
                    headingDeg = c.lead.imuHeadingDeg,
                    clusterCount = c.count,
                    onClick = { onClusterTap(c) }
                )
            }
        }
    // The detail view always uses the source image aspect ratio. The bundled
    // and custom floorplans must not be cropped or stretched differently.
    PlainFloorplan(bitmap = bitmap) { innerW, innerH -> pinsAndTrail(innerW, innerH) }
}

private fun koType(t: String) = when (t) {
    "CRACK" -> "균열"; "LEAK" -> "누수"; "FINISH" -> "마감 불량"; else -> "기타"
}
private fun koSev(s: Severity) = when (s) {
    Severity.MAJOR -> "중대"; Severity.NORMAL -> "보통"; Severity.MINOR -> "경미"
}
