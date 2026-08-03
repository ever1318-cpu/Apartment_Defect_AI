package com.axlife.pinset.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val inspectionSteps = listOf("초기환경", "하자사진", "하자의견")

@Composable
fun InspectionStepBar(
    currentStep: Int,
    darkBackground: Boolean = false,
    onStepSelected: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val visibleSteps = inspectionSteps
    val visibleCurrentStep = currentStep.coerceIn(0, inspectionSteps.lastIndex)
    val pulse by rememberInfiniteTransition(label = "current-step-pulse")
        .animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700),
                repeatMode = RepeatMode.Reverse
            ),
            label = "current-step-alpha"
        )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        visibleSteps.forEachIndexed { index, label ->
            val selected = index == visibleCurrentStep
            val complete = index < visibleCurrentStep
            Text(
                text = " ${index + 1} $label",
                color = when {
                    selected -> Color.White
                    complete -> Color(0xFF14532D)
                    darkBackground -> Color(0xFFBFC7D5)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 10.sp,
                fontWeight = if (selected || complete) FontWeight.ExtraBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = onStepSelected != null) {
                        onStepSelected?.invoke(index)
                    }
                    .graphicsLayer(alpha = if (selected) pulse else 1f)
                    .background(
                        color = when {
                            selected -> Color(0xFF155EEF)
                            complete -> Color(0xFFDCFCE7)
                            darkBackground -> Color(0xFF273244)
                            else -> Color(0xFFEFF2F6)
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(vertical = 4.5.dp, horizontal = 3.dp)
            )
        }
    }
}
