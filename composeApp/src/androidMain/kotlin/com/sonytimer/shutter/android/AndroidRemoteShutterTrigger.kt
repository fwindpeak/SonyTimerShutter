package com.sonytimer.shutter.android

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KeyDecision

/**
 * Android 端小米手环 / 蓝牙遥控快门监听器。
 *
 * 同时支持两条通路以兼顾不同手机系统（包含鸿蒙 HarmonyOS）：
 * 1. 前台 HID / 硬件按键拦截（音量 +/-、拍照键、外接 Enter、媒体键）；
 * 2. 活跃 MediaSession + VolumeProvider（小米手环在任意手机上最稳定的音乐控制通路：播放/暂停、切歌、音量 +/-）。
 */
class AndroidRemoteShutterTrigger(
    context: Context,
    private val onTrigger: (sourceLabel: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val debouncer = TriggerDebouncer(windowMs = 650L)

    private var enabled: Boolean = true
    private var inForeground: Boolean = false
    private var mediaSession: MediaSession? = null
    private var lastTitle: String = "索尼定时快门"
    private var lastSubtitle: String = "点播放/切歌/音量触发"

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        syncMediaSessionActive()
    }

    fun setForeground(inForeground: Boolean) {
        if (this.inForeground == inForeground) return
        this.inForeground = inForeground
        syncMediaSessionActive()
    }

    fun updateSessionDisplay(title: String, subtitle: String) {
        if (lastTitle == title && lastSubtitle == subtitle) return
        lastTitle = title
        lastSubtitle = subtitle
        val session = mediaSession ?: return
        if (enabled && inForeground && session.isActive) {
            pushMetadata(session)
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        val isExternal = runCatching { event.device?.isExternal == true }.getOrDefault(false)
        val decision = RemoteShutterTriggerPolicy.classifyKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            isExternalDevice = isExternal
        )
        return when (decision) {
            KeyDecision.IGNORE -> false
            KeyDecision.CONSUME_ONLY -> true
            KeyDecision.TRIGGER_AND_CONSUME -> {
                val label = RemoteShutterTriggerPolicy.sourceLabelForKeyCode(event.keyCode, isExternal)
                dispatchTrigger(label)
                true
            }
        }
    }

    fun release() {
        inForeground = false
        val session = mediaSession ?: return
        mediaSession = null
        runCatching {
            session.isActive = false
            session.setCallback(null)
            session.release()
        }
    }

    private fun syncMediaSessionActive() {
        val shouldActivate = enabled && inForeground
        if (shouldActivate) {
            val session = ensureMediaSession() ?: return
            runCatching {
                session.setPlaybackState(buildPlaybackState(active = true))
                pushMetadata(session)
                session.isActive = true
            }.onFailure {
                Log.w(TAG, "Failed to activate MediaSession", it)
            }
        } else {
            val session = mediaSession ?: return
            runCatching {
                session.setPlaybackState(buildPlaybackState(active = false))
                session.isActive = false
            }.onFailure {
                Log.w(TAG, "Failed to deactivate MediaSession", it)
            }
        }
    }

    private fun ensureMediaSession(): MediaSession? {
        mediaSession?.let { return it }
        return runCatching {
            MediaSession(appContext, SESSION_TAG).apply {
                @Suppress("DEPRECATION")
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                            val event = extractKeyEvent(mediaButtonIntent)
                            if (event != null &&
                                event.action == KeyEvent.ACTION_DOWN &&
                                event.repeatCount == 0
                            ) {
                                dispatchTrigger("手环媒体控制")
                            }
                            return true
                        }

                        override fun onPlay() {
                            dispatchTrigger("手环音乐控制")
                        }

                        override fun onPause() {
                            dispatchTrigger("手环音乐控制")
                        }

                        override fun onStop() {
                            dispatchTrigger("手环音乐控制")
                        }

                        override fun onSkipToNext() {
                            dispatchTrigger("手环音乐控制")
                        }

                        override fun onSkipToPrevious() {
                            dispatchTrigger("手环音乐控制")
                        }
                    },
                    mainHandler
                )
                setPlaybackToRemote(createRemoteVolumeProvider())
                mediaSession = this
            }
        }.onFailure {
            Log.w(TAG, "Failed to create MediaSession", it)
        }.getOrNull()
    }

    private fun createRemoteVolumeProvider(): VolumeProvider {
        return object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                if (direction == 0) return
                mainHandler.post {
                    runCatching { currentVolume = 50 }
                    dispatchTrigger("手环音量遥控")
                }
            }

            override fun onSetVolumeTo(volume: Int) {
                if (volume == currentVolume) return
                mainHandler.post {
                    runCatching { currentVolume = 50 }
                    dispatchTrigger("手环音量遥控")
                }
            }
        }
    }

    private fun extractKeyEvent(intent: Intent): KeyEvent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    private fun pushMetadata(session: MediaSession) {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, lastTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, lastSubtitle)
            .build()
        session.setMetadata(metadata)
    }

    private fun buildPlaybackState(active: Boolean): PlaybackState {
        return if (active) {
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                .build()
        } else {
            PlaybackState.Builder()
                .setActions(0L)
                .setState(PlaybackState.STATE_NONE, 0L, 0f)
                .build()
        }
    }

    private fun dispatchTrigger(sourceLabel: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dispatchTrigger(sourceLabel) }
            return
        }
        if (!enabled || !inForeground) return
        if (!debouncer.tryAcquire(SystemClock.elapsedRealtime())) {
            Log.d(TAG, "Debounced duplicate remote trigger from $sourceLabel")
            return
        }
        Log.d(TAG, "Remote shutter trigger accepted from $sourceLabel")
        onTrigger(sourceLabel)
    }

    private companion object {
        const val TAG = "RemoteShutterTrigger"
        const val SESSION_TAG = "SonyTimerShutterRemote"
    }
}
