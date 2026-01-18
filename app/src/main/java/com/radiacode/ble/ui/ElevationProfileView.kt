package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import kotlin.math.abs

/**
 * Elevation Profile View
 * 
 * Shows elevation profile along with radiation readings.
 * Useful for detecting if radiation correlates with altitude (e.g., cosmic rays at higher elevations).
 */
class ElevationProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Data
    private val elevationData = mutableListOf<ElevationPoint>()
    private val maxPoints = 200
    
    // Paints
    private val elevationPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radiationPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    // Colors
    private val cyanColor = ContextCompat.getColor(context, R.color.pro_cyan)
    private val magentaColor = ContextCompat.getColor(context, R.color.pro_magenta)
    private val greenColor = ContextCompat.getColor(context, R.color.pro_green)
    private val mutedColor = ContextCompat.getColor(context, R.color.pro_text_muted)
    private val surfaceColor = ContextCompat.getColor(context, R.color.pro_surface)
    
    // Chart padding
    private val paddingLeft = 50f
    private val paddingRight = 50f
    private val paddingTop = 30f
    private val paddingBottom = 40f
    
    // Stats
    private var minElevation = 0.0
    private var maxElevation = 100.0
    private var currentElevation = 0.0
    private var elevationGain = 0.0
    private var elevationLoss = 0.0
    
    private var minDoseRate = 0f
    private var maxDoseRate = 1f
    private var avgDoseRate = 0f
    
    // Correlation
    private var elevationRadiationCorrelation = 0f

    init {
        elevationPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
            color = greenColor
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        
        radiationPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            color = magentaColor
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        
        gridPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.argb(50, 255, 255, 255)
        }
        
        textPaint.apply {
            textSize = 10f * resources.displayMetrics.density
            color = mutedColor
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
    }

    /**
     * Add a new elevation point with radiation reading.
     */
    fun addPoint(elevation: Double, doseRate: Float, distanceMeters: Double = 0.0) {
        val point = ElevationPoint(
            elevation = elevation,
            doseRate = doseRate,
            distanceMeters = if (elevationData.isEmpty()) 0.0 else {
                elevationData.last().distanceMeters + distanceMeters
            },
            timestampMs = System.currentTimeMillis()
        )
        
        // Track elevation change
        if (elevationData.isNotEmpty()) {
            val diff = elevation - elevationData.last().elevation
            if (diff > 0) elevationGain += diff
            else elevationLoss += abs(diff)
        }
        
        elevationData.add(point)
        currentElevation = elevation
        
        // Trim old data
        while (elevationData.size > maxPoints) {
            elevationData.removeAt(0)
        }
        
        recalculateStats()
        invalidate()
    }

    private fun recalculateStats() {
        if (elevationData.isEmpty()) return
        
        val elevations = elevationData.map { it.elevation }
        val doseRates = elevationData.map { it.doseRate }
        
        minElevation = (elevations.minOrNull() ?: 0.0) - 10
        maxElevation = (elevations.maxOrNull() ?: 100.0) + 10
        
        minDoseRate = (doseRates.minOrNull() ?: 0f) * 0.9f
        maxDoseRate = (doseRates.maxOrNull() ?: 1f) * 1.1f
        avgDoseRate = doseRates.average().toFloat()
        
        // Calculate correlation between elevation and dose rate
        if (elevationData.size >= 5) {
            elevationRadiationCorrelation = calculateCorrelation(
                elevations.map { it.toFloat() },
                doseRates
            )
        }
    }

    private fun calculateCorrelation(x: List<Float>, y: List<Float>): Float {
        val n = minOf(x.size, y.size)
        if (n < 2) return 0f
        
        val xMean = x.take(n).average().toFloat()
        val yMean = y.take(n).average().toFloat()
        
        var numerator = 0f
        var xSumSq = 0f
        var ySumSq = 0f
        
        for (i in 0 until n) {
            val xDiff = x[i] - xMean
            val yDiff = y[i] - yMean
            numerator += xDiff * yDiff
            xSumSq += xDiff * xDiff
            ySumSq += yDiff * yDiff
        }
        
        val denominator = kotlin.math.sqrt(xSumSq * ySumSq)
        return if (denominator > 0.0001f) numerator / denominator else 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (elevationData.size < 2) {
            drawEmptyState(canvas)
            return
        }
        
        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom
        
        // Draw background
        canvas.drawColor(surfaceColor)
        
        // Draw grid
        drawGrid(canvas, chartWidth, chartHeight)
        
        // Draw elevation profile
        drawElevationProfile(canvas, chartWidth, chartHeight)
        
        // Draw radiation overlay
        drawRadiationOverlay(canvas, chartWidth, chartHeight)
        
        // Draw axes labels
        drawAxesLabels(canvas, chartWidth, chartHeight)
        
        // Draw stats
        drawStats(canvas)
        
        // Draw correlation indicator
        drawCorrelation(canvas)
    }

    private fun drawEmptyState(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 14f * resources.displayMetrics.density
        textPaint.color = mutedColor
        canvas.drawText("Waiting for elevation data...", width / 2f, height / 2f, textPaint)
        textPaint.textSize = 11f * resources.displayMetrics.density
        canvas.drawText("Move to record elevation profile", width / 2f, height / 2f + 25.dp, textPaint)
    }

    private fun drawGrid(canvas: Canvas, chartWidth: Float, chartHeight: Float) {
        // Horizontal grid lines (elevation)
        val numHLines = 5
        for (i in 0..numHLines) {
            val y = paddingTop + chartHeight * i / numHLines
            canvas.drawLine(paddingLeft, y, paddingLeft + chartWidth, y, gridPaint)
        }
        
        // Vertical grid lines (distance/time)
        val numVLines = 6
        for (i in 0..numVLines) {
            val x = paddingLeft + chartWidth * i / numVLines
            canvas.drawLine(x, paddingTop, x, paddingTop + chartHeight, gridPaint)
        }
    }

    private fun drawElevationProfile(canvas: Canvas, chartWidth: Float, chartHeight: Float) {
        if (elevationData.size < 2) return
        
        val elevationPath = Path()
        val fillPath = Path()
        val elevationRange = maxElevation - minElevation
        
        elevationData.forEachIndexed { index, point ->
            val x = paddingLeft + (index.toFloat() / (elevationData.size - 1)) * chartWidth
            val normalizedElevation = (point.elevation - minElevation) / elevationRange
            val y = paddingTop + chartHeight * (1 - normalizedElevation).toFloat()
            
            if (index == 0) {
                elevationPath.moveTo(x, y)
                fillPath.moveTo(x, paddingTop + chartHeight)
                fillPath.lineTo(x, y)
            } else {
                elevationPath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        
        // Complete fill path
        fillPath.lineTo(paddingLeft + chartWidth, paddingTop + chartHeight)
        fillPath.close()
        
        // Draw fill gradient
        fillPaint.shader = LinearGradient(
            0f, paddingTop,
            0f, paddingTop + chartHeight,
            Color.argb(60, Color.red(greenColor), Color.green(greenColor), Color.blue(greenColor)),
            Color.argb(10, Color.red(greenColor), Color.green(greenColor), Color.blue(greenColor)),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null
        
        // Draw glow
        glowPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f * resources.displayMetrics.density
            color = greenColor
            maskFilter = BlurMaskFilter(6f * resources.displayMetrics.density, BlurMaskFilter.Blur.NORMAL)
            alpha = 80
        }
        canvas.drawPath(elevationPath, glowPaint)
        glowPaint.maskFilter = null
        
        // Draw line
        canvas.drawPath(elevationPath, elevationPaint)
    }

    private fun drawRadiationOverlay(canvas: Canvas, chartWidth: Float, chartHeight: Float) {
        if (elevationData.size < 2) return
        
        val radiationPath = Path()
        val doseRange = maxDoseRate - minDoseRate
        
        elevationData.forEachIndexed { index, point ->
            val x = paddingLeft + (index.toFloat() / (elevationData.size - 1)) * chartWidth
            val normalizedDose = if (doseRange > 0) (point.doseRate - minDoseRate) / doseRange else 0.5f
            val y = paddingTop + chartHeight * (1 - normalizedDose)
            
            if (index == 0) {
                radiationPath.moveTo(x, y)
            } else {
                radiationPath.lineTo(x, y)
            }
        }
        
        // Draw dashed line for radiation
        radiationPaint.pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
        canvas.drawPath(radiationPath, radiationPaint)
        radiationPaint.pathEffect = null
    }

    private fun drawAxesLabels(canvas: Canvas, chartWidth: Float, chartHeight: Float) {
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = greenColor
        
        // Elevation axis (left)
        val elevLabels = listOf(maxElevation, (maxElevation + minElevation) / 2, minElevation)
        elevLabels.forEachIndexed { index, elev ->
            val y = paddingTop + chartHeight * index / 2
            canvas.drawText("%.0fm".format(elev), paddingLeft - 5.dp, y + 4.dp, textPaint)
        }
        
        // Radiation axis (right)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = magentaColor
        
        val doseLabels = listOf(maxDoseRate, (maxDoseRate + minDoseRate) / 2, minDoseRate)
        doseLabels.forEachIndexed { index, dose ->
            val y = paddingTop + chartHeight * index / 2
            canvas.drawText("%.2f".format(dose), paddingLeft + chartWidth + 5.dp, y + 4.dp, textPaint)
        }
    }

    private fun drawStats(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 10f * resources.displayMetrics.density
        
        val statsY = paddingTop - 10.dp
        
        // Current elevation
        textPaint.color = greenColor
        canvas.drawText("▲ %.0fm".format(currentElevation), paddingLeft, statsY, textPaint)
        
        // Elevation change
        textPaint.color = mutedColor
        val changeText = "+%.0f/-%.0f m".format(elevationGain, elevationLoss)
        canvas.drawText(changeText, paddingLeft + 80.dp, statsY, textPaint)
        
        // Current dose rate
        textPaint.color = magentaColor
        val lastDose = elevationData.lastOrNull()?.doseRate ?: 0f
        canvas.drawText("☢ %.3f µSv/h".format(lastDose), width - paddingRight - 100.dp, statsY, textPaint)
    }

    private fun drawCorrelation(canvas: Canvas) {
        // Draw correlation indicator at bottom
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 11f * resources.displayMetrics.density
        
        val corrY = height - 10.dp
        
        val corrText = when {
            elevationData.size < 5 -> "Collecting data..."
            abs(elevationRadiationCorrelation) > 0.7f -> {
                if (elevationRadiationCorrelation > 0) "Strong positive correlation (r=%.2f) - higher altitude = more radiation"
                else "Strong negative correlation (r=%.2f)"
            }
            abs(elevationRadiationCorrelation) > 0.4f -> {
                if (elevationRadiationCorrelation > 0) "Moderate correlation (r=%.2f)"
                else "Moderate negative correlation (r=%.2f)"
            }
            else -> "Weak/no correlation (r=%.2f)"
        }.format(elevationRadiationCorrelation)
        
        textPaint.color = when {
            abs(elevationRadiationCorrelation) > 0.7f -> cyanColor
            abs(elevationRadiationCorrelation) > 0.4f -> Color.YELLOW
            else -> mutedColor
        }
        
        canvas.drawText(corrText, width / 2f, corrY, textPaint)
    }

    fun clearData() {
        elevationData.clear()
        elevationGain = 0.0
        elevationLoss = 0.0
        elevationRadiationCorrelation = 0f
        invalidate()
    }

    fun getData() = elevationData.toList()

    private val Int.dp: Float
        get() = this * resources.displayMetrics.density

    data class ElevationPoint(
        val elevation: Double,
        val doseRate: Float,
        val distanceMeters: Double,
        val timestampMs: Long
    )
}
