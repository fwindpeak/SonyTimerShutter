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

    override fun getWifiHost(defaultValue: String): String {
        return prefs.getString(KEY_WIFI_HOST, defaultValue) ?: defaultValue
    }

    override fun setWifiHost(host: String) {
        prefs.edit().putString(KEY_WIFI_HOST, host).apply()
    }

    override fun isWifiUseSsh(defaultValue: Boolean): Boolean {
        return prefs.getBoolean(KEY_WIFI_USE_SSH, defaultValue)
    }

    override fun setWifiUseSsh(useSsh: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_USE_SSH, useSsh).apply()
    }

    override fun getWifiSshUsername(defaultValue: String): String {
        return prefs.getString(KEY_WIFI_SSH_USERNAME, defaultValue) ?: defaultValue
    }

    override fun setWifiSshUsername(username: String) {
        prefs.edit().putString(KEY_WIFI_SSH_USERNAME, username).apply()
    }

    override fun getWifiSshPassword(defaultValue: String): String {
        return prefs.getString(KEY_WIFI_SSH_PASSWORD, defaultValue) ?: defaultValue
    }

    override fun setWifiSshPassword(password: String) {
        prefs.edit().putString(KEY_WIFI_SSH_PASSWORD, password).apply()
    }

    override fun isPreviewMirrored(defaultValue: Boolean): Boolean {
        return prefs.getBoolean(KEY_PREVIEW_MIRRORED, defaultValue)
    }

    override fun setPreviewMirrored(mirrored: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW_MIRRORED, mirrored).apply()
    }

    override fun isRemoteTriggerEnabled(defaultValue: Boolean): Boolean {
        return prefs.getBoolean(KEY_REMOTE_TRIGGER_ENABLED, defaultValue)
    }

    override fun setRemoteTriggerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMOTE_TRIGGER_ENABLED, enabled).apply()
    }

    override fun getPtpClientGuid(): String {
        return prefs.getString(KEY_PTP_CLIENT_GUID, "").orEmpty()
    }

    override fun setPtpClientGuid(guidHex: String) {
        prefs.edit().putString(KEY_PTP_CLIENT_GUID, guidHex).apply()
    }

    private companion object {
        const val KEY_TIMER_SECONDS = "timer_seconds"
        const val KEY_BURST_ENABLED = "burst_enabled"
        const val KEY_BURST_COUNT = "burst_count"
        const val KEY_BURST_INTERVAL_SECONDS = "burst_interval_seconds"
        const val KEY_WIFI_HOST = "wifi_host"
        const val KEY_WIFI_USE_SSH = "wifi_use_ssh"
        const val KEY_WIFI_SSH_USERNAME = "wifi_ssh_username"
        const val KEY_WIFI_SSH_PASSWORD = "wifi_ssh_password"
        const val KEY_PREVIEW_MIRRORED = "preview_mirrored"
        const val KEY_REMOTE_TRIGGER_ENABLED = "remote_trigger_enabled"
        const val KEY_PTP_CLIENT_GUID = "ptp_client_guid"
    }
}
