package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import java.util.Locale

/**
 * Session statistics summary view
 * Shows duration, sample count, averages, peaks, etc.
 */
class SessionSummaryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    data class SessionStats(
        val durationMs: Long,
        val sampleCount: Int,
        val doseMin: Float,
        val doseMax: Float,
        val doseAvg: Float,
        val dosePeak: Float,
        val cpsMin: Float,
        val cpsMax: Float,
        val cpsAvg: Float,
        val cpsPeak: Float,
        val doseUnit: String = "μSv/h",
        val cpsUnit: String = "cps"
    )

    private var stats: SessionStats? = null

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 10f
        color = ContextCompat.getColor(context, R.color.pro_text_muted)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.08f
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 14f
        color = ContextCompat.getColor(context, R.color.pro_text_primary)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 10f
        color = ContextCompat.getColor(context, R.color.pro_text_secondary)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

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
        alpha = 80
    }

    private val cyanColor = ContextCompat.getColor(context, R.color.pro_cyan)
    private val magentaColor = ContextCompat.getColor(context, R.color.pro_magenta)

    fun setStats(stats: SessionStats) {
        this.stats = stats
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (density * 180f).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = density * 12f
        val padding = density * 16f

        // Draw background
        val rect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        val s = stats ?: run {
            // Empty state
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("NO SESSION DATA", w / 2, h / 2, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            return
        }

        // Layout: 3 rows
        // Row 1: Duration | Samples
        // Row 2: Dose stats (Min/Avg/Max/Peak)
        // Row 3: CPS stats (Min/Avg/Max/Peak)

        val rowHeight = (h - padding * 2) / 3f
        var y = padding

        // === Row 1: Session info ===
        val halfW = w / 2f

        // Duration
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("DURATION", padding, y + labelPaint.textSize, labelPaint)
        val durationText = formatDuration(s.durationMs)
        canvas.drawText(durationText, padding, y + labelPaint.textSize + valuePaint.textSize + density * 4f, valuePaint)

        // Sample count
        canvas.drawText("SAMPLES", halfW + padding, y + labelPaint.textSize, labelPaint)
        canvas.drawText(s.sampleCount.toString(), halfW + padding, y + labelPaint.textSize + valuePaint.textSize + density * 4f, valuePaint)

        // Divider
        y += rowHeight
        canvas.drawLine(padding, y, w - padding, y, dividerPaint)

        // === Row 2: Dose stats ===
        y += density * 8f
        valuePaint.color = cyanColor
        canvas.drawText("DOSE", padding, y + labelPaint.textSize, labelPaint)
        y += labelPaint.textSize + density * 8f

        val colW = (w - padding * 2) / 4f
        drawStatCell(canvas, padding, y, "MIN", s.doseMin, s.doseUnit)
        drawStatCell(canvas, padding + colW, y, "AVG", s.doseAvg, s.doseUnit)
        drawStatCell(canvas, padding + colW * 2, y, "MAX", s.doseMax, s.doseUnit)
        drawStatCell(canvas, padding + colW * 3, y, "PEAK", s.dosePeak, s.doseUnit)

        // Divider
        y = padding + rowHeight * 2
        canvas.drawLine(padding, y, w - padding, y, dividerPaint)

        // === Row 3: CPS stats ===
        y += density * 8f
        valuePaint.color = magentaColor
        canvas.drawText("CPS", padding, y + labelPaint.textSize, labelPaint)
        y += labelPaint.textSize + density * 8f

        drawStatCell(canvas, padding, y, "MIN", s.cpsMin, s.cpsUnit)
        drawStatCell(canvas, padding + colW, y, "AVG", s.cpsAvg, s.cpsUnit)
        drawStatCell(canvas, padding + colW * 2, y, "MAX", s.cpsMax, s.cpsUnit)
        drawStatCell(canvas, padding + colW * 3, y, "PEAK", s.cpsPeak, s.cpsUnit)

        valuePaint.color = ContextCompat.getColor(context, R.color.pro_text_primary)
    }

    private fun drawStatCell(canvas: Canvas, x: Float, y: Float, label: String, value: Float, unit: String) {
        canvas.drawText(label, x, y, labelPaint)
        canvas.drawText(formatValue(value), x, y + valuePaint.textSize + density * 2f, valuePaint)
    }

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000L
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m ${sec % 60}s"
            else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
        }
    }

    private fun formatValue(value: Float): String {
        return when {
            value >= 1000 -> String.format(Locale.US, "%.0f", value)
            value >= 100 -> String.format(Locale.US, "%.1f", value)
            value >= 10 -> String.format(Locale.US, "%.2f", value)
            value >= 1 -> String.format(Locale.US, "%.3f", value)
            else -> String.format(Locale.US, "%.4f", value)
        }
    }
}
