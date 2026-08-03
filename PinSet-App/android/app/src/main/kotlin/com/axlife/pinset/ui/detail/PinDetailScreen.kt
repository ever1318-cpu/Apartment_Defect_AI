package com.axlife.pinset.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.axlife.pinset.data.entity.Defect
import com.axlife.pinset.data.entity.DefectPhoto
import com.axlife.pinset.data.entity.DefectType
import com.axlife.pinset.data.entity.Lens
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.data.entity.SlotRole
import com.axlife.pinset.data.entity.Surface
import com.axlife.pinset.data.entity.SyncState
import com.axlife.pinset.ui.PhotoCarouselDialog
import com.axlife.pinset.ui.pinset.ArrowPin
import com.axlife.pinset.ui.pinset.CroppedFloorplan
import com.axlife.pinset.ui.pinset.PinTagSheet
import com.axlife.pinset.ui.pinset.PlainFloorplan
import com.axlife.pinset.ui.pinset.TagSubmission
import com.axlife.pinset.ui.pinset.cleanRecommendation
import com.axlife.pinset.ui.theme.Danger
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.ui.theme.Secondary
import com.axlife.pinset.ui.theme.Success
import com.axlife.pinset.ui.theme.Warning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinDetailScreen(nav: NavController, defectId: Long) {
    val vm: PinDetailViewModel = viewModel(
        key = "pin-detail-$defectId",
        factory = PinDetailViewModel.Factory(defectId)
    )
    val state by vm.state.collectAsState()

    var carouselIdx by remember { mutableStateOf<Int?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var finalConfirmMode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    var floorplanBmp by remember { mutableStateOf<Bitmap?>(null) }
    var floorplanCustom by remember { mutableStateOf(false) }
    LaunchedEffect(state.defect?.sessionId) {
        val db = com.axlife.pinset.vision.ReferenceDb(ctx)
        val app = ctx.applicationContext as com.axlife.pinset.PinSetApplication
        val sessionId = state.defect?.sessionId ?: return@LaunchedEffect
        val session = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.database.sessionDao().getById(sessionId)
        }
        val custom = session?.customFloorplanPath
        val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (custom != null && java.io.File(custom).exists()) {
                runCatching { BitmapFactory.decodeFile(custom) }.getOrNull()
            } else {
                val assetId = session?.floorplanAssetId ?: db.defaultFloorplanId()
                runCatching {
                    val meta = db.floorplan(assetId)
                    db.loadFloorplanBitmap(assetId, meta)
                }.getOrNull()
            }
        }
        floorplanBmp = bmp
        floorplanCustom = custom != null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryDark,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                title = { Text("하자 상세 정보", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        val defect = state.defect
        if (defect == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("불러오는 중…")
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFF0F4F8))
                .padding(padding)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PhotoStrip(state.photos) { idx -> carouselIdx = idx }
            state.sync?.let { sync ->
                val (label, color) = when (sync.state) {
                    SyncState.COMPLETED -> "서버 전송 완료" to Color(0xFF067647)
                    SyncState.UPLOADING -> "서버 전송 중" to Color(0xFF155EEF)
                    SyncState.CONFLICT -> "서버 확인 필요" to Color(0xFFB42318)
                    SyncState.RETRY -> "로컬 저장됨 · 통신 회복 후 재전송" to Color(0xFFA15C00)
                    else -> "로컬 저장됨 · 전송 대기" to Color(0xFFA15C00)
                }
                Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, color = color, fontWeight = FontWeight.Bold)
                            sync.lastError?.let {
                                Text(it, color = color, fontSize = 11.sp, maxLines = 2)
                            }
                        }
                        if (sync.state == SyncState.RETRY || sync.state == SyncState.CONFLICT) {
                            TextButton(onClick = vm::retrySync) { Text("다시 전송") }
                        }
                    }
                }
            }

            floorplanBmp?.let { bmp ->
                MiniMap(defect, bmp, isCustom = floorplanCustom, onFlagTap = { showEdit = true })
            }

            // v1.2 detail card — only the 3 fields the user really cares
            // about: what the resident said, what the AI thought, and the
            // final catalog path (shown as a reference).
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(10.dp)) {
                    DetailRow(
                        label = "입주민 의견 RAW",
                        value = defect.residentOpinion.ifBlank { "(입력 없음)" }
                    )
                    DetailRow(
                        label = "AI 분석",
                        value = if (defect.aiPathText.isBlank()) "(분석 결과 없음)"
                            else "${defect.aiPathText}  (${(defect.aiConfidence * 100).toInt()}%)"
                    )
                    DetailRow(
                        label = "최종 분류",
                        value = defect.finalPathText
                            .takeIf { it.isNotBlank() }
                            ?.let(::cleanRecommendation)
                            ?: "(미확정)"
                    )
                    if (defect.areaDetail.isNotBlank()) {
                        DetailRow(label = "상세부위 현장 메모", value = defect.areaDetail)
                    }
                }
            }
            val memoPhotos = state.photos.filter { it.filePath.contains("memo_photos") }
            if (memoPhotos.isNotEmpty() || defect.residentOpinion.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("하자의견 및 메모", color = PrimaryDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (defect.residentOpinion.isNotBlank()) Text(defect.residentOpinion, fontSize = 12.sp, maxLines = 4)
                        if (memoPhotos.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(memoPhotos) { photo ->
                                    AsyncImage(model = photo.filePath, contentDescription = "스티커/메모 사진", contentScale = ContentScale.Crop,
                                        modifier = Modifier.width(96.dp).height(64.dp).background(Color.LightGray, RoundedCornerShape(7.dp)))
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { finalConfirmMode = false; showEdit = true },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                    modifier = Modifier.weight(1f).height(40.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                ) { Text("수정", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                Button(
                    onClick = {
                        val app = ctx.applicationContext as com.axlife.pinset.PinSetApplication
                        app.pendingAdditionalPhotoDefectId = defect.id
                        nav.navigate(com.axlife.pinset.ui.Routes.CAMERA)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Secondary),
                    modifier = Modifier.weight(1.6f).height(40.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                ) { Text("수정/추가 사진", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                Button(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                    modifier = Modifier.weight(1f).height(40.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                ) { Text("삭제", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
            Button(
                onClick = { finalConfirmMode = true; showEdit = true },
                colors = ButtonDefaults.buttonColors(containerColor = Success),
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Text(
                    if (defect.status == com.axlife.pinset.data.entity.DefectStatus.DONE) {
                        "최종확인 내용 수정"
                    } else {
                        "최종확인 입력"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showEdit && state.defect != null) {
        val d = state.defect!!
        PinTagSheet(
            title = if (finalConfirmMode) "하자 최종확인 입력" else "하자 정보 수정",
            submitLabel = if (finalConfirmMode) "최종확인 & 저장" else "수정 저장",
            showFinalize = true,
            initial = TagSubmission(
                type = d.defectType, severity = d.severity, trade = d.trade,
                surface = d.surface, areaDetail = d.areaDetail, note = d.note,
                finalize = d.status == com.axlife.pinset.data.entity.DefectStatus.DONE,
                residentOpinion = d.residentOpinion,
                aiPathText = d.aiPathText,
                aiConfidence = d.aiConfidence,
                finalPathText = d.finalPathText,
                memoPhotoPath = state.photos.firstOrNull { it.filePath.contains("memo_photos") }?.filePath.orEmpty()
            ),
            aiTextSuggest = { opinion ->
                if (opinion.isBlank()) null
                else {
                    val stub = com.axlife.pinset.vision.RuleBasedAiClassifier()
                    val out = stub.classify(
                        com.axlife.pinset.vision.AiInput(
                            roomLabel = d.roomLabel,
                            surface = d.surface,
                            residentOpinion = opinion,
                            focusDistanceM = d.focusDistanceM,
                            headingDeg = d.imuHeadingDeg,
                            pitchDeg = d.imuPitchDeg
                        )
                    )
                    com.axlife.pinset.ui.pinset.AiSuggestionUi(out.pathText, out.confidence, out.rationale)
                }
            },
            photoAiPath = d.aiPathText,
            photoAiConfidence = d.aiConfidence,
            defectPhotoPath = state.photos.firstOrNull { it.slot == SlotRole.A }?.filePath.orEmpty(),
            widePhotoPath = state.photos.firstOrNull { it.slot == SlotRole.B }?.filePath.orEmpty(),
            onDismiss = { showEdit = false; finalConfirmMode = false },
            onSubmit = { sub ->
                val confirmAsDone = finalConfirmMode
                showEdit = false
                finalConfirmMode = false
                val effectiveFinal = sub.finalPathText.takeIf { it.isNotBlank() } ?: sub.aiPathText
                vm.update(
                    d.copy(
                        defectType = sub.type,
                        severity = sub.severity,
                        trade = sub.trade,
                        surface = sub.surface,
                        areaDetail = sub.areaDetail,
                        note = sub.note,
                        residentOpinion = sub.residentOpinion,
                        aiPathText = sub.aiPathText,
                        aiConfidence = sub.aiConfidence,
                        finalPathText = effectiveFinal,
                        status = if (sub.finalize || confirmAsDone)
                            com.axlife.pinset.data.entity.DefectStatus.DONE
                        else
                            com.axlife.pinset.data.entity.DefectStatus.PENDING
                    )
                )
                if (confirmAsDone) {
                    nav.navigate(com.axlife.pinset.ui.Routes.HOME) {
                        popUpTo(com.axlife.pinset.ui.Routes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        )
    }

    if (confirmDelete && state.defect != null) {
        val d = state.defect!!
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("정말 삭제할까요?") },
            text = { Text("#${d.defectIndex} · ${koType(d.defectType)} / ${d.roomLabel}\n\n이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete { nav.popBackStack() } }) {
                    Text("삭제", color = Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } }
        )
    }

    val idx = carouselIdx
    if (idx != null) {
        PhotoCarouselDialog(
            items = orderedBySlot(state.photos).map { p ->
                val label = when (p.lens) {
                    Lens.ULTRA -> "초광각"
                    Lens.TELE -> "망원"
                    Lens.MAIN -> "광각"
                }
                com.axlife.pinset.ui.CarouselItem(
                    filePath = p.filePath,
                    caption = "$label · ${formatX(p.zoomRatio)}x" + if (p.isDigital) " (디지털)" else ""
                )
            },
            initialIndex = idx,
            onDismiss = { carouselIdx = null }
        )
    }
}

@Composable
private fun MiniMap(defect: Defect, bitmap: Bitmap, isCustom: Boolean, onFlagTap: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(8.dp)) {
            Text(
                "📍 평면도 위치 · ${defect.roomLabel}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            val pin: @Composable (androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp) -> Unit =
                { innerW, innerH ->
                    ArrowPin(
                        xNorm = defect.xNorm,
                        yNorm = defect.yNorm,
                        parentWidthDp = innerW,
                        parentHeightDp = innerH,
                        index = defect.defectIndex,
                        severity = defect.severity,
                        headingDeg = defect.imuHeadingDeg,
                        current = true,
                        onClick = onFlagTap
                    )
                }
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                PlainFloorplan(
                    bitmap = bitmap,
                    modifier = Modifier
                        .fillMaxWidth(0.68f)
                        .clickable { onFlagTap() }
                ) { w, h -> pin(w, h) }
            }
        }
    }
}

@Composable
private fun PhotoStrip(photos: List<DefectPhoto>, onTap: (Int) -> Unit) {
    val ordered = orderedBySlot(photos)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
    ) {
        if (ordered.isEmpty()) {
            item {
                Surface(
                    color = Color(0xFFE8EEF3),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.width(112.dp).height(84.dp)
                ) {
                    Text(
                        "가상 하자\n사진 없음",
                        color = Color(0xFF546E7A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
        items(ordered) { p ->
            val (label, tint) = when (p.slot) {
                SlotRole.A -> "기본" to PrimaryDark
                SlotRole.B -> "중간" to Secondary
                SlotRole.C -> if (p.filePath.contains("memo_photos")) "스티커/메모" to Warning else "광역" to Warning
            }
            val idx = ordered.indexOf(p)
            Box(
                Modifier
                    .width(112.dp)
                    .height(84.dp)
                    .background(Color.Black, RoundedCornerShape(10.dp))
                    .clickable { onTap(idx) }
            ) {
                AsyncImage(
                    model = p.filePath,
                    contentDescription = label,
                    contentScale = if (p.isDigital) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(color = tint, shape = RoundedCornerShape(bottomEnd = 10.dp, topStart = 10.dp)) {
                    Text(
                        "$label · ${formatX(p.zoomRatio)}x" + if (p.isDigital) " *" else "",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private fun formatX(v: Float): String {
    val i = v.toInt()
    return if (kotlin.math.abs(v - i) < 0.05f) "$i" else String.format("%.1f", v)
}
private fun orderedBySlot(photos: List<DefectPhoto>): List<DefectPhoto> {
    val order = listOf(SlotRole.A, SlotRole.B, SlotRole.C)
    return order.flatMap { role -> photos.filter { it.slot == role } }
}

@Composable
private fun DetailRow(label: String, value: String, badgeColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.Gray, modifier = Modifier.weight(1f), fontSize = 12.sp)
        if (badgeColor != null) {
            Surface(color = badgeColor, shape = RoundedCornerShape(10.dp)) {
                Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp))
            }
        } else {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

private fun koType(t: DefectType) = when (t) {
    DefectType.CRACK -> "균열"; DefectType.LEAK -> "누수"; DefectType.FINISH -> "마감 불량"; DefectType.OTHER -> "기타"
}
private fun koSeverity(s: Severity) = when (s) {
    Severity.MINOR -> "경미"; Severity.NORMAL -> "보통"; Severity.MAJOR -> "중대"
}
private fun koSurface(s: Surface) = when (s) {
    Surface.CEILING -> "천장"; Surface.WALL -> "벽"; Surface.FLOOR -> "바닥"
}
private fun severityColor(s: Severity): Color = when (s) {
    Severity.MINOR -> Success; Severity.NORMAL -> Warning; Severity.MAJOR -> Danger
}
