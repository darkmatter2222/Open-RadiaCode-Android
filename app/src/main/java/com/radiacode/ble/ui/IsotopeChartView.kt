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
 * Multi-line time series chart for isotope probability/fraction display.
 * Shows up to 5 isotopes with distinct colors, legends, and time axis.
 */
class IsotopeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    companion object {
        // Distinct colors for top 5 isotopes
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
     * Time series data for a single isotope.
     */
    data class IsotopeSeries(
        val isotopeId: String,
        val name: String,
        val timestamps: List<Long>,
        val values: List<Float>,  // Probability or fraction (0-1)
        val color: Int
    )
    
    // Data
    private var series: List<IsotopeSeries> = emptyList()
    private var showProbability: Boolean = true  // true = probability, false = fraction
    
    // Dimensions
    private var chartLeft = 0f
    private var chartTop = 0f
    private var chartRight = 0f
    private var chartBottom = 0f
    private var legendHeight = 0f
    
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    
    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
    }
    
    private val linePath = Path()
    private val fillPath = Path()
    
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    
    /**
     * Set the isotope time series data from prediction history.
     */
    fun setData(
        history: List<IsotopeDetector.AnalysisResult>,
        topIsotopeIds: List<String>,
        showProbability: Boolean = true
    ) {
        this.showProbability = showProbability
        
        // Build series for top isotopes
        val newSeries = mutableListOf<IsotopeSeries>()
        
        topIsotopeIds.take(MAX_SERIES).forEachIndexed { index, isotopeId ->
            val timestamps = mutableListOf<Long>()
            val values = mutableListOf<Float>()
            var name = isotopeId
            
            for (result in history) {
                val prediction = result.predictions.find { it.isotopeId == isotopeId }
                if (prediction != null) {
                    timestamps.add(result.timestampMs)
                    values.add(if (showProbability) prediction.probability else prediction.fraction)
                    name = prediction.name
                }
            }
            
            if (timestamps.isNotEmpty()) {
                newSeries.add(IsotopeSeries(
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
        
        val leftMargin = density * 40f   // Y-axis labels
        val rightMargin = density * 8f
        val topMargin = density * 8f
        legendHeight = density * 32f      // Legend area at bottom
        val bottomMargin = density * 20f  // Time labels
        
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
        drawSeries(canvas)
        drawLegend(canvas)
    }
    
    private fun drawGrid(canvas: Canvas) {
        val chartHeight = chartBottom - chartTop
        val chartWidth = chartRight - chartLeft
        
        // Horizontal grid lines (5 divisions for 0%, 25%, 50%, 75%, 100%)
        for (i in 0..4) {
            val y = chartTop + (chartHeight * i / 4f)
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }
        
        // Vertical grid lines (4-6 time divisions)
        val divisions = 4
        for (i in 0..divisions) {
            val x = chartLeft + (chartWidth * i / divisions)
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
        }
    }
    
    private fun drawYAxis(canvas: Canvas) {
        val chartHeight = chartBottom - chartTop
        val unit = if (showProbability) "%" else ""
        
        // Y-axis labels: 0%, 25%, 50%, 75%, 100%
        val labels = if (showProbability) {
            listOf("0%", "25%", "50%", "75%", "100%")
        } else {
            listOf("0.0", "0.25", "0.5", "0.75", "1.0")
        }
        
        labels.forEachIndexed { i, label ->
            val y = chartBottom - (chartHeight * i / 4f)
            val textWidth = axisTextPaint.measureText(label)
            canvas.drawText(label, chartLeft - textWidth - density * 4f, y + axisTextPaint.textSize / 3f, axisTextPaint)
        }
    }
    
    private fun drawXAxis(canvas: Canvas) {
        if (series.isEmpty()) return
        
        // Find time range across all series
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
        val timeRange = max(1L, maxTime - minTime)
        
        // Draw 3 time labels
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
    
    private fun drawSeries(canvas: Canvas) {
        if (series.isEmpty()) return
        
        // Find time range
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
        
        // Draw each series
        for (s in series) {
            if (s.timestamps.size < 2) continue
            
            linePaint.color = s.color
            linePath.reset()
            
            // Create fill gradient
            val gradientColor = Color.argb(40, Color.red(s.color), Color.green(s.color), Color.blue(s.color))
            fillPaint.shader = LinearGradient(
                0f, chartTop, 0f, chartBottom,
                gradientColor, Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            fillPath.reset()
            
            var firstPoint = true
            for (i in s.timestamps.indices) {
                val t = s.timestamps[i]
                val v = s.values[i].coerceIn(0f, 1f)
                
                val x = chartLeft + ((t - minTime).toFloat() / timeRange) * chartWidth
                val y = chartBottom - (v * chartHeight)
                
                if (firstPoint) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, chartBottom)
                    fillPath.lineTo(x, y)
                    firstPoint = false
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            
            // Complete fill path
            if (s.timestamps.isNotEmpty()) {
                val lastX = chartLeft + ((s.timestamps.last() - minTime).toFloat() / timeRange) * chartWidth
                fillPath.lineTo(lastX, chartBottom)
                fillPath.close()
            }
            
            // Draw fill then line
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, linePaint)
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
            // Truncate name if too long
            val displayName = if (s.name.length > 12) {
                s.name.take(10) + "…"
            } else {
                s.name
            }
            
            // Current value
            val currentValue = s.values.lastOrNull() ?: 0f
            val valueText = if (showProbability) {
                "${(currentValue * 100).toInt()}%"
            } else {
                "%.2f".format(currentValue)
            }
            
            val label = "$displayName: $valueText"
            val labelWidth = legendTextPaint.measureText(label)
            
            // Check if we need to wrap
            if (currentX + dotRadius * 2 + spacing + labelWidth > chartRight && currentX > chartLeft) {
                // Would overflow - stop drawing more legend items
                break
            }
            
            // Draw colored dot
            legendDotPaint.color = s.color
            canvas.drawCircle(currentX + dotRadius, legendY, dotRadius, legendDotPaint)
            
            // Draw label
            legendTextPaint.color = s.color
            canvas.drawText(label, currentX + dotRadius * 2 + spacing, legendY + legendTextPaint.textSize / 3f, legendTextPaint)
            
            currentX += dotRadius * 2 + spacing + labelWidth + itemSpacing
        }
    }
    
    /**
     * Get color for an isotope by index.
     */
    fun getColorForIndex(index: Int): Int {
        return ISOTOPE_COLORS.getOrElse(index) { ISOTOPE_COLORS[0] }
    }
}
