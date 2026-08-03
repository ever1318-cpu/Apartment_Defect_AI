package com.axlife.pinset.ui.gallery

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.axlife.pinset.gallery.GalleryDefect
import com.axlife.pinset.gallery.GalleryHousehold
import com.axlife.pinset.gallery.GalleryRequestException
import com.axlife.pinset.gallery.ServerGalleryRepository
import com.axlife.pinset.ui.theme.PrimaryDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CommonAreaFilter { ALL, COMMON_AREA, OTHER_DEFECT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerGalleryScreen(nav: NavController) {
    val context = LocalContext.current
    val repository = remember(context) { ServerGalleryRepository(context) }
    val scope = rememberCoroutineScope()
    var households by remember { mutableStateOf<List<GalleryHousehold>>(emptyList()) }
    var selectedBuilding by remember { mutableStateOf<String?>(null) }
    var selectedUnit by remember { mutableStateOf<String?>(null) }
    var defects by remember { mutableStateOf<List<GalleryDefect>>(emptyList()) }
    /** Last household whose server query completed successfully. */
    var completedQuery by remember { mutableStateOf<Pair<String, String>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var buildingMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    var expandedImage by remember { mutableStateOf<String?>(null) }
    var commonAreaFilter by remember { mutableStateOf(CommonAreaFilter.ALL) }
    // The DB is the only source of choices: an uncompleted inspection must
    // never appear in this review gallery.
    // Keep the non-household collection after all numbered apartment buildings.
    val buildingOptions = remember(households) {
        households.map { it.buildingNo }.distinct().sortedWith(
            compareBy<String> { if (it == "COMMON") 1 else 0 }.thenBy { it }
        )
    }
    val unitOptions = remember(selectedBuilding, households) {
        households.filter { it.buildingNo == selectedBuilding }.map { it.unitNo }.distinct().sorted()
    }

    fun loadHouseholds() {
        scope.launch {
            loading = true
            error = null
            try {
                households = withContext(Dispatchers.IO) { repository.households() }
                val firstBuilding = buildingOptions.firstOrNull()
                if (selectedBuilding == null && firstBuilding != null) {
                    selectedBuilding = firstBuilding
                    selectedUnit = households.firstOrNull { it.buildingNo == firstBuilding }?.unitNo
                    if (firstBuilding == "COMMON") commonAreaFilter = CommonAreaFilter.COMMON_AREA
                } else if (households.isEmpty()) {
                    selectedBuilding = null
                    selectedUnit = null
                    defects = emptyList()
                    completedQuery = null
                }
            } catch (exc: Exception) {
                error = galleryErrorMessage(exc)
            } finally {
                loading = false
            }
        }
    }

    fun loadDefects() {
        val building = selectedBuilding ?: return
        val unit = selectedUnit ?: return
        scope.launch {
            loading = true
            error = null
            completedQuery = null
            try {
                defects = withContext(Dispatchers.IO) { repository.defects(building, unit) }
                completedQuery = building to unit
            } catch (exc: Exception) {
                error = galleryErrorMessage(exc)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadHouseholds() }
    LaunchedEffect(selectedBuilding, selectedUnit) {
        if (selectedBuilding != null && selectedUnit != null && households.isNotEmpty()) loadDefects()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("서버 저장 이미지", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "뒤로") }
                },
                actions = {
                    IconButton(onClick = { loadHouseholds() }) { Icon(Icons.Filled.Refresh, "새로고침") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PrimaryDark, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary, actionIconContentColor = MaterialTheme.colorScheme.onPrimary)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("동·호수를 선택하면 서버에 전송된 하자 사진과 메타데이터를 표시합니다.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { buildingMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = buildingOptions.isNotEmpty()) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "동 목록")
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    when (selectedBuilding) {
                                        "COMMON" -> commonAreaFilter.galleryLabel()
                                        null -> "동 선택"
                                        else -> galleryBuildingLabel(selectedBuilding!!)
                                    }
                                )
                            }
                            DropdownMenu(expanded = buildingMenu, onDismissRequest = { buildingMenu = false }) {
                                buildingOptions.forEach { building ->
                                    if (building == "COMMON") {
                                        listOf(CommonAreaFilter.COMMON_AREA, CommonAreaFilter.OTHER_DEFECT).forEach { filter ->
                                            DropdownMenuItem(text = { Text(filter.galleryLabel()) }, onClick = {
                                                selectedBuilding = "COMMON"
                                                selectedUnit = "0000"
                                                defects = emptyList()
                                                completedQuery = null
                                                commonAreaFilter = filter
                                                buildingMenu = false
                                            })
                                        }
                                    } else {
                                        DropdownMenuItem(text = { Text(galleryBuildingLabel(building)) }, onClick = {
                                            selectedBuilding = building
                                            selectedUnit = null
                                            defects = emptyList()
                                            completedQuery = null
                                            commonAreaFilter = CommonAreaFilter.ALL
                                            buildingMenu = false
                                        })
                                    }
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { unitMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = unitOptions.isNotEmpty()) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "호 목록")
                                Spacer(Modifier.width(2.dp))
                                Text(selectedUnit?.let { galleryUnitLabel(selectedBuilding, it) } ?: "호 선택")
                            }
                            DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                                unitOptions.forEach { unit ->
                                    DropdownMenuItem(text = { Text(galleryUnitLabel(selectedBuilding, unit)) }, onClick = {
                                        selectedUnit = unit
                                        defects = emptyList()
                                        completedQuery = null
                                        if (selectedBuilding != "COMMON") commonAreaFilter = CommonAreaFilter.ALL
                                        unitMenu = false
                                    })
                                }
                            }
                        }
                    }
                    selectedBuilding?.let { building -> selectedUnit?.let { unit ->
                        households.firstOrNull { it.buildingNo == building && it.unitNo == unit }?.let { selected ->
                            Text("등록 하자 ${selected.defectCount}건 · 최근 점검 ${selected.lastInspectedAt}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        }
                    } }
                    if (selectedBuilding == "COMMON") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                CommonAreaFilter.ALL to "전체",
                                CommonAreaFilter.COMMON_AREA to "공용부",
                                CommonAreaFilter.OTHER_DEFECT to "기타 하자",
                            ).forEach { (filter, label) ->
                                if (commonAreaFilter == filter) {
                                    Button(onClick = { commonAreaFilter = filter }, modifier = Modifier.weight(1f)) { Text(label) }
                                } else {
                                    OutlinedButton(onClick = { commonAreaFilter = filter }, modifier = Modifier.weight(1f)) { Text(label) }
                                }
                            }
                        }
                    }
                }
            }
            if (loading) item { Text("서버 이미지 조회 중…", modifier = Modifier.padding(16.dp)) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) } }
            if (!loading && error == null && households.isEmpty()) item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text(
                        "서버 통신은 정상입니다. 전송 및 마감이 완료된 세대 점검 데이터가 아직 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
            val selectedQuery = selectedBuilding?.let { building -> selectedUnit?.let { unit -> building to unit } }
            if (!loading && error == null && completedQuery == selectedQuery && defects.isEmpty()) item {
                val (building, unit) = selectedQuery ?: return@item
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("저장된 하자 데이터 없음", fontWeight = FontWeight.Bold)
                        Text(
                            "서버 통신은 정상입니다. ${building}동 ${unit}호에는 현재 저장된 하자 사진 또는 메타데이터가 없습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
            val visibleDefects = when (commonAreaFilter) {
                CommonAreaFilter.ALL -> defects
                CommonAreaFilter.COMMON_AREA -> defects.filterNot { it.isOtherCommonDefect() }
                CommonAreaFilter.OTHER_DEFECT -> defects.filter { it.isOtherCommonDefect() }
            }
            items(visibleDefects, key = { it.index }) { defect ->
                DefectGalleryCard(
                    defect,
                    repository::contentUrl,
                    commonArea = selectedBuilding == "COMMON",
                    onImageClick = { expandedImage = it },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
    expandedImage?.let { url ->
        AlertDialog(
            onDismissRequest = { expandedImage = null },
            confirmButton = {},
            text = { AsyncImage(model = url, contentDescription = "하자 원본 사진", modifier = Modifier.fillMaxWidth().height(460.dp), contentScale = ContentScale.Fit) }
        )
    }
}

@Composable
private fun DefectGalleryCard(
    defect: GalleryDefect,
    contentUrl: (String) -> String,
    commonArea: Boolean,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val title = if (commonArea) "공용부 기록 ${defect.index} · ${defect.room}" else "#${defect.index} · ${defect.room}"
            Text(
                if (commonArea) {
                    "${if (defect.isOtherCommonDefect()) "기타 하자" else "공용부"} 기록 ${defect.index} · ${defect.room}"
                } else title,
                fontWeight = FontWeight.Bold
            )
            if (defect.classification.isNotBlank()) Text(defect.classification, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            defect.focusDistanceM?.let { distance ->
                Text("?? ?? ${String.format("%.1f", distance)}m", style = MaterialTheme.typography.labelSmall)
            }
            defect.measuredGapMm?.let { gap ->
                val result = when (defect.measurementStatus) {
                    "GAUGE_INSERTED" -> "???"
                    "GAUGE_BLOCKED" -> "???"
                    else -> "???"
                }
                Text("?? ${String.format("%.1f", gap)}mm ? $result", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (defect.finalOpinion.isNotBlank()) Text(defect.finalOpinion, style = MaterialTheme.typography.bodyMedium)
            else if (defect.rawOpinion.isNotBlank()) Text(defect.rawOpinion, style = MaterialTheme.typography.bodyMedium)
            if (defect.media.isEmpty()) Text("저장된 사진 없음", style = MaterialTheme.typography.bodySmall)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(defect.media, key = { it.id }) { media ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(
                            model = contentUrl(media.id), contentDescription = "${media.role} 사진",
                            modifier = Modifier.size(120.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface).clickable { onImageClick(contentUrl(media.id)) },
                            contentScale = ContentScale.Crop
                        )
                        Text(mediaRoleName(media.role, media.isVirtualReference), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun GalleryDefect.isOtherCommonDefect(): Boolean =
    commonAreaLabel?.trim()?.startsWith("기타") == true

private fun CommonAreaFilter.galleryLabel(): String = when (this) {
    CommonAreaFilter.ALL -> "전체"
    CommonAreaFilter.COMMON_AREA -> "공용부"
    CommonAreaFilter.OTHER_DEFECT -> "기타 하자"
}

private fun mediaRoleName(role: String, isVirtualReference: Boolean): String = when {
    isVirtualReference -> "가상·참고"
    role == "CLOSE" || role == "ANCHOR_NEAR" -> "근경"
    role == "WIDE" || role == "ANCHOR_FAR" -> "원경"
    role == "EXTRA" -> "메모·추가"
    else -> role
}

private fun galleryBuildingLabel(buildingNo: String): String =
    if (buildingNo == "COMMON") "공용부" else "${buildingNo}동"

private fun galleryUnitLabel(buildingNo: String?, unitNo: String): String =
    if (buildingNo == "COMMON" && unitNo == "0000") "공용부 기록" else "${unitNo}호"

private fun galleryErrorMessage(error: Exception): String = when (error) {
    is GalleryRequestException -> when (error.status) {
        404 -> "서버 연결은 정상입니다. 다만 실행 중인 서버에 하자DB 조회 기능이 아직 반영되지 않았습니다. 서버를 최신 코드로 재시작해주세요."
        401, 403 -> "서버 연결은 되었지만 이미지 조회 권한이 없습니다."
        in 500..599 -> "서버와 연결되었지만 조회 처리 중 오류가 발생했습니다."
        else -> "서버가 하자DB 조회를 처리하지 못했습니다. (HTTP ${error.status})"
    }
    else -> "서버와 통신할 수 없습니다. 네트워크 상태와 서버 주소를 확인해주세요."
}
