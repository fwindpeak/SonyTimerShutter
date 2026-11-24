package com.sonytimer.shutter.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.sonytimer.shutter.controller.FeedbackPlayer
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 纯代码实时合成低延迟 PCM 电子滴声与机械咔哒声（不依赖外部音频文件），
 * 搭配 Android VibrationEffect 模拟复古物理按键段落手感。
 */
class AndroidFeedbackPlayer(context: Context) : FeedbackPlayer {

    private val appContext = context.applicationContext
    private val audioExecutor = Executors.newSingleThreadExecutor()

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // 预合成各状态音频样本
    private val tickNormalPcm = synthesizeTone(freqHz = 880.0, durationMs = 68, volume = 0.45)
    private val tickFinalPcm = synthesizeTone(freqHz = 1320.0, durationMs = 92, volume = 0.58)
    private val shutterTriggerPcm = synthesizeTone(freqHz = 1760.0, durationMs = 115, volume = 0.60)
    private val cancelPcm = synthesizeTone(freqHz = 420.0, durationMs = 110, volume = 0.40)
    private val mechanicalClickPcm = synthesizeMechanicalThud(durationMs = 22, volume = 0.55)

    override fun playMechanicalPress() {
        playPcm(mechanicalClickPcm)
        vibrateHeavyClick()
    }

    override fun playCountdownTick(finalTick: Boolean) {
        playPcm(if (finalTick) tickFinalPcm else tickNormalPcm)
        vibrateTick(strong = finalTick)
    }

    override fun playShutterTrigger() {
        playPcm(shutterTriggerPcm)
        vibrateHeavyClick()
    }

    override fun playCancel() {
        playPcm(cancelPcm)
        vibrateTick(strong = false)
    }

    override fun playLongPressDetent() {
        vibrateHeavyClick()
    }

    override fun release() {
        audioExecutor.shutdownNow()
    }

    private fun playPcm(samples: ShortArray) {
        audioExecutor.execute {
            val byteSize = samples.size * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(byteSize)
                .build()

            try {
                track.write(samples, 0, samples.size)
                track.play()
                val playMs = (samples.size * 1000L) / SAMPLE_RATE + 25L
                Thread.sleep(playMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }
    }

    private fun vibrateHeavyClick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            v.vibrate(VibrationEffect.createOneShot(28L, 210))
        }
    }

    private fun vibrateTick(strong: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !strong) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            v.vibrate(VibrationEffect.createOneShot(if (strong) 32L else 14L, if (strong) 180 else 110))
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44100

        fun synthesizeTone(freqHz: Double, durationMs: Int, volume: Double): ShortArray {
            val count = (SAMPLE_RATE * durationMs) / 1000
            val out = ShortArray(count)
            val fadeSamples = (SAMPLE_RATE * 6) / 1000 // 6ms 平滑淡入淡出避免爆音
            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                val env = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i > count - fadeSamples -> (count - i).toDouble() / fadeSamples
                    else -> 1.0
                }
                val sample = sin(2.0 * PI * freqHz * t) * env * volume
                out[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }

        fun synthesizeMechanicalThud(durationMs: Int, volume: Double): ShortArray {
            val count = (SAMPLE_RATE * durationMs) / 1000
            val out = ShortArray(count)
            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                val decay = exp(-t * 140.0)
                val wave = (sin(2.0 * PI * 210.0 * t) * 0.7 + sin(2.0 * PI * 980.0 * t) * 0.3) * decay * volume
                out[i] = (wave * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return out
        }
    }
}
