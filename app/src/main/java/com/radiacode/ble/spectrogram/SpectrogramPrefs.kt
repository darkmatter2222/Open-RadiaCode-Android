package com.radiacode.ble.spectrogram

import android.content.Context

/**
 * Preferences for the Vega Spectral Analysis feature.
 * All settings persist across app restarts and reboots.
 */
object SpectrogramPrefs {
    private const val FILE = "spectrogram_prefs"
    
    // Recording state - PERSISTS ALWAYS (user is in full control)
    private const val KEY_RECORDING_ENABLED = "recording_enabled"
    
    // Update frequency (milliseconds)
    private const val KEY_UPDATE_INTERVAL_MS = "update_interval_ms"
    private const val DEFAULT_UPDATE_INTERVAL_MS = 5000L  // 5 seconds
    
    // History depth (milliseconds)
    private const val KEY_HISTORY_DEPTH_MS = "history_depth_ms"
    private const val DEFAULT_HISTORY_DEPTH_MS = 15L * 60 * 1000  // 15 minutes
    
    // Spectrum source type
    private const val KEY_SPECTRUM_SOURCE = "spectrum_source"
    
    // Display mode
    private const val KEY_DISPLAY_MODE = "display_mode"
    
    // Isotope markers
    private const val KEY_SHOW_ISOTOPE_MARKERS = "show_isotope_markers"
    
    // Background subtraction
    private const val KEY_SHOW_BACKGROUND_SUBTRACTION = "show_background_subtraction"
    private const val KEY_SELECTED_BACKGROUND_ID = "selected_background_id"
    
    // Color scheme (pro theme is default)
    private const val KEY_COLOR_SCHEME = "color_scheme"
    
    // Last viewed device
    private const val KEY_LAST_DEVICE_ID = "last_device_id"
    
    // Recording start time (for tracking duration)
    private const val KEY_RECORDING_START_MS = "recording_start_ms"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECORDING STATE (Persists always - user controls)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if spectrum recording is enabled.
     * This state NEVER changes except by explicit user action.
     */
    fun isRecordingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_RECORDING_ENABLED, false)
    }
    
    /**
     * Set spectrum recording enabled/disabled.
     * Called ONLY when user explicitly toggles recording.
     */
    fun setRecordingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putBoolean(KEY_RECORDING_ENABLED, enabled)
        
        // Track when recording started
        if (enabled && !prefs.getBoolean(KEY_RECORDING_ENABLED, false)) {
            editor.putLong(KEY_RECORDING_START_MS, System.currentTimeMillis())
        }
        
        editor.apply()
    }
    
    /**
     * Get when recording was started (for duration display).
     */
    fun getRecordingStartMs(context: Context): Long {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_RECORDING_START_MS, System.currentTimeMillis())
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE FREQUENCY
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get the spectrum update interval in milliseconds.
     */
    fun getUpdateIntervalMs(context: Context): Long {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_UPDATE_INTERVAL_MS, DEFAULT_UPDATE_INTERVAL_MS)
    }
    
    /**
     * Set the spectrum update interval in milliseconds.
     */
    fun setUpdateIntervalMs(context: Context, intervalMs: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_UPDATE_INTERVAL_MS, intervalMs)
            .apply()
    }
    
    /**
     * Available update interval options.
     */
    enum class UpdateInterval(val ms: Long, val label: String) {
        FAST(1000L, "1 second"),
        NORMAL(5000L, "5 seconds"),
        SLOW(10000L, "10 seconds"),
        VERY_SLOW(30000L, "30 seconds");
        
        companion object {
            fun fromMs(ms: Long): UpdateInterval {
                return values().find { it.ms == ms } ?: NORMAL
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HISTORY DEPTH
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get the display history depth in milliseconds.
     */
    fun getHistoryDepthMs(context: Context): Long {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_HISTORY_DEPTH_MS, DEFAULT_HISTORY_DEPTH_MS)
    }
    
    /**
     * Set the display history depth in milliseconds.
     */
    fun setHistoryDepthMs(context: Context, depthMs: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_HISTORY_DEPTH_MS, depthMs)
            .apply()
    }
    
    /**
     * Available history depth options.
     */
    enum class HistoryDepth(val ms: Long, val label: String) {
        FIVE_MIN(5L * 60 * 1000, "5 minutes"),
        FIFTEEN_MIN(15L * 60 * 1000, "15 minutes"),
        THIRTY_MIN(30L * 60 * 1000, "30 minutes"),
        ONE_HOUR(60L * 60 * 1000, "1 hour"),
        TWO_HOURS(2L * 60 * 60 * 1000, "2 hours"),
        FOUR_HOURS(4L * 60 * 60 * 1000, "4 hours");
        
        companion object {
            fun fromMs(ms: Long): HistoryDepth {
                return values().find { it.ms == ms } ?: FIFTEEN_MIN
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SPECTRUM SOURCE TYPE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get the spectrum source type.
     */
    fun getSpectrumSource(context: Context): SpectrumSourceType {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SPECTRUM_SOURCE, SpectrumSourceType.DIFFERENTIAL.name)
        return try {
            SpectrumSourceType.valueOf(name!!)
        } catch (e: Exception) {
            SpectrumSourceType.DIFFERENTIAL
        }
    }
    
    /**
     * Set the spectrum source type.
     */
    fun setSpectrumSource(context: Context, source: SpectrumSourceType) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SPECTRUM_SOURCE, source.name)
            .apply()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DISPLAY MODE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get the display mode for the waterfall.
     */
    fun getDisplayMode(context: Context): SpectrogramDisplayMode {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_DISPLAY_MODE, SpectrogramDisplayMode.COLOR_CODED_SEGMENTS.name)
        return try {
            SpectrogramDisplayMode.valueOf(name!!)
        } catch (e: Exception) {
            SpectrogramDisplayMode.COLOR_CODED_SEGMENTS
        }
    }
    
    /**
     * Set the display mode.
     */
    fun setDisplayMode(context: Context, mode: SpectrogramDisplayMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_MODE, mode.name)
            .apply()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ISOTOPE MARKERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if isotope markers should be shown.
     */
    fun isShowIsotopeMarkers(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_ISOTOPE_MARKERS, false)  // Default OFF as requested
    }
    
    /**
     * Set whether to show isotope markers.
     */
    fun setShowIsotopeMarkers(context: Context, show: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_ISOTOPE_MARKERS, show)
            .apply()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BACKGROUND SUBTRACTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if background subtraction is enabled.
     */
    fun isBackgroundSubtractionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_BACKGROUND_SUBTRACTION, false)
    }
    
    /**
     * Set whether background subtraction is enabled.
     */
    fun setBackgroundSubtractionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_BACKGROUND_SUBTRACTION, enabled)
            .apply()
    }
    
    /**
     * Get the selected background ID for subtraction.
     */
    fun getSelectedBackgroundId(context: Context): Long? {
        val id = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_SELECTED_BACKGROUND_ID, -1)
        return if (id >= 0) id else null
    }
    
    /**
     * Set the selected background ID for subtraction.
     */
    fun setSelectedBackgroundId(context: Context, id: Long?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SELECTED_BACKGROUND_ID, id ?: -1)
            .apply()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // COLOR SCHEME
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Color schemes for the heatmap.
     */
    enum class ColorScheme(val label: String) {
        PRO_THEME("Pro Theme (Dark-Magenta-Cyan)"),
        HOT("Hot (Black-Red-Orange-Yellow-White)"),
        VIRIDIS("Viridis (Purple-Blue-Green-Yellow)"),
        PLASMA("Plasma (Magenta-Red-Orange-Yellow)");
    }
    
    /**
     * Get the color scheme.
     */
    fun getColorScheme(context: Context): ColorScheme {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_COLOR_SCHEME, ColorScheme.PRO_THEME.name)
        return try {
            ColorScheme.valueOf(name!!)
        } catch (e: Exception) {
            ColorScheme.PRO_THEME
        }
    }
    
    /**
     * Set the color scheme.
     */
    fun setColorScheme(context: Context, scheme: ColorScheme) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COLOR_SCHEME, scheme.name)
            .apply()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LAST DEVICE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get the last viewed device ID.
     */
    fun getLastDeviceId(context: Context): String? {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_ID, null)
    }
    
    /**
     * Set the last viewed device ID.
     */
    fun setLastDeviceId(context: Context, deviceId: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_ID, deviceId)
            .apply()
    }
}
