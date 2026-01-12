package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Professional metric card with:
 * - Label (uppercase)
 * - Hero value (large monospace)
 * - Unit label
 * - Trend indicator (arrow + percentage)
 * - Mini sparkline at bottom
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
        invalidate()
    }

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

        val (arrow, color) = when {
            trend > 0.5f -> "▲" to colorGreen
            trend < -0.5f -> "▼" to colorRed
            else -> "─" to colorMuted
        }

        trendPaint.color = color

        val text = if (kotlin.math.abs(trend) >= 0.5f) {
            "$arrow ${String.format(Locale.US, "%.1f", kotlin.math.abs(trend))}%"
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
        val deltaThreshold = 0.005f  // 0.5% change threshold for coloring

        // Calculate positions
        val points = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until n) {
            val x = left + width * (i.toFloat() / (n - 1))
            val yNorm = (sparklineData[i] - yMin) / yRange
            val y = top + height - height * yNorm
            points.add(x to y)
        }

        // Draw per-segment color-coded fills AND lines
        for (i in 1 until n) {
            val prev = sparklineData[i - 1]
            val curr = sparklineData[i]
            val deltaPercent = if (prev != 0f) (curr - prev) / prev else 0f
            
            val (x1, y1) = points[i - 1]
            val (x2, y2) = points[i]
            
            // Determine segment color based on direction
            val segmentColor = when {
                deltaPercent > deltaThreshold -> greenColor   // Increasing = green
                deltaPercent < -deltaThreshold -> redColor    // Decreasing = red  
                else -> accentColor                            // Neutral = accent
            }
            
            // Draw segment fill with more opacity for colored segments
            val fillAlpha = if (segmentColor != accentColor) 120 else 60
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
            
            // Draw line segment in the same color
            sparklinePaint.color = segmentColor
            canvas.drawLine(x1, y1, x2, y2, sparklinePaint)
        }
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
