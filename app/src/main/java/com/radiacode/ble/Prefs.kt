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

    private const val KEY_WINDOW_SECONDS = "window_seconds"
    private const val KEY_SMOOTH_SECONDS = "smooth_seconds"
    private const val KEY_PAUSE_LIVE = "pause_live"
    private const val KEY_DOSE_UNIT = "dose_unit"
    private const val KEY_COUNT_UNIT = "count_unit"
    private const val KEY_COMPACT_LAYOUT = "compact_layout"
    private const val KEY_SHOW_SPIKE_MARKERS = "show_spike_markers"
    private const val KEY_SHOW_SPIKE_PERCENTAGES = "show_spike_percentages"
    private const val KEY_SHOW_TREND_ARROWS = "show_trend_arrows"

    private const val KEY_DOSE_THRESHOLD_USV_H = "dose_threshold_usv_h"
    
    // Notification Settings keys
    private const val KEY_NOTIFICATION_STYLE = "notification_style"
    private const val KEY_NOTIFICATION_SHOW_READINGS = "notification_show_readings"
    private const val KEY_NOTIFICATION_SHOW_DEVICE_COUNT = "notification_show_device_count"
    private const val KEY_NOTIFICATION_SHOW_CONNECTION_STATUS = "notification_show_connection_status"
    private const val KEY_NOTIFICATION_SHOW_ALERTS = "notification_show_alerts"
    private const val KEY_NOTIFICATION_SHOW_ANOMALIES = "notification_show_anomalies"
    
    // Smart Alert keys
    private const val KEY_ALERTS_JSON = "alerts_json"
    
    // Multi-device keys
    private const val KEY_DEVICES_JSON = "devices_json"
    private const val KEY_SELECTED_DEVICE_ID = "selected_device_id"
    private const val KEY_DEVICE_READINGS_PREFIX = "device_readings_"
    private const val KEY_DEVICE_CHART_HISTORY_PREFIX = "device_chart_history_"
    private const val KEY_DEVICE_METADATA_PREFIX = "device_metadata_"
    private const val MAX_DEVICES = 8

    enum class DoseUnit { USV_H, NSV_H }
    enum class CountUnit { CPS, CPM }
    
    /**
     * Notification style - controls what appears in the persistent notification
     */
    enum class NotificationStyle {
        NONE,                   // Hidden/None - minimal notification, guide to disable in Android settings
        OFF,                    // Minimal notification (required for foreground service, but minimal content)
        STATUS_ONLY,            // Connection status only (e.g., "Connected" or "Disconnected")
        READINGS,               // Show current readings (default)
        DETAILED                // Show readings + device count + additional info
    }
    
    /**
     * Device metadata: signal strength (RSSI), temperature, and battery level.
     */
    data class DeviceMetadataInfo(
        val signalStrength: Int?,   // RSSI in dBm (typically -100 to 0)
        val temperature: Float?,    // Device temperature in Celsius
        val batteryLevel: Int?,     // Battery percentage (0-100)
        val timestampMs: Long?      // When this data was captured
    )

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

    fun getWindowSeconds(context: Context, defaultSeconds: Int = 60): Int {
        val v = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_WINDOW_SECONDS, defaultSeconds)
        // Valid values: 30s, 1m, 5m, 15m, 1h
        return when (v) {
            30, 60, 300, 900, 3600 -> v
            else -> defaultSeconds
        }
    }

    fun setWindowSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_WINDOW_SECONDS, seconds)
            .apply()
    }

    fun getSmoothSeconds(context: Context, defaultSeconds: Int = 0): Int {
        val v = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_SMOOTH_SECONDS, defaultSeconds)
        return when (v) {
            0, 5, 30 -> v
            else -> defaultSeconds
        }
    }

    fun setSmoothSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SMOOTH_SECONDS, seconds)
            .apply()
    }

    fun isPauseLiveEnabled(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAUSE_LIVE, false)
    }

    fun setPauseLiveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAUSE_LIVE, enabled)
            .apply()
    }

    fun getDoseUnit(context: Context, default: DoseUnit = DoseUnit.USV_H): DoseUnit {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_DOSE_UNIT, default.name) ?: default.name
        return runCatching { DoseUnit.valueOf(raw) }.getOrDefault(default)
    }

    fun setDoseUnit(context: Context, unit: DoseUnit) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DOSE_UNIT, unit.name)
            .apply()
    }

    fun getCountUnit(context: Context, default: CountUnit = CountUnit.CPS): CountUnit {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_COUNT_UNIT, default.name) ?: default.name
        return runCatching { CountUnit.valueOf(raw) }.getOrDefault(default)
    }

    fun setCountUnit(context: Context, unit: CountUnit) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COUNT_UNIT, unit.name)
            .apply()
    }

    /**
     * Dose threshold stored in base uSv/h. Use 0f to mean disabled.
     */
    fun getDoseThresholdUsvH(context: Context, defaultUsvH: Float = 0f): Float {
        val v = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(KEY_DOSE_THRESHOLD_USV_H, defaultUsvH)
        return if (v.isFinite() && v > 0f) v else 0f
    }

    fun setDoseThresholdUsvH(context: Context, thresholdUsvH: Float) {
        val v = if (thresholdUsvH.isFinite() && thresholdUsvH > 0f) thresholdUsvH else 0f
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_DOSE_THRESHOLD_USV_H, v)
            .apply()
    }

    fun isCompactLayoutEnabled(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPACT_LAYOUT, false)
    }

    fun setCompactLayoutEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPACT_LAYOUT, enabled)
            .apply()
    }

    fun isShowSpikeMarkersEnabled(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SPIKE_MARKERS, true)  // Default ON
    }

    fun setShowSpikeMarkersEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_SPIKE_MARKERS, enabled)
            .apply()
    }
    
    fun isShowSpikePercentagesEnabled(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SPIKE_PERCENTAGES, true)  // Default ON
    }

    fun setShowSpikePercentagesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_SPIKE_PERCENTAGES, enabled)
            .apply()
    }
    
    fun isShowTrendArrowsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_TREND_ARROWS, true)  // Default ON
    }

    fun setShowTrendArrowsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_TREND_ARROWS, enabled)
            .apply()
    }
    
    // ========== Notification Settings ==========
    
    /**
     * Get the notification style preference
     * @return The selected notification style, defaults to READINGS
     */
    fun getNotificationStyle(context: Context): NotificationStyle {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_NOTIFICATION_STYLE, NotificationStyle.READINGS.name)
        return try {
            NotificationStyle.valueOf(name ?: NotificationStyle.READINGS.name)
        } catch (_: Exception) {
            NotificationStyle.READINGS
        }
    }
    
    fun setNotificationStyle(context: Context, style: NotificationStyle) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOTIFICATION_STYLE, style.name)
            .apply()
    }
    
    /**
     * Whether to show current readings in the notification (when style is DETAILED)
     */
    fun isNotificationShowReadings(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_SHOW_READINGS, true)
    }
    
    fun setNotificationShowReadings(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_SHOW_READINGS, enabled)
            .apply()
    }
    
    /**
     * Whether to show connected device count in the notification
     */
    fun isNotificationShowDeviceCount(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_SHOW_DEVICE_COUNT, true)
    }
    
    fun setNotificationShowDeviceCount(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_SHOW_DEVICE_COUNT, enabled)
            .apply()
    }
    
    /**
     * Whether to show connection status changes (Connected/Disconnected/Reconnecting)
     */
    fun isNotificationShowConnectionStatus(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_SHOW_CONNECTION_STATUS, true)
    }
    
    fun setNotificationShowConnectionStatus(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_SHOW_CONNECTION_STATUS, enabled)
            .apply()
    }
    
    /**
     * Whether to show active alert status in the notification
     */
    fun isNotificationShowAlerts(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_SHOW_ALERTS, false)  // Default OFF - alerts have their own notifications
    }
    
    fun setNotificationShowAlerts(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_SHOW_ALERTS, enabled)
            .apply()
    }
    
    /**
     * Whether to show anomaly detection status in the notification
     */
    fun isNotificationShowAnomalies(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_SHOW_ANOMALIES, false)  // Default OFF
    }
    
    fun setNotificationShowAnomalies(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_SHOW_ANOMALIES, enabled)
            .apply()
    }
    
    // ========== Smart Alerts System ==========
    
    /**
     * Smart Alert configuration
     * @param id Unique identifier
     * @param name User-friendly name
     * @param enabled Whether the alert is active
     * @param metric "dose" or "cps"
     * @param condition "above", "below", "outside_sigma"
     * @param threshold For above/below: the threshold value. For outside_sigma: number of std devs
     * @param durationSeconds How long the condition must persist before alerting (0 = instant)
     * @param cooldownMs Minimum time in milliseconds between repeat alerts
     * @param lastTriggeredMs Last time this alert fired
     * @param color Alert color: "amber" or "red"
     * @param severity Alert severity: "low", "medium", or "high"
     */
    data class SmartAlert(
        val id: String,
        val name: String,
        val enabled: Boolean = true,
        val metric: String = "dose",  // "dose" or "count"
        val condition: String = "above",  // "above", "below", "outside_sigma"
        val threshold: Double = 0.5,
        val sigma: Double = 2.0,  // For "outside_sigma" condition: 1, 2, or 3 standard deviations
        val durationSeconds: Int = 0,  // 0 = instant, >0 = sustained
        val cooldownMs: Long = 60000L,  // milliseconds
        val lastTriggeredMs: Long = 0L,
        val color: String = "amber",  // "amber" or "red"
        val severity: String = "medium"  // "low", "medium", or "high"
    ) {
        fun toJson(): String {
            return """{"id":"$id","name":"$name","enabled":$enabled,"metric":"$metric","condition":"$condition","threshold":$threshold,"sigma":$sigma,"durationSeconds":$durationSeconds,"cooldownMs":$cooldownMs,"lastTriggeredMs":$lastTriggeredMs,"color":"$color","severity":"$severity"}"""
        }
        
        companion object {
            fun fromJson(json: String): SmartAlert? {
                return try {
                    // Simple JSON parsing without external library
                    val id = json.substringAfter("\"id\":\"").substringBefore("\"")
                    val name = json.substringAfter("\"name\":\"").substringBefore("\"")
                    val enabled = json.substringAfter("\"enabled\":").substringBefore(",").toBoolean()
                    val metric = json.substringAfter("\"metric\":\"").substringBefore("\"")
                    val condition = json.substringAfter("\"condition\":\"").substringBefore("\"")
                    val threshold = json.substringAfter("\"threshold\":").substringBefore(",").toDouble()
                    val sigma = json.substringAfter("\"sigma\":").substringBefore(",").toDoubleOrNull() ?: 2.0
                    val durationSeconds = json.substringAfter("\"durationSeconds\":").substringBefore(",").toInt()
                    val cooldownMs = json.substringAfter("\"cooldownMs\":").substringBefore(",").toLongOrNull() 
                        ?: (json.substringAfter("\"cooldownSeconds\":").substringBefore(",").toIntOrNull()?.times(1000L) ?: 60000L)
                    val lastTriggeredMs = json.substringAfter("\"lastTriggeredMs\":").substringBefore(",").substringBefore("}").toLong()
                    val color = json.substringAfter("\"color\":\"").substringBefore("\"").ifEmpty { "amber" }
                    val severity = json.substringAfter("\"severity\":\"").substringBefore("\"").ifEmpty { "medium" }
                    SmartAlert(id, name, enabled, metric, condition, threshold, sigma, durationSeconds, cooldownMs, lastTriggeredMs, color, severity)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    
    fun getSmartAlerts(context: Context): List<SmartAlert> {
        val json = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ALERTS_JSON, "[]") ?: "[]"
        if (json == "[]") return emptyList()
        
        return json.removeSurrounding("[", "]")
            .split("},{")
            .map { it.trim().let { s -> if (!s.startsWith("{")) "{$s" else s }.let { s -> if (!s.endsWith("}")) "$s}" else s } }
            .mapNotNull { SmartAlert.fromJson(it) }
    }
    
    fun setSmartAlerts(context: Context, alerts: List<SmartAlert>) {
        val json = "[${alerts.joinToString(",") { it.toJson() }}]"
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALERTS_JSON, json)
            .apply()
    }
    
    fun addSmartAlert(context: Context, alert: SmartAlert): Boolean {
        val alerts = getSmartAlerts(context).toMutableList()
        if (alerts.size >= 10) return false  // Max 10 alerts
        alerts.add(alert)
        setSmartAlerts(context, alerts)
        return true
    }
    
    fun updateSmartAlert(context: Context, alert: SmartAlert) {
        val alerts = getSmartAlerts(context).toMutableList()
        val idx = alerts.indexOfFirst { it.id == alert.id }
        if (idx >= 0) {
            alerts[idx] = alert
            setSmartAlerts(context, alerts)
        }
    }
    
    fun deleteSmartAlert(context: Context, alertId: String) {
        val alerts = getSmartAlerts(context).filter { it.id != alertId }
        setSmartAlerts(context, alerts)
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

    // Chart history (stored as compact CSV string for efficiency)
    // Store enough data for 1+ hour at 1 sample/sec
    private const val KEY_RECENT_READINGS = "recent_readings"
    private const val KEY_CHART_HISTORY = "chart_history"
    private const val MAX_RECENT_READINGS = 60  // For widgets (small)
    private const val MAX_CHART_HISTORY = 4000  // For charts (large, ~1+ hour of data)

    fun addRecentReading(context: Context, reading: LastReading) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_RECENT_READINGS, "") ?: ""
        val chartExisting = prefs.getString(KEY_CHART_HISTORY, "") ?: ""
        
        // Format: ts,usv,cps;ts,usv,cps;...
        val newEntry = "${reading.timestampMs},${reading.uSvPerHour},${reading.cps}"
        
        // Widget history (small)
        val entries = existing.split(";").filter { it.isNotBlank() }.toMutableList()
        entries.add(newEntry)
        while (entries.size > MAX_RECENT_READINGS) {
            entries.removeAt(0)
        }
        
        // Chart history (large)
        val chartEntries = chartExisting.split(";").filter { it.isNotBlank() }.toMutableList()
        chartEntries.add(newEntry)
        while (chartEntries.size > MAX_CHART_HISTORY) {
            chartEntries.removeAt(0)
        }
        
        prefs.edit()
            .putString(KEY_RECENT_READINGS, entries.joinToString(";"))
            .putString(KEY_CHART_HISTORY, chartEntries.joinToString(";"))
            .apply()
    }

    fun getRecentReadings(context: Context, maxCount: Int = MAX_RECENT_READINGS): List<LastReading> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT_READINGS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        
        return raw.split(";")
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val parts = entry.split(",")
                if (parts.size == 3) {
                    try {
                        LastReading(
                            timestampMs = parts[0].toLong(),
                            uSvPerHour = parts[1].toFloat(),
                            cps = parts[2].toFloat()
                        )
                    } catch (_: Exception) { null }
                } else null
            }
            .takeLast(maxCount)
    }
    
    /** Get full chart history (for restoring charts after app restart) */
    fun getChartHistory(context: Context, maxCount: Int = MAX_CHART_HISTORY): List<LastReading> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CHART_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        
        return raw.split(";")
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val parts = entry.split(",")
                if (parts.size == 3) {
                    try {
                        LastReading(
                            timestampMs = parts[0].toLong(),
                            uSvPerHour = parts[1].toFloat(),
                            cps = parts[2].toFloat()
                        )
                    } catch (_: Exception) { null }
                } else null
            }
            .takeLast(maxCount)
    }
    
    // ========== Multi-Device Management ==========
    
    /**
     * Get all configured devices.
     */
    fun getDevices(context: Context): List<DeviceConfig> {
        val json = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_DEVICES_JSON, "[]") ?: "[]"
        if (json == "[]") return emptyList()
        
        val parsed = json.removeSurrounding("[", "]")
            .split("},{")
            .map { it.trim().let { s -> if (!s.startsWith("{")) "{$s" else s }.let { s -> if (!s.endsWith("}")) "$s}" else s } }
            .mapNotNull { DeviceConfig.fromJson(it) }
        
        // Remove duplicates by MAC address (keep first occurrence)
        val seen = mutableSetOf<String>()
        val unique = parsed.filter { device ->
            val mac = device.macAddress.uppercase()
            if (mac in seen) {
                android.util.Log.w("Prefs", "getDevices: duplicate MAC removed: $mac")
                false
            } else {
                seen.add(mac)
                true
            }
        }
        
        android.util.Log.d("Prefs", "getDevices: parsed ${parsed.size} -> ${unique.size} unique devices")
        return unique
    }
    
    /**
     * Get all enabled devices (for auto-connect).
     */
    fun getEnabledDevices(context: Context): List<DeviceConfig> {
        return getDevices(context).filter { it.enabled }
    }
    
    /**
     * Save all devices.
     */
    fun setDevices(context: Context, devices: List<DeviceConfig>) {
        val json = "[${devices.joinToString(",") { it.toJson() }}]"
        android.util.Log.d("Prefs", "setDevices: saving ${devices.size} devices")
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICES_JSON, json)
            .apply()
    }
    
    /**
     * Clear all devices and reset migration flag (for debugging).
     */
    fun clearAllDevices(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DEVICES_JSON)
            .remove("multi_device_migrated")
            .apply()
        android.util.Log.d("Prefs", "clearAllDevices: cleared all devices and migration flag")
    }
    
    /**
     * Add a new device. Returns false if max devices reached or device already exists.
     */
    fun addDevice(context: Context, device: DeviceConfig): Boolean {
        val devices = getDevices(context).toMutableList()
        if (devices.size >= MAX_DEVICES) return false
        // Check for duplicate MAC (case insensitive)
        if (devices.any { it.macAddress.equals(device.macAddress, ignoreCase = true) }) {
            android.util.Log.d("Prefs", "addDevice: device ${device.macAddress} already exists")
            return false
        }
        
        // Assign a color based on position
        val coloredDevice = device.copy(colorHex = DeviceConfig.getColorForIndex(devices.size))
        devices.add(coloredDevice)
        setDevices(context, devices)
        android.util.Log.d("Prefs", "addDevice: added ${device.macAddress}, total devices: ${devices.size}")
        return true
    }
    
    /**
     * Update an existing device configuration.
     */
    fun updateDevice(context: Context, device: DeviceConfig) {
        val devices = getDevices(context).toMutableList()
        val idx = devices.indexOfFirst { it.id == device.id }
        if (idx >= 0) {
            devices[idx] = device
            setDevices(context, devices)
        }
    }
    
    /**
     * Delete a device by ID.
     */
    fun deleteDevice(context: Context, deviceId: String) {
        val devices = getDevices(context).filter { it.id != deviceId }
        setDevices(context, devices)
        
        // Also clean up per-device data
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DEVICE_READINGS_PREFIX + deviceId)
            .remove(KEY_DEVICE_CHART_HISTORY_PREFIX + deviceId)
            .apply()
    }
    
    /**
     * Get device by MAC address.
     */
    fun getDeviceByMac(context: Context, macAddress: String): DeviceConfig? {
        return getDevices(context).find { it.macAddress.equals(macAddress, ignoreCase = true) }
    }
    
    /**
     * Get device by ID.
     */
    fun getDeviceById(context: Context, deviceId: String): DeviceConfig? {
        return getDevices(context).find { it.id == deviceId }
    }
    
    /**
     * Get currently selected device ID for dashboard display.
     * null means "All Devices" view.
     */
    fun getSelectedDeviceId(context: Context): String? {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_DEVICE_ID, null)
    }
    
    /**
     * Set selected device ID for dashboard. null = "All Devices".
     */
    fun setSelectedDeviceId(context: Context, deviceId: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_DEVICE_ID, deviceId)
            .apply()
    }
    
    /**
     * Add a reading for a specific device.
     */
    fun addDeviceReading(context: Context, deviceId: String, reading: LastReading) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val key = KEY_DEVICE_READINGS_PREFIX + deviceId
        val chartKey = KEY_DEVICE_CHART_HISTORY_PREFIX + deviceId
        val existing = prefs.getString(key, "") ?: ""
        val chartExisting = prefs.getString(chartKey, "") ?: ""
        
        val newEntry = "${reading.timestampMs},${reading.uSvPerHour},${reading.cps}"
        
        // Widget history (small)
        val entries = existing.split(";").filter { it.isNotBlank() }.toMutableList()
        entries.add(newEntry)
        while (entries.size > MAX_RECENT_READINGS) {
            entries.removeAt(0)
        }
        
        // Chart history (large)
        val chartEntries = chartExisting.split(";").filter { it.isNotBlank() }.toMutableList()
        chartEntries.add(newEntry)
        while (chartEntries.size > MAX_CHART_HISTORY) {
            chartEntries.removeAt(0)
        }
        
        prefs.edit()
            .putString(key, entries.joinToString(";"))
            .putString(chartKey, chartEntries.joinToString(";"))
            .apply()
    }
    
    /**
     * Get recent readings for a specific device.
     */
    fun getDeviceRecentReadings(context: Context, deviceId: String, maxCount: Int = MAX_RECENT_READINGS): List<LastReading> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DEVICE_READINGS_PREFIX + deviceId, "") ?: ""
        if (raw.isBlank()) return emptyList()
        
        return raw.split(";")
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val parts = entry.split(",")
                if (parts.size == 3) {
                    try {
                        LastReading(
                            timestampMs = parts[0].toLong(),
                            uSvPerHour = parts[1].toFloat(),
                            cps = parts[2].toFloat()
                        )
                    } catch (_: Exception) { null }
                } else null
            }
            .takeLast(maxCount)
    }
    
    /**
     * Get chart history for a specific device.
     */
    fun getDeviceChartHistory(context: Context, deviceId: String, maxCount: Int = MAX_CHART_HISTORY): List<LastReading> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DEVICE_CHART_HISTORY_PREFIX + deviceId, "") ?: ""
        if (raw.isBlank()) return emptyList()
        
        return raw.split(";")
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val parts = entry.split(",")
                if (parts.size == 3) {
                    try {
                        LastReading(
                            timestampMs = parts[0].toLong(),
                            uSvPerHour = parts[1].toFloat(),
                            cps = parts[2].toFloat()
                        )
                    } catch (_: Exception) { null }
                } else null
            }
            .takeLast(maxCount)
    }
    
    /**
     * Store the last reading for a device (for quick access).
     */
    fun setDeviceLastReading(context: Context, deviceId: String, uSvPerHour: Float, cps: Float, timestampMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat("device_last_usv_$deviceId", uSvPerHour)
            .putFloat("device_last_cps_$deviceId", cps)
            .putLong("device_last_ts_$deviceId", timestampMs)
            .apply()
    }
    
    /**
     * Get the last reading for a device.
     */
    fun getDeviceLastReading(context: Context, deviceId: String): LastReading? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val ts = prefs.getLong("device_last_ts_$deviceId", 0L)
        if (ts <= 0L) return null
        return LastReading(
            uSvPerHour = prefs.getFloat("device_last_usv_$deviceId", 0f),
            cps = prefs.getFloat("device_last_cps_$deviceId", 0f),
            timestampMs = ts
        )
    }
    
    /**
     * Get all device last readings (for dashboard "All Devices" view).
     */
    fun getAllDeviceLastReadings(context: Context): Map<String, LastReading> {
        val devices = getDevices(context)
        val result = mutableMapOf<String, LastReading>()
        for (device in devices) {
            getDeviceLastReading(context, device.id)?.let {
                result[device.id] = it
            }
        }
        return result
    }
    
    /**
     * Migrate existing single-device data to multi-device format.
     * Call this once on app upgrade.
     */
    fun migrateToMultiDevice(context: Context) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        
        // Check if already migrated
        if (prefs.getBoolean("multi_device_migrated", false)) return
        
        val existingAddress = getPreferredAddress(context)
        if (!existingAddress.isNullOrBlank()) {
            // Create a device config for the existing device
            val existingDevice = DeviceConfig(
                macAddress = existingAddress,
                customName = "",  // User can name it later
                enabled = isAutoConnectEnabled(context),
                colorHex = "00E5FF"  // Primary cyan
            )
            
            // Only add if not already present
            val devices = getDevices(context)
            if (devices.none { it.macAddress.equals(existingAddress, ignoreCase = true) }) {
                addDevice(context, existingDevice)
                
                // Migrate readings to the new device
                val chartHistory = getChartHistory(context)
                for (reading in chartHistory) {
                    addDeviceReading(context, existingDevice.id, reading)
                }
                
                // Set as selected device
                setSelectedDeviceId(context, existingDevice.id)
            }
        }
        
        prefs.edit().putBoolean("multi_device_migrated", true).apply()
    }
    
    // ========== Widget Configuration ==========
    
    private const val KEY_WIDGET_CONFIGS_JSON = "widget_configs_json"
    private const val KEY_WIDGET_CONFIG_PREFIX = "widget_config_"
    private const val KEY_DYNAMIC_COLOR_THRESHOLDS = "dynamic_color_thresholds"
    
    /**
     * Get configuration for a specific widget instance.
     */
    fun getWidgetConfig(context: Context, widgetId: Int): WidgetConfig? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_WIDGET_CONFIG_PREFIX + widgetId, null) ?: return null
        return WidgetConfig.fromJson(json)
    }
    
    /**
     * Save configuration for a specific widget instance.
     */
    fun setWidgetConfig(context: Context, config: WidgetConfig) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WIDGET_CONFIG_PREFIX + config.widgetId, config.toJson())
            .apply()
        
        // Also update the master list of widget IDs
        val widgetIds = getConfiguredWidgetIds(context).toMutableSet()
        widgetIds.add(config.widgetId)
        saveConfiguredWidgetIds(context, widgetIds)
    }
    
    /**
     * Delete configuration for a widget instance (when widget is removed).
     */
    fun deleteWidgetConfig(context: Context, widgetId: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_WIDGET_CONFIG_PREFIX + widgetId)
            .apply()
        
        val widgetIds = getConfiguredWidgetIds(context).toMutableSet()
        widgetIds.remove(widgetId)
        saveConfiguredWidgetIds(context, widgetIds)
    }
    
    /**
     * Get all configured widget IDs.
     */
    fun getConfiguredWidgetIds(context: Context): Set<Int> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_WIDGET_CONFIGS_JSON, "[]") ?: "[]"
        return json.removeSurrounding("[", "]")
            .split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }
    
    private fun saveConfiguredWidgetIds(context: Context, ids: Set<Int>) {
        val json = "[${ids.joinToString(",")}]"
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WIDGET_CONFIGS_JSON, json)
            .apply()
    }
    
    /**
     * Get all widget configurations.
     */
    fun getAllWidgetConfigs(context: Context): List<WidgetConfig> {
        return getConfiguredWidgetIds(context)
            .mapNotNull { getWidgetConfig(context, it) }
    }
    
    /**
     * Get widget configs bound to a specific device.
     */
    fun getWidgetConfigsForDevice(context: Context, deviceId: String?): List<WidgetConfig> {
        return getAllWidgetConfigs(context)
            .filter { it.deviceId == deviceId }
    }
    
    /**
     * Duplicate a widget config for a different device.
     */
    fun duplicateWidgetConfig(context: Context, sourceWidgetId: Int, newWidgetId: Int, newDeviceId: String?): WidgetConfig? {
        val source = getWidgetConfig(context, sourceWidgetId) ?: return null
        val duplicate = source.copy(
            widgetId = newWidgetId,
            deviceId = newDeviceId,
            createdAtMs = System.currentTimeMillis()
        )
        setWidgetConfig(context, duplicate)
        return duplicate
    }
    
    /**
     * Get dynamic color thresholds configuration.
     */
    fun getDynamicColorThresholds(context: Context): DynamicColorThresholds {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_DYNAMIC_COLOR_THRESHOLDS, null)
        return if (json != null) {
            DynamicColorThresholds.fromJson(json) ?: DynamicColorThresholds.DEFAULT
        } else {
            DynamicColorThresholds.DEFAULT
        }
    }
    
    /**
     * Save dynamic color thresholds configuration.
     */
    fun setDynamicColorThresholds(context: Context, thresholds: DynamicColorThresholds) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DYNAMIC_COLOR_THRESHOLDS, thresholds.toJson())
            .apply()
    }
    
    // ========== Device Metadata (Signal, Temperature, Battery) ==========
    
    /**
     * Store device metadata (signal strength, temperature, battery) for a device.
     */
    fun setDeviceMetadata(
        context: Context, 
        deviceId: String, 
        signalStrength: Int,
        temperature: Float, 
        batteryLevel: Int,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        updateDeviceMetadata(
            context = context,
            deviceId = deviceId,
            signalStrength = signalStrength,
            temperature = temperature,
            batteryLevel = batteryLevel,
            timestampMs = timestampMs
        )
    }

    /**
     * Update any subset of device metadata (signal strength, temperature, battery).
     * Fields that are null are left unchanged.
     */
    fun updateDeviceMetadata(
        context: Context,
        deviceId: String,
        signalStrength: Int? = null,
        temperature: Float? = null,
        batteryLevel: Int? = null,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (signalStrength != null) editor.putInt("${KEY_DEVICE_METADATA_PREFIX}rssi_$deviceId", signalStrength)
        if (temperature != null) editor.putFloat("${KEY_DEVICE_METADATA_PREFIX}temp_$deviceId", temperature)
        if (batteryLevel != null) editor.putInt("${KEY_DEVICE_METADATA_PREFIX}battery_$deviceId", batteryLevel)
        editor.putLong("${KEY_DEVICE_METADATA_PREFIX}ts_$deviceId", timestampMs)
        editor.apply()
    }
    
    /**
     * Get device metadata for a specific device.
     */
    fun getDeviceMetadata(context: Context, deviceId: String): DeviceMetadataInfo? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        val rssiKey = "${KEY_DEVICE_METADATA_PREFIX}rssi_$deviceId"
        val tempKey = "${KEY_DEVICE_METADATA_PREFIX}temp_$deviceId"
        val battKey = "${KEY_DEVICE_METADATA_PREFIX}battery_$deviceId"
        val tsKey = "${KEY_DEVICE_METADATA_PREFIX}ts_$deviceId"

        val hasAny = prefs.contains(rssiKey) || prefs.contains(tempKey) || prefs.contains(battKey)
        if (!hasAny) return null

        val ts = prefs.getLong(tsKey, 0L)
        return DeviceMetadataInfo(
            signalStrength = if (prefs.contains(rssiKey)) prefs.getInt(rssiKey, -100) else null,
            temperature = if (prefs.contains(tempKey)) prefs.getFloat(tempKey, 0f) else null,
            batteryLevel = if (prefs.contains(battKey)) prefs.getInt(battKey, 0) else null,
            timestampMs = if (ts > 0L) ts else null
        )
    }
    
    /**
     * Update only the signal strength for a device (called frequently from BLE callbacks).
     */
    fun setDeviceSignalStrength(context: Context, deviceId: String, rssi: Int) {
        updateDeviceMetadata(context = context, deviceId = deviceId, signalStrength = rssi)
    }
    
    /**
     * Get all device metadata for widgets.
     */
    fun getAllDeviceMetadata(context: Context): Map<String, DeviceMetadataInfo> {
        val devices = getDevices(context)
        val result = mutableMapOf<String, DeviceMetadataInfo>()
        for (device in devices) {
            getDeviceMetadata(context, device.id)?.let {
                result[device.id] = it
            }
        }
        return result
    }
    
    // ==================== ISOTOPE DETECTION SETTINGS ====================
    
    private const val KEY_ISOTOPE_ENABLED_SET = "isotope_enabled_set"
    private const val KEY_ISOTOPE_REALTIME_ENABLED = "isotope_realtime_enabled"
    private const val KEY_ISOTOPE_CHART_MODE = "isotope_chart_mode"
    private const val KEY_ISOTOPE_DISPLAY_MODE = "isotope_display_mode"
    private const val KEY_ISOTOPE_SHOW_DAUGHTERS = "isotope_show_daughters"
    private const val KEY_ISOTOPE_ACCUMULATION_MODE = "isotope_accumulation_mode"
    private const val KEY_ISOTOPE_HIDE_BACKGROUND = "isotope_hide_background"
    
    /**
     * Chart display mode for isotope panel.
     */
    enum class IsotopeChartMode {
        MULTI_LINE,     // Multi-line time series (default)
        STACKED_AREA,   // Stacked area chart
        ANIMATED_BAR    // Animated horizontal bars
    }
    
    /**
     * What value to display on isotope charts.
     */
    enum class IsotopeDisplayMode {
        PROBABILITY,    // 0-100% probability (default)
        FRACTION        // Fractional contribution
    }
    
    /**
     * How to accumulate spectrum data for isotope analysis.
     * INTERVAL: Only use recent data within the chart time window (30s, 1m, 5m, etc.)
     * FULL_DURATION: Accumulate ALL data since streaming started (better statistics)
     */
    enum class IsotopeAccumulationMode {
        INTERVAL,       // Use chart time window (default)
        FULL_DURATION   // Accumulate all data since start
    }
    
    /**
     * Get enabled isotopes (returns set of isotope IDs).
     */
    fun getEnabledIsotopes(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet(KEY_ISOTOPE_ENABLED_SET, null)
        return raw ?: IsotopeLibrary.DEFAULT_ENABLED
    }
    
    /**
     * Set enabled isotopes.
     */
    fun setEnabledIsotopes(context: Context, isotopes: Set<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ISOTOPE_ENABLED_SET, isotopes)
            .apply()
    }
    
    /**
     * Enable or disable a specific isotope.
     */
    fun setIsotopeEnabled(context: Context, isotopeId: String, enabled: Boolean) {
        val current = getEnabledIsotopes(context).toMutableSet()
        if (enabled) {
            current.add(isotopeId)
        } else {
            current.remove(isotopeId)
        }
        setEnabledIsotopes(context, current)
    }
    
    /**
     * Check if real-time isotope detection is enabled.
     */
    fun isIsotopeRealtimeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ISOTOPE_REALTIME_ENABLED, false)
    }
    
    /**
     * Enable or disable real-time isotope detection.
     */
    fun setIsotopeRealtimeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ISOTOPE_REALTIME_ENABLED, enabled)
            .apply()
    }
    
    /**
     * Get isotope chart display mode.
     */
    fun getIsotopeChartMode(context: Context): IsotopeChartMode {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_ISOTOPE_CHART_MODE, null)
        return try {
            if (name != null) IsotopeChartMode.valueOf(name) else IsotopeChartMode.MULTI_LINE
        } catch (_: Exception) {
            IsotopeChartMode.MULTI_LINE
        }
    }
    
    /**
     * Set isotope chart display mode.
     */
    fun setIsotopeChartMode(context: Context, mode: IsotopeChartMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ISOTOPE_CHART_MODE, mode.name)
            .apply()
    }
    
    /**
     * Get isotope display mode (probability vs fraction).
     */
    fun getIsotopeDisplayMode(context: Context): IsotopeDisplayMode {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_ISOTOPE_DISPLAY_MODE, null)
        return try {
            if (name != null) IsotopeDisplayMode.valueOf(name) else IsotopeDisplayMode.PROBABILITY
        } catch (_: Exception) {
            IsotopeDisplayMode.PROBABILITY
        }
    }
    
    /**
     * Set isotope display mode.
     */
    fun setIsotopeDisplayMode(context: Context, mode: IsotopeDisplayMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ISOTOPE_DISPLAY_MODE, mode.name)
            .apply()
    }
    
    /**
     * Get whether to show decay chain daughters vs parent isotope.
     */
    fun isShowIsotopeDaughters(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ISOTOPE_SHOW_DAUGHTERS, false)
    }
    
    /**
     * Set whether to show decay chain daughters vs parent isotope.
     */
    fun setShowIsotopeDaughters(context: Context, show: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ISOTOPE_SHOW_DAUGHTERS, show)
            .apply()
    }
    
    /**
     * Get isotope spectrum accumulation mode (interval vs full duration).
     */
    fun getIsotopeAccumulationMode(context: Context): IsotopeAccumulationMode {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_ISOTOPE_ACCUMULATION_MODE, null)
        return try {
            if (name != null) IsotopeAccumulationMode.valueOf(name) else IsotopeAccumulationMode.FULL_DURATION
        } catch (_: Exception) {
            IsotopeAccumulationMode.FULL_DURATION
        }
    }
    
    /**
     * Set isotope spectrum accumulation mode.
     */
    fun setIsotopeAccumulationMode(context: Context, mode: IsotopeAccumulationMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ISOTOPE_ACCUMULATION_MODE, mode.name)
            .apply()
    }
    
    /**
     * Get whether to hide Background/Unknown from isotope charts.
     */
    fun isIsotopeHideBackground(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ISOTOPE_HIDE_BACKGROUND, true)  // Default to hiding background
    }
    
    /**
     * Set whether to hide Background/Unknown from isotope charts.
     */
    fun setIsotopeHideBackground(context: Context, hide: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ISOTOPE_HIDE_BACKGROUND, hide)
            .apply()
    }
}
