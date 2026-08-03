package com.axlife.pinset.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FlashModeTest {
    @Test
    fun `flash button cycles auto on off`() {
        assertEquals(FlashMode.ON, FlashMode.AUTO.next())
        assertEquals(FlashMode.OFF, FlashMode.ON.next())
        assertEquals(FlashMode.AUTO, FlashMode.OFF.next())
    }
}
