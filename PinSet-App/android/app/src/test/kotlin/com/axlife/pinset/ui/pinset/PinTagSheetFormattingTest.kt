package com.axlife.pinset.ui.pinset

import org.junit.Assert.assertEquals
import org.junit.Test

class PinTagSheetFormattingTest {
    @Test
    fun recommendation_is_rendered_as_clean_hierarchy() {
        assertEquals(
            "거실.벽.도장.균열",
            cleanRecommendation("거실 > 벽 → 도장 / 확인필요 | 균열")
        )
    }

    @Test
    fun recommendations_are_merged_without_duplicates() {
        assertEquals(
            "거실.벽.균열.도장",
            mergeRecommendations("거실 > 벽 > 균열", "벽 → 도장", "확인 필요")
        )
    }

    @Test
    fun stt_partial_result_is_combined_with_text_before_current_session() {
        assertEquals(
            "기존 점검 기록\n거실 벽면에 균열이 있습니다",
            combineSttTranscript("기존 점검 기록", "거실 벽면에 균열이 있습니다")
        )
    }

    @Test
    fun blank_stt_parts_do_not_add_extra_spaces() {
        assertEquals("누수 흔적", combineSttTranscript(" ", " 누수 흔적 "))
    }
    @Test
    fun opinion_end_command_is_detected_and_removed() {
        val input = "\uAC70\uC2E4 \uCC9C\uC7A5 \uADE0\uC5F4 \uB9C8\uAC10"

        assertEquals(true, containsOpinionEndCommand(input))
        assertEquals(
            "\uAC70\uC2E4 \uCC9C\uC7A5 \uADE0\uC5F4",
            removeOpinionEndCommand(input)
        )
    }

    @Test
    fun material_word_does_not_finish_opinion() {
        val input = "\uB9C8\uAC10\uC7AC \uADE0\uC5F4"

        assertEquals(false, containsOpinionEndCommand(input))
        assertEquals(input, removeOpinionEndCommand(input))
    }
}
