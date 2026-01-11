package com.radiacode.ble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RadiaCodeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val options = appWidgetManager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80)
            appWidgetManager.updateAppWidget(id, buildViews(context, width, height))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        val width = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
        val height = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80)
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, width, height))
    }

    companion object {
        private const val SPARKLINE_POINTS = 30
        
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, RadiaCodeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            
            ids.forEach { id ->
                val options = mgr.getAppWidgetOptions(id)
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80)
                mgr.updateAppWidget(id, buildViews(context, width, height))
            }
        }

        private fun buildViews(context: Context, widgetWidth: Int, widgetHeight: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.radiacode_widget)

            val last = Prefs.getLastReading(context)
            val history = Prefs.getRecentReadings(context, SPARKLINE_POINTS)
            val isConnected = last != null && (System.currentTimeMillis() - last.timestampMs) < 30000
            
            // Status indicator
            views.setInt(R.id.widgetStatusDot, "setBackgroundResource", 
                if (isConnected) R.drawable.status_dot else R.drawable.status_dot_inactive)
            views.setTextViewText(R.id.widgetStatus, if (isConnected) "LIVE" else "—")
            views.setTextColor(R.id.widgetStatus, 
                ContextCompat.getColor(context, if (isConnected) R.color.pro_status_live else R.color.pro_text_muted))
            
            if (last == null) {
                views.setTextViewText(R.id.widgetDose, "—")
                views.setTextViewText(R.id.widgetCps, "—")
                views.setTextViewText(R.id.widgetUpdated, "")
                views.setViewVisibility(R.id.widgetDoseTrend, View.GONE)
                views.setViewVisibility(R.id.widgetCpsTrend, View.GONE)
                views.setViewVisibility(R.id.widgetSparkline, View.GONE)
            } else {
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
                
                // Timestamp
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                views.setTextViewText(R.id.widgetUpdated, fmt.format(Date(last.timestampMs)))
                
                // Trend indicators
                if (history.size >= 2) {
                    val recent = history.takeLast(5)
                    val avgRecent = recent.map { it.uSvPerHour }.average().toFloat()
                    val older = history.dropLast(5).takeLast(5)
                    if (older.isNotEmpty()) {
                        val avgOlder = older.map { it.uSvPerHour }.average().toFloat()
                        val delta = ((avgRecent - avgOlder) / avgOlder * 100).toInt()
                        if (kotlin.math.abs(delta) >= 3) {
                            val arrow = if (delta > 0) "▲" else "▼"
                            val color = ContextCompat.getColor(context, 
                                if (delta > 0) R.color.pro_red else R.color.pro_green)
                            views.setTextViewText(R.id.widgetDoseTrend, "$arrow $delta%")
                            views.setTextColor(R.id.widgetDoseTrend, color)
                            views.setViewVisibility(R.id.widgetDoseTrend, View.VISIBLE)
                        } else {
                            views.setViewVisibility(R.id.widgetDoseTrend, View.GONE)
                        }
                    }
                }
                
                // Sparkline (only if widget is tall enough)
                if (widgetHeight >= 120 && history.size >= 3) {
                    val sparkline = createSparklineBitmap(context, history, widgetWidth)
                    if (sparkline != null) {
                        views.setImageViewBitmap(R.id.widgetSparkline, sparkline)
                        views.setViewVisibility(R.id.widgetSparkline, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widgetSparkline, View.GONE)
                    }
                } else {
                    views.setViewVisibility(R.id.widgetSparkline, View.GONE)
                }
            }

            // Click handler
            val launchIntent = Intent(context, MainActivity::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)

            return views
        }
        
        private fun createSparklineBitmap(
            context: Context, 
            history: List<Prefs.LastReading>,
            widgetWidthDp: Int
        ): Bitmap? {
            if (history.size < 3) return null
            
            val density = context.resources.displayMetrics.density
            val width = (widgetWidthDp * density).toInt().coerceAtLeast(100)
            val height = (32 * density).toInt()
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            val values = history.map { it.uSvPerHour }
            val min = values.minOrNull() ?: return null
            val max = values.maxOrNull() ?: return null
            val range = (max - min).coerceAtLeast(0.001f)
            
            val cyanColor = ContextCompat.getColor(context, R.color.pro_cyan)
            
            // Gradient fill
            val fillPath = Path()
            val linePath = Path()
            
            val padding = 4 * density
            val chartWidth = width - padding * 2
            val chartHeight = height - padding * 2
            
            for (i in values.indices) {
                val x = padding + chartWidth * i / (values.size - 1)
                val yNorm = (values[i] - min) / range
                val y = height - padding - chartHeight * yNorm
                
                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, height.toFloat())
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            
            fillPath.lineTo(width - padding, height.toFloat())
            fillPath.close()
            
            // Draw fill with gradient
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    android.graphics.Color.argb(60, android.graphics.Color.red(cyanColor), 
                        android.graphics.Color.green(cyanColor), android.graphics.Color.blue(cyanColor)),
                    android.graphics.Color.argb(0, android.graphics.Color.red(cyanColor), 
                        android.graphics.Color.green(cyanColor), android.graphics.Color.blue(cyanColor)),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(fillPath, fillPaint)
            
            // Draw line
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2 * density
                color = cyanColor
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(linePath, linePaint)
            
            return bitmap
        }
    }
}
