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
 * Supports per-widget device binding and color customization.
 */
class ChartWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val options = appWidgetManager.getAppWidgetOptions(id)
            appWidgetManager.updateAppWidget(id, buildViews(context, id, options))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId, newOptions))
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
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChartWidgetProvider::class.java))
            if (ids.isEmpty()) return
            
            ids.forEach { id ->
                val options = mgr.getAppWidgetOptions(id)
                try {
                    mgr.updateAppWidget(id, buildViews(context, id, options))
                } catch (e: Exception) {
                    android.util.Log.e("RadiaCode", "ChartWidget updateAll error", e)
                }
            }
        }
        
        /**
         * Update a specific widget by ID (for targeted updates based on device).
         */
        fun updateWidget(context: Context, widgetId: Int) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val options = mgr.getAppWidgetOptions(widgetId)
            try {
                mgr.updateAppWidget(widgetId, buildViews(context, widgetId, options))
            } catch (e: Exception) {
                android.util.Log.e("RadiaCode", "ChartWidget updateWidget error for $widgetId", e)
            }
        }
        
        /**
         * Get trend indicator symbol and color based on rate of change.
         * Returns a pair of (symbol, color).
         */
        private fun getTrendIndicator(rateOfChange: Float, avgValue: Float): Pair<String, Int> {
            // Calculate threshold based on average value (5% of average as threshold)
            val threshold = avgValue * 0.05f
            
            return when {
                rateOfChange > threshold * 2 -> "↑↑" to COLOR_RED       // Rapid rise
                rateOfChange > threshold -> "↑" to 0xFFFFD600.toInt()    // Rising (yellow)
                rateOfChange < -threshold * 2 -> "↓↓" to 0xFF4DD0E1.toInt() // Rapid fall (cyan)
                rateOfChange < -threshold -> "↓" to COLOR_GREEN          // Falling
                else -> "" to 0 // Stable - no indicator
            }
        }
        
        /**
         * Update all widgets bound to a specific device.
         */
        fun updateForDevice(context: Context, deviceId: String?) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChartWidgetProvider::class.java))
            
            ids.forEach { id ->
                val config = Prefs.getWidgetConfig(context, id)
                // Update if widget is bound to this device OR if widget has no config (legacy)
                if (config == null || config.deviceId == deviceId) {
                    val options = mgr.getAppWidgetOptions(id)
                    try {
                        mgr.updateAppWidget(id, buildViews(context, id, options))
                    } catch (e: Exception) {
                        android.util.Log.e("RadiaCode", "ChartWidget updateForDevice error", e)
                    }
                }
            }
        }

        private fun buildViews(context: Context, widgetId: Int, options: Bundle?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_chart)
            
            try {
            // Get widget configuration (or use defaults for legacy widgets)
            val config = Prefs.getWidgetConfig(context, widgetId)
            val deviceId = config?.deviceId
            
            // Get data based on device binding
            val (last, recentReadings) = getDataForDevice(context, deviceId)
            val isConnected = last != null && (System.currentTimeMillis() - last.timestampMs) < 30000
            
            // Get colors from config, device, or use defaults
            val (doseColor, cpsColor) = getColors(context, config)
            
            // Get user's preferred units
            val doseUnit = Prefs.getDoseUnit(context, Prefs.DoseUnit.USV_H)
            val countUnit = Prefs.getCountUnit(context, Prefs.CountUnit.CPS)
            
            // Status indicator
            views.setTextViewText(R.id.widgetStatusDot, if (isConnected) "●" else "○")
            views.setTextColor(R.id.widgetStatusDot, if (isConnected) COLOR_GREEN else COLOR_RED)
            
            // Device name and color - show bound device or default title
            val device = if (deviceId != null) {
                Prefs.getDevices(context).find { it.id == deviceId }
            } else null
            
            val deviceName = device?.displayName ?: "Open RadiaCode"
            views.setTextViewText(R.id.widgetDeviceName, deviceName)
            
            // Set device color on accent bar and dot
            val deviceColorInt = try {
                Color.parseColor("#${device?.colorHex ?: "00E5FF"}")
            } catch (_: Exception) { COLOR_CYAN }
            views.setInt(R.id.widgetDeviceColorBar, "setBackgroundColor", deviceColorInt)
            views.setTextColor(R.id.widgetDeviceColorDot, deviceColorInt)
            
            // Anomaly detection badge
            if (recentReadings.size >= 10 && config?.showIntelligence != false) {
                val doseValues = recentReadings.map { it.uSvPerHour }
                val anomalyIndices = StatisticsCalculator.detectAnomaliesZScore(doseValues, 2.5f)
                if (anomalyIndices.isNotEmpty()) {
                    views.setViewVisibility(R.id.widgetAnomalyBadge, android.view.View.VISIBLE)
                    // Color by severity: recent anomaly = red, older = yellow
                    val recentAnomaly = anomalyIndices.any { it >= recentReadings.size - 3 }
                    views.setTextColor(R.id.widgetAnomalyBadge, if (recentAnomaly) COLOR_RED else 0xFFFFD600.toInt())
                } else {
                    views.setViewVisibility(R.id.widgetAnomalyBadge, android.view.View.GONE)
                }
            } else {
                views.setViewVisibility(R.id.widgetAnomalyBadge, android.view.View.GONE)
            }
            
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
                // Check if dynamic coloring is enabled
                val dynamicColorEnabled = config?.dynamicColorEnabled ?: false
                val thresholds = if (dynamicColorEnabled) Prefs.getDynamicColorThresholds(context) else null
                
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
                
                // Apply dynamic color if enabled, otherwise use config color
                val finalDoseColor = if (dynamicColorEnabled) {
                    DynamicColorCalculator.getColorForDose(last.uSvPerHour, thresholds)
                } else {
                    doseColor
                }
                views.setTextColor(R.id.widgetDoseValue, finalDoseColor)
                
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
                
                // Apply dynamic color to CPS if enabled
                val finalCpsColor = if (dynamicColorEnabled) {
                    DynamicColorCalculator.getColorForCps(last.cps, thresholds)
                } else {
                    cpsColor
                }
                views.setTextColor(R.id.widgetCpsValue, finalCpsColor)
                
                // Calculate and display rate of change (trend indicators)
                if (recentReadings.size >= 10) {
                    val doseRateValues = when (doseUnit) {
                        Prefs.DoseUnit.USV_H -> recentReadings.map { it.uSvPerHour }
                        Prefs.DoseUnit.NSV_H -> recentReadings.map { it.uSvPerHour * 1000f }
                    }
                    val cpsRateValues = when (countUnit) {
                        Prefs.CountUnit.CPS -> recentReadings.map { it.cps }
                        Prefs.CountUnit.CPM -> recentReadings.map { it.cps * 60f }
                    }
                    
                    // Calculate rate of change (using last 10 samples)
                    val doseRoc = StatisticsCalculator.calculateRateOfChange(doseRateValues.takeLast(10))
                    val cpsRoc = StatisticsCalculator.calculateRateOfChange(cpsRateValues.takeLast(10))
                    
                    // Determine trend symbols and colors
                    val (doseTrendSymbol, doseTrendColor) = getTrendIndicator(doseRoc, doseRateValues.average().toFloat())
                    val (cpsTrendSymbol, cpsTrendColor) = getTrendIndicator(cpsRoc, cpsRateValues.average().toFloat())
                    
                    // Set dose trend
                    if (doseTrendSymbol.isNotEmpty()) {
                        views.setTextViewText(R.id.widgetDoseTrend, doseTrendSymbol)
                        views.setTextColor(R.id.widgetDoseTrend, doseTrendColor)
                        views.setViewVisibility(R.id.widgetDoseTrend, android.view.View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widgetDoseTrend, android.view.View.GONE)
                    }
                    
                    // Set CPS trend
                    if (cpsTrendSymbol.isNotEmpty()) {
                        views.setTextViewText(R.id.widgetCpsTrend, cpsTrendSymbol)
                        views.setTextColor(R.id.widgetCpsTrend, cpsTrendColor)
                        views.setViewVisibility(R.id.widgetCpsTrend, android.view.View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widgetCpsTrend, android.view.View.GONE)
                    }
                } else {
                    // Not enough data for trend
                    views.setViewVisibility(R.id.widgetDoseTrend, android.view.View.GONE)
                    views.setViewVisibility(R.id.widgetCpsTrend, android.view.View.GONE)
                }
                
                // Timestamp
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                views.setTextViewText(R.id.widgetTime, fmt.format(Date(last.timestampMs)))
                views.setTextColor(R.id.widgetTime, if (isConnected) COLOR_MUTED else COLOR_RED)
                
                // Generate charts based on chart type from config
                if (recentReadings.size >= 5) {
                    val chartType = config?.chartType ?: ChartType.SPARKLINE
                    val showBands = config?.showBollingerBands ?: false
                    
                    // Dose chart (convert to user's unit)
                    val doseValues = when (doseUnit) {
                        Prefs.DoseUnit.USV_H -> recentReadings.map { it.uSvPerHour }
                        Prefs.DoseUnit.NSV_H -> recentReadings.map { it.uSvPerHour * 1000f }
                    }
                    val doseBitmap = createChart(doseValues, recentReadings, sparkWidth, sparkHeight, doseColor, chartType, true, showBands)
                    views.setImageViewBitmap(R.id.widgetDoseSparkline, doseBitmap)
                    
                    // CPS chart (convert to user's unit)
                    val cpsValues = when (countUnit) {
                        Prefs.CountUnit.CPS -> recentReadings.map { it.cps }
                        Prefs.CountUnit.CPM -> recentReadings.map { it.cps * 60f }
                    }
                    val cpsBitmap = createChart(cpsValues, recentReadings, sparkWidth, sparkHeight, cpsColor, chartType, false, showBands)
                    views.setImageViewBitmap(R.id.widgetCpsSparkline, cpsBitmap)
                }
            }
            
            // Apply visibility based on config settings
            val showDose = config?.showDose ?: true
            val showCps = config?.showCps ?: true
            val showTime = config?.showTime ?: true
            val showSparkline = config?.showSparkline ?: true
            
            // Hide/show dose row
            views.setViewVisibility(R.id.widgetDoseRow, 
                if (showDose) android.view.View.VISIBLE else android.view.View.GONE)
            
            // Hide/show CPS row
            views.setViewVisibility(R.id.widgetCpsRow,
                if (showCps) android.view.View.VISIBLE else android.view.View.GONE)
            
            // Hide/show timestamp
            views.setViewVisibility(R.id.widgetTime,
                if (showTime) android.view.View.VISIBLE else android.view.View.GONE)
            
            // Hide/show sparklines (within their rows)
            views.setViewVisibility(R.id.widgetDoseSparkline,
                if (showDose && showSparkline) android.view.View.VISIBLE else android.view.View.GONE)
            views.setViewVisibility(R.id.widgetCpsSparkline,
                if (showCps && showSparkline) android.view.View.VISIBLE else android.view.View.GONE)
            
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
        
        /**
         * Create chart bitmap based on chart type.
         */
        private fun createChart(
            values: List<Float>,
            readings: List<Prefs.LastReading>,
            width: Int,
            height: Int,
            color: Int,
            chartType: ChartType,
            isDose: Boolean,
            showBollingerBands: Boolean = false
        ): Bitmap {
            return when (chartType) {
                ChartType.SPARKLINE, ChartType.LINE -> createSparkline(values, width, height, color, showBollingerBands)
                ChartType.BAR -> createBarChart(values, width, height, color)
                ChartType.CANDLE -> createCandlestickChart(readings, width, height, isDose)
                ChartType.AREA -> createAreaChart(values, width, height, color)
                ChartType.NONE -> Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
        }
        
        private fun createSparkline(values: List<Float>, width: Int, height: Int, color: Int, showBollingerBands: Boolean = false): Bitmap {
            if (width <= 0 || height <= 0) {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Transparent background - let the card background show through
            
            // If no data, show "No data" indicator
            if (values.isEmpty() || values.size < 2) {
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.argb(80, 255, 255, 255)
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
            
            // Draw Bollinger Bands if enabled
            if (showBollingerBands && values.size >= 10) {
                val windowSize = min(20, values.size / 2).coerceAtLeast(5)
                val bands = StatisticsCalculator.calculateBollingerBands(values, windowSize, 2.0f)
                
                if (!bands.isEmpty()) {
                    // Calculate expanded range to include bands
                    val bandMin = bands.lower.minOrNull() ?: minV
                    val bandMax = bands.upper.maxOrNull() ?: maxV
                    val expandedMin = min(minV, bandMin)
                    val expandedMax = max(maxV, bandMax)
                    val expandedRange = max(0.0001f, expandedMax - expandedMin)
                    
                    // Draw band fill (area between upper and lower)
                    val bandFillPath = Path()
                    val startIndex = values.size - bands.middle.size
                    
                    // Upper band line (forward)
                    for (i in bands.upper.indices) {
                        val dataIndex = startIndex + i
                        val x = padLeft + (dataIndex.toFloat() / max(1, n - 1)) * chartWidth
                        val yNorm = (bands.upper[i] - expandedMin) / expandedRange
                        val y = padTop + chartHeight * (1f - yNorm)
                        if (i == 0) bandFillPath.moveTo(x, y) else bandFillPath.lineTo(x, y)
                    }
                    // Lower band line (backward)
                    for (i in bands.lower.indices.reversed()) {
                        val dataIndex = startIndex + i
                        val x = padLeft + (dataIndex.toFloat() / max(1, n - 1)) * chartWidth
                        val yNorm = (bands.lower[i] - expandedMin) / expandedRange
                        val y = padTop + chartHeight * (1f - yNorm)
                        bandFillPath.lineTo(x, y)
                    }
                    bandFillPath.close()
                    
                    // Draw band fill with semi-transparent color
                    val bandFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        this.color = Color.argb(30, 255, 255, 255)
                    }
                    canvas.drawPath(bandFillPath, bandFillPaint)
                    
                    // Draw upper and lower band lines
                    val bandLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 1f
                        this.color = Color.argb(100, 255, 255, 255)
                    }
                    
                    // Upper band
                    val upperPath = Path()
                    for (i in bands.upper.indices) {
                        val dataIndex = startIndex + i
                        val x = padLeft + (dataIndex.toFloat() / max(1, n - 1)) * chartWidth
                        val yNorm = (bands.upper[i] - expandedMin) / expandedRange
                        val y = padTop + chartHeight * (1f - yNorm)
                        if (i == 0) upperPath.moveTo(x, y) else upperPath.lineTo(x, y)
                    }
                    canvas.drawPath(upperPath, bandLinePaint)
                    
                    // Lower band
                    val lowerPath = Path()
                    for (i in bands.lower.indices) {
                        val dataIndex = startIndex + i
                        val x = padLeft + (dataIndex.toFloat() / max(1, n - 1)) * chartWidth
                        val yNorm = (bands.lower[i] - expandedMin) / expandedRange
                        val y = padTop + chartHeight * (1f - yNorm)
                        if (i == 0) lowerPath.moveTo(x, y) else lowerPath.lineTo(x, y)
                    }
                    canvas.drawPath(lowerPath, bandLinePaint)
                }
            }
            
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
        
        /**
         * Create bar chart for widget display.
         */
        private fun createBarChart(values: List<Float>, width: Int, height: Int, color: Int): Bitmap {
            if (width <= 0 || height <= 0) {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            if (values.isEmpty()) {
                return bitmap
            }
            
            val padding = 8f
            val chartWidth = width - 2 * padding
            val chartHeight = height - 2 * padding
            val barGap = 2f
            val barWidth = (chartWidth - barGap * (values.size - 1)) / values.size
            
            val minVal = 0f
            val maxVal = values.maxOrNull() ?: 1f
            val range = if (maxVal > 0.001f) maxVal else 1f
            
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
            }
            
            values.forEachIndexed { index, value ->
                val x = padding + index * (barWidth + barGap)
                val barHeight = ((value - minVal) / range) * chartHeight
                val y = padding + chartHeight - barHeight
                
                canvas.drawRoundRect(x, y, x + barWidth, padding + chartHeight, 2f, 2f, barPaint)
            }
            
            return bitmap
        }
        
        /**
         * Create candlestick chart for widget display.
         */
        private fun createCandlestickChart(
            readings: List<Prefs.LastReading>,
            width: Int,
            height: Int,
            isDose: Boolean
        ): Bitmap {
            if (width <= 0 || height <= 0) {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Draw subtle background for visibility
            val bgPaint = Paint().apply {
                color = Color.argb(30, 255, 255, 255)
            }
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
            
            if (readings.size < 4) {
                // Show placeholder text for insufficient data
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(80, 255, 255, 255)
                    textSize = height / 3f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("—", width / 2f, height / 2f + textPaint.textSize / 3f, textPaint)
                return bitmap
            }
            
            // Group readings into buckets for candlestick representation
            val bucketCount = min(readings.size / 2, 15)
            val bucketSize = (readings.size + bucketCount - 1) / bucketCount
            
            val candles = readings.chunked(bucketSize).map { bucket ->
                val values = if (isDose) bucket.map { it.uSvPerHour } else bucket.map { it.cps }
                if (values.isEmpty()) {
                    CandleData(0f, 0f, 0f, 0f)
                } else {
                    CandleData(
                        open = values.first(),
                        close = values.last(),
                        high = values.maxOrNull() ?: 0f,
                        low = values.minOrNull() ?: 0f
                    )
                }
            }
            
            if (candles.isEmpty()) return bitmap
            
            val padding = 8f
            val chartWidth = width - 2 * padding
            val chartHeight = height - 2 * padding
            val candleGap = 2f
            val candleWidth = (chartWidth - candleGap * (candles.size - 1)) / candles.size
            
            val minVal = candles.minOfOrNull { it.low } ?: 0f
            val maxVal = candles.maxOfOrNull { it.high } ?: 1f
            val range = if (maxVal - minVal > 0.001f) maxVal - minVal else 1f
            
            val upColor = 0xFF69F0AE.toInt()
            val downColor = 0xFFFF5252.toInt()
            val wickColor = 0xFF9E9E9E.toInt()
            
            val wickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = wickColor
                strokeWidth = 1f
            }
            
            candles.forEachIndexed { index, candle ->
                val centerX = padding + index * (candleWidth + candleGap) + candleWidth / 2
                
                fun toY(value: Float) = padding + chartHeight - ((value - minVal) / range) * chartHeight
                
                val highY = toY(candle.high)
                val lowY = toY(candle.low)
                val openY = toY(candle.open)
                val closeY = toY(candle.close)
                
                canvas.drawLine(centerX, highY, centerX, lowY, wickPaint)
                
                val isUp = candle.close >= candle.open
                val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isUp) upColor else downColor
                }
                
                val bodyTop = min(openY, closeY)
                val bodyBottom = max(openY, closeY)
                val bodyLeft = padding + index * (candleWidth + candleGap)
                val bodyRight = bodyLeft + candleWidth
                val actualBottom = if (bodyBottom - bodyTop < 2f) bodyTop + 2f else bodyBottom
                
                canvas.drawRect(bodyLeft, bodyTop, bodyRight, actualBottom, bodyPaint)
            }
            
            return bitmap
        }
        
        /**
         * Create area chart for widget display.
         */
        private fun createAreaChart(values: List<Float>, width: Int, height: Int, color: Int): Bitmap {
            if (width <= 0 || height <= 0) {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            if (values.isEmpty() || values.size < 2) {
                return bitmap
            }
            
            val minV = values.minOrNull() ?: 0f
            val maxV = values.maxOrNull() ?: 1f
            val range = max(0.0001f, maxV - minV)
            
            val padLeft = 8f
            val padRight = 8f
            val padTop = 8f
            val padBottom = 8f
            val chartWidth = width - padLeft - padRight
            val chartHeight = height - padTop - padBottom
            
            val fillPath = Path()
            val n = values.size
            
            for (i in 0 until n) {
                val x = padLeft + (i.toFloat() / max(1, n - 1)) * chartWidth
                val yNorm = (values[i] - minV) / range
                val y = padTop + chartHeight * (1f - yNorm)
                
                if (i == 0) {
                    fillPath.moveTo(x, height.toFloat() - padBottom)
                    fillPath.lineTo(x, y)
                } else {
                    fillPath.lineTo(x, y)
                }
            }
            
            fillPath.lineTo(padLeft + chartWidth, height.toFloat() - padBottom)
            fillPath.close()
            
            // Draw solid fill with high opacity
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, padTop, 0f, height.toFloat() - padBottom,
                    Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(fillPath, fillPaint)
            
            return bitmap
        }
        
        /**
         * Get reading data for a specific device or aggregate all devices.
         */
        private fun getDataForDevice(context: Context, deviceId: String?): Pair<Prefs.LastReading?, List<Prefs.LastReading>> {
            return if (deviceId == null) {
                // No device bound - use global readings (legacy behavior or aggregate)
                val last = Prefs.getLastReading(context)
                val recent = Prefs.getRecentReadings(context, 60)
                last to recent
            } else {
                // Device-specific data
                val last = Prefs.getDeviceLastReading(context, deviceId)
                val recent = Prefs.getDeviceRecentReadings(context, deviceId, 60)
                last to recent
            }
        }
        
        /**
         * Get colors from widget config, device config, or use defaults.
         * Returns (doseColor, cpsColor) pair.
         */
        private fun getColors(context: Context, config: WidgetConfig?): Pair<Int, Int> {
            if (config == null) {
                return COLOR_CYAN to COLOR_MAGENTA
            }
            
            val scheme = config.colorScheme
            val customColors = config.customColors
            
            // If config has custom colors, use those
            if (scheme == ColorScheme.CUSTOM && customColors != null) {
                return parseColor(customColors.doseColor, COLOR_CYAN) to parseColor(customColors.cpsColor, COLOR_MAGENTA)
            }
            
            // Apply color scheme - line color for dose, derive CPS color
            val schemeColor = parseColor(scheme.lineColor, COLOR_CYAN)
            
            // For non-custom schemes, use the scheme's line color for dose
            // and derive a complementary CPS color based on the scheme
            val cpsColor = when (scheme) {
                ColorScheme.DEFAULT -> COLOR_MAGENTA
                ColorScheme.CYBERPUNK -> parseColor("00FFFF", COLOR_MAGENTA)
                ColorScheme.FOREST -> parseColor("81C784", COLOR_MAGENTA)
                ColorScheme.OCEAN -> parseColor("29B6F6", COLOR_MAGENTA)
                ColorScheme.FIRE -> parseColor("FFAB91", COLOR_MAGENTA)
                ColorScheme.GRAYSCALE -> parseColor("757575", COLOR_MAGENTA)
                ColorScheme.AMBER -> parseColor("FFE082", COLOR_MAGENTA)
                ColorScheme.PURPLE -> parseColor("CE93D8", COLOR_MAGENTA)
                else -> COLOR_MAGENTA
            }
            
            // If device is specified, optionally use device color as override
            if (config.deviceId != null) {
                val devices = Prefs.getDevices(context)
                val device = devices.find { it.id == config.deviceId }
                if (device != null) {
                    val deviceColor = parseColor(device.colorHex, schemeColor)
                    return deviceColor to cpsColor
                }
            }
            
            return schemeColor to cpsColor
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
