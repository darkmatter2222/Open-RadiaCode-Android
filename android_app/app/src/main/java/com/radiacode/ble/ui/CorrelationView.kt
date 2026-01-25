package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import com.radiacode.ble.StatisticsCalculator
import kotlin.math.max
import kotlin.math.min

/**
 * Correlation View
 * Shows synchronized dose rate vs count rate with Pearson correlation coefficient.
 * Scientists expect these to correlate; deviations indicate spectral changes.
 */
class CorrelationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    // Data
    private var doseValues: List<Float> = emptyList()
    private var cpsValues: List<Float> = emptyList()
    private var timestamps: List<Long> = emptyList()
    private var correlationCoefficient: Float = 0f
    private var rSquared: Float = 0f

    // Colors
    private val cyanColor = ContextCompat.getColor(context, R.color.pro_cyan)
    private val magentaColor = ContextCompat.getColor(context, R.color.pro_magenta)
    private val greenColor = ContextCompat.getColor(context, R.color.pro_green)
    private val amberColor = ContextCompat.getColor(context, R.color.pro_amber)
    private val redColor = ContextCompat.getColor(context, R.color.pro_red)
    private val gridColor = ContextCompat.getColor(context, R.color.pro_border)
    private val textMuted = ContextCompat.getColor(context, R.color.pro_text_muted)
    private val textPrimary = ContextCompat.getColor(context, R.color.pro_text_primary)

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

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 0.5f
        color = gridColor
        alpha = 60
    }

    private val dosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        color = cyanColor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val cpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        color = magentaColor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val scatterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 10f
        color = textMuted
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.1f
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 11f
        color = textMuted
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.1f
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 20f
        color = textPrimary
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 9f
        color = textMuted
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val cornerRadius = density * 16f
    private val padding = density * 12f
    
    private val dosePath = Path()
    private val cpsPath = Path()
    private val cardRect = RectF()

    // Display mode
    enum class DisplayMode {
        DUAL_LINE,      // Two overlaid line charts (normalized)
        SCATTER_PLOT,   // Scatter plot with regression line
        BOTH            // Split view
    }
    private var displayMode = DisplayMode.DUAL_LINE

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Set the data for correlation analysis
     */
    fun setData(
        doseValues: List<Float>,
        cpsValues: List<Float>,
        timestamps: List<Long>
    ) {
        this.doseValues = doseValues
        this.cpsValues = cpsValues
        this.timestamps = timestamps
        
        // Calculate correlation
        if (doseValues.size >= 2 && cpsValues.size >= 2) {
            correlationCoefficient = StatisticsCalculator.calculateCorrelation(doseValues, cpsValues)
            rSquared = correlationCoefficient * correlationCoefficient
        } else {
            correlationCoefficient = 0f
            rSquared = 0f
        }
        
        invalidate()
    }

    /**
     * Set display mode
     */
    fun setDisplayMode(mode: DisplayMode) {
        displayMode = mode
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw card background
        cardRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

        if (doseValues.size < 2 || cpsValues.size < 2) {
            // Not enough data
            val text = "Need more data for correlation"
            val textWidth = labelPaint.measureText(text)
            canvas.drawText(text, (w - textWidth) / 2, h / 2, labelPaint)
            return
        }

        var y = padding + titlePaint.textSize
        
        // Title
        canvas.drawText("DOSE/COUNT CORRELATION", padding, y, titlePaint)
        
        // Correlation coefficient
        val corrText = String.format("r = %.3f", correlationCoefficient)
        val corrWidth = valuePaint.measureText(corrText)
        valuePaint.color = getCorrelationColor()
        canvas.drawText(corrText, w - padding - corrWidth, y + density * 4, valuePaint)
        
        // R² value
        y += labelPaint.textSize + density * 4
        val r2Text = String.format("R² = %.3f", rSquared)
        labelPaint.color = textMuted
        canvas.drawText(r2Text, w - padding - labelPaint.measureText(r2Text), y, labelPaint)
        
        // Correlation interpretation
        val interpretation = getCorrelationInterpretation()
        canvas.drawText(interpretation, padding, y, labelPaint)

        y += density * 12

        when (displayMode) {
            DisplayMode.DUAL_LINE -> drawDualLineChart(canvas, y, w, h)
            DisplayMode.SCATTER_PLOT -> drawScatterPlot(canvas, y, w, h)
            DisplayMode.BOTH -> {
                val midY = (y + h) / 2
                drawDualLineChart(canvas, y, w, midY - density * 8)
                drawScatterPlot(canvas, midY + density * 8, w, h)
            }
        }

        // Legend
        drawLegend(canvas, w, h)
    }

    private fun drawDualLineChart(canvas: Canvas, topY: Float, width: Float, bottomY: Float) {
        val chartLeft = padding + density * 30  // Room for Y axis
        val chartRight = width - padding
        val chartTop = topY + density * 8
        val chartBottom = bottomY - padding - density * 20
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        if (chartHeight <= 0 || chartWidth <= 0) return

        // Normalize both series to 0-1 for overlay
        val doseMin = doseValues.minOrNull() ?: 0f
        val doseMax = doseValues.maxOrNull() ?: 1f
        val doseRange = max(doseMax - doseMin, 0.0001f)
        
        val cpsMin = cpsValues.minOrNull() ?: 0f
        val cpsMax = cpsValues.maxOrNull() ?: 1f
        val cpsRange = max(cpsMax - cpsMin, 0.0001f)

        // Grid
        for (i in 0..4) {
            val y = chartTop + chartHeight * i / 4
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }

        // Dose line
        dosePath.reset()
        val minDataPoints = min(doseValues.size, cpsValues.size)
        for (i in 0 until minDataPoints) {
            val x = chartLeft + chartWidth * i / (minDataPoints - 1)
            val normalizedDose = (doseValues[i] - doseMin) / doseRange
            val y = chartBottom - chartHeight * normalizedDose
            
            if (i == 0) dosePath.moveTo(x, y) else dosePath.lineTo(x, y)
        }
        canvas.drawPath(dosePath, dosePaint)

        // CPS line
        cpsPath.reset()
        for (i in 0 until minDataPoints) {
            val x = chartLeft + chartWidth * i / (minDataPoints - 1)
            val normalizedCps = (cpsValues[i] - cpsMin) / cpsRange
            val y = chartBottom - chartHeight * normalizedCps
            
            if (i == 0) cpsPath.moveTo(x, y) else cpsPath.lineTo(x, y)
        }
        canvas.drawPath(cpsPath, cpsPaint)

        // Y axis labels
        axisPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("100%", chartLeft - density * 4, chartTop + axisPaint.textSize / 2, axisPaint)
        canvas.drawText("50%", chartLeft - density * 4, chartTop + chartHeight / 2 + axisPaint.textSize / 2, axisPaint)
        canvas.drawText("0%", chartLeft - density * 4, chartBottom + axisPaint.textSize / 2, axisPaint)
    }

    private fun drawScatterPlot(canvas: Canvas, topY: Float, width: Float, bottomY: Float) {
        val chartLeft = padding + density * 40
        val chartRight = width - padding - density * 10
        val chartTop = topY + density * 8
        val chartBottom = bottomY - padding - density * 30
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        if (chartHeight <= 0 || chartWidth <= 0) return

        val doseMin = doseValues.minOrNull() ?: 0f
        val doseMax = doseValues.maxOrNull() ?: 1f
        val doseRange = max(doseMax - doseMin, 0.0001f)
        
        val cpsMin = cpsValues.minOrNull() ?: 0f
        val cpsMax = cpsValues.maxOrNull() ?: 1f
        val cpsRange = max(cpsMax - cpsMin, 0.0001f)

        // Grid
        for (i in 0..4) {
            val x = chartLeft + chartWidth * i / 4
            val y = chartTop + chartHeight * i / 4
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
        }

        // Scatter points
        val minDataPoints = min(doseValues.size, cpsValues.size)
        scatterPaint.color = greenColor
        scatterPaint.alpha = 180
        
        for (i in 0 until minDataPoints) {
            val normalizedDose = (doseValues[i] - doseMin) / doseRange
            val normalizedCps = (cpsValues[i] - cpsMin) / cpsRange
            
            val x = chartLeft + chartWidth * normalizedDose
            val y = chartBottom - chartHeight * normalizedCps
            
            canvas.drawCircle(x, y, density * 3f, scatterPaint)
        }

        // Regression line (if good correlation)
        if (kotlin.math.abs(correlationCoefficient) > 0.3f) {
            val trend = StatisticsCalculator.calculateTrend(doseValues.zip(cpsValues) { d, c -> 
                (d - doseMin) / doseRange to (c - cpsMin) / cpsRange
            }.map { it.first })
            
            val regressionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = density * 1.5f
                color = amberColor
                alpha = 150
                pathEffect = DashPathEffect(floatArrayOf(density * 8, density * 4), 0f)
            }
            
            // Draw line from 0,0 to 1,1 (normalized)
            val startX = chartLeft
            val startY = chartBottom
            val endX = chartRight
            val endY = chartTop
            canvas.drawLine(startX, startY, endX, endY, regressionPaint)
        }

        // Axis labels
        axisPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Dose Rate →", (chartLeft + chartRight) / 2, chartBottom + density * 16, axisPaint)
        
        // Rotated Y label (simplified - just put at left)
        axisPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Count Rate ↑", chartLeft - density * 4, (chartTop + chartBottom) / 2, axisPaint)
    }

    private fun drawLegend(canvas: Canvas, w: Float, h: Float) {
        val legendY = h - padding - density * 4
        
        // Dose legend
        dosePaint.style = Paint.Style.FILL
        canvas.drawCircle(padding + density * 4, legendY - density * 4, density * 4, dosePaint)
        dosePaint.style = Paint.Style.STROKE
        
        labelPaint.color = cyanColor
        canvas.drawText("Dose", padding + density * 12, legendY, labelPaint)
        
        // CPS legend
        val cpsLegendX = padding + density * 60
        cpsPaint.style = Paint.Style.FILL
        canvas.drawCircle(cpsLegendX + density * 4, legendY - density * 4, density * 4, cpsPaint)
        cpsPaint.style = Paint.Style.STROKE
        
        labelPaint.color = magentaColor
        canvas.drawText("CPS", cpsLegendX + density * 12, legendY, labelPaint)
    }

    private fun getCorrelationColor(): Int {
        val absCorr = kotlin.math.abs(correlationCoefficient)
        return when {
            absCorr >= 0.9 -> greenColor
            absCorr >= 0.7 -> cyanColor
            absCorr >= 0.5 -> amberColor
            else -> redColor
        }
    }

    private fun getCorrelationInterpretation(): String {
        val absCorr = kotlin.math.abs(correlationCoefficient)
        val direction = if (correlationCoefficient >= 0) "positive" else "negative"
        
        return when {
            absCorr >= 0.9 -> "● Very strong $direction"
            absCorr >= 0.7 -> "● Strong $direction"
            absCorr >= 0.5 -> "◐ Moderate $direction"
            absCorr >= 0.3 -> "○ Weak $direction"
            else -> "○ No correlation"
        }
    }
}
