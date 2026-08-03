package com.axlife.pinset.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.axlife.pinset.ai.AiInspectionUiState
import com.axlife.pinset.ai.AiInspectionViewModel
import com.axlife.pinset.ai.ClassificationCandidate
import com.axlife.pinset.ai.InspectionStage
import com.axlife.pinset.intro.InspectionContextStore
import com.axlife.pinset.ui.InlineSttButton
import com.axlife.pinset.ui.InspectionStepBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiInspectionScreen(nav: NavController) {
    val vm: AiInspectionViewModel = viewModel(factory = AiInspectionViewModel.Factory)
    val state by vm.state.collectAsState()
    val inspectionContext = InspectionContextStore.load(LocalContext.current)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 하자분류 어시스턴트") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { InspectionStepBar(currentStep = 3) }
            inspectionContext?.let { context ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("${context.unitLabel} · ${context.roomLabel}", fontWeight = FontWeight.Bold)
                            Text(
                                "선택한 세대·공간 문맥을 이미지와 입주자 의견 분석에 함께 사용합니다.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (context.sourceType == "fallback") {
                                Text("기본도면 적용 · 현장에서 공간을 확인하세요.", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            item { DevelopmentNotice(state.apiMode) }
            item { ProgressHeader(state) }
            when (state.stage) {
                InspectionStage.DRAFT, InspectionStage.ERROR -> item {
                    OpinionInput(
                        state = state,
                        onChanged = vm::updateOpinion,
                        onAnalyze = vm::startAnalysis
                    )
                }
                InspectionStage.ANALYZING -> item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.padding(6.dp))
                        Text("이미지·의견 단서를 함께 분석하고 있습니다.")
                    }
                }
                InspectionStage.QUESTION -> item {
                    val question = state.question
                    if (question != null) {
                        AssistantQuestion(
                            text = question.text,
                            options = question.options,
                            onAnswer = vm::answerQuestion
                        )
                    }
                }
                InspectionStage.PROPOSAL -> {
                    item {
                        Text("추천 분류", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    items(state.candidates) { candidate ->
                        CandidateCard(
                            candidate = candidate,
                            selected = candidate == state.selectedCandidate ||
                                (state.selectedCandidate == null && candidate.rank == 1),
                            onSelect = { vm.selectCandidate(candidate) }
                        )
                    }
                    item {
                        Button(onClick = vm::confirm, modifier = Modifier.fillMaxWidth()) {
                            Text("추천 분류 확인·확정")
                        }
                    }
                }
                InspectionStage.CONFIRMED -> item {
                    ConfirmedCard(state.confirmedSummary.orEmpty(), onRestart = vm::restart)
                }
            }
        }
    }
}

@Composable
private fun DevelopmentNotice(apiMode: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("Phase 3 · $apiMode 모드", fontWeight = FontWeight.Bold)
            Text(
                if (apiMode == "Real API")
                    "설정된 HTTPS 개발 서버를 사용합니다. 업로드 완료된 원거리·근거리 이미지 ID가 없으면 요청하지 않습니다."
                else
                    "서버 주소가 설정되지 않아 안전한 Fake 흐름을 사용합니다. 운영 DB에는 저장하지 않습니다.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ProgressHeader(state: AiInspectionUiState) {
    val label = when (state.stage) {
        InspectionStage.DRAFT -> "1. 의견 입력"
        InspectionStage.ANALYZING -> "2. AI 분석"
        InspectionStage.QUESTION -> "3. 추가 확인"
        InspectionStage.PROPOSAL -> "4. 추천 검토"
        InspectionStage.CONFIRMED -> "5. 분류 확정"
        InspectionStage.ERROR -> "오류 확인"
    }
    Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun OpinionInput(
    state: AiInspectionUiState,
    onChanged: (String) -> Unit,
    onAnalyze: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("발견한 현상을 평소 표현대로 입력하세요. AI가 표준 분류 후보로 바꿔 제안합니다.")
        OutlinedTextField(
            value = state.residentOpinion,
            onValueChange = onChanged,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text("입주자·점검자 의견") },
            placeholder = { Text("예: 비가 오면 거실 창문 위쪽에서 물이 맺혀요.") },
            supportingText = state.errorMessage?.let { message -> { Text(message) } }
        )
        InlineSttButton(
            currentText = state.residentOpinion,
            onTextChanged = onChanged
        )
        Button(onClick = onAnalyze, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
            Text("  AI 분석 시작")
        }
    }
}

@Composable
private fun AssistantQuestion(text: String, options: List<String>, onAnswer: (String) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AI 어시스턴트", fontWeight = FontWeight.Bold)
            Text(text)
            options.forEach { option ->
                OutlinedButton(onClick = { onAnswer(option) }, modifier = Modifier.fillMaxWidth()) {
                    Text(option)
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: ClassificationCandidate,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onSelect
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${candidate.rank}순위 · ${candidate.workKind}", fontWeight = FontWeight.Bold)
                FilterChip(
                    selected = selected,
                    onClick = onSelect,
                    label = { Text("${(candidate.confidence * 100).toInt()}%") }
                )
            }
            Text("${candidate.location} > ${candidate.defectPart} > ${candidate.partDetail}")
            Text("추정 원인: ${candidate.defectCause}")
            Text(candidate.rationale, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConfirmedCard(summary: String, onRestart: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("분류 확인 완료", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(summary)
            Text("아직 운영 DB에는 저장되지 않았습니다.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("새 하자 분류") }
        }
    }
}
