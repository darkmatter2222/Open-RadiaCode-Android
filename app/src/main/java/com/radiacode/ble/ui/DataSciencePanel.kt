package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import com.radiacode.ble.StatisticsCalculator
import kotlin.math.*

/**
 * Data Science Analytics Panel
 * Advanced statistical visualizations: histogram, PDF, autocorrelation
 */
class DataSciencePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    enum class ChartType { HISTOGRAM, PDF, AUTOCORRELATION, TIME_TO_THRESHOLD }
    
    private var chartType: ChartType = ChartType.HISTOGRAM
    private var data: List<Float> = emptyList()
    private var stats: StatisticsCalculator.Statistics? = null

    // Histogram data
    private var histogramBins: IntArray = IntArray(0)
    private var histogramMin: Float = 0f
    private var histogramMax: Float = 0f
    private var binWidth: Float = 0f

    // PDF data
    private var pdfPoints: List<PointF> = emptyList()

    // Autocorrelation data
    private var autocorrValues: List<Float> = emptyList()

    // Time to threshold
    private var thresholdUSvH: Float = 0.5f
    private var currentRate: Float = 0f
    private var predictedTimeMinutes: Float? = null

    // Colors
    private val bgColor = ContextCompat.getColor(context, R.color.pro_surface)
    private val borderColor = ContextCompat.getColor(context, R.color.pro_border)
    private val cyanColor = ContextCompat.getColor(context, R.color.pro_cyan)
    private val magentaColor = ContextCompat.getColor(context, R.color.pro_magenta)
    private val greenColor = ContextCompat.getColor(context, R.color.pro_green)
    private val amberColor = ContextCompat.getColor(context, R.color.pro_yellow)
    private val textPrimary = ContextCompat.getColor(context, R.color.pro_text_primary)
    private val textMuted = ContextCompat.getColor(context, R.color.pro_text_muted)
    private val gridColor = ContextCompat.getColor(context, R.color.pro_border)

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = borderColor
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 11f
        color = textMuted
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.1f
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 9f
        color = textMuted
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 0.5f
        color = gridColor
        alpha = 50
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val meanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = amberColor
        pathEffect = DashPathEffect(floatArrayOf(density * 6f, density * 4f), 0f)
    }

    private val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 12f
        color = textPrimary
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 24f
        color = cyanColor
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun setChartType(type: ChartType) {
        chartType = type
        recalculate()
        invalidate()
    }

    fun setData(readings: List<Float>) {
        data = readings
        if (readings.size >= 5) {
            stats = StatisticsCalculator.calculateStatistics(readings)
        }
        recalculate()
        invalidate()
    }

    fun setThreshold(thresholdUSvH: Float) {
        this.thresholdUSvH = thresholdUSvH
        if (chartType == ChartType.TIME_TO_THRESHOLD) {
            recalculate()
            invalidate()
        }
    }

    fun setCurrentRate(rate: Float) {
        currentRate = rate
        if (chartType == ChartType.TIME_TO_THRESHOLD) {
            recalculate()
            invalidate()
        }
    }

    private fun recalculate() {
        when (chartType) {
            ChartType.HISTOGRAM -> calculateHistogram()
            ChartType.PDF -> calculatePDF()
            ChartType.AUTOCORRELATION -> calculateAutocorrelation()
            ChartType.TIME_TO_THRESHOLD -> calculateTimeToThreshold()
        }
    }

    private fun calculateHistogram() {
        if (data.isEmpty()) {
            histogramBins = IntArray(0)
            return
        }

        val numBins = min(20, max(5, sqrt(data.size.toDouble()).toInt()))
        histogramMin = data.minOrNull() ?: 0f
        histogramMax = data.maxOrNull() ?: 1f
        
        // Ensure some range
        if (histogramMax - histogramMin < 0.001f) {
            histogramMin -= 0.01f
            histogramMax += 0.01f
        }

        binWidth = (histogramMax - histogramMin) / numBins
        histogramBins = IntArray(numBins)

        data.forEach { value ->
            val binIndex = ((value - histogramMin) / binWidth).toInt().coerceIn(0, numBins - 1)
            histogramBins[binIndex]++
        }
    }

    private fun calculatePDF() {
        if (data.size < 5 || stats == null) {
            pdfPoints = emptyList()
            return
        }

        val s = stats!!
        val points = mutableListOf<PointF>()
        
        // Generate Gaussian PDF points
        val xMin = s.mean - 4 * s.stdDev
        val xMax = s.mean + 4 * s.stdDev
        val numPoints = 100

        for (i in 0 until numPoints) {
            val x = xMin + (xMax - xMin) * i / (numPoints - 1)
            val y = gaussianPDF(x, s.mean, s.stdDev)
            points.add(PointF(x, y))
        }

        pdfPoints = points
    }

    private fun gaussianPDF(x: Float, mean: Float, stdDev: Float): Float {
        if (stdDev <= 0) return 0f
        val exponent = -0.5f * ((x - mean) / stdDev).pow(2)
        return (1f / (stdDev * sqrt(2 * PI.toFloat()))) * exp(exponent)
    }

    private fun calculateAutocorrelation() {
        if (data.size < 10) {
            autocorrValues = emptyList()
            return
        }

        val maxLag = min(50, data.size / 4)
        val mean = data.average().toFloat()
        val variance = data.map { (it - mean).pow(2) }.average().toFloat()

        if (variance < 0.0001f) {
            autocorrValues = List(maxLag) { 0f }
            return
        }

        autocorrValues = (0 until maxLag).map { lag ->
            var sum = 0f
            for (i in 0 until data.size - lag) {
                sum += (data[i] - mean) * (data[i + lag] - mean)
            }
            sum / ((data.size - lag) * variance)
        }
    }

    private fun calculateTimeToThreshold() {
        stats?.let { s ->
            // Calculate trend (slope) from recent data
            if (data.size >= 10) {
                val trend = StatisticsCalculator.calculateTrend(data)
                if (trend.slope > 0.0001f) {
                    // Time to reach threshold at current rate
                    val remaining = thresholdUSvH - currentRate
                    if (remaining > 0) {
                        // slope is per sample, assuming 1 sample/second
                        predictedTimeMinutes = remaining / (trend.slope * 60f)
                    } else {
                        predictedTimeMinutes = 0f  // Already exceeded
                    }
                } else {
                    predictedTimeMinutes = null  // Not trending up
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (density * 200f).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = density * 12f
        val padding = density * 12f

        // Background
        val rect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // Title
        val title = when (chartType) {
            ChartType.HISTOGRAM -> "DISTRIBUTION HISTOGRAM"
            ChartType.PDF -> "PROBABILITY DENSITY"
            ChartType.AUTOCORRELATION -> "AUTOCORRELATION"
            ChartType.TIME_TO_THRESHOLD -> "TIME TO THRESHOLD"
        }
        canvas.drawText(title, padding, padding + labelPaint.textSize, labelPaint)

        // Chart area
        val chartLeft = padding + density * 30f
        val chartTop = padding + labelPaint.textSize + density * 16f
        val chartRight = w - padding
        val chartBottom = h - padding - density * 24f
        val chartRect = RectF(chartLeft, chartTop, chartRight, chartBottom)

        when (chartType) {
            ChartType.HISTOGRAM -> drawHistogram(canvas, chartRect)
            ChartType.PDF -> drawPDF(canvas, chartRect)
            ChartType.AUTOCORRELATION -> drawAutocorrelation(canvas, chartRect)
            ChartType.TIME_TO_THRESHOLD -> drawTimeToThreshold(canvas, chartRect)
        }
    }

    private fun drawHistogram(canvas: Canvas, chartRect: RectF) {
        if (histogramBins.isEmpty()) {
            drawNoData(canvas, chartRect)
            return
        }

        val maxCount = histogramBins.maxOrNull() ?: 1
        val barSpacing = density * 2f
        val barWidth = (chartRect.width() - barSpacing * (histogramBins.size - 1)) / histogramBins.size

        // Draw grid
        drawGrid(canvas, chartRect, 5)

        // Draw bars
        histogramBins.forEachIndexed { index, count ->
            val barHeight = (count.toFloat() / maxCount) * chartRect.height()
            val left = chartRect.left + index * (barWidth + barSpacing)
            val top = chartRect.bottom - barHeight
            val right = left + barWidth
            val bottom = chartRect.bottom

            // Gradient color based on distance from mean
            val binCenter = histogramMin + (index + 0.5f) * binWidth
            val zScore = stats?.let { abs(StatisticsCalculator.calculateZScore(binCenter, it.mean, it.stdDev)) } ?: 0f
            barPaint.color = when {
                zScore < 1 -> greenColor
                zScore < 2 -> amberColor
                else -> ContextCompat.getColor(context, R.color.pro_red)
            }
            barPaint.alpha = 200

            canvas.drawRect(left, top, right, bottom, barPaint)
        }

        // Draw mean line
        stats?.let { s ->
            val meanX = chartRect.left + ((s.mean - histogramMin) / (histogramMax - histogramMin)) * chartRect.width()
            if (meanX >= chartRect.left && meanX <= chartRect.right) {
                canvas.drawLine(meanX, chartRect.top, meanX, chartRect.bottom, meanLinePaint)
            }
        }

        // Draw axis labels
        axisPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(String.format("%.3f", histogramMin), chartRect.left, chartRect.bottom + axisPaint.textSize + density * 4f, axisPaint)
        axisPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format("%.3f", histogramMax), chartRect.right, chartRect.bottom + axisPaint.textSize + density * 4f, axisPaint)

        // Draw stats
        stats?.let { s ->
            val statsText = "μ=${String.format("%.4f", s.mean)}  σ=${String.format("%.4f", s.stdDev)}  n=${data.size}"
            statsPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(statsText, chartRect.right, chartRect.top - density * 4f, statsPaint)
        }
    }

    private fun drawPDF(canvas: Canvas, chartRect: RectF) {
        if (pdfPoints.isEmpty() || stats == null) {
            drawNoData(canvas, chartRect)
            return
        }

        val s = stats!!
        val xMin = pdfPoints.first().x
        val xMax = pdfPoints.last().x
        val yMax = pdfPoints.maxOfOrNull { it.y } ?: 1f

        // Draw grid
        drawGrid(canvas, chartRect, 5)

        // Draw filled area
        val path = Path()
        pdfPoints.forEachIndexed { index, point ->
            val x = chartRect.left + ((point.x - xMin) / (xMax - xMin)) * chartRect.width()
            val y = chartRect.bottom - (point.y / yMax) * chartRect.height()
            
            if (index == 0) {
                path.moveTo(x, chartRect.bottom)
                path.lineTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.lineTo(chartRect.right, chartRect.bottom)
        path.close()

        // Gradient fill
        fillPaint.shader = LinearGradient(
            0f, chartRect.top, 0f, chartRect.bottom,
            cyanColor and 0x40FFFFFF.toInt(),
            cyanColor and 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = null

        // Draw line
        linePaint.color = cyanColor
        val linePath = Path()
        pdfPoints.forEachIndexed { index, point ->
            val x = chartRect.left + ((point.x - xMin) / (xMax - xMin)) * chartRect.width()
            val y = chartRect.bottom - (point.y / yMax) * chartRect.height()
            
            if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }
        canvas.drawPath(linePath, linePaint)

        // Draw σ bands
        val sigma1LeftX = chartRect.left + ((s.mean - s.stdDev - xMin) / (xMax - xMin)) * chartRect.width()
        val sigma1RightX = chartRect.left + ((s.mean + s.stdDev - xMin) / (xMax - xMin)) * chartRect.width()
        
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = greenColor
            alpha = 30
        }
        canvas.drawRect(sigma1LeftX, chartRect.top, sigma1RightX, chartRect.bottom, bandPaint)

        // Labels
        axisPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("μ", chartRect.centerX(), chartRect.bottom + axisPaint.textSize + density * 4f, axisPaint)
        canvas.drawText("-2σ", chartRect.left + chartRect.width() * 0.16f, chartRect.bottom + axisPaint.textSize + density * 4f, axisPaint)
        canvas.drawText("+2σ", chartRect.left + chartRect.width() * 0.84f, chartRect.bottom + axisPaint.textSize + density * 4f, axisPaint)
    }

    private fun drawAutocorrelation(canvas: Canvas, chartRect: RectF) {
        if (autocorrValues.isEmpty()) {
            drawNoData(canvas, chartRect)
            return
        }

        // Draw grid
        drawGrid(canvas, chartRect, 5)

        // Draw zero line
        val zeroY = chartRect.centerY()
        canvas.drawLine(chartRect.left, zeroY, chartRect.right, zeroY, gridPaint)

        // Draw confidence bands (95% = 1.96/sqrt(n))
        val confidenceBound = 1.96f / sqrt(data.size.toFloat())
        val upperY = zeroY - confidenceBound * (chartRect.height() / 2)
        val lowerY = zeroY + confidenceBound * (chartRect.height() / 2)
        
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = greenColor
            alpha = 30
        }
        canvas.drawRect(chartRect.left, upperY, chartRect.right, lowerY, bandPaint)

        // Draw bars
        val barWidth = chartRect.width() / autocorrValues.size
        autocorrValues.forEachIndexed { lag, value ->
            val x = chartRect.left + lag * barWidth
            val barHeight = abs(value) * (chartRect.height() / 2)
            
            barPaint.color = if (abs(value) > confidenceBound) magentaColor else cyanColor
            barPaint.alpha = 180
            
            if (value >= 0) {
                canvas.drawRect(x, zeroY - barHeight, x + barWidth - density, zeroY, barPaint)
            } else {
                canvas.drawRect(x, zeroY, x + barWidth - density, zeroY + barHeight, barPaint)
            }
        }

        // Labels
        axisPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Lag 0", chartRect.left, chartRect.bottom + axisPaint.textSize + density * 4f, axisPaint)
        axisPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Lag ${autocorrValues.size}", chartRect.right, chartRect.bottom + axisPaint.textSize + density * 4f, axisPaint)
    }

    private fun drawTimeToThreshold(canvas: Canvas, chartRect: RectF) {
        // Large centered display
        valuePaint.textAlign = Paint.Align.CENTER

        predictedTimeMinutes?.let { minutes ->
            val text = when {
                minutes <= 0 -> "EXCEEDED"
                minutes < 1 -> "< 1 min"
                minutes < 60 -> "${minutes.toInt()} min"
                minutes < 1440 -> String.format("%.1f hrs", minutes / 60f)
                else -> String.format("%.1f days", minutes / 1440f)
            }
            
            valuePaint.color = when {
                minutes <= 0 -> ContextCompat.getColor(context, R.color.pro_red)
                minutes < 10 -> amberColor
                else -> greenColor
            }
            
            canvas.drawText(text, chartRect.centerX(), chartRect.centerY() + valuePaint.textSize / 3f, valuePaint)
        } ?: run {
            valuePaint.color = greenColor
            canvas.drawText("∞", chartRect.centerX(), chartRect.centerY() + valuePaint.textSize / 3f, valuePaint)
        }

        // Subtitle
        statsPaint.textAlign = Paint.Align.CENTER
        statsPaint.color = textMuted
        val subtitle = "until ${String.format("%.2f", thresholdUSvH)} μSv/h"
        canvas.drawText(subtitle, chartRect.centerX(), chartRect.centerY() + valuePaint.textSize + density * 8f, statsPaint)

        // Current rate
        statsPaint.color = cyanColor
        val current = "Current: ${String.format("%.3f", currentRate)} μSv/h"
        canvas.drawText(current, chartRect.centerX(), chartRect.bottom, statsPaint)
    }

    private fun drawGrid(canvas: Canvas, chartRect: RectF, lines: Int) {
        val stepY = chartRect.height() / lines
        for (i in 1 until lines) {
            val y = chartRect.top + i * stepY
            canvas.drawLine(chartRect.left, y, chartRect.right, y, gridPaint)
        }
    }

    private fun drawNoData(canvas: Canvas, chartRect: RectF) {
        statsPaint.textAlign = Paint.Align.CENTER
        statsPaint.color = textMuted
        canvas.drawText("Collecting data...", chartRect.centerX(), chartRect.centerY(), statsPaint)
    }
}
