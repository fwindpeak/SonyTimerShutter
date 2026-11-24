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

    private companion object {
        const val KEY_TIMER_SECONDS = "timer_seconds"
    }
}
