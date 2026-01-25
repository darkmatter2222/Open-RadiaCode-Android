package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.IsotopeDetector
import com.radiacode.ble.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * Stacked area chart for isotope fraction display.
 * Shows how different isotopes contribute to the total spectrum over time.
 * Best for visualizing fractional contributions that sum to ~1.
 */
class StackedAreaChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    companion object {
        private val ISOTOPE_COLORS = listOf(
            0xFF00E5FF.toInt(),  // Cyan
            0xFFE040FB.toInt(),  // Magenta  
            0xFF69F0AE.toInt(),  // Green
            0xFFFFD600.toInt(),  // Yellow/Amber
            0xFFFF5252.toInt()   // Red
        )
        
        private const val MAX_SERIES = 5
    }
    
    /**
     * Time series data for stacking.
     */
    data class StackedSeries(
        val isotopeId: String,
        val name: String,
        val timestamps: List<Long>,
        val values: List<Float>,  // Fraction (0-1)
        val color: Int
    )
    
    // Data
    private var series: List<StackedSeries> = emptyList()
    
    // Dimensions
    private var chartLeft = 0f
    private var chartTop = 0f
    private var chartRight = 0f
    private var chartBottom = 0f
    private var legendHeight = 0f
    
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    
    // Paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = ContextCompat.getColor(context, R.color.pro_border)
        alpha = 60
    }
    
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 10f
        color = ContextCompat.getColor(context, R.color.pro_text_muted)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    
    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 10f
        color = ContextCompat.getColor(context, R.color.pro_text_secondary)
    }
    
    private val legendDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val fillPath = Path()
    private val strokePath = Path()
    
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    
    /**
     * Set the isotope time series data from prediction history.
     */
    fun setData(
        history: List<IsotopeDetector.AnalysisResult>,
        topIsotopeIds: List<String>
    ) {
        // Build series for top isotopes (use fractions for stacking)
        val newSeries = mutableListOf<StackedSeries>()
        
        topIsotopeIds.take(MAX_SERIES).forEachIndexed { index, isotopeId ->
            val timestamps = mutableListOf<Long>()
            val values = mutableListOf<Float>()
            var name = isotopeId
            
            for (result in history) {
                val prediction = result.predictions.find { it.isotopeId == isotopeId }
                if (prediction != null) {
                    timestamps.add(result.timestampMs)
                    values.add(prediction.fraction)
                    name = prediction.name
                }
            }
            
            if (timestamps.isNotEmpty()) {
                newSeries.add(StackedSeries(
                    isotopeId = isotopeId,
                    name = name,
                    timestamps = timestamps,
                    values = values,
                    color = ISOTOPE_COLORS.getOrElse(index) { ISOTOPE_COLORS[0] }
                ))
            }
        }
        
        series = newSeries
        invalidate()
    }
    
    /**
     * Clear all data.
     */
    fun clear() {
        series = emptyList()
        invalidate()
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateDimensions()
    }
    
    private fun calculateDimensions() {
        val w = width.toFloat()
        val h = height.toFloat()
        
        val leftMargin = density * 40f
        val rightMargin = density * 8f
        val topMargin = density * 8f
        legendHeight = density * 32f
        val bottomMargin = density * 20f
        
        chartLeft = leftMargin
        chartTop = topMargin
        chartRight = w - rightMargin
        chartBottom = h - bottomMargin - legendHeight
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width == 0 || height == 0) return
        
        drawGrid(canvas)
        drawYAxis(canvas)
        drawXAxis(canvas)
        drawStackedAreas(canvas)
        drawLegend(canvas)
    }
    
    private fun drawGrid(canvas: Canvas) {
        val chartHeight = chartBottom - chartTop
        val chartWidth = chartRight - chartLeft
        
        // Horizontal grid lines
        for (i in 0..4) {
            val y = chartTop + (chartHeight * i / 4f)
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }
        
        // Vertical grid lines
        for (i in 0..4) {
            val x = chartLeft + (chartWidth * i / 4)
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
        }
    }
    
    private fun drawYAxis(canvas: Canvas) {
        val chartHeight = chartBottom - chartTop
        
        // Y-axis labels: 0%, 25%, 50%, 75%, 100%
        val labels = listOf("0%", "25%", "50%", "75%", "100%")
        
        labels.forEachIndexed { i, label ->
            val y = chartBottom - (chartHeight * i / 4f)
            val textWidth = axisTextPaint.measureText(label)
            canvas.drawText(label, chartLeft - textWidth - density * 4f, y + axisTextPaint.textSize / 3f, axisTextPaint)
        }
    }
    
    private fun drawXAxis(canvas: Canvas) {
        if (series.isEmpty()) return
        
        var minTime = Long.MAX_VALUE
        var maxTime = Long.MIN_VALUE
        for (s in series) {
            if (s.timestamps.isNotEmpty()) {
                minTime = min(minTime, s.timestamps.first())
                maxTime = max(maxTime, s.timestamps.last())
            }
        }
        
        if (minTime == Long.MAX_VALUE) return
        
        val chartWidth = chartRight - chartLeft
        
        val timePoints = listOf(minTime, (minTime + maxTime) / 2, maxTime)
        val xPositions = listOf(chartLeft, chartLeft + chartWidth / 2, chartRight)
        
        timePoints.forEachIndexed { i, time ->
            val label = timeFormatter.format(Instant.ofEpochMilli(time))
            val x = xPositions[i]
            val textWidth = axisTextPaint.measureText(label)
            val textX = when (i) {
                0 -> x
                timePoints.lastIndex -> x - textWidth
                else -> x - textWidth / 2
            }
            canvas.drawText(label, textX, chartBottom + axisTextPaint.textSize + density * 4f, axisTextPaint)
        }
    }
    
    private fun drawStackedAreas(canvas: Canvas) {
        if (series.isEmpty()) return
        
        // Find common time range
        var minTime = Long.MAX_VALUE
        var maxTime = Long.MIN_VALUE
        for (s in series) {
            if (s.timestamps.isNotEmpty()) {
                minTime = min(minTime, s.timestamps.first())
                maxTime = max(maxTime, s.timestamps.last())
            }
        }
        
        if (minTime == Long.MAX_VALUE) return
        
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop
        val timeRange = max(1L, maxTime - minTime)
        
        // Build cumulative stacked values
        // Use the first series timestamps as reference
        val refSeries = series.firstOrNull() ?: return
        val numPoints = refSeries.timestamps.size
        
        // Previous cumulative values (starts at 0)
        val previousCumulative = FloatArray(numPoints) { 0f }
        
        // Draw areas from bottom to top (reverse order for proper stacking visual)
        for ((seriesIndex, s) in series.withIndex()) {
            fillPath.reset()
            strokePath.reset()
            
            val currentCumulative = FloatArray(numPoints)
            
            // Calculate cumulative values
            for (i in 0 until numPoints) {
                val value = s.values.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                currentCumulative[i] = (previousCumulative[i] + value).coerceAtMost(1f)
            }
            
            // Build the fill path
            // Start from bottom-left of the area (previous cumulative)
            var firstX = 0f
            var firstY = 0f
            
            for (i in 0 until numPoints) {
                val t = refSeries.timestamps.getOrElse(i) { minTime }
                val x = chartLeft + ((t - minTime).toFloat() / timeRange) * chartWidth
                val yTop = chartBottom - (currentCumulative[i] * chartHeight)
                val yBottom = chartBottom - (previousCumulative[i] * chartHeight)
                
                if (i == 0) {
                    fillPath.moveTo(x, yBottom)
                    firstX = x
                    firstY = yBottom
                }
                
                fillPath.lineTo(x, yTop)
                strokePath.moveTo(x, yTop)
                if (i > 0) {
                    val prevT = refSeries.timestamps[i - 1]
                    val prevX = chartLeft + ((prevT - minTime).toFloat() / timeRange) * chartWidth
                    val prevYTop = chartBottom - (currentCumulative[i - 1] * chartHeight)
                    strokePath.moveTo(prevX, prevYTop)
                    strokePath.lineTo(x, yTop)
                }
            }
            
            // Complete the fill path by going back along the bottom
            for (i in (numPoints - 1) downTo 0) {
                val t = refSeries.timestamps.getOrElse(i) { minTime }
                val x = chartLeft + ((t - minTime).toFloat() / timeRange) * chartWidth
                val yBottom = chartBottom - (previousCumulative[i] * chartHeight)
                fillPath.lineTo(x, yBottom)
            }
            
            fillPath.close()
            
            // Draw filled area with semi-transparency
            fillPaint.color = Color.argb(
                120,
                Color.red(s.color),
                Color.green(s.color),
                Color.blue(s.color)
            )
            canvas.drawPath(fillPath, fillPaint)
            
            // Draw top edge stroke
            strokePaint.color = s.color
            // Redraw top edge
            strokePath.reset()
            for (i in 0 until numPoints) {
                val t = refSeries.timestamps.getOrElse(i) { minTime }
                val x = chartLeft + ((t - minTime).toFloat() / timeRange) * chartWidth
                val yTop = chartBottom - (currentCumulative[i] * chartHeight)
                
                if (i == 0) {
                    strokePath.moveTo(x, yTop)
                } else {
                    strokePath.lineTo(x, yTop)
                }
            }
            canvas.drawPath(strokePath, strokePaint)
            
            // Update previous for next layer
            System.arraycopy(currentCumulative, 0, previousCumulative, 0, numPoints)
        }
    }
    
    private fun drawLegend(canvas: Canvas) {
        if (series.isEmpty()) return
        
        val legendY = chartBottom + density * 24f
        var currentX = chartLeft
        val dotRadius = density * 4f
        val spacing = density * 8f
        val itemSpacing = density * 16f
        
        for (s in series) {
            val displayName = if (s.name.length > 12) s.name.take(10) + "…" else s.name
            val currentValue = s.values.lastOrNull() ?: 0f
            val valueText = "${(currentValue * 100).toInt()}%"
            
            val label = "$displayName: $valueText"
            val labelWidth = legendTextPaint.measureText(label)
            
            if (currentX + dotRadius * 2 + spacing + labelWidth > chartRight && currentX > chartLeft) {
                break
            }
            
            legendDotPaint.color = s.color
            canvas.drawCircle(currentX + dotRadius, legendY, dotRadius, legendDotPaint)
            
            legendTextPaint.color = s.color
            canvas.drawText(label, currentX + dotRadius * 2 + spacing, legendY + legendTextPaint.textSize / 3f, legendTextPaint)
            
            currentX += dotRadius * 2 + spacing + labelWidth + itemSpacing
        }
    }
}
