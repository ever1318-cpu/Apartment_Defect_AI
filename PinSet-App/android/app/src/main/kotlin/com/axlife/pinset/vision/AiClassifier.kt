package com.axlife.pinset.vision

import com.axlife.pinset.data.entity.Surface

/**
 * 3-Source classification's second source ("AI 어시스턴트").
 *
 * The current implementation is a deterministic rule-based stub that mixes
 * a few obvious signals (surface derived from IMU pitch, keyword hits in the
 * resident's opinion, room label) so the UI has plausible content to render.
 *
 * Real classifiers will land here later:
 *   - on-device ML (e.g. TFLite classifier over the 20x close-up), or
 *   - LLM call with the resident's opinion + JPEG data-uri + sensor context.
 *
 * The [classify] contract stays the same: given raw inputs, return a single
 * catalog path string (4-tier: 공간 > 부위 > 자재/부품 > 원인) plus a 0..1
 * confidence. Callers do NOT need to know which backend produced it.
 */
interface AiClassifier {
    fun classify(input: AiInput): AiSuggestion
}

data class AiInput(
    val roomLabel: String?,
    val surface: Surface,
    val residentOpinion: String,
    val focusDistanceM: Float?,
    val headingDeg: Float,
    val pitchDeg: Float
)

data class AiSuggestion(
    val pathText: String,   // "주방발코니 > 벽 > 도장 > 흠집"
    val confidence: Float,  // 0..1
    val rationale: String = ""
)

/**
 * Rule-based placeholder. Look for a couple of common Korean defect keywords
 * in the resident opinion; fall back to a generic (room > surface > 마감 > 확인필요)
 * path when nothing matches. Confidence scales with how many signals agreed.
 */
class RuleBasedAiClassifier : AiClassifier {
    override fun classify(input: AiInput): AiSuggestion {
        val room = input.roomLabel?.takeIf { it.isNotBlank() } ?: "미확인"
        val surface = when (input.surface) {
            Surface.CEILING -> "천장"
            Surface.FLOOR -> "바닥"
            Surface.WALL -> "벽"
        }
        val op = input.residentOpinion

        // Keyword table — cheap, obvious hits. Ordered by specificity.
        val hits = listOf(
            listOf("흠집", "스크래치", "긁힘") to Triple("도장", "흠집", 0.85f),
            listOf("코킹", "실링") to Triple("코킹", "탈락", 0.80f),
            listOf("곰팡이", "누수", "젖", "결로") to Triple("도장", "누수/결로", 0.80f),
            listOf("깨짐", "균열", "크랙") to Triple("마감", "균열", 0.82f),
            listOf("벽지", "도배") to Triple("벽지", "들뜸", 0.75f),
            listOf("타일") to Triple("타일", "파손", 0.75f),
            listOf("문", "도어", "가틀") to Triple("목문가틀", "시공 불량", 0.72f),
            listOf("창", "샤시", "sash") to Triple("PL창", "시공 불량", 0.72f)
        )

        val match = hits.firstOrNull { (kw, _) -> kw.any { op.contains(it) } }
        val (material, cause, baseConf) = match?.second ?: Triple("마감", "확인 필요", 0.45f)

        // Small boosts when independent signals corroborate the guess.
        var conf = baseConf
        if (input.focusDistanceM != null) conf += 0.05f
        if (input.roomLabel != null) conf += 0.05f
        conf = conf.coerceIn(0f, 0.98f)

        val path = "$room > $surface > $material > $cause"
        val rationale = buildString {
            append("표면=$surface")
            input.focusDistanceM?.let { append(" · 거리 ${"%.1f".format(it)}m") }
            append(" · 방향 ${input.headingDeg.toInt()}°")
            if (match != null) append(" · 키워드 매칭")
        }
        return AiSuggestion(path, conf, rationale)
    }
}
