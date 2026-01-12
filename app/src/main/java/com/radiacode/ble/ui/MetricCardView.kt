package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Professional metric card with:
 * - Label (uppercase)
 * - Hero value (large monospace)
 * - Unit label
 * - Trend indicator (arrow + percentage) - now based on standard deviation
 * - Mini sparkline at bottom with statistical color coding
 */
class MetricCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    // Data
    private var label: String = "VALUE"
    private var value: Float = 0f
    private var valueText: String? = null  // If set, displayed instead of formatted value
    private var unit: String = "unit"
    private var trend: Float = 0f // percentage change
    private var sparklineData: List<Float> = emptyList()
    private var accentColor: Int = ContextCompat.getColor(context, R.color.pro_cyan)
    
    // Statistics (calculated from sparkline data)
    private var mean: Float = 0f
    private var stdDev: Float = 0f
    private var zScore: Float = 0f  // How many std devs from mean

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pro_surface)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = ContextCompat.getColor(context, R.color.pro_border)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 11f
        color = ContextCompat.getColor(context, R.color.pro_text_muted)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.1f
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 36f
        color = ContextCompat.getColor(context, R.color.pro_text_primary)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 14f
        color = ContextCompat.getColor(context, R.color.pro_text_secondary)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val trendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val sparklinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val sparklineFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setLabel(label: String) {
        this.label = label.uppercase()
        invalidate()
    }

    fun setValue(value: Float, unit: String) {
        this.value = value
        this.unit = unit
        this.valueText = null
        invalidate()
    }

    fun setValueText(text: String) {
        this.valueText = text
        invalidate()
    }

    fun setTrend(percentage: Float) {
        this.trend = percentage
        invalidate()
    }

    fun setSparkline(data: List<Float>) {
        this.sparklineData = data
        calculateStatistics()
        invalidate()
    }
    
    /** Calculate mean, standard deviation, and z-score for current value */
    private fun calculateStatistics() {
        if (sparklineData.size < 2) {
            mean = value
            stdDev = 0f
            zScore = 0f
            return
        }
        
        // Calculate mean
        mean = sparklineData.sum() / sparklineData.size
        
        // Calculate standard deviation
        val sumSquaredDiff = sparklineData.map { (it - mean) * (it - mean) }.sum()
        stdDev = sqrt(sumSquaredDiff / sparklineData.size)
        
        // Calculate z-score for current value (last value in sparkline or current value)
        val currentVal = if (sparklineData.isNotEmpty()) sparklineData.last() else value
        zScore = if (stdDev > 0.0001f) (currentVal - mean) / stdDev else 0f
    }
    
    /** Get the z-score (for external use, e.g., widget) */
    fun getZScore(): Float = zScore
    
    /** Get the standard deviation (for external use) */
    fun getStdDev(): Float = stdDev
    
    /** Get the mean (for external use) */
    fun getMean(): Float = mean

    fun setAccentColor(color: Int) {
        this.accentColor = color
        sparklinePaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = density * 12f
        val padding = density * 16f

        // Background
        val rect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // Label (top left)
        canvas.drawText(label, padding, padding + labelPaint.textSize, labelPaint)

        // Trend (top right)
        drawTrend(canvas, w - padding, padding + trendPaint.textSize)

        // Value (center)
        val displayText = valueText ?: formatValue(value)
        val valueY = padding + labelPaint.textSize + density * 8f + valuePaint.textSize
        canvas.drawText(displayText, padding, valueY, valuePaint)

        // Unit (below value) - only show if we have a real value
        val unitY = valueY + density * 4f + unitPaint.textSize
        if (valueText == null) {
            canvas.drawText(unit, padding, unitY, unitPaint)
        }

        // Sparkline (bottom)
        val sparklineTop = unitY + density * 12f
        val sparklineHeight = h - sparklineTop - padding
        if (sparklineHeight > density * 16f && sparklineData.size >= 2) {
            drawSparkline(canvas, padding, sparklineTop, w - padding * 2, sparklineHeight)
        }
    }

    private fun drawTrend(canvas: Canvas, rightX: Float, y: Float) {
        val colorGreen = ContextCompat.getColor(context, R.color.pro_green)
        val colorRed = ContextCompat.getColor(context, R.color.pro_red)
        val colorMuted = ContextCompat.getColor(context, R.color.pro_text_muted)

        // Use z-score for statistical significance
        // |z| > 1 means outside 1 standard deviation (~68% of data)
        // |z| > 2 means outside 2 standard deviations (~95% of data)
        val absZ = abs(zScore)
        
        val (arrow, color, showValue) = when {
            absZ > 2f && zScore > 0 -> Triple("▲▲", colorGreen, true)   // Very high (>2σ)
            absZ > 1f && zScore > 0 -> Triple("▲", colorGreen, true)    // High (>1σ)
            absZ > 2f && zScore < 0 -> Triple("▼▼", colorRed, true)     // Very low (<-2σ)
            absZ > 1f && zScore < 0 -> Triple("▼", colorRed, true)      // Low (<-1σ)
            else -> Triple("─", colorMuted, false)                       // Within 1σ (normal)
        }

        trendPaint.color = color

        val text = if (showValue && abs(trend) >= 0.1f) {
            val sign = if (trend > 0) "+" else ""
            "$arrow $sign${String.format(Locale.US, "%.1f", trend)}%"
        } else {
            arrow
        }

        trendPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(text, rightX, y, trendPaint)
        trendPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawSparkline(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        if (sparklineData.size < 2) return

        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (v in sparklineData) {
            minV = min(minV, v)
            maxV = max(maxV, v)
        }

        val range = max(1e-6f, maxV - minV)
        val padding = range * 0.1f
        val yMin = minV - padding
        val yMax = maxV + padding
        val yRange = max(1e-6f, yMax - yMin)

        val n = sparklineData.size
        val greenColor = ContextCompat.getColor(context, R.color.pro_green)
        val redColor = ContextCompat.getColor(context, R.color.pro_red)
        val whiteColor = Color.WHITE

        // Calculate positions
        val points = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until n) {
            val x = left + width * (i.toFloat() / (n - 1))
            val yNorm = (sparklineData[i] - yMin) / yRange
            val y = top + height - height * yNorm
            points.add(x to y)
        }

        // Draw per-segment with statistical coloring based on z-score
        for (i in 1 until n) {
            val curr = sparklineData[i]
            
            val (x1, y1) = points[i - 1]
            val (x2, y2) = points[i]
            
            // Calculate z-score for this point (how many std devs from mean)
            val pointZScore = if (stdDev > 0.0001f) (curr - mean) / stdDev else 0f
            val absZ = abs(pointZScore)
            
            // Intensity based on how far outside 1 std dev
            // 0 at z=0, peaks at z=2+
            val intensity = when {
                absZ < 0.5f -> 0f           // Within 0.5σ = white (normal noise)
                absZ >= 2f -> 1f            // Beyond 2σ = full color (significant)
                else -> (absZ - 0.5f) / 1.5f // 0.5σ to 2σ = gradient
            }.coerceIn(0f, 1f)
            
            // Determine color based on direction and intensity
            val segmentColor = when {
                intensity < 0.05f -> whiteColor  // Nearly zero = white
                pointZScore > 0f -> blendColors(whiteColor, greenColor, intensity)
                pointZScore < 0f -> blendColors(whiteColor, redColor, intensity)
                else -> whiteColor
            }
            
            // Fill alpha scales with intensity - more prominent fills for significant deviations
            val baseFillAlpha = 40
            val maxFillAlpha = 200
            val fillAlpha = (baseFillAlpha + (maxFillAlpha - baseFillAlpha) * intensity).toInt()
            
            val segmentPath = Path().apply {
                moveTo(x1, top + height)
                lineTo(x1, y1)
                lineTo(x2, y2)
                lineTo(x2, top + height)
                close()
            }
            
            val segmentGradient = LinearGradient(
                0f, top, 0f, top + height,
                Color.argb(fillAlpha, Color.red(segmentColor), Color.green(segmentColor), Color.blue(segmentColor)),
                Color.argb(0, Color.red(segmentColor), Color.green(segmentColor), Color.blue(segmentColor)),
                Shader.TileMode.CLAMP
            )
            sparklineFillPaint.shader = segmentGradient
            canvas.drawPath(segmentPath, sparklineFillPaint)
            
            // Line alpha also scales with intensity
            val lineAlpha = (120 + 135 * intensity).toInt().coerceIn(120, 255)
            sparklinePaint.color = Color.argb(lineAlpha, Color.red(segmentColor), Color.green(segmentColor), Color.blue(segmentColor))
            canvas.drawLine(x1, y1, x2, y2, sparklinePaint)
        }
        
        // Draw mean line (subtle dotted line)
        if (stdDev > 0.0001f) {
            val meanYNorm = (mean - yMin) / yRange
            val meanY = top + height - height * meanYNorm
            val meanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = density * 1f
                color = ContextCompat.getColor(context, R.color.pro_text_muted)
                alpha = 60
                pathEffect = DashPathEffect(floatArrayOf(density * 4f, density * 4f), 0f)
            }
            canvas.drawLine(left, meanY, left + width, meanY, meanLinePaint)
        }
    }
    
    // Blend two colors based on ratio (0.0 = color1, 1.0 = color2)
    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val r = (Color.red(color1) * inverseRatio + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * inverseRatio + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    private fun formatValue(value: Float): String {
        return when {
            value >= 10000 -> String.format(Locale.US, "%.0f", value)
            value >= 1000 -> String.format(Locale.US, "%.1f", value)
            value >= 100 -> String.format(Locale.US, "%.1f", value)
            value >= 10 -> String.format(Locale.US, "%.2f", value)
            value >= 1 -> String.format(Locale.US, "%.3f", value)
            else -> String.format(Locale.US, "%.3f", value)
        }
    }
}
