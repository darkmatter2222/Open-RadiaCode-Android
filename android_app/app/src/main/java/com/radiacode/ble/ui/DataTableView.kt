package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Data table view showing recent readings in a grid format
 * Columns: Time | Dose | CPS | Δ Dose | Δ CPS
 */
class DataTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    data class Reading(
        val timestampMs: Long,
        val dose: Float,
        val cps: Float,
        val doseUnit: String = "μSv/h",
        val cpsUnit: String = "cps"
    )

    private var readings: List<Reading> = emptyList()
    private var maxVisibleRows = 10

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 10f
        color = ContextCompat.getColor(context, R.color.pro_text_muted)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.05f
    }

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 12f
        color = ContextCompat.getColor(context, R.color.pro_text_primary)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val deltaPaintPositive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 11f
        color = ContextCompat.getColor(context, R.color.pro_green)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val deltaPaintNegative = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 11f
        color = ContextCompat.getColor(context, R.color.pro_red)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val rowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pro_surface)
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = ContextCompat.getColor(context, R.color.pro_border)
        alpha = 80
    }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.US)

    private val headers = listOf("TIME", "DOSE", "CPS", "Δ DOSE", "Δ CPS")
    private val colWeights = listOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f)

    fun setReadings(readings: List<Reading>) {
        this.readings = readings.takeLast(maxVisibleRows)
        invalidate()
    }

    fun setMaxVisibleRows(max: Int) {
        this.maxVisibleRows = max
        this.readings = readings.takeLast(max)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rowHeight = density * 32f
        val headerHeight = density * 28f
        val rows = minOf(readings.size, maxVisibleRows)
        val desiredHeight = (headerHeight + rowHeight * rows).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val rowHeight = density * 32f
        val headerHeight = density * 28f
        val padding = density * 8f

        // Calculate column positions
        val colWidths = colWeights.map { it * w }
        val colX = mutableListOf(0f)
        for (i in 0 until colWidths.size - 1) {
            colX.add(colX.last() + colWidths[i])
        }

        // Draw header row
        headerPaint.textAlign = Paint.Align.LEFT
        for (i in headers.indices) {
            val x = colX[i] + padding
            canvas.drawText(headers[i], x, headerHeight / 2 + headerPaint.textSize / 3f, headerPaint)
        }

        // Draw header divider
        canvas.drawLine(0f, headerHeight, w, headerHeight, dividerPaint)

        // Draw data rows (newest at top)
        val displayReadings = readings.reversed()
        for (rowIdx in displayReadings.indices) {
            if (rowIdx >= maxVisibleRows) break

            val reading = displayReadings[rowIdx]
            val prevReading = displayReadings.getOrNull(rowIdx + 1)

            val y = headerHeight + rowHeight * rowIdx

            // Alternating row background
            if (rowIdx % 2 == 1) {
                canvas.drawRect(0f, y, w, y + rowHeight, rowBgPaint)
            }

            val textY = y + rowHeight / 2 + cellPaint.textSize / 3f

            // Time
            val time = Instant.ofEpochMilli(reading.timestampMs)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
            canvas.drawText(timeFmt.format(time), colX[0] + padding, textY, cellPaint)

            // Dose
            val doseText = formatValue(reading.dose)
            canvas.drawText(doseText, colX[1] + padding, textY, cellPaint)

            // CPS
            val cpsText = formatValue(reading.cps)
            canvas.drawText(cpsText, colX[2] + padding, textY, cellPaint)

            // Delta Dose
            if (prevReading != null) {
                val deltaDose = reading.dose - prevReading.dose
                val paint = if (deltaDose >= 0) deltaPaintPositive else deltaPaintNegative
                val prefix = if (deltaDose >= 0) "+" else ""
                canvas.drawText(prefix + formatValue(deltaDose), colX[3] + padding, textY, paint)

                // Delta CPS
                val deltaCps = reading.cps - prevReading.cps
                val cpsPaint = if (deltaCps >= 0) deltaPaintPositive else deltaPaintNegative
                val cpsPrefix = if (deltaCps >= 0) "+" else ""
                canvas.drawText(cpsPrefix + formatValue(deltaCps), colX[4] + padding, textY, cpsPaint)
            }

            // Row divider
            if (rowIdx < displayReadings.size - 1) {
                canvas.drawLine(0f, y + rowHeight, w, y + rowHeight, dividerPaint)
            }
        }
    }

    private fun formatValue(value: Float): String {
        return when {
            kotlin.math.abs(value) >= 1000 -> String.format(Locale.US, "%.0f", value)
            kotlin.math.abs(value) >= 100 -> String.format(Locale.US, "%.1f", value)
            kotlin.math.abs(value) >= 10 -> String.format(Locale.US, "%.2f", value)
            kotlin.math.abs(value) >= 1 -> String.format(Locale.US, "%.3f", value)
            else -> String.format(Locale.US, "%.4f", value)
        }
    }
}
