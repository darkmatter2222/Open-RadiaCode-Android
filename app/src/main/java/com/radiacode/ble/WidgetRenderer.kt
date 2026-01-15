package com.radiacode.ble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Unified Widget Renderer - V2
 * 
 * CRITICAL: This class is the SINGLE SOURCE OF TRUTH for widget rendering.
 * Both the preview in WidgetConfigActivity AND the actual home screen widget
 * use this class to render. This guarantees pixel-perfect preview matching.
 * 
 * Design principle: Same data + same config = identical visual output.
 */
object WidgetRenderer {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Data container for widget rendering.
     * Populated from device readings or sample data for preview.
     */
    data class WidgetData(
        val doseValue: Float?,
        val countValue: Float?,
        val timestamp: Long?,
        val isConnected: Boolean,
        val doseHistory: List<Float> = emptyList(),
        val countHistory: List<Float> = emptyList(),
        val hasAnomaly: Boolean = false,
        val doseTrend: TrendDirection = TrendDirection.STABLE,
        val countTrend: TrendDirection = TrendDirection.STABLE
    )

    enum class TrendDirection(val symbol: String, val color: Int) {
        RISING_FAST("↑↑", 0xFFFF5252.toInt()),
        RISING("↑", 0xFFFFD600.toInt()),
        STABLE("", 0),
        FALLING("↓", 0xFF69F0AE.toInt()),
        FALLING_FAST("↓↓", 0xFF4DD0E1.toInt())
    }

    /**
     * Render to RemoteViews for actual home screen widget.
     * Uses the EXACT same logic as renderToPreview.
     */
    fun renderToRemoteViews(
        context: Context,
        config: WidgetConfig,
        data: WidgetData,
        widgetWidth: Int,
        widgetHeight: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_unified)

        // Get theme colors
        val theme = getThemeColors(config.colorScheme)
        val doseUnit = Prefs.getDoseUnit(context)
        val countUnit = Prefs.getCountUnit(context)

        // === HEADER ===
        // Status indicator (using ImageView + setImageViewResource for RemoteViews compatibility)
        val statusDrawable = if (data.isConnected) {
            R.drawable.status_dot_connected
        } else {
            R.drawable.status_dot_disconnected
        }
        views.setImageViewResource(R.id.statusIndicator, statusDrawable)

        // Timestamp
        if (config.showTime && data.timestamp != null) {
            views.setTextViewText(R.id.timestamp, timeFormat.format(Date(data.timestamp)))
            views.setViewVisibility(R.id.timestamp, View.VISIBLE)
        } else if (!config.showTime) {
            views.setViewVisibility(R.id.timestamp, View.GONE)
        } else {
            views.setTextViewText(R.id.timestamp, "--:--:--")
        }

        // Anomaly badge
        if (config.showIntelligence && data.hasAnomaly) {
            views.setViewVisibility(R.id.anomalyBadge, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.anomalyBadge, View.GONE)
        }

        // === DOSE SECTION ===
        if (config.showDose) {
            views.setViewVisibility(R.id.doseSection, View.VISIBLE)

            // Format dose value
            val (doseStr, doseUnitStr) = formatDoseValue(data.doseValue, doseUnit)
            views.setTextViewText(R.id.doseValue, doseStr)
            views.setTextViewText(R.id.doseUnit, doseUnitStr)

            // Apply color (dynamic or theme)
            val doseColor = if (config.dynamicColorEnabled && data.doseValue != null) {
                DynamicColorCalculator.getColorForDose(data.doseValue, Prefs.getDynamicColorThresholds(context))
            } else {
                theme.doseColor
            }
            views.setTextColor(R.id.doseValue, doseColor)
            views.setTextColor(R.id.doseLabel, adjustColorBrightness(doseColor, 0.7f))

            // Trend indicator
            if (config.showIntelligence && data.doseTrend != TrendDirection.STABLE) {
                views.setTextViewText(R.id.doseTrend, data.doseTrend.symbol)
                views.setTextColor(R.id.doseTrend, data.doseTrend.color)
                views.setViewVisibility(R.id.doseTrend, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.doseTrend, View.GONE)
            }

            // Chart - use independent doseChartType
            if (config.doseChartType != ChartType.NONE && data.doseHistory.size >= 3) {
                val chartWidth = (widgetWidth * 0.4f).toInt().coerceIn(80, 400)
                val chartHeight = (widgetHeight * 0.25f).toInt().coerceIn(30, 100)
                val chartBitmap = generateChartBitmap(
                    config.doseChartType,
                    data.doseHistory,
                    doseColor,
                    theme.backgroundColor,
                    chartWidth,
                    chartHeight,
                    config.showBollingerBands
                )
                views.setImageViewBitmap(R.id.doseChart, chartBitmap)
                views.setViewVisibility(R.id.doseChart, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.doseChart, View.GONE)
            }
        } else {
            views.setViewVisibility(R.id.doseSection, View.GONE)
        }

        // === COUNT SECTION ===
        if (config.showCps) {
            views.setViewVisibility(R.id.countSection, View.VISIBLE)
            views.setViewVisibility(R.id.sectionSpacer, if (config.showDose) View.VISIBLE else View.GONE)

            // Format count value
            val (countStr, countUnitStr) = formatCountValue(data.countValue, countUnit)
            views.setTextViewText(R.id.countValue, countStr)
            views.setTextViewText(R.id.countUnit, countUnitStr)

            // Apply color
            val countColor = if (config.dynamicColorEnabled && data.countValue != null) {
                DynamicColorCalculator.getColorForCps(data.countValue, Prefs.getDynamicColorThresholds(context))
            } else {
                theme.countColor
            }
            views.setTextColor(R.id.countValue, countColor)
            views.setTextColor(R.id.countLabel, adjustColorBrightness(countColor, 0.7f))

            // Trend indicator
            if (config.showIntelligence && data.countTrend != TrendDirection.STABLE) {
                views.setTextViewText(R.id.countTrend, data.countTrend.symbol)
                views.setTextColor(R.id.countTrend, data.countTrend.color)
                views.setViewVisibility(R.id.countTrend, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.countTrend, View.GONE)
            }

            // Chart - use independent countChartType
            if (config.countChartType != ChartType.NONE && data.countHistory.size >= 3) {
                val chartWidth = (widgetWidth * 0.4f).toInt().coerceIn(80, 400)
                val chartHeight = (widgetHeight * 0.25f).toInt().coerceIn(30, 100)
                val chartBitmap = generateChartBitmap(
                    config.countChartType,
                    data.countHistory,
                    countColor,
                    theme.backgroundColor,
                    chartWidth,
                    chartHeight,
                    config.showBollingerBands
                )
                views.setImageViewBitmap(R.id.countChart, chartBitmap)
                views.setViewVisibility(R.id.countChart, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.countChart, View.GONE)
            }
        } else {
            views.setViewVisibility(R.id.countSection, View.GONE)
            views.setViewVisibility(R.id.sectionSpacer, View.GONE)
        }

        return views
    }

    /**
     * Render to preview Views in configuration activity.
     * Uses the EXACT same logic as renderToRemoteViews.
     */
    fun renderToPreview(
        context: Context,
        config: WidgetConfig,
        data: WidgetData,
        rootView: View
    ) {
        // Get theme colors
        val theme = getThemeColors(config.colorScheme)
        val doseUnit = Prefs.getDoseUnit(context)
        val countUnit = Prefs.getCountUnit(context)

        // === HEADER ===
        // Status indicator (now ImageView for consistency with widget)
        rootView.findViewById<ImageView>(R.id.statusIndicator)?.apply {
            setImageResource(
                if (data.isConnected) R.drawable.status_dot_connected
                else R.drawable.status_dot_disconnected
            )
        }

        rootView.findViewById<TextView>(R.id.timestamp)?.apply {
            if (config.showTime && data.timestamp != null) {
                text = timeFormat.format(Date(data.timestamp))
                visibility = View.VISIBLE
            } else if (!config.showTime) {
                visibility = View.GONE
            } else {
                text = "--:--:--"
                visibility = View.VISIBLE
            }
        }

        rootView.findViewById<TextView>(R.id.anomalyBadge)?.visibility =
            if (config.showIntelligence && data.hasAnomaly) View.VISIBLE else View.GONE

        // === DOSE SECTION ===
        val doseSection = rootView.findViewById<LinearLayout>(R.id.doseSection)
        if (config.showDose) {
            doseSection?.visibility = View.VISIBLE

            val (doseStr, doseUnitStr) = formatDoseValue(data.doseValue, doseUnit)
            rootView.findViewById<TextView>(R.id.doseValue)?.text = doseStr
            rootView.findViewById<TextView>(R.id.doseUnit)?.text = doseUnitStr

            val doseColor = if (config.dynamicColorEnabled && data.doseValue != null) {
                DynamicColorCalculator.getColorForDose(data.doseValue, Prefs.getDynamicColorThresholds(context))
            } else {
                theme.doseColor
            }
            rootView.findViewById<TextView>(R.id.doseValue)?.setTextColor(doseColor)
            rootView.findViewById<TextView>(R.id.doseLabel)?.setTextColor(adjustColorBrightness(doseColor, 0.7f))

            // Trend
            rootView.findViewById<TextView>(R.id.doseTrend)?.apply {
                if (config.showIntelligence && data.doseTrend != TrendDirection.STABLE) {
                    text = data.doseTrend.symbol
                    setTextColor(data.doseTrend.color)
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }

            // Chart - use independent doseChartType
            val doseChartView = rootView.findViewById<ImageView>(R.id.doseChart)
            if (config.doseChartType != ChartType.NONE && data.doseHistory.size >= 3) {
                val chartBitmap = generateChartBitmap(
                    config.doseChartType,
                    data.doseHistory,
                    doseColor,
                    theme.backgroundColor,
                    180, 50,
                    config.showBollingerBands
                )
                doseChartView?.setImageBitmap(chartBitmap)
                doseChartView?.visibility = View.VISIBLE
            } else {
                doseChartView?.visibility = View.GONE
            }
        } else {
            doseSection?.visibility = View.GONE
        }

        // === COUNT SECTION ===
        val countSection = rootView.findViewById<LinearLayout>(R.id.countSection)
        val sectionSpacer = rootView.findViewById<View>(R.id.sectionSpacer)
        
        if (config.showCps) {
            countSection?.visibility = View.VISIBLE
            sectionSpacer?.visibility = if (config.showDose) View.VISIBLE else View.GONE

            val (countStr, countUnitStr) = formatCountValue(data.countValue, countUnit)
            rootView.findViewById<TextView>(R.id.countValue)?.text = countStr
            rootView.findViewById<TextView>(R.id.countUnit)?.text = countUnitStr

            val countColor = if (config.dynamicColorEnabled && data.countValue != null) {
                DynamicColorCalculator.getColorForCps(data.countValue, Prefs.getDynamicColorThresholds(context))
            } else {
                theme.countColor
            }
            rootView.findViewById<TextView>(R.id.countValue)?.setTextColor(countColor)
            rootView.findViewById<TextView>(R.id.countLabel)?.setTextColor(adjustColorBrightness(countColor, 0.7f))

            // Trend
            rootView.findViewById<TextView>(R.id.countTrend)?.apply {
                if (config.showIntelligence && data.countTrend != TrendDirection.STABLE) {
                    text = data.countTrend.symbol
                    setTextColor(data.countTrend.color)
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }

            // Chart - use independent countChartType
            val countChartView = rootView.findViewById<ImageView>(R.id.countChart)
            if (config.countChartType != ChartType.NONE && data.countHistory.size >= 3) {
                val chartBitmap = generateChartBitmap(
                    config.countChartType,
                    data.countHistory,
                    countColor,
                    theme.backgroundColor,
                    180, 50,
                    config.showBollingerBands
                )
                countChartView?.setImageBitmap(chartBitmap)
                countChartView?.visibility = View.VISIBLE
            } else {
                countChartView?.visibility = View.GONE
            }
        } else {
            countSection?.visibility = View.GONE
            sectionSpacer?.visibility = View.GONE
        }
    }

    /**
     * Generate chart bitmap - SINGLE implementation used by both preview and widget.
     */
    fun generateChartBitmap(
        type: ChartType,
        values: List<Float>,
        lineColor: Int,
        bgColor: Int,
        width: Int,
        height: Int,
        showBollinger: Boolean
    ): Bitmap {
        return when (type) {
            ChartType.SPARKLINE -> createSparklineChart(width, height, values, lineColor, bgColor, showBollinger)
            ChartType.LINE -> createLineChart(width, height, values, lineColor, bgColor, showBollinger)
            ChartType.BAR -> createBarChart(width, height, values, lineColor, bgColor)
            ChartType.CANDLE -> createCandlestickChart(width, height, values, bgColor)
            ChartType.AREA -> createAreaChart(width, height, values, lineColor, bgColor)
            ChartType.NONE -> Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    // ========== VALUE FORMATTING ==========

    private fun formatDoseValue(value: Float?, unit: Prefs.DoseUnit): Pair<String, String> {
        if (value == null) return "—" to "μSv/h"
        
        val (displayValue, unitStr) = when (unit) {
            Prefs.DoseUnit.USV_H -> value to "μSv/h"
            Prefs.DoseUnit.NSV_H -> (value * 1000f) to "nSv/h"
        }
        
        val formatted = when {
            displayValue >= 100 -> String.format(Locale.US, "%.0f", displayValue)
            displayValue >= 10 -> String.format(Locale.US, "%.1f", displayValue)
            displayValue >= 1 -> String.format(Locale.US, "%.2f", displayValue)
            else -> String.format(Locale.US, "%.3f", displayValue)
        }
        return formatted to unitStr
    }

    private fun formatCountValue(value: Float?, unit: Prefs.CountUnit): Pair<String, String> {
        if (value == null) return "—" to "cps"
        
        val (displayValue, unitStr) = when (unit) {
            Prefs.CountUnit.CPS -> value to "cps"
            Prefs.CountUnit.CPM -> (value * 60f) to "cpm"
        }
        
        val formatted = when {
            displayValue >= 1000 -> String.format(Locale.US, "%.0f", displayValue)
            displayValue >= 100 -> String.format(Locale.US, "%.0f", displayValue)
            displayValue >= 10 -> String.format(Locale.US, "%.1f", displayValue)
            else -> String.format(Locale.US, "%.2f", displayValue)
        }
        return formatted to unitStr
    }

    // ========== THEME COLORS ==========

    data class ThemeColors(
        val doseColor: Int,
        val countColor: Int,
        val backgroundColor: Int,
        val textColor: Int
    )

    private fun getThemeColors(scheme: ColorScheme): ThemeColors {
        return when (scheme) {
            ColorScheme.DEFAULT -> ThemeColors(0xFF00E5FF.toInt(), 0xFFE040FB.toInt(), 0xFF1A1A1E.toInt(), 0xFFFFFFFF.toInt())
            ColorScheme.CYBERPUNK -> ThemeColors(0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFF0D0D1A.toInt(), 0xFFFFFFFF.toInt())
            ColorScheme.OCEAN -> ThemeColors(0xFF40C4FF.toInt(), 0xFF4FC3F7.toInt(), 0xFF0D1B2A.toInt(), 0xFFFFFFFF.toInt())
            ColorScheme.FOREST -> ThemeColors(0xFF69F0AE.toInt(), 0xFF81C784.toInt(), 0xFF1B2E1B.toInt(), 0xFFFFFFFF.toInt())
            ColorScheme.FIRE -> ThemeColors(0xFFFF5722.toInt(), 0xFFFFAB91.toInt(), 0xFF2A1B0D.toInt(), 0xFFFFFFFF.toInt())
            ColorScheme.AMBER -> ThemeColors(0xFFFFD740.toInt(), 0xFFFFE082.toInt(), 0xFF2A2100.toInt(), 0xFFFFFFFF.toInt())
            ColorScheme.PURPLE -> ThemeColors(0xFFB388FF.toInt(), 0xFFCE93D8.toInt(), 0xFF1A0D2A.toInt(), 0xFFFFFFFF.toInt())
            ColorScheme.GRAYSCALE -> ThemeColors(0xFFBDBDBD.toInt(), 0xFF9E9E9E.toInt(), 0xFF1A1A1A.toInt(), 0xFFFFFFFF.toInt())
            ColorScheme.CUSTOM -> ThemeColors(0xFF00E5FF.toInt(), 0xFFE040FB.toInt(), 0xFF1A1A1E.toInt(), 0xFFFFFFFF.toInt())
        }
    }

    private fun adjustColorBrightness(color: Int, factor: Float): Int {
        val r = ((color shr 16) and 0xFF) * factor
        val g = ((color shr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
    }

    // ========== CHART GENERATION ==========

    private fun createSparklineChart(
        width: Int, height: Int, values: List<Float>,
        color: Int, bgColor: Int, showBollinger: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Rounded background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f, bgPaint)

        if (values.size < 2) return bitmap

        val minVal = values.minOrNull() ?: 0f
        val maxVal = values.maxOrNull() ?: 1f
        val range = max(maxVal - minVal, 0.0001f)

        val padding = 4f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2

        // Bollinger bands if enabled
        if (showBollinger && values.size >= 10) {
            drawBollingerBands(canvas, values, padding, chartWidth, chartHeight, minVal, range, color)
        }

        // Build line path
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range) * chartHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Glow effect
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = 60
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        canvas.drawPath(path, glowPaint)

        // Main line
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, linePaint)

        // Gradient fill under line
        val fillPath = Path(path)
        fillPath.lineTo(padding + chartWidth, padding + chartHeight)
        fillPath.lineTo(padding, padding + chartHeight)
        fillPath.close()

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, padding, 0f, padding + chartHeight,
                (color and 0x00FFFFFF) or 0x40000000,
                (color and 0x00FFFFFF) or 0x08000000,
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawPath(fillPath, fillPaint)

        return bitmap
    }

    private fun createLineChart(
        width: Int, height: Int, values: List<Float>,
        color: Int, bgColor: Int, showBollinger: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f, bgPaint)

        if (values.size < 2) return bitmap

        val minVal = values.minOrNull() ?: 0f
        val maxVal = values.maxOrNull() ?: 1f
        val range = max(maxVal - minVal, 0.0001f)

        val padding = 6f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2

        // Grid lines (subtle)
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(25, 255, 255, 255)
            strokeWidth = 1f
        }
        for (i in 0..2) {
            val y = padding + (i / 2f) * chartHeight
            canvas.drawLine(padding, y, padding + chartWidth, y, gridPaint)
        }

        // Bollinger bands
        if (showBollinger && values.size >= 10) {
            drawBollingerBands(canvas, values, padding, chartWidth, chartHeight, minVal, range, color)
        }

        // Line
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range) * chartHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = 50
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        canvas.drawPath(path, glowPaint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(path, linePaint)

        // Data points
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val lastX = padding + chartWidth
        val lastY = padding + chartHeight - ((values.last() - minVal) / range) * chartHeight
        canvas.drawCircle(lastX, lastY, 3f, dotPaint)

        return bitmap
    }

    private fun createBarChart(
        width: Int, height: Int, values: List<Float>,
        color: Int, bgColor: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f, bgPaint)

        if (values.isEmpty()) return bitmap

        // Aggregate into bars
        val barCount = min(10, values.size)
        val groupSize = max(1, values.size / barCount)
        val barValues = values.chunked(groupSize).map { it.average().toFloat() }

        val minVal = barValues.minOrNull() ?: 0f
        val maxVal = barValues.maxOrNull() ?: 1f
        val range = max(maxVal - minVal, 0.0001f)

        val padding = 6f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        val barGap = 2f
        val barWidth = (chartWidth - barGap * (barValues.size - 1)) / barValues.size

        barValues.forEachIndexed { index, value ->
            val barHeight = ((value - minVal) / range) * chartHeight
            val x = padding + index * (barWidth + barGap)
            val y = padding + chartHeight - barHeight

            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    x, y, x, padding + chartHeight,
                    color,
                    (color and 0x00FFFFFF) or 0x60000000,
                    Shader.TileMode.CLAMP
                )
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(x, y, x + barWidth, padding + chartHeight, 2f, 2f, barPaint)
        }

        return bitmap
    }

    private fun createCandlestickChart(
        width: Int, height: Int, values: List<Float>, bgColor: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f, bgPaint)

        if (values.size < 4) return bitmap

        // Create candles
        val candleCount = min(8, values.size / 3)
        val groupSize = max(3, values.size / candleCount)

        data class Candle(val open: Float, val high: Float, val low: Float, val close: Float)

        val candles = values.chunked(groupSize).map { group ->
            if (group.isEmpty()) Candle(0f, 0f, 0f, 0f)
            else Candle(
                open = group.first(),
                close = group.last(),
                high = group.maxOrNull() ?: 0f,
                low = group.minOrNull() ?: 0f
            )
        }

        if (candles.isEmpty()) return bitmap

        val minVal = candles.minOfOrNull { it.low } ?: 0f
        val maxVal = candles.maxOfOrNull { it.high } ?: 1f
        val range = max(maxVal - minVal, 0.0001f)

        val padding = 6f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        val candleGap = 2f
        val candleWidth = (chartWidth - candleGap * (candles.size - 1)) / candles.size

        val upColor = 0xFF69F0AE.toInt()
        val downColor = 0xFFFF5252.toInt()
        val wickColor = 0xFF9E9E9E.toInt()

        candles.forEachIndexed { index, candle ->
            val centerX = padding + index * (candleWidth + candleGap) + candleWidth / 2

            fun toY(v: Float) = padding + chartHeight - ((v - minVal) / range) * chartHeight

            // Wick
            val wickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = wickColor
                strokeWidth = 1f
            }
            canvas.drawLine(centerX, toY(candle.high), centerX, toY(candle.low), wickPaint)

            // Body
            val isUp = candle.close >= candle.open
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isUp) upColor else downColor
                style = Paint.Style.FILL
            }

            val bodyTop = min(toY(candle.open), toY(candle.close))
            val bodyBottom = max(toY(candle.open), toY(candle.close))
            val bodyHeight = max(bodyBottom - bodyTop, 2f)
            val bodyLeft = padding + index * (candleWidth + candleGap)

            canvas.drawRoundRect(bodyLeft, bodyTop, bodyLeft + candleWidth, bodyTop + bodyHeight, 1f, 1f, bodyPaint)
        }

        return bitmap
    }

    private fun createAreaChart(
        width: Int, height: Int, values: List<Float>,
        color: Int, bgColor: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f, bgPaint)

        if (values.size < 2) return bitmap

        val minVal = values.minOrNull() ?: 0f
        val maxVal = values.maxOrNull() ?: 1f
        val range = max(maxVal - minVal, 0.0001f)

        val padding = 4f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2

        // Build area path
        val fillPath = Path()
        fillPath.moveTo(padding, padding + chartHeight)

        values.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range) * chartHeight
            fillPath.lineTo(x, y)
        }

        fillPath.lineTo(padding + chartWidth, padding + chartHeight)
        fillPath.close()

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, padding, 0f, padding + chartHeight,
                (color and 0x00FFFFFF) or 0xB0000000.toInt(),
                (color and 0x00FFFFFF) or 0x20000000,
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawPath(fillPath, fillPaint)

        // Top edge line
        val linePath = Path()
        values.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range) * chartHeight
            if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        canvas.drawPath(linePath, linePaint)

        return bitmap
    }

    private fun drawBollingerBands(
        canvas: Canvas, values: List<Float>,
        padding: Float, chartWidth: Float, chartHeight: Float,
        minVal: Float, range: Float, color: Int
    ) {
        val period = min(20, values.size)
        if (values.size < period) return

        val upperBand = mutableListOf<Float>()
        val lowerBand = mutableListOf<Float>()

        for (i in values.indices) {
            val start = max(0, i - period + 1)
            val window = values.subList(start, i + 1)
            val mean = window.average().toFloat()
            val variance = window.map { (it - mean) * (it - mean) }.average().toFloat()
            val stdDev = kotlin.math.sqrt(variance)
            upperBand.add(mean + 2 * stdDev)
            lowerBand.add(mean - 2 * stdDev)
        }

        val bandPath = Path()
        upperBand.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range).coerceIn(0f, 1f) * chartHeight
            if (index == 0) bandPath.moveTo(x, y) else bandPath.lineTo(x, y)
        }

        for (i in lowerBand.indices.reversed()) {
            val x = padding + (i.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((lowerBand[i] - minVal) / range).coerceIn(0f, 1f) * chartHeight
            bandPath.lineTo(x, y)
        }
        bandPath.close()

        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = (color and 0x00FFFFFF) or 0x18000000
            style = Paint.Style.FILL
        }
        canvas.drawPath(bandPath, bandPaint)
    }

    // ========== DATA HELPERS ==========

    /**
     * Load widget data from device readings.
     */
    fun loadWidgetData(context: Context, deviceId: String?): WidgetData {
        val reading = if (deviceId != null) {
            Prefs.getDeviceLastReading(context, deviceId)
        } else {
            Prefs.getLastReading(context)
        }

        val readings = if (deviceId != null) {
            Prefs.getDeviceRecentReadings(context, deviceId)
        } else {
            Prefs.getRecentReadings(context)
        }

        val isConnected = reading != null && 
            (System.currentTimeMillis() - reading.timestampMs) < 30000

        // Calculate trends if enough data
        var doseTrend = TrendDirection.STABLE
        var countTrend = TrendDirection.STABLE
        var hasAnomaly = false

        if (readings.size >= 10) {
            val doseValues = readings.takeLast(10).map { it.uSvPerHour }
            val countValues = readings.takeLast(10).map { it.cps }

            doseTrend = calculateTrend(doseValues)
            countTrend = calculateTrend(countValues)

            // Check for anomalies
            val anomalyIndices = StatisticsCalculator.detectAnomaliesZScore(
                readings.map { it.uSvPerHour }, 2.5f
            )
            hasAnomaly = anomalyIndices.any { it >= readings.size - 5 }
        }

        return WidgetData(
            doseValue = reading?.uSvPerHour,
            countValue = reading?.cps,
            timestamp = reading?.timestampMs,
            isConnected = isConnected,
            doseHistory = readings.map { it.uSvPerHour },
            countHistory = readings.map { it.cps },
            hasAnomaly = hasAnomaly,
            doseTrend = doseTrend,
            countTrend = countTrend
        )
    }

    /**
     * Generate sample data for preview when no real data available.
     */
    fun generateSampleData(): WidgetData {
        val sampleDose = (0 until 30).map { i ->
            0.057f + 0.02f * kotlin.math.sin(i * 0.35f).toFloat()
        }
        val sampleCount = (0 until 30).map { i ->
            8.2f + 3f * kotlin.math.sin(i * 0.35f + 1f).toFloat()
        }

        return WidgetData(
            doseValue = 0.057f,
            countValue = 8.2f,
            timestamp = System.currentTimeMillis(),
            isConnected = true,
            doseHistory = sampleDose,
            countHistory = sampleCount,
            hasAnomaly = false,
            doseTrend = TrendDirection.STABLE,
            countTrend = TrendDirection.STABLE
        )
    }

    private fun calculateTrend(values: List<Float>): TrendDirection {
        if (values.size < 5) return TrendDirection.STABLE

        val roc = StatisticsCalculator.calculateRateOfChange(values)
        val avg = values.average().toFloat()
        val threshold = avg * 0.05f

        return when {
            roc > threshold * 2 -> TrendDirection.RISING_FAST
            roc > threshold -> TrendDirection.RISING
            roc < -threshold * 2 -> TrendDirection.FALLING_FAST
            roc < -threshold -> TrendDirection.FALLING
            else -> TrendDirection.STABLE
        }
    }
}
