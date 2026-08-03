package com.axlife.pinset.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun InlineSttButton(
    currentText: String,
    onTextChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var sessionBase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val recognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }

    fun begin() {
        if (recognizer == null) {
            status = "이 기기에서는 음성 인식을 사용할 수 없습니다."
            return
        }
        sessionBase = currentText.trim()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { recognizer.startListening(intent) }
            .onSuccess {
                listening = true
                status = "음성 인식을 준비하고 있습니다."
            }
            .onFailure { status = "음성 인식을 시작하지 못했습니다." }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) begin() else status = "마이크 권한을 허용해 주세요."
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                status = "듣고 있습니다."
            }
            override fun onBeginningOfSpeech() { status = "말씀하신 내용을 기록하고 있습니다." }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { status = "음성을 처리하고 있습니다." }
            override fun onError(error: Int) {
                listening = false
                status = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "음성을 인식하지 못했습니다. 다시 말씀해 주세요."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리가 감지되지 않았습니다."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한을 허용해 주세요."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "통신 상태가 좋지 않습니다. 다시 시도해 주세요."
                    else -> "음성 인식이 중단되었습니다. 다시 시도해 주세요."
                }
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (spoken.isNotBlank()) onTextChanged(joinSpeech(sessionBase, spoken))
                status = if (spoken.isBlank()) "인식된 문장이 없습니다." else "음성 입력이 완료되었습니다."
                listening = false
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val spoken = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (spoken.isNotBlank()) onTextChanged(joinSpeech(sessionBase, spoken))
                status = "실시간 음성 입력 중"
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose {
            recognizer?.cancel()
            recognizer?.destroy()
        }
    }

    Column(modifier) {
        TextButton(
            onClick = {
                if (listening) {
                    recognizer?.stopListening()
                    status = "음성을 처리하고 있습니다."
                } else if (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    begin()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Text(if (listening) "  STT 입력 중 · 중지" else "  STT 음성으로 의견 입력")
        }
        Text(
            status.ifBlank { "마이크를 누르고 의견을 말하면 입력란에 바로 기록됩니다." },
            color = Color.Gray,
            fontSize = 11.sp
        )
    }
}

internal fun joinSpeech(existing: String, spoken: String): String =
    listOf(existing.trim(), spoken.trim()).filter(String::isNotBlank).joinToString(" ")
