package com.axlife.pinset.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineSttButtonTest {
    @Test
    fun partial_speech_replaces_only_current_session_tail() {
        assertEquals(
            "기존 의견 거실 창호 주변에 누수 흔적",
            joinSpeech("기존 의견", "거실 창호 주변에 누수 흔적")
        )
    }
}
