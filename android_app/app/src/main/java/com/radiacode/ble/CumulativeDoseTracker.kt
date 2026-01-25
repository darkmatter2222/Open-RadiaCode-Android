package com.radiacode.ble

import android.content.Context

/**
 * Cumulative Dose Tracker
 * Tracks total accumulated dose over session, day, week, and all-time periods.
 * Uses trapezoidal integration for accurate dose accumulation.
 */
object CumulativeDoseTracker {
    
    private const val PREFS_FILE = "cumulative_dose_prefs"
    
    // Keys for persistent storage
    private const val KEY_SESSION_DOSE_USV = "session_dose_usv"
    private const val KEY_SESSION_START_MS = "session_start_ms"
    private const val KEY_LAST_READING_TIME_MS = "last_reading_time_ms"
    private const val KEY_LAST_DOSE_RATE = "last_dose_rate"
    
    private const val KEY_TODAY_DOSE_USV = "today_dose_usv"
    private const val KEY_TODAY_DATE = "today_date"  // YYYYMMDD format
    
    private const val KEY_WEEK_DOSE_USV = "week_dose_usv"
    private const val KEY_WEEK_START_DATE = "week_start_date"  // YYYYMMDD format
    
    private const val KEY_ALLTIME_DOSE_USV = "alltime_dose_usv"
    private const val KEY_ALLTIME_START_MS = "alltime_start_ms"
    
    // Route tracking keys
    private const val KEY_ROUTE_DOSE_USV = "route_dose_usv"
    private const val KEY_ROUTE_START_MS = "route_start_ms"
    private const val KEY_ROUTE_RECORDING = "route_recording"
    
    /**
     * Cumulative dose data for display
     */
    data class CumulativeDose(
        val sessionDoseUSv: Double,       // Current session accumulated dose
        val sessionDurationMs: Long,       // Session duration
        val sessionStartMs: Long,          // When session started
        
        val todayDoseUSv: Double,          // Today's accumulated dose
        val weekDoseUSv: Double,           // This week's accumulated dose
        val allTimeDoseUSv: Double,        // All-time accumulated dose
        val allTimeStartMs: Long,          // When tracking started
        
        val routeDoseUSv: Double,          // Current route dose (if recording)
        val routeDurationMs: Long,         // Route duration
        val isRouteRecording: Boolean,     // Whether route recording is active
        
        val averageDoseRate: Double,       // Average dose rate this session (μSv/h)
        val peakDoseRate: Float,           // Peak dose rate this session
        val peakDoseRateTime: Long         // When peak occurred
    )
    
    // In-memory tracking for session
    private var sessionDoseUSv: Double = 0.0
    private var sessionStartMs: Long = System.currentTimeMillis()
    private var lastReadingTimeMs: Long = 0L
    private var lastDoseRateUSvH: Float = 0f
    private var peakDoseRate: Float = 0f
    private var peakDoseRateTime: Long = 0L
    private var sampleCount: Int = 0
    private var doseRateSum: Double = 0.0
    
    // Route tracking in memory
    private var routeDoseUSv: Double = 0.0
    private var routeStartMs: Long = 0L
    private var isRouteRecording: Boolean = false
    
    /**
     * Initialize tracker from persistent storage
     */
    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        
        // Restore session state (only if recent - within 5 minutes)
        val savedLastTime = prefs.getLong(KEY_LAST_READING_TIME_MS, 0L)
        val now = System.currentTimeMillis()
        
        if (now - savedLastTime < 5 * 60 * 1000) {
            // Continue previous session
            sessionDoseUSv = prefs.getFloat(KEY_SESSION_DOSE_USV, 0f).toDouble()
            sessionStartMs = prefs.getLong(KEY_SESSION_START_MS, now)
            lastReadingTimeMs = savedLastTime
            lastDoseRateUSvH = prefs.getFloat(KEY_LAST_DOSE_RATE, 0f)
        } else {
            // Start new session
            resetSession(context)
        }
        
        // Restore route state
        isRouteRecording = prefs.getBoolean(KEY_ROUTE_RECORDING, false)
        if (isRouteRecording) {
            routeDoseUSv = prefs.getFloat(KEY_ROUTE_DOSE_USV, 0f).toDouble()
            routeStartMs = prefs.getLong(KEY_ROUTE_START_MS, now)
        }
        
        // Check for day/week rollover
        checkDateRollover(context)
    }
    
    /**
     * Add a new reading and accumulate dose using trapezoidal integration
     */
    fun addReading(context: Context, doseRateUSvH: Float, timestampMs: Long = System.currentTimeMillis()) {
        if (doseRateUSvH < 0 || !doseRateUSvH.isFinite()) return
        
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        
        // Calculate dose increment using trapezoidal integration
        if (lastReadingTimeMs > 0 && timestampMs > lastReadingTimeMs) {
            val deltaHours = (timestampMs - lastReadingTimeMs) / (1000.0 * 3600.0)
            
            // Trapezoidal: average of last and current rate × time
            val avgRate = (lastDoseRateUSvH + doseRateUSvH) / 2.0
            val doseIncrement = avgRate * deltaHours
            
            if (doseIncrement > 0 && doseIncrement.isFinite()) {
                // Accumulate to all trackers
                sessionDoseUSv += doseIncrement
                routeDoseUSv += doseIncrement  // Will be 0 if not recording
                
                // Persist to daily/weekly/alltime
                val todayDose = getTodayDose(context) + doseIncrement
                val weekDose = getWeekDose(context) + doseIncrement
                val allTimeDose = getAllTimeDose(context) + doseIncrement
                
                prefs.edit()
                    .putFloat(KEY_TODAY_DOSE_USV, todayDose.toFloat())
                    .putFloat(KEY_WEEK_DOSE_USV, weekDose.toFloat())
                    .putFloat(KEY_ALLTIME_DOSE_USV, allTimeDose.toFloat())
                    .apply()
            }
        }
        
        // Track peak
        if (doseRateUSvH > peakDoseRate) {
            peakDoseRate = doseRateUSvH
            peakDoseRateTime = timestampMs
        }
        
        // Track average
        sampleCount++
        doseRateSum += doseRateUSvH
        
        // Update state
        lastReadingTimeMs = timestampMs
        lastDoseRateUSvH = doseRateUSvH
        
        // Save session state
        prefs.edit()
            .putFloat(KEY_SESSION_DOSE_USV, sessionDoseUSv.toFloat())
            .putLong(KEY_LAST_READING_TIME_MS, lastReadingTimeMs)
            .putFloat(KEY_LAST_DOSE_RATE, lastDoseRateUSvH)
            .apply()
        
        // Save route state if recording
        if (isRouteRecording) {
            prefs.edit()
                .putFloat(KEY_ROUTE_DOSE_USV, routeDoseUSv.toFloat())
                .apply()
        }
    }
    
    /**
     * Get current cumulative dose data
     */
    fun getCumulativeDose(context: Context): CumulativeDose {
        checkDateRollover(context)
        
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        
        return CumulativeDose(
            sessionDoseUSv = sessionDoseUSv,
            sessionDurationMs = now - sessionStartMs,
            sessionStartMs = sessionStartMs,
            
            todayDoseUSv = getTodayDose(context),
            weekDoseUSv = getWeekDose(context),
            allTimeDoseUSv = getAllTimeDose(context),
            allTimeStartMs = prefs.getLong(KEY_ALLTIME_START_MS, sessionStartMs),
            
            routeDoseUSv = if (isRouteRecording) routeDoseUSv else 0.0,
            routeDurationMs = if (isRouteRecording) now - routeStartMs else 0L,
            isRouteRecording = isRouteRecording,
            
            averageDoseRate = if (sampleCount > 0) doseRateSum / sampleCount else 0.0,
            peakDoseRate = peakDoseRate,
            peakDoseRateTime = peakDoseRateTime
        )
    }
    
    /**
     * Reset the current session
     */
    fun resetSession(context: Context) {
        val now = System.currentTimeMillis()
        sessionDoseUSv = 0.0
        sessionStartMs = now
        lastReadingTimeMs = 0L
        lastDoseRateUSvH = 0f
        peakDoseRate = 0f
        peakDoseRateTime = 0L
        sampleCount = 0
        doseRateSum = 0.0
        
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SESSION_DOSE_USV, 0f)
            .putLong(KEY_SESSION_START_MS, now)
            .putLong(KEY_LAST_READING_TIME_MS, 0L)
            .putFloat(KEY_LAST_DOSE_RATE, 0f)
            .apply()
    }
    
    /**
     * Start recording a route
     */
    fun startRouteRecording(context: Context) {
        val now = System.currentTimeMillis()
        isRouteRecording = true
        routeDoseUSv = 0.0
        routeStartMs = now
        
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ROUTE_RECORDING, true)
            .putFloat(KEY_ROUTE_DOSE_USV, 0f)
            .putLong(KEY_ROUTE_START_MS, now)
            .apply()
    }
    
    /**
     * Stop recording a route and return the final dose
     */
    fun stopRouteRecording(context: Context): Double {
        val finalDose = routeDoseUSv
        isRouteRecording = false
        routeDoseUSv = 0.0
        
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ROUTE_RECORDING, false)
            .apply()
        
        return finalDose
    }
    
    /**
     * Check and handle date rollover for daily/weekly tracking
     */
    private fun checkDateRollover(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val today = getCurrentDateInt()
        val savedToday = prefs.getInt(KEY_TODAY_DATE, 0)
        
        if (savedToday != today) {
            // New day - reset today's dose
            prefs.edit()
                .putFloat(KEY_TODAY_DOSE_USV, 0f)
                .putInt(KEY_TODAY_DATE, today)
                .apply()
            
            // Check for week rollover (Monday = start of week)
            val weekStart = getWeekStartDateInt()
            val savedWeekStart = prefs.getInt(KEY_WEEK_START_DATE, 0)
            
            if (savedWeekStart != weekStart) {
                prefs.edit()
                    .putFloat(KEY_WEEK_DOSE_USV, 0f)
                    .putInt(KEY_WEEK_START_DATE, weekStart)
                    .apply()
            }
        }
        
        // Initialize all-time tracking if needed
        if (prefs.getLong(KEY_ALLTIME_START_MS, 0L) == 0L) {
            prefs.edit()
                .putLong(KEY_ALLTIME_START_MS, System.currentTimeMillis())
                .putFloat(KEY_ALLTIME_DOSE_USV, 0f)
                .apply()
        }
    }
    
    private fun getTodayDose(context: Context): Double {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getFloat(KEY_TODAY_DOSE_USV, 0f).toDouble()
    }
    
    private fun getWeekDose(context: Context): Double {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getFloat(KEY_WEEK_DOSE_USV, 0f).toDouble()
    }
    
    private fun getAllTimeDose(context: Context): Double {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getFloat(KEY_ALLTIME_DOSE_USV, 0f).toDouble()
    }
    
    private fun getCurrentDateInt(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.YEAR) * 10000 +
               (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
               cal.get(java.util.Calendar.DAY_OF_MONTH)
    }
    
    private fun getWeekStartDateInt(): Int {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
        return cal.get(java.util.Calendar.YEAR) * 10000 +
               (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
               cal.get(java.util.Calendar.DAY_OF_MONTH)
    }
    
    /**
     * Format dose for display
     */
    fun formatDose(doseUSv: Double): String {
        return when {
            doseUSv < 0.001 -> String.format("%.4f μSv", doseUSv)
            doseUSv < 0.01 -> String.format("%.3f μSv", doseUSv)
            doseUSv < 1.0 -> String.format("%.2f μSv", doseUSv)
            doseUSv < 10.0 -> String.format("%.2f μSv", doseUSv)
            doseUSv < 1000.0 -> String.format("%.1f μSv", doseUSv)
            else -> String.format("%.2f mSv", doseUSv / 1000.0)
        }
    }
    
    /**
     * Format duration for display
     */
    fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}
