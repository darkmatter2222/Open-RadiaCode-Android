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
import kotlin.math.abs
import kotlin.math.sqrt

class RadiaCodeWidgetProvider : AppWidgetProvider() {

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
        // Colors matching pro theme
        private const val COLOR_GREEN = 0xFF69F0AE.toInt()
        private const val COLOR_RED = 0xFFFF5252.toInt()
        private const val COLOR_MUTED = 0xFF6E6E78.toInt()
        private const val COLOR_CYAN = 0xFF00E5FF.toInt()
        private const val COLOR_MAGENTA = 0xFFE040FB.toInt()
        
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, RadiaCodeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            
            ids.forEach { id ->
                mgr.updateAppWidget(id, buildViews(context))
            }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.radiacode_widget_simple)

            val last = Prefs.getLastReading(context)
            val recentReadings = Prefs.getRecentReadings(context, 30)
            val isConnected = last != null && (System.currentTimeMillis() - last.timestampMs) < 30000
            
            // Status indicator color
            views.setTextColor(R.id.widgetStatusIndicator, if (isConnected) COLOR_GREEN else COLOR_RED)
            views.setTextViewText(R.id.widgetStatusIndicator, if (isConnected) "●" else "○")
            
            if (last == null) {
                views.setTextViewText(R.id.widgetDose, "—")
                views.setTextViewText(R.id.widgetCps, "—")
                views.setTextViewText(R.id.widgetDoseTrend, "─")
                views.setTextViewText(R.id.widgetCpsTrend, "─")
                views.setTextColor(R.id.widgetDoseTrend, COLOR_MUTED)
                views.setTextColor(R.id.widgetCpsTrend, COLOR_MUTED)
                views.setTextViewText(R.id.widgetStatus, "No data")
            } else {
                // Calculate statistics from recent readings
                val doseStats = calculateStats(recentReadings.map { it.uSvPerHour })
                val cpsStats = calculateStats(recentReadings.map { it.cps })
                
                // Format dose value
                val doseStr = if (last.uSvPerHour >= 1) {
                    String.format(Locale.US, "%.2f", last.uSvPerHour)
                } else {
                    String.format(Locale.US, "%.3f", last.uSvPerHour)
                }
                views.setTextViewText(R.id.widgetDose, doseStr)
                
                // Format CPS value
                val cpsStr = if (last.cps >= 100) {
                    String.format(Locale.US, "%.0f", last.cps)
                } else {
                    String.format(Locale.US, "%.1f", last.cps)
                }
                views.setTextViewText(R.id.widgetCps, cpsStr)
                
                // Calculate z-scores
                val doseZScore = if (doseStats.stdDev > 0.0001f) (last.uSvPerHour - doseStats.mean) / doseStats.stdDev else 0f
                val cpsZScore = if (cpsStats.stdDev > 0.0001f) (last.cps - cpsStats.mean) / cpsStats.stdDev else 0f
                
                // Set dose trend with color
                val (doseTrendText, doseTrendColor) = getTrendDisplay(doseZScore, doseStats)
                views.setTextViewText(R.id.widgetDoseTrend, doseTrendText)
                views.setTextColor(R.id.widgetDoseTrend, doseTrendColor)
                
                // Set CPS trend with color
                val (cpsTrendText, cpsTrendColor) = getTrendDisplay(cpsZScore, cpsStats)
                views.setTextViewText(R.id.widgetCpsTrend, cpsTrendText)
                views.setTextColor(R.id.widgetCpsTrend, cpsTrendColor)
                
                // Color the main values based on z-score intensity
                views.setTextColor(R.id.widgetDose, getValueColor(doseZScore, COLOR_CYAN))
                views.setTextColor(R.id.widgetCps, getValueColor(cpsZScore, COLOR_MAGENTA))
                
                // Status/Timestamp
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                val statusText = if (isConnected) "● ${fmt.format(Date(last.timestampMs))}" else fmt.format(Date(last.timestampMs))
                views.setTextViewText(R.id.widgetStatus, statusText)
                views.setTextColor(R.id.widgetStatus, if (isConnected) COLOR_GREEN else COLOR_MUTED)
            }

            // Click handler to launch app
            val launchIntent = Intent(context, MainActivity::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)

            return views
        }
        
        private data class Stats(val mean: Float, val stdDev: Float)
        
        private fun calculateStats(values: List<Float>): Stats {
            if (values.size < 2) return Stats(values.firstOrNull() ?: 0f, 0f)
            
            val mean = values.sum() / values.size
            val variance = values.map { (it - mean) * (it - mean) }.sum() / values.size
            val stdDev = sqrt(variance)
            
            return Stats(mean, stdDev)
        }
        
        private fun getTrendDisplay(zScore: Float, stats: Stats): Pair<String, Int> {
            val absZ = abs(zScore)
            
            return when {
                absZ > 2f && zScore > 0 -> "▲▲ +${String.format(Locale.US, "%.0f", absZ)}σ" to COLOR_GREEN
                absZ > 1f && zScore > 0 -> "▲ +${String.format(Locale.US, "%.1f", absZ)}σ" to COLOR_GREEN
                absZ > 2f && zScore < 0 -> "▼▼ -${String.format(Locale.US, "%.0f", absZ)}σ" to COLOR_RED
                absZ > 1f && zScore < 0 -> "▼ -${String.format(Locale.US, "%.1f", absZ)}σ" to COLOR_RED
                else -> "─" to COLOR_MUTED
            }
        }
        
        private fun getValueColor(zScore: Float, baseColor: Int): Int {
            val absZ = abs(zScore)
            
            // If outside 1 sigma, tint toward green or red
            return when {
                absZ <= 1f -> baseColor
                zScore > 0 -> blendColors(baseColor, COLOR_GREEN, ((absZ - 1f) / 2f).coerceIn(0f, 0.5f))
                else -> blendColors(baseColor, COLOR_RED, ((absZ - 1f) / 2f).coerceIn(0f, 0.5f))
            }
        }
        
        private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
            val inverseRatio = 1f - ratio
            val r = (Color.red(color1) * inverseRatio + Color.red(color2) * ratio).toInt()
            val g = (Color.green(color1) * inverseRatio + Color.green(color2) * ratio).toInt()
            val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio).toInt()
            return Color.rgb(r, g, b)
        }
    }
}
