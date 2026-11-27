package com.sonytimer.shutter.android

import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.ACTION_DOWN
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.ACTION_UP
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KEYCODE_CAMERA
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KEYCODE_ENTER
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KEYCODE_HEADSETHOOK
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KEYCODE_MEDIA_NEXT
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KEYCODE_MEDIA_PAUSE
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KEYCODE_MEDIA_PLAY
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KEYCODE_MEDIA_PLAY_PAUSE
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KEYCODE_MEDIA_PREVIOUS
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KEYCODE_VOLUME_DOWN
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KEYCODE_VOLUME_UP
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KeyDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteShutterTriggerPolicyTest {

    @Test
    fun classifyKeyEvent_matchesShutterAndMediaKeysOnlyOnInitialPress() {
        val universalKeys = listOf(
            KEYCODE_VOLUME_UP,
            KEYCODE_VOLUME_DOWN,
            KEYCODE_CAMERA,
            KEYCODE_HEADSETHOOK,
            KEYCODE_MEDIA_PLAY_PAUSE,
            KEYCODE_MEDIA_PLAY,
            KEYCODE_MEDIA_PAUSE,
            KEYCODE_MEDIA_NEXT,
            KEYCODE_MEDIA_PREVIOUS
        )

        for (key in universalKeys) {
            assertEquals(
                KeyDecision.TRIGGER_AND_CONSUME,
                RemoteShutterTriggerPolicy.classifyKeyEvent(key, ACTION_DOWN, repeatCount = 0, isExternalDevice = false)
            )
            assertEquals(
                KeyDecision.CONSUME_ONLY,
                RemoteShutterTriggerPolicy.classifyKeyEvent(key, ACTION_DOWN, repeatCount = 1, isExternalDevice = false)
            )
            assertEquals(
                KeyDecision.CONSUME_ONLY,
                RemoteShutterTriggerPolicy.classifyKeyEvent(key, ACTION_UP, repeatCount = 0, isExternalDevice = false)
            )
        }

        // KEYCODE_ENTER only triggers when originating from an external Bluetooth HID device
        assertEquals(
            KeyDecision.IGNORE,
            RemoteShutterTriggerPolicy.classifyKeyEvent(KEYCODE_ENTER, ACTION_DOWN, repeatCount = 0, isExternalDevice = false)
        )
        assertEquals(
            KeyDecision.TRIGGER_AND_CONSUME,
            RemoteShutterTriggerPolicy.classifyKeyEvent(KEYCODE_ENTER, ACTION_DOWN, repeatCount = 0, isExternalDevice = true)
        )
        assertEquals(
            KeyDecision.CONSUME_ONLY,
            RemoteShutterTriggerPolicy.classifyKeyEvent(KEYCODE_ENTER, ACTION_UP, repeatCount = 0, isExternalDevice = true)
        )

        // System navigation keys such as Back (4) are ignored
        assertEquals(
            KeyDecision.IGNORE,
            RemoteShutterTriggerPolicy.classifyKeyEvent(keyCode = 4, action = ACTION_DOWN, repeatCount = 0, isExternalDevice = true)
        )
    }

    @Test
    fun debouncer_suppressesBurstsWithinWindowAndAllowsAfter() {
        val debouncer = TriggerDebouncer(windowMs = 650L)

        assertTrue(debouncer.tryAcquire(1_000L))
        assertFalse(debouncer.tryAcquire(1_100L))
        assertFalse(debouncer.tryAcquire(1_649L))
        assertTrue(debouncer.tryAcquire(1_650L))
        assertFalse(debouncer.tryAcquire(2_000L))

        // Handles clock rollback safely
        assertTrue(debouncer.tryAcquire(500L))
    }

    @Test
    fun sourceLabelForKeyCode_returnsClearLabels() {
        assertEquals(
            "手表/手环遥控",
            RemoteShutterTriggerPolicy.sourceLabelForKeyCode(KEYCODE_VOLUME_UP, isExternalDevice = true)
        )
        assertEquals(
            "手表/手环媒体控制",
            RemoteShutterTriggerPolicy.sourceLabelForKeyCode(KEYCODE_MEDIA_PLAY_PAUSE, isExternalDevice = false)
        )
        assertEquals(
            "快门键",
            RemoteShutterTriggerPolicy.sourceLabelForKeyCode(KEYCODE_CAMERA, isExternalDevice = false)
        )
        assertEquals(
            "音量键",
            RemoteShutterTriggerPolicy.sourceLabelForKeyCode(KEYCODE_VOLUME_DOWN, isExternalDevice = false)
        )
    }

    @Test
    fun musicVolumeChangePolicy_triggersOnlyOnGenuineMusicStreamChanges() {
        assertTrue(
            RemoteShutterTriggerPolicy.shouldTriggerOnMusicVolumeChange(
                streamType = RemoteShutterTriggerPolicy.STREAM_MUSIC,
                newVolume = 6,
                prevVolume = 5,
                isSelfRestore = false
            )
        )
        assertFalse(
            RemoteShutterTriggerPolicy.shouldTriggerOnMusicVolumeChange(
                streamType = RemoteShutterTriggerPolicy.STREAM_MUSIC,
                newVolume = 5,
                prevVolume = 5,
                isSelfRestore = false
            )
        )
        assertFalse(
            RemoteShutterTriggerPolicy.shouldTriggerOnMusicVolumeChange(
                streamType = RemoteShutterTriggerPolicy.STREAM_MUSIC,
                newVolume = 5,
                prevVolume = 6,
                isSelfRestore = true
            )
        )
        assertFalse(
            RemoteShutterTriggerPolicy.shouldTriggerOnMusicVolumeChange(
                streamType = 2, // STREAM_RING
                newVolume = 4,
                prevVolume = 3,
                isSelfRestore = false
            )
        )
    }

    @Test
    fun computeRestoredVolume_keepsVolumeWithinRepeatableRange() {
        assertEquals(5, RemoteShutterTriggerPolicy.computeRestoredVolume(prevVolume = 5, newVolume = 6, maxVolume = 15))
        assertEquals(1, RemoteShutterTriggerPolicy.computeRestoredVolume(prevVolume = 0, newVolume = 1, maxVolume = 15))
        assertEquals(14, RemoteShutterTriggerPolicy.computeRestoredVolume(prevVolume = 15, newVolume = 14, maxVolume = 15))
        assertEquals(null, RemoteShutterTriggerPolicy.computeRestoredVolume(prevVolume = 5, newVolume = 5, maxVolume = 15))
    }
}
