package com.sonytimer.shutter.android

/**
 * 远程快门按键分类与防抖策略（纯 Kotlin 实现，不依赖 Android Framework 类以支持 JVM 单元测试）。
 *
 * 支持：
 * 1. 小米手环 9 Pro 遥控拍照 / 蓝牙自拍器发送的 HID 按键（音量 +/-、拍照键、外接 Enter）
 * 2. 小米手环 9 Pro 音乐控制发送的媒体键（播放/暂停、切歌、耳机线控）
 */
internal object RemoteShutterTriggerPolicy {

    const val ACTION_DOWN: Int = 0
    const val ACTION_UP: Int = 1

    const val KEYCODE_VOLUME_UP: Int = 24
    const val KEYCODE_VOLUME_DOWN: Int = 25
    const val KEYCODE_CAMERA: Int = 27
    const val KEYCODE_ENTER: Int = 66
    const val KEYCODE_HEADSETHOOK: Int = 79
    const val KEYCODE_MEDIA_PLAY_PAUSE: Int = 85
    const val KEYCODE_MEDIA_NEXT: Int = 87
    const val KEYCODE_MEDIA_PREVIOUS: Int = 88
    const val KEYCODE_MEDIA_PLAY: Int = 126
    const val KEYCODE_MEDIA_PAUSE: Int = 127

    enum class KeyDecision {
        IGNORE,
        CONSUME_ONLY,
        TRIGGER_AND_CONSUME
    }

    fun classifyKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        isExternalDevice: Boolean
    ): KeyDecision {
        val isUniversalTrigger = keyCode == KEYCODE_VOLUME_UP ||
            keyCode == KEYCODE_VOLUME_DOWN ||
            keyCode == KEYCODE_CAMERA ||
            keyCode == KEYCODE_HEADSETHOOK ||
            keyCode == KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KEYCODE_MEDIA_PLAY ||
            keyCode == KEYCODE_MEDIA_PAUSE ||
            keyCode == KEYCODE_MEDIA_NEXT ||
            keyCode == KEYCODE_MEDIA_PREVIOUS

        val isExternalHidEnter = keyCode == KEYCODE_ENTER && isExternalDevice
        if (!isUniversalTrigger && !isExternalHidEnter) {
            return KeyDecision.IGNORE
        }

        return if (action == ACTION_DOWN && repeatCount == 0) {
            KeyDecision.TRIGGER_AND_CONSUME
        } else {
            KeyDecision.CONSUME_ONLY
        }
    }

    fun sourceLabelForKeyCode(keyCode: Int, isExternalDevice: Boolean): String {
        if (isExternalDevice) return "手环/蓝牙遥控"
        return when (keyCode) {
            KEYCODE_HEADSETHOOK,
            KEYCODE_MEDIA_PLAY_PAUSE,
            KEYCODE_MEDIA_PLAY,
            KEYCODE_MEDIA_PAUSE,
            KEYCODE_MEDIA_NEXT,
            KEYCODE_MEDIA_PREVIOUS -> "手环媒体控制"
            KEYCODE_CAMERA -> "快门键"
            KEYCODE_VOLUME_UP,
            KEYCODE_VOLUME_DOWN -> "音量键"
            else -> "遥控按键"
        }
    }
}

internal class TriggerDebouncer(
    private val windowMs: Long = 650L
) {
    private var lastTriggerMs: Long = Long.MIN_VALUE

    fun tryAcquire(nowMs: Long): Boolean {
        if (lastTriggerMs != Long.MIN_VALUE && nowMs >= lastTriggerMs && nowMs - lastTriggerMs < windowMs) {
            return false
        }
        lastTriggerMs = nowMs
        return true
    }
}
