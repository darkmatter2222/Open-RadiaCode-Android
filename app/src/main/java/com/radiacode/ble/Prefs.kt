package com.radiacode.ble

import android.content.Context

object Prefs {
    private const val FILE = "radiacode_prefs"

    private const val KEY_PREFERRED_ADDRESS = "preferred_address"
    private const val KEY_AUTO_CONNECT = "auto_connect"

    private const val KEY_LAST_USV_H = "last_usv_h"
    private const val KEY_LAST_CPS = "last_cps"
    private const val KEY_LAST_TS_MS = "last_ts_ms"

    private const val KEY_POLL_INTERVAL_MS = "poll_interval_ms"
    private const val KEY_LAST_CSV_TS_MS = "last_csv_ts_ms"

    private const val KEY_SERVICE_STATUS = "service_status"
    private const val KEY_SERVICE_STATUS_TS_MS = "service_status_ts_ms"

    fun getPreferredAddress(context: Context): String? {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_ADDRESS, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun setPreferredAddress(context: Context, address: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERRED_ADDRESS, address?.trim())
            .apply()
    }

    fun isAutoConnectEnabled(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONNECT, true)
    }

    fun setAutoConnectEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONNECT, enabled)
            .apply()
    }

    data class LastReading(
        val uSvPerHour: Float,
        val cps: Float,
        val timestampMs: Long,
    )

    fun setLastReading(context: Context, uSvPerHour: Float, cps: Float, timestampMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LAST_USV_H, uSvPerHour)
            .putFloat(KEY_LAST_CPS, cps)
            .putLong(KEY_LAST_TS_MS, timestampMs)
            .apply()
    }

    fun getLastReading(context: Context): LastReading? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_LAST_TS_MS, 0L)
        if (ts <= 0L) return null
        return LastReading(
            uSvPerHour = prefs.getFloat(KEY_LAST_USV_H, 0f),
            cps = prefs.getFloat(KEY_LAST_CPS, 0f),
            timestampMs = ts,
        )
    }

    fun getPollIntervalMs(context: Context, defaultMs: Long = 1000L): Long {
        val v = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_POLL_INTERVAL_MS, defaultMs)
        // Keep it sane.
        return when {
            v < 250L -> 250L
            v > 60_000L -> 60_000L
            else -> v
        }
    }

    fun setPollIntervalMs(context: Context, intervalMs: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_POLL_INTERVAL_MS, intervalMs)
            .apply()
    }

    fun getLastCsvTimestampMs(context: Context): Long {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CSV_TS_MS, 0L)
    }

    fun setLastCsvTimestampMs(context: Context, timestampMs: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CSV_TS_MS, timestampMs)
            .apply()
    }

    data class ServiceStatus(
        val message: String,
        val timestampMs: Long,
    )

    fun setServiceStatus(context: Context, message: String, timestampMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVICE_STATUS, message)
            .putLong(KEY_SERVICE_STATUS_TS_MS, timestampMs)
            .apply()
    }

    fun getServiceStatus(context: Context): ServiceStatus? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val msg = prefs.getString(KEY_SERVICE_STATUS, null)?.trim().orEmpty()
        val ts = prefs.getLong(KEY_SERVICE_STATUS_TS_MS, 0L)
        if (msg.isEmpty() || ts <= 0L) return null
        return ServiceStatus(message = msg, timestampMs = ts)
    }
}
