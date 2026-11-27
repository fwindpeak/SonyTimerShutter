package com.sonytimer.shutter.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.sonytimer.shutter.android.RemoteShutterTriggerPolicy.KeyDecision

/**
 * Android 端手表 / 手环 / 蓝牙遥控快门监听器。
 *
 * 同时支持三条通路，兼顾标准 Android、小米手环、蓝牙自拍器以及华为手表 + 鸿蒙 (EMUI / HarmonyOS)：
 * 1. 前台 HID / 硬件按键拦截（音量 +/-、拍照键、外接 Enter、媒体键）；
 * 2. 标准本地 MediaSession (`PLAYBACK_TYPE_LOCAL`) + AudioFocus + 短静音激活，
 *    确保华为播控中心、华为手表「音乐控制」与小米手环音乐控制都能识别并路由到本 App；
 * 3. 前台 `STREAM_MUSIC` 音量变化监听，接住华为手表旋转表冠 / 音乐音量 +/- 遥控事件。
 */
class AndroidRemoteShutterTrigger(
    context: Context,
    private val onTrigger: (sourceLabel: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val debouncer = TriggerDebouncer(windowMs = 650L)

    private var enabled: Boolean = true
    private var inForeground: Boolean = false
    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    private var volumeReceiverRegistered: Boolean = false
    private var lastTitle: String = "索尼定时快门"
    private var lastSubtitle: String = "点播放/切歌/音量触发"

    @Volatile
    private var ignoreVolumeRestoreTarget: Int = -1
    @Volatile
    private var ignoreVolumeRestoreUntilMs: Long = 0L

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            hasAudioFocus = false
        }
    }

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_VOLUME_CHANGED) return
            if (!enabled || !inForeground) return

            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            val newVolume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
            val prevVolume = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)

            val nowMs = SystemClock.elapsedRealtime()
            val isSelfRestore = nowMs <= ignoreVolumeRestoreUntilMs &&
                newVolume == ignoreVolumeRestoreTarget
            if (isSelfRestore) {
                ignoreVolumeRestoreTarget = -1
                ignoreVolumeRestoreUntilMs = 0L
                return
            }

            if (!RemoteShutterTriggerPolicy.shouldTriggerOnMusicVolumeChange(
                    streamType = streamType,
                    newVolume = newVolume,
                    prevVolume = prevVolume,
                    isSelfRestore = false
                )
            ) {
                return
            }

            restoreMusicVolumeQuietly(prevVolume = prevVolume, newVolume = newVolume)
            dispatchTrigger("手表/手环音量遥控")
        }
    }

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
        unregisterVolumeReceiver()
        abandonAudioFocus()
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
            val wasActive = session.isActive
            requestAudioFocus()
            runCatching {
                session.setPlaybackState(buildPlaybackState(active = true))
                pushMetadata(session)
                session.isActive = true
            }.onFailure {
                Log.w(TAG, "Failed to activate MediaSession", it)
            }
            if (!wasActive) {
                primeSilentAudioSessionAsync()
            }
            registerVolumeReceiver()
        } else {
            unregisterVolumeReceiver()
            abandonAudioFocus()
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
                // 使用本地媒体属性（PLAYBACK_TYPE_LOCAL），确保华为播控中心与手表音乐控制能识别本 App
                setPlaybackToLocal(mediaAudioAttributes())
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                            val event = extractKeyEvent(mediaButtonIntent)
                            if (event != null &&
                                event.action == KeyEvent.ACTION_DOWN &&
                                event.repeatCount == 0
                            ) {
                                dispatchTrigger("手表/手环媒体控制")
                            }
                            return true
                        }

                        override fun onPlay() {
                            dispatchTrigger("手表/手环音乐控制")
                        }

                        override fun onPause() {
                            dispatchTrigger("手表/手环音乐控制")
                        }

                        override fun onStop() {
                            dispatchTrigger("手表/手环音乐控制")
                        }

                        override fun onSkipToNext() {
                            dispatchTrigger("手表/手环音乐控制")
                        }

                        override fun onSkipToPrevious() {
                            dispatchTrigger("手表/手环音乐控制")
                        }
                    },
                    mainHandler
                )
                mediaSession = this
            }
        }.onFailure {
            Log.w(TAG, "Failed to create MediaSession", it)
        }.getOrNull()
    }

    private fun mediaAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (hasAudioFocus) return
        runCatching {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(mediaAudioAttributes())
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }.onFailure {
            Log.w(TAG, "Failed to request audio focus", it)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (!hasAudioFocus && audioFocusRequest == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
        }
        hasAudioFocus = false
    }

    /**
     * 播放一段约 60ms 的纯零静音 PCM 并立即释放，促使 Android 8+ 与华为 HarmonyOS / EMUI
     * 播控中心将本 App 的 MediaSession 提升为当前活跃媒体按键接收者。
     */
    private fun primeSilentAudioSessionAsync() {
        Thread(
            {
                runCatching {
                    val sampleRate = 8_000
                    val silentBytes = ByteArray(960) // ~60ms, 16-bit mono PCM silence
                    val minBuf = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(silentBytes.size)
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(mediaAudioAttributes())
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    try {
                        track.write(silentBytes, 0, silentBytes.size)
                        track.play()
                        Thread.sleep(70L)
                        track.stop()
                    } finally {
                        track.release()
                    }
                }.onFailure {
                    Log.d(TAG, "Silent audio prime skipped: ${it.message}")
                }
            },
            "SonyShutterMediaPrime"
        ).start()
    }

    private fun registerVolumeReceiver() {
        if (volumeReceiverRegistered) return
        runCatching {
            val filter = IntentFilter(ACTION_VOLUME_CHANGED)
            ContextCompat.registerReceiver(
                appContext,
                volumeChangeReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            volumeReceiverRegistered = true
        }.onFailure {
            Log.w(TAG, "Failed to register volume change receiver", it)
        }
    }

    private fun unregisterVolumeReceiver() {
        if (!volumeReceiverRegistered) return
        volumeReceiverRegistered = false
        runCatching {
            appContext.unregisterReceiver(volumeChangeReceiver)
        }
    }

    private fun restoreMusicVolumeQuietly(prevVolume: Int, newVolume: Int) {
        val am = audioManager ?: return
        runCatching {
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val restored = RemoteShutterTriggerPolicy.computeRestoredVolume(
                prevVolume = prevVolume,
                newVolume = newVolume,
                maxVolume = maxVol
            ) ?: return
            ignoreVolumeRestoreTarget = restored
            ignoreVolumeRestoreUntilMs = SystemClock.elapsedRealtime() + 500L
            am.setStreamVolume(AudioManager.STREAM_MUSIC, restored, 0)
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
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 60_000L)
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
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
