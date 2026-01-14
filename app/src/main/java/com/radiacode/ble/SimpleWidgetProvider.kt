package com.radiacode.ble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple widget showing dose rate and count rate with proper unit conversion.
 * Compact design for quick glance at current readings.
 * Supports per-widget device binding and color customization.
 */
class SimpleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, id))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
    }
    
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Clean up widget configurations when widgets are removed
        appWidgetIds.forEach { id ->
            Prefs.deleteWidgetConfig(context, id)
        }
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        private const val COLOR_GREEN = 0xFF69F0AE.toInt()
        private const val COLOR_RED = 0xFFFF5252.toInt()
        private const val COLOR_MUTED = 0xFF6E6E78.toInt()
        private const val COLOR_CYAN = 0xFF00E5FF.toInt()
        private const val COLOR_MAGENTA = 0xFFE040FB.toInt()
        
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, SimpleWidgetProvider::class.java))
            if (ids.isEmpty()) return
            
            ids.forEach { id ->
                try {
                    mgr.updateAppWidget(id, buildViews(context, id))
                } catch (e: Exception) {
                    android.util.Log.e("RadiaCode", "SimpleWidget updateAll error", e)
                }
            }
        }
        
        /**
         * Update a specific widget by ID.
         */
        fun updateWidget(context: Context, widgetId: Int) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            try {
                mgr.updateAppWidget(widgetId, buildViews(context, widgetId))
            } catch (e: Exception) {
                android.util.Log.e("RadiaCode", "SimpleWidget updateWidget error for $widgetId", e)
            }
        }
        
        /**
         * Update all widgets bound to a specific device.
         */
        fun updateForDevice(context: Context, deviceId: String?) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, SimpleWidgetProvider::class.java))
            
            ids.forEach { id ->
                val config = Prefs.getWidgetConfig(context, id)
                // Update if widget is bound to this device OR if widget has no config (legacy)
                if (config == null || config.deviceId == deviceId) {
                    try {
                        mgr.updateAppWidget(id, buildViews(context, id))
                    } catch (e: Exception) {
                        android.util.Log.e("RadiaCode", "SimpleWidget updateForDevice error", e)
                    }
                }
            }
        }

        private fun buildViews(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_simple)

            try {
            // Get widget configuration (or use defaults for legacy widgets)
            val config = Prefs.getWidgetConfig(context, widgetId)
            val deviceId = config?.deviceId
            
            // Get data based on device binding
            val last = getLastReadingForDevice(context, deviceId)
            val isConnected = last != null && (System.currentTimeMillis() - last.timestampMs) < 30000
            
            // Get colors from config or use defaults
            val (doseColor, cpsColor) = getColors(config)
            
            // Get user's preferred units
            val doseUnit = Prefs.getDoseUnit(context, Prefs.DoseUnit.USV_H)
            val countUnit = Prefs.getCountUnit(context, Prefs.CountUnit.CPS)
            
            // Status indicator
            views.setTextViewText(R.id.widgetStatusDot, if (isConnected) "●" else "○")
            views.setTextColor(R.id.widgetStatusDot, if (isConnected) COLOR_GREEN else COLOR_RED)
            
            // Show device name if bound to specific device
            if (deviceId != null) {
                val device = Prefs.getDeviceById(context, deviceId)
                val deviceName = device?.shortDisplayName ?: "Device"
                views.setTextViewText(R.id.widgetTitle, deviceName)
            } else {
                views.setTextViewText(R.id.widgetTitle, "Open RadiaCode")
            }
            
            if (last == null) {
                views.setTextViewText(R.id.widgetDoseValue, "—")
                views.setTextViewText(R.id.widgetCpsValue, "—")
                views.setTextViewText(R.id.widgetTime, "No data")
                views.setTextColor(R.id.widgetTime, COLOR_MUTED)
            } else {
                // Get dynamic color if enabled
                val dynamicColorEnabled = config?.dynamicColorEnabled == true
                val thresholds = if (dynamicColorEnabled) Prefs.getDynamicColorThresholds(context) else null
                
                val effectiveDoseColor = if (dynamicColorEnabled) {
                    DynamicColorCalculator.getColorForDose(last.uSvPerHour, thresholds)
                } else {
                    doseColor
                }
                
                val effectiveCpsColor = if (dynamicColorEnabled) {
                    DynamicColorCalculator.getColorForCps(last.cps, thresholds)
                } else {
                    cpsColor
                }
                
                // Convert and format dose value based on user's unit preference
                val (doseValue, doseUnitStr) = when (doseUnit) {
                    Prefs.DoseUnit.USV_H -> last.uSvPerHour to "μSv/h"
                    Prefs.DoseUnit.NSV_H -> (last.uSvPerHour * 1000f) to "nSv/h"
                }
                
                val doseStr = when {
                    doseValue >= 100 -> String.format(Locale.US, "%.0f", doseValue)
                    doseValue >= 10 -> String.format(Locale.US, "%.1f", doseValue)
                    doseValue >= 1 -> String.format(Locale.US, "%.2f", doseValue)
                    else -> String.format(Locale.US, "%.3f", doseValue)
                }
                views.setTextViewText(R.id.widgetDoseValue, doseStr)
                views.setTextViewText(R.id.widgetDoseUnit, doseUnitStr)
                views.setTextColor(R.id.widgetDoseValue, effectiveDoseColor)
                
                // Convert and format count value based on user's unit preference
                val (cpsValue, cpsUnitStr) = when (countUnit) {
                    Prefs.CountUnit.CPS -> last.cps to "cps"
                    Prefs.CountUnit.CPM -> (last.cps * 60f) to "cpm"
                }
                
                val cpsStr = when {
                    cpsValue >= 1000 -> String.format(Locale.US, "%.0f", cpsValue)
                    cpsValue >= 100 -> String.format(Locale.US, "%.0f", cpsValue)
                    cpsValue >= 10 -> String.format(Locale.US, "%.1f", cpsValue)
                    else -> String.format(Locale.US, "%.2f", cpsValue)
                }
                views.setTextViewText(R.id.widgetCpsValue, cpsStr)
                views.setTextViewText(R.id.widgetCpsUnit, cpsUnitStr)
                views.setTextColor(R.id.widgetCpsValue, effectiveCpsColor)
                
                // Timestamp
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                views.setTextViewText(R.id.widgetTime, fmt.format(Date(last.timestampMs)))
                views.setTextColor(R.id.widgetTime, if (isConnected) COLOR_MUTED else COLOR_RED)
            }
            } catch (e: Exception) {
                android.util.Log.e("RadiaCode", "SimpleWidget buildViews error", e)
                views.setTextViewText(R.id.widgetDoseValue, "ERR")
                views.setTextViewText(R.id.widgetCpsValue, "ERR")
            }

            // Click handler to launch app
            val launchIntent = Intent(context, MainActivity::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)

            return views
        }
        
        /**
         * Get last reading for a specific device or global.
         */
        private fun getLastReadingForDevice(context: Context, deviceId: String?): Prefs.LastReading? {
            return if (deviceId == null) {
                Prefs.getLastReading(context)
            } else {
                Prefs.getDeviceLastReading(context, deviceId)
            }
        }
        
        /**
         * Get colors from widget config or use defaults.
         */
        private fun getColors(config: WidgetConfig?): Pair<Int, Int> {
            if (config == null) {
                return COLOR_CYAN to COLOR_MAGENTA
            }
            
            val scheme = config.colorScheme
            val customColors = config.customColors
            
            return when {
                scheme == ColorScheme.CUSTOM && customColors != null -> {
                    parseColor(customColors.doseColor, COLOR_CYAN) to parseColor(customColors.cpsColor, COLOR_MAGENTA)
                }
                else -> {
                    parseColor(scheme.lineColor, COLOR_CYAN) to COLOR_MAGENTA
                }
            }
        }
        
        private fun parseColor(hex: String, default: Int): Int {
            return try {
                Color.parseColor("#$hex")
            } catch (_: Exception) {
                default
            }
        }
    }
}
