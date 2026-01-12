package com.radiacode.ble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple widget showing dose rate and count rate with proper unit conversion.
 * Compact design for quick glance at current readings.
 */
class SimpleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context))
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
                mgr.updateAppWidget(id, buildViews(context))
            }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_simple)

            val last = Prefs.getLastReading(context)
            val isConnected = last != null && (System.currentTimeMillis() - last.timestampMs) < 30000
            
            // Get user's preferred units
            val doseUnit = Prefs.getDoseUnit(context, Prefs.DoseUnit.USV_H)
            val countUnit = Prefs.getCountUnit(context, Prefs.CountUnit.CPS)
            
            // Status indicator
            views.setTextViewText(R.id.widgetStatusDot, if (isConnected) "●" else "○")
            views.setTextColor(R.id.widgetStatusDot, if (isConnected) COLOR_GREEN else COLOR_RED)
            
            if (last == null) {
                views.setTextViewText(R.id.widgetDoseValue, "—")
                views.setTextViewText(R.id.widgetCpsValue, "—")
                views.setTextViewText(R.id.widgetTime, "No data")
                views.setTextColor(R.id.widgetTime, COLOR_MUTED)
            } else {
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
                views.setTextColor(R.id.widgetDoseValue, COLOR_CYAN)
                
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
                views.setTextColor(R.id.widgetCpsValue, COLOR_MAGENTA)
                
                // Timestamp
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                views.setTextViewText(R.id.widgetTime, fmt.format(Date(last.timestampMs)))
                views.setTextColor(R.id.widgetTime, if (isConnected) COLOR_MUTED else COLOR_RED)
            }

            // Click handler to launch app
            val launchIntent = Intent(context, MainActivity::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)

            return views
        }
    }
}
