package com.sonytimer.shutter.android

import android.content.Context
import com.sonytimer.shutter.controller.TimerPreferences

class AndroidTimerPreferences(context: Context) : TimerPreferences {
    private val prefs = context.applicationContext.getSharedPreferences(
        "sony_timer_shutter_prefs",
        Context.MODE_PRIVATE
    )

    override fun getTimerSeconds(defaultValue: Int): Int {
        return prefs.getInt(KEY_TIMER_SECONDS, defaultValue)
    }

    override fun setTimerSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_TIMER_SECONDS, seconds).apply()
    }

    override fun isBurstEnabled(defaultValue: Boolean): Boolean {
        return prefs.getBoolean(KEY_BURST_ENABLED, defaultValue)
    }

    override fun setBurstEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BURST_ENABLED, enabled).apply()
    }

    override fun getBurstCount(defaultValue: Int): Int {
        return prefs.getInt(KEY_BURST_COUNT, defaultValue)
    }

    override fun setBurstCount(count: Int) {
        prefs.edit().putInt(KEY_BURST_COUNT, count).apply()
    }

    override fun getBurstIntervalSeconds(defaultValue: Int): Int {
        return prefs.getInt(KEY_BURST_INTERVAL_SECONDS, defaultValue)
    }

    override fun setBurstIntervalSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_BURST_INTERVAL_SECONDS, seconds).apply()
    }

    private companion object {
        const val KEY_TIMER_SECONDS = "timer_seconds"
        const val KEY_BURST_ENABLED = "burst_enabled"
        const val KEY_BURST_COUNT = "burst_count"
        const val KEY_BURST_INTERVAL_SECONDS = "burst_interval_seconds"
    }
}
