package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import java.util.Locale
import kotlin.math.max

/**
 * Horizontal stats row showing min/avg/max/delta
 */
class StatRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    data class Stats(
        val min: Float,
        val avg: Float,
        val max: Float,
        val delta: Float, // change per minute
        val unit: String
    )

    private var stats: Stats? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pro_surface)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = ContextCompat.getColor(context, R.color.pro_border)
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = ContextCompat.getColor(context, R.color.pro_border)
        alpha = 128
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 10f
        color = ContextCompat.getColor(context, R.color.pro_text_muted)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.05f
        textAlign = Paint.Align.CENTER
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 14f
        color = ContextCompat.getColor(context, R.color.pro_text_primary)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val deltaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 14f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    fun setStats(stats: Stats) {
        this.stats = stats
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = density * 8f
        val padding = density * 8f

        // Background
        val rect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        val s = stats ?: return

        // Divide into 4 columns
        val colWidth = w / 4f

        // Draw dividers
        for (i in 1..3) {
            val x = colWidth * i
            canvas.drawLine(x, padding, x, h - padding, dividerPaint)
        }

        val labelY = padding + labelPaint.textSize + density * 4f
        val valueY = labelY + density * 6f + valuePaint.textSize

        // MIN
        drawColumn(canvas, 0, colWidth, "MIN", formatValue(s.min), null)

        // AVG
        drawColumn(canvas, 1, colWidth, "AVG", formatValue(s.avg), null)

        // MAX
        drawColumn(canvas, 2, colWidth, "MAX", formatValue(s.max), null)

        // DELTA
        val deltaColor = when {
            s.delta > 0.001f -> ContextCompat.getColor(context, R.color.pro_green)
            s.delta < -0.001f -> ContextCompat.getColor(context, R.color.pro_red)
            else -> ContextCompat.getColor(context, R.color.pro_text_muted)
        }
        val deltaText = when {
            s.delta > 0 -> "+${formatValue(s.delta)}"
            s.delta < 0 -> formatValue(s.delta)
            else -> "0"
        }
        drawColumn(canvas, 3, colWidth, "Δ/min", deltaText, deltaColor)
    }

    private fun drawColumn(canvas: Canvas, index: Int, colWidth: Float, label: String, value: String, valueColor: Int?) {
        val centerX = colWidth * index + colWidth / 2f
        val padding = density * 8f
        val labelY = padding + labelPaint.textSize + density * 4f
        val valueY = labelY + density * 6f + valuePaint.textSize

        canvas.drawText(label, centerX, labelY, labelPaint)

        if (valueColor != null) {
            deltaPaint.color = valueColor
            canvas.drawText(value, centerX, valueY, deltaPaint)
        } else {
            canvas.drawText(value, centerX, valueY, valuePaint)
        }
    }

    private fun formatValue(value: Float): String {
        val absVal = kotlin.math.abs(value)
        return when {
            absVal >= 1000 -> String.format(Locale.US, "%.0f", value)
            absVal >= 100 -> String.format(Locale.US, "%.1f", value)
            absVal >= 10 -> String.format(Locale.US, "%.2f", value)
            absVal >= 1 -> String.format(Locale.US, "%.3f", value)
            else -> String.format(Locale.US, "%.4f", value)
        }
    }
}
