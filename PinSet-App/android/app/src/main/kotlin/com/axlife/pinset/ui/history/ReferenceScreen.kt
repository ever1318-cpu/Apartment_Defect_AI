package com.axlife.pinset.ui.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.navigation.NavController
import com.axlife.pinset.PinSetApplication
import com.axlife.pinset.data.entity.Session
import com.axlife.pinset.ui.theme.Anchor
import com.axlife.pinset.ui.theme.Danger
import com.axlife.pinset.ui.theme.Primary
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.ui.theme.PrimaryLight
import com.axlife.pinset.ui.theme.TextSub
import com.axlife.pinset.vision.FloorplanRoomAnchor
import com.axlife.pinset.vision.ReferenceDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceScreen(nav: NavController) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PinSetApplication
    val db = remember { ReferenceDb(app) }
    var rooms by remember { mutableStateOf<List<FloorplanRoomAnchor>>(emptyList()) }
    var session by remember { mutableStateOf<Session?>(null) }
    var floorplanBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var refresh by remember { mutableStateOf(0) }

    val aiSnapshot by com.axlife.pinset.data.AiPrefs.observe(ctx)
        .collectAsState(initial = com.axlife.pinset.data.AiPrefs.Snapshot(false, "", "gemini-2.0-flash-exp"))

    LaunchedEffect(refresh) {
        val s = app.repository.activeSession(app)
        session = s
        val (roomList, bmp) = withContext(Dispatchers.IO) {
            val meta = runCatching { db.floorplan(s.floorplanAssetId) }.getOrNull()
            val loaded = when {
                s.customFloorplanPath != null && File(s.customFloorplanPath).exists() ->
                    runCatching { BitmapFactory.decodeFile(s.customFloorplanPath) }.getOrNull()
                meta != null ->
                    runCatching { db.loadFloorplanBitmap(s.floorplanAssetId, meta) }.getOrNull()
                else -> null
            }
            (meta?.rooms.orEmpty()) to loaded
        }
        rooms = roomList
        floorplanBitmap = bmp
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryDark, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                title = { Text("참조 사진 관리", color = Color.White, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ============ 1순위: 동 호수 평면도 ============
            // Sits above every room card because the whole matching pipeline
            // hinges on which floorplan is registered for this unit.
            UnitFloorplanCard(
                session = session,
                bitmap = floorplanBitmap,
                onPicked = { uri ->
                    val scope = MainScope()
                    scope.launch {
                        val savedPath = withContext(Dispatchers.IO) {
                            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: return@withContext null
                            val dir = File(ctx.filesDir, "floorplans").apply { mkdirs() }
                            val dst = File(dir, "unit_${session?.id ?: "x"}_${System.currentTimeMillis()}.jpg")
                            dst.writeBytes(bytes)
                            dst.absolutePath
                        }
                        val id = session?.id ?: return@launch
                        if (savedPath != null) {
                            app.repository.setCustomFloorplan(id, savedPath)
                            refresh++
                            db.invalidateIndex()
                        }
                    }
                },
                onClearCustom = {
                    val id = session?.id ?: return@UnitFloorplanCard
                    MainScope().launch {
                        app.repository.setCustomFloorplan(id, null)
                        refresh++
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
            AiSettingsCard(
                snapshot = aiSnapshot,
                onSave = { enabled, key, model ->
                    kotlinx.coroutines.MainScope().launch {
                        com.axlife.pinset.data.AiPrefs.save(ctx, enabled, key, model)
                        val msg = if (enabled && key.isNotBlank())
                            "AI 설정 저장됨 (활성)"
                        else if (enabled)
                            "AI 활성이지만 API 키 없음"
                        else
                            "AI 설정 저장됨 (비활성)"
                        android.widget.Toast.makeText(
                            ctx, msg, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "각 방을 3~5장 촬영·업로드해두면 촬영 후 자동 위치 매칭 정확도가 향상됩니다.",
                fontSize = 12.sp, color = TextSub
            )
            Spacer(Modifier.height(8.dp))
            rooms.forEach { room ->
                key(room.id, refresh) {
                    RoomReferenceCard(ctx, room, onChanged = { refresh++; db.invalidateIndex() })
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 1순위 card: shows the currently-registered floorplan for this session (동
 * 호수) plus a picker to swap it for a user-supplied image. Small "기본 도면"
 * badge when the built-in asset is in use, "커스텀 · 되돌리기" when a custom
 * image has been uploaded.
 */
@Composable
private fun UnitFloorplanCard(
    session: Session?,
    bitmap: Bitmap?,
    onPicked: (android.net.Uri) -> Unit,
    onClearCustom: () -> Unit
) {
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onPicked) }

    val isCustom = session?.customFloorplanPath != null
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Rank-1 marker so it's visually obvious this is the top slot.
                Surface(color = Primary, shape = RoundedCornerShape(50)) {
                    Icon(
                        Icons.Filled.Star, null, tint = Color.White,
                        modifier = Modifier.padding(4.dp).width(16.dp).height(16.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "동 호수 평면도",
                        fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                        color = PrimaryDark
                    )
                    Text(
                        session?.unitLabel ?: "—",
                        fontSize = 11.sp, color = TextSub
                    )
                }
                Surface(
                    color = if (isCustom) Anchor else PrimaryLight,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (isCustom) "커스텀" else "기본 도면",
                        color = if (isCustom) Color.White else PrimaryDark,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(Color(0xFFF5F5F5), RoundedCornerShape(10.dp))
                    .clickable { picker.launch("image/*") }
            ) {
                bitmap?.let { b ->
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (bitmap == null) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.Image, null, tint = TextSub)
                        Spacer(Modifier.height(4.dp))
                        Text("탭하여 평면도 이미지 선택", color = TextSub, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(
                    label = if (isCustom) "다른 도면으로 교체" else "갤러리에서 업로드",
                    tint = Primary,
                    onClick = { picker.launch("image/*") }
                )
                if (isCustom) {
                    ActionChip(
                        label = "기본 도면 복원",
                        tint = TextSub,
                        onClick = onClearCustom
                    )
                }
            }
        }
    }
}

/**
 * Settings card for the Gemini Vision classifier. Everything the user needs
 * to opt in: enable switch + API key text field + model dropdown, plus a
 * short "get a key here" hint that opens a browser tab.
 */
@Composable
private fun AiSettingsCard(
    snapshot: com.axlife.pinset.data.AiPrefs.Snapshot,
    onSave: (enabled: Boolean, key: String, model: String) -> Unit
) {
    var enabled by remember(snapshot) { mutableStateOf(snapshot.enabled) }
    var apiKey by remember(snapshot) { mutableStateOf(snapshot.apiKey) }
    var model by remember(snapshot) { mutableStateOf(snapshot.model) }
    var showKey by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Surface(
                    color = Primary, shape = RoundedCornerShape(50)
                ) {
                    Text(
                        "🤖",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "AI 하자분류 (Gemini Vision)",
                        fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                        color = PrimaryDark
                    )
                    Text(
                        if (enabled && apiKey.isNotBlank())
                            "활성화됨 · 촬영 직후 자동 분석"
                        else
                            "비활성 · 규칙 기반 분류만 사용됨",
                        fontSize = 11.sp,
                        color = if (enabled && apiKey.isNotBlank()) Anchor else TextSub
                    )
                }
                androidx.compose.material3.Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = Primary
                    )
                )
            }

            // Live status chip — reflects what is CURRENTLY persisted, not
            // what's in the editable fields above. If they differ, warn the
            // user that changes need saving.
            Spacer(Modifier.height(6.dp))
            val dirty = snapshot.enabled != enabled ||
                snapshot.apiKey != apiKey.trim() ||
                snapshot.model != model
            val statusText = when {
                dirty -> "⚠ 변경사항 미저장"
                snapshot.isConfigured -> "✓ 저장됨 · 키 " + snapshot.apiKey.take(4) +
                    "…" + snapshot.apiKey.takeLast(4) + " · ${snapshot.model}"
                snapshot.enabled -> "⚠ 활성이지만 API 키 없음"
                else -> "○ AI 미사용 (규칙 기반만)"
            }
            val statusColor = when {
                dirty -> Color(0xFFF57F17)
                snapshot.isConfigured -> Anchor
                snapshot.enabled -> Danger
                else -> TextSub
            }
            Text(statusText,
                color = statusColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold)

            if (enabled) {
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    singleLine = true,
                    label = { Text("Gemini API 키", fontSize = 11.sp) },
                    placeholder = { Text("AIza...", fontSize = 12.sp, color = TextSub) },
                    visualTransformation = if (showKey)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        androidx.compose.material3.TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "숨김" else "표시", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    singleLine = true,
                    label = { Text("모델", fontSize = 11.sp) },
                    placeholder = { Text("gemini-2.0-flash-exp", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "무료 API 키: aistudio.google.com/apikey 에서 발급 (사진 1장당 약 0.5원)",
                    fontSize = 10.sp,
                    color = TextSub
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip(
                        label = "발급 페이지 열기",
                        tint = TextSub,
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://aistudio.google.com/apikey")
                            )
                            runCatching { ctx.startActivity(intent) }
                        }
                    )
                    ActionChip(
                        label = "저장",
                        tint = Primary,
                        onClick = { onSave(enabled, apiKey, model) }
                    )
                }
            } else {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.TextButton(
                    onClick = { onSave(false, apiKey, model) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("변경사항 저장", color = TextSub, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, tint: Color, onClick: () -> Unit) {
    Surface(
        color = tint,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun RoomReferenceCard(ctx: Context, room: FloorplanRoomAnchor, onChanged: () -> Unit) {
    val dir = remember(room.id) { File(ctx.filesDir, "reference/${room.id}").apply { mkdirs() } }
    val files = dir.listFiles()?.filter { it.isFile }?.toList() ?: emptyList()

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val scope = MainScope()
        scope.launch {
            withContext(Dispatchers.IO) {
                uris.forEachIndexed { i, uri ->
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@forEachIndexed
                    val dst = File(dir, "u_${System.currentTimeMillis()}_$i.jpg")
                    dst.writeBytes(bytes)
                }
            }
            onChanged()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(room.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${files.size}장", color = TextSub, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { pickLauncher.launch("image/*") }) {
                    Icon(Icons.Filled.Add, null, tint = PrimaryDark)
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(files) { f ->
                    RefThumb(f, onDelete = { f.delete(); onChanged() })
                }
            }
        }
    }
}

@Composable
private fun RefThumb(file: File, onDelete: () -> Unit) {
    var bmp by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.absolutePath) {
        bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
    }
    Box(
        Modifier
            .width(90.dp)
            .aspectRatio(1f)
            .background(Color(0xFFECEFF1), RoundedCornerShape(8.dp))
    ) {
        bmp?.let { b ->
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(Icons.Filled.Delete, null, tint = Danger)
        }
    }
}
