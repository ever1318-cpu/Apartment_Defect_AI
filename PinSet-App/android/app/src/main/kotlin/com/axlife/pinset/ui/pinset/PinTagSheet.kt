package com.axlife.pinset.ui.pinset

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.axlife.pinset.data.entity.DefectType
import com.axlife.pinset.data.entity.Severity
import com.axlife.pinset.data.entity.Surface
import com.axlife.pinset.data.entity.Trade
import com.axlife.pinset.ui.theme.Primary
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.ui.theme.Purple
import com.axlife.pinset.ui.theme.Success
import com.axlife.pinset.ui.InspectionStepBar
import com.axlife.pinset.ui.CarouselItem
import com.axlife.pinset.ui.PhotoCarouselDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class TagSubmission(
    val type: DefectType = DefectType.OTHER,
    val severity: Severity = Severity.NORMAL,
    val trade: Trade = Trade.OTHER,
    val surface: Surface = Surface.WALL,
    val areaDetail: String = "",
    val note: String = "",
    val finalize: Boolean = false,
    val residentOpinion: String = "",
    val aiPathText: String = "",
    val aiConfidence: Float = 0f,
    val finalPathText: String = "",
    val memoPhotoPath: String = "",
    /** Physical feeler-gauge result for the precision gap workflow. */
    val measuredGapMm: Float? = null,
    val measurementMethod: String = "",
    val measurementStatus: String = ""
)

/**
 * v1.2 tag sheet layout:
 *
 *   ┌──────────────────────┬──────────────────────┐
 *   │ 1. 입주민 의견        │ 🤖 AI 실시간 분석    │ <- twin side-by-side
 *   │ (multi-line text)    │ (auto-updated path   │
 *   │                      │  + confidence)       │
 *   └──────────────────────┴──────────────────────┘
 *   │ 사진 분석 (별도 카드) │
 *   ─────────────────────────────────────────────
 *   3. 최종 부위 및 원인  [ 확정하기 → 다이얼로그: AI 의견 수용? ]
 *   ─────────────────────────────────────────────
 *   상세 하자부위
 *   ─────────────────────────────────────────────
 *   [ 저장 ]
 *
 * The AI text panel updates live via [aiTextSuggest]. When the user taps
 * "최종 의견 확정", a confirmation dialog asks whether to also copy the AI
 * suggestion into the final field.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PinTagSheet(
    initial: TagSubmission? = null,
    title: String = "하자의견 입력",
    submitLabel: String = "저장",
    showFinalize: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (TagSubmission) -> Unit,
    aiTextSuggest: ((String) -> AiSuggestionUi?)? = null,
    photoAiPath: String = "",
    photoAiConfidence: Float = 0f,
    onStepSelected: ((Int) -> Unit)? = null,
    defectPhotoPath: String = "",
    widePhotoPath: String = "",
    suggestedDetailOptions: List<String> = emptyList(),
    hierarchySuggestion: String = "",
    surfaceBandLabel: String = "",
    suggestedTradeLabel: String = "",
    boundaryYNorm: Float? = null,
    boundaryLabel: String = "",
    aiHierarchySuggestion: String = "",
    precisionMeasurement: Boolean = false,
    referenceMarkerCaptured: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var finalPath by remember { mutableStateOf(initial?.finalPathText.orEmpty()) }
    var finalPathEdited by remember { mutableStateOf(false) }
    var detailMenuExpanded by remember { mutableStateOf(false) }
    val currentSurface = initial?.surface ?: Surface.WALL
    val detailOptions = suggestedDetailOptions.take(5).ifEmpty { detailOptionsFor(currentSurface).take(5) }
    // Keep the location and camera-angle surface visible as one compact title.
    // Example: "거실/벽". The recommended detail itself is the field value.
    val suggestedRoom = hierarchySuggestion.substringBefore(".").trim().ifBlank { "\uc704\uce58" }
    val detailMenuTitle = "$suggestedRoom -> ${surfaceBandLabel.ifBlank { surfaceLabel(currentSurface) }.replace("/", "-")} ->"
    // The operator starts with an editable, complete hierarchy proposal rather
    // than an empty opinion box. Distance/direction notes are excluded here.
    val suggestedDetail = detailOptions.firstOrNull().orEmpty()
    val suggestedTrade = suggestedTradeLabel.ifBlank { defaultTradeForDetail(suggestedDetail) }
    val suggestedCause = "\uc2dc\uacf5 \ubd88\ub7c9"
    // The first two lines carry the suggested five-level classification. The
    // operator starts typing on the third line, without a second prompt card.
    val suggestedOpinionSeed = "\uc704\uce58: $suggestedRoom  \uc7a5\uc18c: ${surfaceBandLabel.ifBlank { surfaceLabel(currentSurface) }}\n" +
        "\uc138\ubd80\ubd80\uc704: $suggestedDetail  \uacf5\uc885: $suggestedTrade  \uc6d0\uc778: $suggestedCause\n"
    var residentOpinion by rememberSaveable {
        mutableStateOf(initial?.residentOpinion?.takeIf { it.isNotBlank() } ?: suggestedOpinionSeed)
    }
    // Start with the server/AI's first detailed-part candidate. The operator can
    // immediately change it or choose the free-opinion option in the menu.
    var areaDetail by remember {
        mutableStateOf(
            initial?.areaDetail?.takeIf { it.isNotBlank() }
                ?: detailOptions.firstOrNull().orEmpty()
        )
    }
    var finalize by remember { mutableStateOf(initial?.finalize ?: false) }
    var sttStatus by remember { mutableStateOf("") }
    var sttTranscript by rememberSaveable { mutableStateOf("") }
    var sttSessionBase by rememberSaveable { mutableStateOf("") }
    var sttListening by remember { mutableStateOf(false) }
    var initialSpeechWait by remember { mutableStateOf(true) }
    var micPulseVisible by remember { mutableStateOf(true) }
    var voiceInputMode by rememberSaveable { mutableStateOf(true) }
    var opinionFinished by rememberSaveable { mutableStateOf(false) }
    var finalReady by rememberSaveable { mutableStateOf(false) }
    var memoPhotoPath by rememberSaveable { mutableStateOf(initial?.memoPhotoPath.orEmpty()) }
    var measuredGapMm by rememberSaveable { mutableStateOf(initial?.measuredGapMm) }
    var gaugePassed by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var expandedImageIndex by remember { mutableStateOf<Int?>(null) }
    // Keep the review carousel in a fixed evidence order. Empty slots are
    // intentional placeholders, so close / wide / memo can always be checked
    // by a single horizontal swipe sequence.
    val reviewPhotos = listOf(
        CarouselItem(defectPhotoPath, "근경 하자사진", defectPhotoPath.isBlank()),
        CarouselItem(widePhotoPath, "원경 하자사진", widePhotoPath.isBlank()),
        CarouselItem(
            memoPhotoPath,
            if (memoPhotoPath.isBlank()) "MEMO" else "메모(스티커) 사진",
            memoPhotoPath.isBlank()
        )
    )
    val sttScope = rememberCoroutineScope()
    var sttSilenceJob by remember { mutableStateOf<Job?>(null) }
    var imeSilenceJob by remember { mutableStateOf<Job?>(null) }
    val opinionFocusRequester = remember { FocusRequester() }
    // Keeps the confirmation button in the visible viewport when the IME opens.
    val opinionCompleteBringIntoView = remember { BringIntoViewRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val memoPhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            runCatching {
                val directory = java.io.File(context.filesDir, "memo_photos").apply { mkdirs() }
                val file = java.io.File(directory, "memo_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
                }
                memoPhotoPath = file.absolutePath
            }.onFailure {
                sttStatus = "스티커/메모 사진을 저장하지 못했습니다. 다시 촬영해 주세요."
            }
        }
    }
    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    fun scheduleSttAutoFinish() {
        sttSilenceJob?.cancel()
        sttSilenceJob = sttScope.launch {
            delay(10_000)
            if (sttListening) {
                speechRecognizer?.stopListening()
                sttListening = false
                voiceInputMode = false
                sttTranscript = residentOpinion
                opinionFocusRequester.requestFocus()
                keyboardController?.show()
                sttStatus = "3초간 음성이 없어 음성인식을 마감했습니다. 문자 입력 대기 중입니다."
            }
        }
    }

    fun beginSpeechToText() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            sttStatus = "이 기기에서 음성 인식을 사용할 수 없습니다. 키보드로 입력하세요."
            return
        }
        sttSessionBase = residentOpinion.trim()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "하자 의견을 말씀해 주세요")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        runCatching {
            recognizer.startListening(intent)
            sttListening = true
            sttStatus = "음성 인식을 준비하고 있습니다."
        }.onFailure {
            sttListening = false
            sttStatus = "음성 인식을 시작하지 못했습니다. 잠시 후 다시 시도하세요."
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginSpeechToText()
        } else {
            sttStatus = "마이크 권한이 필요합니다. 앱 설정에서 마이크를 허용하세요."
        }
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                sttListening = true
                sttStatus = "듣고 있습니다. 하자 의견을 말씀해 주세요."
                scheduleSttAutoFinish()
            }

            override fun onBeginningOfSpeech() {
                initialSpeechWait = false
                sttStatus = "음성을 인식하고 있습니다."
                scheduleSttAutoFinish()
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                sttStatus = "마지막 음성을 처리하고 있습니다. 3초 후 자동 마감됩니다."
            }

            override fun onError(error: Int) {
                sttSilenceJob?.cancel()
                sttListening = false
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    voiceInputMode = false
                    opinionFocusRequester.requestFocus()
                    keyboardController?.show()
                    sttStatus = "음성 입력이 없어 문자 입력 대기 중입니다."
                    return
                }
                sttStatus = speechRecognitionErrorMessage(error)
            }

            override fun onResults(results: Bundle?) {
                initialSpeechWait = false
                sttSilenceJob?.cancel()
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (spoken.isNotBlank()) {
                    val commandDetected = containsOpinionEndCommand(spoken)
                    sttTranscript = combineSttTranscript(
                        sttSessionBase,
                        removeOpinionEndCommand(spoken)
                    )
                    residentOpinion = sttTranscript
                    if (commandDetected) {
                        voiceInputMode = false
                        opinionFinished = true
                        sttStatus = "하자의견 입력을 마감하고 AI 분석으로 이동합니다."
                    } else {
                        sttStatus = "음성 인식이 완료되었습니다. 내용을 확인해 주세요."
                    }
                } else {
                    sttStatus = "인식된 문장이 없습니다. 다시 말씀해 주세요."
                }
                sttListening = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                initialSpeechWait = false
                val spoken = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (spoken.isNotBlank()) {
                    sttTranscript = combineSttTranscript(sttSessionBase, spoken)
                    residentOpinion = sttTranscript
                    sttStatus = "음성 인식 중 · 문장을 실시간 기록하고 있습니다."
                    scheduleSttAutoFinish()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose {
            sttSilenceJob?.cancel()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
    }

    fun startOrStopSpeechToText() {
        if (sttListening) {
            speechRecognizer?.stopListening()
            sttStatus = "음성을 처리하고 있습니다."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            beginSpeechToText()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun finishSpeechToText() {
        sttSilenceJob?.cancel()
        if (sttListening) speechRecognizer?.stopListening()
        if (sttTranscript.isNotBlank()) residentOpinion = sttTranscript
        sttListening = false
        sttStatus = "STT 의견을 마감했습니다. 인식 문장을 확인·수정하세요."
    }

    LaunchedEffect(Unit) {
        delay(350)
        opinionFocusRequester.requestFocus()
        keyboardController?.show()
        startOrStopSpeechToText()
    }

    LaunchedEffect(sttListening) {
        micPulseVisible = true
        while (sttListening) {
            delay(450)
            micPulseVisible = !micPulseVisible
        }
        micPulseVisible = true
    }

    // AI analysis is triggered by an explicit "입력완료" button so the user
    // doesn't see the panel churn on every keystroke.
    var aiTextResult by remember { mutableStateOf<AiSuggestionUi?>(null) }
    var aiOpinionApplied by rememberSaveable { mutableStateOf(false) }
    var aiAnalysisMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var lastAnalyzedOpinion by remember { mutableStateOf("") }
    fun runAiAnalysis() {
        val sourceOpinion = residentOpinion.trim()
        if (sourceOpinion.isNotBlank()) {
            aiTextResult = aiTextSuggest?.invoke(sourceOpinion)
            lastAnalyzedOpinion = sourceOpinion
        }
    }

    fun adoptAiOpinion() {
        aiAnalysisMessage = null
        runCatching {
            runAiAnalysis()
            val result = mergeRecommendations(aiTextResult?.pathText.orEmpty(), photoAiPath)
            if (result.isBlank()) {
                aiAnalysisMessage = "AI API 연결 실패 또는 분석 결과가 없습니다. 의견 입력은 계속할 수 있습니다."
            } else {
                aiOpinionApplied = true
                finalPath = "AI 분류: $result"
                finalPathEdited = false
                finalReady = true
            }
        }.onFailure { error ->
            aiAnalysisMessage = "AI API 연결 실패: ${error.message ?: "알 수 없는 오류"}. 의견 입력은 계속할 수 있습니다."
        }
    }

    fun updateOpinion(value: String) {
        val commandDetected = containsOpinionEndCommand(value)
        residentOpinion = removeOpinionEndCommand(value)
        sttTranscript = residentOpinion
        aiOpinionApplied = false
        imeSilenceJob?.cancel()
        if (commandDetected) {
            voiceInputMode = false
            opinionFinished = true
            keyboardController?.hide()
            sttStatus = "하자의견 입력을 마감하고 AI 분석으로 이동합니다."
        } else if (voiceInputMode && residentOpinion.isNotBlank()) {
            imeSilenceJob = sttScope.launch {
                delay(3_000)
                voiceInputMode = false
                opinionFinished = true
                keyboardController?.hide()
                sttStatus = "3초간 추가 입력이 없어 AI 분석으로 이동합니다."
            }
        }
    }

    LaunchedEffect(residentOpinion) {
        if (false && residentOpinion.isNotBlank()) {
            delay(700)
            if (residentOpinion.isNotBlank() && residentOpinion != lastAnalyzedOpinion) {
                runAiAnalysis()
            }
        }
    }

    LaunchedEffect(opinionFinished) {
        if (opinionFinished) {
            delay(800)
            finalReady = true
        }
    }

    LaunchedEffect(residentOpinion, aiTextResult, photoAiPath, areaDetail) {
        if (false && !finalPathEdited) {
            finalPath = mergeRecommendations(
                residentOpinion,
                areaDetail,
                if (aiOpinionApplied) aiTextResult?.pathText.orEmpty() else "",
                if (aiOpinionApplied) photoAiPath else ""
            )
        }
    }

    fun submitOpinion(finalizeValue: Boolean) {
        keyboardController?.hide()
        val combinedOpinion = residentOpinion.trim()
        val chosenAiPath = cleanRecommendation(
            aiTextResult?.pathText?.takeIf { it.isNotBlank() } ?: photoAiPath
        )
        val chosenAiConf = aiTextResult?.confidence?.takeIf { it > 0f } ?: photoAiConfidence
        onSubmit(
            TagSubmission(
                type = initial?.type ?: DefectType.OTHER,
                severity = initial?.severity ?: Severity.NORMAL,
                trade = initial?.trade ?: Trade.OTHER,
                surface = initial?.surface ?: Surface.WALL,
                areaDetail = areaDetail.trim(),
                note = combinedOpinion,
                finalize = finalizeValue,
                residentOpinion = combinedOpinion,
                aiPathText = chosenAiPath,
                aiConfidence = chosenAiConf,
                finalPathText = cleanRecommendation(finalPath),
                memoPhotoPath = memoPhotoPath,
                measuredGapMm = measuredGapMm,
                measurementMethod = when {
                    precisionMeasurement && gaugePassed != null -> "FEELER_GAUGE"
                    precisionMeasurement && referenceMarkerCaptured -> "REFERENCE_MARKER_40MM"
                    else -> ""
                },
                measurementStatus = when {
                    !precisionMeasurement -> "NOT_REQUESTED"
                    measuredGapMm == null -> "REFERENCE_REQUIRED"
                    gaugePassed == true -> "GAUGE_INSERTED"
                    gaugePassed == false -> "GAUGE_BLOCKED"
                    referenceMarkerCaptured -> "MARKER_CAPTURED_ESTIMATE_PENDING"
                    else -> "GAUGE_SELECTED"
                }
            )
        )
    }

    expandedImageIndex?.let { index ->
        PhotoCarouselDialog(
            items = reviewPhotos,
            initialIndex = index,
            onDismiss = { expandedImageIndex = null }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val scrollState = rememberScrollState()
        LaunchedEffect(opinionFinished, finalReady) {
            if (opinionFinished) {
                delay(150)
                scrollState.animateScrollTo(
                    if (finalReady) scrollState.maxValue else scrollState.maxValue / 2
                )
            }
        }
        Column(
            Modifier
                .padding(horizontal = 9.dp)
                .imePadding()
                .verticalScroll(scrollState)
        ) {
            InspectionStepBar(currentStep = 2, onStepSelected = onStepSelected)
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))

            // ============ 1) STT + inspector opinion ============
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Primary, shape = RoundedCornerShape(50)) {
                    Text("1", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
                }
                Text("  하자설명", color = PrimaryDark, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                // 근경(A)은 왼쪽, 원경(B)은 오른쪽에 항상 같은 크기로 표시한다.
                // 아직 사진이 없거나 원경 확보에 실패했으면 재촬영 대기 칸을 남긴다.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("근경", color = PrimaryDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        if (defectPhotoPath.isNotBlank()) {
                            coil.compose.AsyncImage(
                                model = defectPhotoPath,
                                contentDescription = "근경 하자사진 미리보기",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 54.dp, height = 52.dp)
                                    .background(Color.LightGray, RoundedCornerShape(7.dp))
                                    .miniBoundaryLine(boundaryYNorm)
                                    .clickable { expandedImageIndex = reviewPhotos.indexOfFirst { it.filePath == defectPhotoPath }.takeIf { it >= 0 } }
                            )
                        } else {
                            Surface(
                                color = Color(0xFFE8EEF3),
                                shape = RoundedCornerShape(7.dp),
                                modifier = Modifier
                                    .size(width = 54.dp, height = 52.dp)
                                    .clickable { onStepSelected?.invoke(1) }
                            ) {
                                Text("사진 없음\n다시촬영", color = Color(0xFF546E7A), fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(5.dp))
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("원경", color = PrimaryDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        if (widePhotoPath.isNotBlank()) {
                            coil.compose.AsyncImage(
                                model = widePhotoPath,
                                contentDescription = "원경 하자사진 미리보기",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 54.dp, height = 52.dp)
                                    .background(Color.LightGray, RoundedCornerShape(7.dp))
                                    .miniBoundaryLine(boundaryYNorm)
                                    .clickable { expandedImageIndex = reviewPhotos.indexOfFirst { it.filePath == widePhotoPath }.takeIf { it >= 0 } }
                            )
                        } else {
                            Surface(
                                color = Color(0xFFE8EEF3),
                                shape = RoundedCornerShape(7.dp),
                                modifier = Modifier
                                    .size(width = 54.dp, height = 52.dp)
                                    .clickable { onStepSelected?.invoke(1) }
                            ) {
                                Text("사진 없음\n다시촬영", color = Color(0xFF546E7A), fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(5.dp))
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("메모", color = PrimaryDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        if (memoPhotoPath.isNotBlank()) {
                            coil.compose.AsyncImage(
                                model = memoPhotoPath,
                                contentDescription = "설명 스티커 사진 미리보기",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 54.dp, height = 52.dp)
                                    .background(Color.LightGray, RoundedCornerShape(7.dp))
                                    .clickable { expandedImageIndex = 2 }
                            )
                        } else {
                            Surface(
                                color = Color(0xFFE8EEF3),
                                shape = RoundedCornerShape(7.dp),
                                modifier = Modifier
                                    .size(width = 54.dp, height = 52.dp)
                                    .clickable { memoPhotoLauncher.launch(null) }
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Filled.PhotoCamera,
                                        contentDescription = "Memo placeholder",
                                        tint = Color(0xFF546E7A),
                                        modifier = Modifier.size(21.dp)
                                    )
                                    Text("MEMO", color = Color(0xFF546E7A), fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = if (sttListening) "음성인식 중" else "음성인식 시작",
                    tint = if (sttListening) {
                        Color(0xFFE53935).copy(alpha = if (micPulseVisible) 1f else 0.22f)
                    } else {
                        Color(0xFF78909C)
                    },
                    modifier = Modifier
                        .size(0.dp)
                        .clickable {
                            opinionFinished = false
                            voiceInputMode = true
                            startOrStopSpeechToText()
                        }
                )
            }
            Spacer(Modifier.height(3.dp))
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "",
                    color = Color.Transparent, fontSize = 0.sp,
                    modifier = Modifier.height(0.dp)
                )
                if (false && (sttListening || residentOpinion.isBlank())) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "음성인식 시작",
                        tint = if (sttListening) Color(0xFFE53935).copy(alpha = if (micPulseVisible) 1f else 0.22f) else Color(0xFF78909C),
                        modifier = Modifier.size(23.dp).clickable {
                            opinionFinished = false
                            voiceInputMode = true
                            startOrStopSpeechToText()
                        }
                    )
                    Text(
                        "  음성으로 입력 (10초 이내)",
                        color = if (sttListening) Color(0xFF2E7D32) else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = if (sttListening) FontWeight.Bold else FontWeight.Normal
                    )
                }
                // Classification and inspector input are presented as one card.
                Surface(
                    color = Color(0xFFFFFCFB),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = residentOpinion,
                            onValueChange = ::updateOpinion,
                            visualTransformation = hierarchyLabelVisualTransformation,
                            placeholder = { Text("\ud558\uc790 \uc0c1\ud0dc\uc640 \ud655\uc778 \ub0b4\uc6a9\uc744 \uc785\ub825\ud558\uc138\uc694", fontSize = 11.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = if (sttListening) Color(0xFF2E7D32) else PrimaryDark,
                                fontSize = 12.sp
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(opinionFocusRequester).onFocusChanged { focus ->
                                if (focus.isFocused) sttScope.launch {
                                    delay(180)
                                    opinionCompleteBringIntoView.bringIntoView()
                                }
                            },
                            minLines = 4,
                            maxLines = Int.MAX_VALUE
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(opinionCompleteBringIntoView)
                ) {
                    Button(
                        onClick = { submitOpinion(true) },
                        enabled = residentOpinion.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Success)
                    ) { Text("\uc810\uac80\uc790 \uc785\ub825\uc644\ub8cc", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    Button(
                        onClick = ::adoptAiOpinion,
                        modifier = Modifier
                            .weight(0.78f)
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) { Text("AI \ucd94\ub860\n\u2193", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                }
                if (precisionMeasurement) {
                    Surface(color = Color(0xFFFFF3E0), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().padding(9.dp)) {
                            Text("?? ?? ?? ? ??? ??", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                            Text(if (referenceMarkerCaptured) "40mm ?? ??? ??? ???????. ??? ???? ?? ??? ??????." else "1~2mm ??? ??? ?? ?? ??? ???? ?????.", fontSize = 10.sp, color = Color(0xFF6D4C41))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f).forEach { value ->
                                    TextButton(onClick = { measuredGapMm = value }) {
                                        Text("${value}mm", color = if (measuredGapMm == value) Color(0xFFE65100) else PrimaryDark, fontSize = 11.sp)
                                    }
                                }
                            }
                            if (measuredGapMm != null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { gaugePassed = true }) { Text("???", color = if (gaugePassed == true) Success else PrimaryDark) }
                                    TextButton(onClick = { gaugePassed = false }) { Text("???", color = if (gaugePassed == false) Color(0xFFC62828) else PrimaryDark) }
                                    Text("??: ${measuredGapMm}mm", fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Primary, shape = RoundedCornerShape(50)) {
                        Text("2", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
                    }
                    Text("  2. AI 추론", color = PrimaryDark, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(3.dp))
                Text("\uc138\ubd80\ubd80\uc704AI", color = PrimaryDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                ExposedDropdownMenuBox(
                    expanded = detailMenuExpanded,
                    onExpandedChange = { detailMenuExpanded = !detailMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = areaDetail.ifBlank { "-" }, onValueChange = {}, readOnly = true,
                        singleLine = true, label = { Text(detailMenuTitle, fontSize = 11.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(detailMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                    )
                    ExposedDropdownMenu(
                        expanded = detailMenuExpanded,
                        onDismissRequest = { detailMenuExpanded = false }
                    ) {
                        detailOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = { areaDetail = option; detailMenuExpanded = false }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("\uc790\uc720 \uc785\ub825") },
                            onClick = { areaDetail = ""; detailMenuExpanded = false }
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                OutlinedTextField(
                    value = finalPath,
                    onValueChange = { finalPath = it; finalPathEdited = true }, visualTransformation = hierarchyLabelVisualTransformation,
                    placeholder = { Text("AI 추론 결과 및 점검자 추가 의견", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = Int.MAX_VALUE,
                    label = { Text("AI 추론", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                )
                Surface(
                    color = Color(0xFFFFFCFB),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Column(Modifier.padding(9.dp)) {
                        Text("최종 의견", color = PrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("\uc810\uac80\uc790 \uc758\uacac", color = Color(0xFFC62828), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        Text(residentOpinion.ifBlank { "\uc810\uac80\uc790 \uc758\uacac \uc5c6\uc74c" }, color = PrimaryDark, fontSize = 11.sp, maxLines = 4)
                        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFD1C4E9)).padding(top = 6.dp))
                        Text("AI 의견", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        Text(finalPath.ifBlank { "AI 추론 버튼을 누르면 사진 기반 분류 결과가 표시됩니다." }, color = Purple, fontSize = 11.sp, maxLines = 5)
                    }
                }
                Spacer(Modifier.height(7.dp))
                Button(
                    onClick = { submitOpinion(true) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    enabled = residentOpinion.isNotBlank() || finalPath.isNotBlank()
                ) { Text("\uc810\uac80\uc790 \uc785\ub825\uc644\ub8cc", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
private val hierarchyLabelPattern = Regex("(?:^|\\s)(\\uc704\\uce58|\\uc7a5\\uc18c|\\uc138\\ubd80\\ubd80\\uc704|\\uacf5\\uc885|\\uc6d0\\uc778):")

private val hierarchyLabelVisualTransformation = VisualTransformation { text ->
    val builder = AnnotatedString.Builder(text)
    hierarchyLabelPattern.findAll(text.text).forEach { match ->
        val labelRange = match.groups[1]?.range ?: return@forEach
        // Apply red only to the field name and its colon, never to its value.
        builder.addStyle(
            SpanStyle(color = Color(0xFFC62828), fontWeight = FontWeight.Bold),
            labelRange.first,
            labelRange.last + 2
        )
    }
    TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
}

private fun defaultTradeForDetail(detail: String): String = when {
    detail.contains("도장") -> "도장공사"
    detail.contains("벽지") || detail.contains("도배") -> "도배공사"
    detail.contains("타일") -> "타일공사"
    detail.contains("몰딩") || detail.contains("문틀") || detail.contains("창틀") -> "창호·목공사"
    detail.contains("콘센트") || detail.contains("스위치") -> "전기공사"
    detail.contains("배수") || detail.contains("배관") -> "설비공사"
    detail.contains("마루") || detail.contains("바닥") -> "바닥공사"
    else -> "마감공사"
}

internal fun cleanRecommendation(value: String): String =
    value
        .replace("확인 필요", "", ignoreCase = true)
        .replace("확인필요", "", ignoreCase = true)
        .split(Regex("""\s*(?:>|→|/|\||\.)\s*"""))
        .map { it.trim().trim(',', '·', '-', ' ') }
        .filter { it.isNotBlank() }
        .joinToString(".")

internal fun mergeRecommendations(vararg values: String): String =
    values
        .flatMap { cleanRecommendation(it).split(Regex("""\s*(?:\.|>|,)\s*""")) }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(".")

internal fun combineSttTranscript(existing: String, spoken: String): String =
    listOf(existing.trim(), spoken.trim())
        .filter(String::isNotBlank)
        .joinToString("\\n")

private fun surfaceLabel(surface: Surface): String = when (surface) {
    Surface.CEILING -> "천장"
    Surface.WALL -> "벽체"
    Surface.FLOOR -> "바닥"
}

private fun detailOptionsFor(surface: Surface): List<String> = when (surface) {
    Surface.CEILING -> listOf("천장 마감재", "도배지", "도장면", "몰딩", "조명·점검구", "배관 흔적", "균열부")
    Surface.WALL -> listOf("벽지", "도장면", "타일", "몰딩", "문틀·창틀 주변", "콘센트·스위치", "균열부")
    Surface.FLOOR -> listOf("마루·바닥재", "타일", "걸레받이", "문턱", "배수구 주변", "난방·들뜸 부위")
}

private val opinionEndCommandPattern =
    Regex("""(?:^|\s)(끝|마감|엔터|종료|엔드)\s*[.!?。]?\s*$""")

internal fun containsOpinionEndCommand(value: String): Boolean =
    opinionEndCommandPattern.containsMatchIn(value.trim())

internal fun removeOpinionEndCommand(value: String): String =
    value.replace(opinionEndCommandPattern, "").trim()

internal fun speechRecognitionErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "마이크 음성을 읽지 못했습니다. 다시 시도하세요."
    SpeechRecognizer.ERROR_CLIENT -> "음성 인식이 중단되었습니다. 다시 시도하세요."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
        "마이크 권한이 필요합니다. 앱 설정에서 마이크를 허용하세요."
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        "네트워크 상태가 좋지 않습니다. 연결을 확인한 뒤 다시 시도하세요."
    SpeechRecognizer.ERROR_NO_MATCH -> "음성을 문장으로 인식하지 못했습니다. 다시 말씀해 주세요."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식기가 사용 중입니다. 잠시 후 다시 시도하세요."
    SpeechRecognizer.ERROR_SERVER -> "음성 인식 서버에 연결하지 못했습니다. 다시 시도하세요."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리가 감지되지 않았습니다. 다시 말씀해 주세요."
    else -> "음성 인식 중 오류가 발생했습니다. 다시 시도하세요."
}

data class AiSuggestionUi(
    val pathText: String,
    val confidence: Float,
    val rationale: String
)

@Composable
private fun SectionHeader(
    iconLabel: String,
    title: String,
    trailing: String? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Primary, shape = RoundedCornerShape(50)) {
            Text(
                iconLabel,
                color = Color.White,
                fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Text(
            "  $title",
            fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = PrimaryDark,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(trailing,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
        }
    }
}

@Composable
private fun TwinHeader(label: String, tint: Color, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label,
            fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = tint,
            modifier = Modifier.weight(1f))
        if (trailing != null) {
            Surface(color = tint, shape = RoundedCornerShape(6.dp)) {
                Text(trailing,
                    color = Color.White,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tint: Color, onClick: () -> Unit) {
    Surface(
        color = if (selected) tint else Color(0xFFECEFF1),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            color = if (selected) Color.White else Color.DarkGray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

private fun Modifier.miniBoundaryLine(yNorm: Float?): Modifier = drawBehind {
    // Do not draw generic thirds. The amber line is visible only when an
    // on-device image edge was detected as a ceiling-wall or wall-floor junction.
    yNorm?.let {
        val y = size.height * it.coerceIn(0.05f, 0.95f)
        drawLine(Color(0xFFFFB300), Offset(0f, y), Offset(size.width, y), strokeWidth = 3f)
    }
}
