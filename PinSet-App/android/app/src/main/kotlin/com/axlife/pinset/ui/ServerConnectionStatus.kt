package com.axlife.pinset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.axlife.pinset.ai.UrlConnectionAiTransport
import com.axlife.pinset.data.FieldEndpointPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI

private enum class FieldConnectionState { CHECKING, CONNECTED, UNAVAILABLE, NOT_CONFIGURED }

/** Compact, real health-check indicator for the field VPN/server endpoint. */
@Composable
fun ServerConnectionStatus() {
    val context = LocalContext.current
    val endpoint = FieldEndpointPrefs.load(context)
    var state by remember(endpoint) { mutableStateOf(FieldConnectionState.CHECKING) }
    val isVpnEndpoint = runCatching { URI(endpoint).host?.startsWith("100.") == true }.getOrDefault(false)
    val channel = if (isVpnEndpoint) "VPN" else "서버"

    LaunchedEffect(endpoint) {
        if (endpoint.isBlank()) {
            state = FieldConnectionState.NOT_CONFIGURED
            return@LaunchedEffect
        }
        while (true) {
            state = FieldConnectionState.CHECKING
            state = runCatching {
                withContext(Dispatchers.IO) {
                    UrlConnectionAiTransport(connectTimeoutMs = 3_000, readTimeoutMs = 4_000)
                        .request("GET", "$endpoint/health", emptyMap(), null)
                        .status in 200..299
                }
            }.getOrDefault(false).let { connected ->
                if (connected) FieldConnectionState.CONNECTED else FieldConnectionState.UNAVAILABLE
            }
            delay(15_000)
        }
    }

    val (label, color) = when (state) {
        FieldConnectionState.CONNECTED -> "$channel 연결" to Color(0xFF067647)
        FieldConnectionState.CHECKING -> "$channel 확인 중" to Color(0xFF9A6700)
        FieldConnectionState.UNAVAILABLE -> "$channel 미연결" to Color(0xFFB42318)
        FieldConnectionState.NOT_CONFIGURED -> "서버 주소 없음" to Color(0xFF667085)
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFFF8FAFC)).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("  $label", style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
    }
}
