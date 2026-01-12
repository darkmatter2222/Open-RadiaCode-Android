package com.radiacode.ble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Chart widget showing dose rate and count rate with sparkline charts.
 * Larger widget for visualizing trends alongside current values.
 */
class ChartWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val options = appWidgetManager.getAppWidgetOptions(id)
            appWidgetManager.updateAppWidget(id, buildViews(context, options))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, newOptions))
    }

    companion object {
        private const val COLOR_GREEN = 0xFF69F0AE.toInt()
        private const val COLOR_RED = 0xFFFF5252.toInt()
        private const val COLOR_MUTED = 0xFF6E6E78.toInt()
        private const val COLOR_CYAN = 0xFF00E5FF.toInt()
        private const val COLOR_MAGENTA = 0xFFE040FB.toInt()
        
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChartWidgetProvider::class.java))
            if (ids.isEmpty()) return
            
            ids.forEach { id ->
                val options = mgr.getAppWidgetOptions(id)
                try {
                    mgr.updateAppWidget(id, buildViews(context, options))
                } catch (e: Exception) {
                    android.util.Log.e("RadiaCode", "ChartWidget updateAll error", e)
                }
            }
        }

        private fun buildViews(context: Context, options: Bundle?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_chart)
            
            try {

            val last = Prefs.getLastReading(context)
            val recentReadings = Prefs.getRecentReadings(context, 60)
            val isConnected = last != null && (System.currentTimeMillis() - last.timestampMs) < 30000
            
            // Get user's preferred units
            val doseUnit = Prefs.getDoseUnit(context, Prefs.DoseUnit.USV_H)
            val countUnit = Prefs.getCountUnit(context, Prefs.CountUnit.CPS)
            
            // Status indicator
            views.setTextViewText(R.id.widgetStatusDot, if (isConnected) "●" else "○")
            views.setTextColor(R.id.widgetStatusDot, if (isConnected) COLOR_GREEN else COLOR_RED)
            
            // Get widget dimensions for sparkline sizing
            val density = context.resources.displayMetrics.density
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300) ?: 300
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150) ?: 150
            
            // Calculate sparkline dimensions - larger for better visibility
            val sparkWidth = ((minWidth * density) / 2f).toInt().coerceIn(100, 600)
            val sparkHeight = ((minHeight * density) / 5f).toInt().coerceIn(40, 150)
            
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
                
                // Generate sparklines if we have enough data
                if (recentReadings.size >= 5) {
                    // Dose sparkline (convert to user's unit)
                    val doseValues = when (doseUnit) {
                        Prefs.DoseUnit.USV_H -> recentReadings.map { it.uSvPerHour }
                        Prefs.DoseUnit.NSV_H -> recentReadings.map { it.uSvPerHour * 1000f }
                    }
                    val doseBitmap = createSparkline(doseValues, sparkWidth, sparkHeight, COLOR_CYAN)
                    views.setImageViewBitmap(R.id.widgetDoseSparkline, doseBitmap)
                    
                    // CPS sparkline (convert to user's unit)
                    val cpsValues = when (countUnit) {
                        Prefs.CountUnit.CPS -> recentReadings.map { it.cps }
                        Prefs.CountUnit.CPM -> recentReadings.map { it.cps * 60f }
                    }
                    val cpsBitmap = createSparkline(cpsValues, sparkWidth, sparkHeight, COLOR_MAGENTA)
                    views.setImageViewBitmap(R.id.widgetCpsSparkline, cpsBitmap)
                }
            }
            } catch (e: Exception) {
                android.util.Log.e("RadiaCode", "ChartWidget buildViews error", e)
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
        
        private fun createSparkline(values: List<Float>, width: Int, height: Int, color: Int): Bitmap {
            if (width <= 0 || height <= 0) {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Draw subtle background
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = Color.argb(30, 255, 255, 255)
            }
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
            
            // If no data, show "No data" indicator
            if (values.isEmpty() || values.size < 2) {
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.argb(100, 255, 255, 255)
                    textSize = height / 3f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("—", width / 2f, height / 2f + textPaint.textSize / 3f, textPaint)
                return bitmap
            }
            
            val minV = values.minOrNull() ?: 0f
            val maxV = values.maxOrNull() ?: 1f
            val range = max(0.0001f, maxV - minV)
            
            // Padding
            val padLeft = 8f
            val padRight = 8f
            val padTop = 8f
            val padBottom = 8f
            val chartWidth = width - padLeft - padRight
            val chartHeight = height - padTop - padBottom
            
            // Create line path
            val linePath = Path()
            val fillPath = Path()
            
            val n = values.size
            
            for (i in 0 until n) {
                val x = padLeft + (i.toFloat() / max(1, n - 1)) * chartWidth
                val yNorm = (values[i] - minV) / range
                val y = padTop + chartHeight * (1f - yNorm)
                
                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, height.toFloat() - padBottom)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            
            // Close fill path
            fillPath.lineTo(padLeft + chartWidth, height.toFloat() - padBottom)
            fillPath.close()
            
            // Draw gradient fill
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, padTop, 0f, height.toFloat() - padBottom,
                    Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.argb(10, Color.red(color), Color.green(color), Color.blue(color)),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(fillPath, fillPaint)
            
            // Draw line with glow effect
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f
                this.color = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(linePath, glowPaint)
            
            // Draw main line
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                this.color = color
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(linePath, linePaint)
            
            return bitmap
        }
    }
}
