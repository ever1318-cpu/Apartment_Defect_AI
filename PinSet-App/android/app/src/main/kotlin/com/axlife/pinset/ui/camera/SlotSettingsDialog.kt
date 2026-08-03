package com.axlife.pinset.ui.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.axlife.pinset.data.SlotPrefs
import com.axlife.pinset.ui.theme.Primary
import com.axlife.pinset.ui.theme.PrimaryDark
import com.axlife.pinset.ui.theme.Success
import com.axlife.pinset.ui.theme.Warning

@Composable
fun SlotSettingsDialog(
    initial: SlotPrefs,
    /** Current rule-of-thirds grid visibility. Null hides the toggle row. */
    showGrid: Boolean = true,
    /** Null in anchor capture: only defect photos have close/wide selection. */
    defectPhotoMode: DefectPhotoMode? = null,
    precisionMeasurementEnabled: Boolean = false,
    onPrecisionMeasurementChange: (Boolean) -> Unit = {},
    referenceMarkerEnabled: Boolean = false,
    onReferenceMarkerChange: (Boolean) -> Unit = {},
    onToggleGrid: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onDefectPhotoModeChange: (DefectPhotoMode) -> Unit = {},
    onSave: (SlotPrefs) -> Unit
) {
    var a by remember { mutableStateOf(initial.a) }
    var b by remember { mutableStateOf(initial.b) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\ucd2c\uc601 \uc124\uc815", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "셔터 1회에 2장이 찍힙니다. 상단은 하자 부위를 자세히, 하단은 위치 파악용 넓은 화각으로 촬영됩니다.",
                    fontSize = 12.sp, color = Color.Gray
                )
                Spacer(Modifier.height(12.dp))
                SlotRow("상단 · 고배율 (망원)", a, 1f, 100f, PrimaryDark) { a = snap(it) }
                Spacer(Modifier.height(12.dp))
                SlotRow("하단 · 저배율 (광각)", b, 0.05f, 1.5f, Warning) { b = snap(it) }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { a = 20f; b = 0.5f },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("기본값(20x · 0.5x)로 초기화") }
                Spacer(Modifier.height(4.dp))
                Text(
                    "* 하드웨어가 지원하지 않는 배율은 자동으로 디지털 축소/확대되며 사진에 '*' 표시가 붙습니다.",
                    fontSize = 11.sp, color = Color.Gray
                )
                if (defectPhotoMode != null) {
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "하자 촬영 방식",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark
                    )
                    Text(
                        "기본은 근경 + 원경입니다. 근경은 상세 확인용, 원경은 위치 확인용으로 연속 촬영합니다.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PhotoModeButton(
                            label = "근경 1장",
                            selected = defectPhotoMode == DefectPhotoMode.CLOSE_ONLY,
                            modifier = Modifier.weight(1f),
                            onClick = { onDefectPhotoModeChange(DefectPhotoMode.CLOSE_ONLY) }
                        )
                        PhotoModeButton(
                            label = "근경 + 원경",
                            selected = defectPhotoMode == DefectPhotoMode.CLOSE_AND_WIDE,
                            modifier = Modifier.weight(1f),
                            onClick = { onDefectPhotoModeChange(DefectPhotoMode.CLOSE_AND_WIDE) }
                        )
                    }
                }
                if (defectPhotoMode != null) {
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text("\uc815\ubc00 \ud2c8\uc0c8 \uce21\uc815", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                    Text(
                        "1~2 mm \ud2c8\uc0c8\ub97c \ud655\uc778\ud560 \ub54c \ucf1c\uc138\uc694. \ucd2c\uc601 \ud6c4 \uc0c1\uc138 \uc785\ub825\uc5d0\uc11c \uce21\uc815\uac12\uc744 \uae30\ub85d\ud569\ub2c8\ub2e4.",
                        fontSize = 11.sp, color = Color.Gray
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("\uc815\ubc00 \ud2c8\uc0c8 \uce21\uc815 \ucf1c\uae30", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PrimaryDark)
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.Switch(
                            checked = precisionMeasurementEnabled,
                            onCheckedChange = onPrecisionMeasurementChange,
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Warning
                            )
                        )
                    }
                    if (precisionMeasurementEnabled) {
                        Spacer(Modifier.height(6.dp))
                        Text("\uae30\uc900 \ub9c8\ucee4 \uc124\uc815", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                        Text("40 mm \uae30\uc900 \ub9c8\ucee4\ub97c \uc0ac\uc9c4 \ud654\uba74\uc5d0 \ud568\uaed8 \ub193\uc73c\uba74 \uce21\uc815 \ucd94\uc815 \uc815\ud655\ub3c4\uac00 \ub192\uc544\uc9d1\ub2c8\ub2e4.", fontSize = 11.sp, color = Color.Gray)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("40 mm \uae30\uc900 \ub9c8\ucee4 \uc0ac\uc6a9", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            androidx.compose.material3.Switch(checked = referenceMarkerEnabled, onCheckedChange = onReferenceMarkerChange, colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Primary))
                        }
                    }
                }
                if (onToggleGrid != null) {
                    Spacer(Modifier.height(14.dp))
                    androidx.compose.material3.HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text("프리뷰 오버레이",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("3×3 격자선 표시",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 8.dp))
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f).fillMaxWidth().height(0.dp))
                        androidx.compose.material3.Switch(
                            checked = showGrid,
                            onCheckedChange = { onToggleGrid() },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Primary
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(SlotPrefs(a, b)) },
                colors = ButtonDefaults.buttonColors(containerColor = Success)
            ) { Text("저장") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun PhotoModeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) Primary else Color(0xFFF1F4F6),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .height(38.dp)
            .clickable(onClick = onClick)
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                label,
                color = if (selected) Color.White else PrimaryDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SlotRow(label: String, value: Float, min: Float, max: Float, tint: Color, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f).fillMaxWidth().height(0.dp))
            Surface(color = tint, shape = RoundedCornerShape(12.dp)) {
                Text(
                    SlotPrefs.format(value),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

/** Snap to 0.05 increments (below 1x) or 1.0 (above 1x). */
private fun snap(v: Float): Float =
    if (v >= 1f) kotlin.math.round(v) else kotlin.math.round(v * 20f) / 20f
