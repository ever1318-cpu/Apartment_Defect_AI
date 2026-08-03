package com.axlife.pinset.camera

enum class FlashMode(val label: String) {
    AUTO("자동"),
    ON("켜짐"),
    OFF("꺼짐");

    fun next(): FlashMode = when (this) {
        AUTO -> ON
        ON -> OFF
        OFF -> AUTO
    }
}
