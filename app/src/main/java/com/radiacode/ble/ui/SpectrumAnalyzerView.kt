package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Advanced Spectrum Analyzer View
 * Deep-dive spectrum visualization with zoom, pan, and isotope markers
 */
class SpectrumAnalyzerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Colors - Pro Dark Theme
    private val colorBackground = Color.parseColor("#0D0D0F")
    private val colorSurface = Color.parseColor("#1A1A1E")
    private val colorBorder = Color.parseColor("#2A2A2E")
    private val colorMuted = Color.parseColor("#6E6E78")
    private val colorText = Color.parseColor("#9E9EA8")
    private val colorCyan = Color.parseColor("#00E5FF")
    private val colorMagenta = Color.parseColor("#E040FB")
    private val colorGreen = Color.parseColor("#69F0AE")
    private val colorYellow = Color.parseColor("#FFD600")
    private val colorRed = Color.parseColor("#FF5252")

    // Data
    private var spectrumData: IntArray = IntArray(0)
    private var energyCalibration = Triple(0f, 0f, 0f) // a0, a1, a2 coefficients
    private var maxCount = 1
    
    // Isotope markers
    data class IsotopeMarker(
        val name: String,
        val energy: Float, // keV
        val color: Int,
        val confidence: Float = 0f
    )
    private val isotopeMarkers = mutableListOf<IsotopeMarker>()

    // View state
    private var viewMode = ViewMode.LINEAR
    private var showIsotopes = true
    private var showGrid = true
    private var showStats = true

    // Zoom & Pan
    private var scaleX = 1f
    private var offsetX = 0f
    private var minEnergy = 0f
    private var maxEnergy = 3000f // keV

    // Chart bounds
    private val chartRect = RectF()
    private val padding = 60f
    private val topPadding = 40f

    // Paints
    private val backgroundPaint = Paint().apply {
        color = colorBackground
        style = Paint.Style.FILL
    }

    private val surfacePaint = Paint().apply {
        color = colorSurface
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = colorBorder
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1E")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }

    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorCyan
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val spectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorMuted
        textSize = 20f
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
    }

    // Gesture detectors
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val focusX = detector.focusX
            
            // Calculate energy at focus point before scale
            val focusEnergy = screenToEnergy(focusX)
            
            // Apply scale
            scaleX *= scaleFactor
            scaleX = scaleX.coerceIn(0.5f, 10f)
            
            // Adjust offset to keep focus point stationary
            val newFocusScreen = energyToScreen(focusEnergy)
            offsetX += (focusX - newFocusScreen)
            
            clampOffset()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            offsetX -= distanceX
            clampOffset()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Reset zoom on double tap
            scaleX = 1f
            offsetX = 0f
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Show energy at tap point
            val energy = screenToEnergy(e.x)
            val channel = energyToChannel(energy)
            if (channel in spectrumData.indices) {
                val count = spectrumData[channel]
                onTapListener?.invoke(energy, count)
            }
            return true
        }
    })

    var onTapListener: ((energy: Float, count: Int) -> Unit)? = null

    enum class ViewMode {
        LINEAR, LOG, SQRT
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateChartBounds()
    }

    private fun updateChartBounds() {
        chartRect.set(
            padding,
            topPadding,
            width - padding / 2,
            height - padding
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    fun setSpectrum(data: IntArray, a0: Float = 0f, a1: Float = 3f, a2: Float = 0f) {
        spectrumData = data.clone()
        energyCalibration = Triple(a0, a1, a2)
        maxCount = spectrumData.maxOrNull()?.coerceAtLeast(1) ?: 1
        maxEnergy = channelToEnergy(spectrumData.size.toFloat())
        invalidate()
    }

    fun setIsotopeMarkers(markers: List<IsotopeMarker>) {
        isotopeMarkers.clear()
        isotopeMarkers.addAll(markers)
        invalidate()
    }

    fun addCommonIsotopeMarkers() {
        isotopeMarkers.clear()
        // Common isotopes with their primary gamma energies
        isotopeMarkers.add(IsotopeMarker("K-40", 1461f, colorGreen))
        isotopeMarkers.add(IsotopeMarker("Ra-226", 186f, colorYellow))
        isotopeMarkers.add(IsotopeMarker("Bi-214", 609f, colorYellow))
        isotopeMarkers.add(IsotopeMarker("Bi-214", 1120f, colorYellow))
        isotopeMarkers.add(IsotopeMarker("Pb-214", 352f, colorYellow))
        isotopeMarkers.add(IsotopeMarker("Tl-208", 583f, colorMagenta))
        isotopeMarkers.add(IsotopeMarker("Tl-208", 2614f, colorMagenta))
        isotopeMarkers.add(IsotopeMarker("Cs-137", 662f, colorRed))
        isotopeMarkers.add(IsotopeMarker("Co-60", 1173f, colorCyan))
        isotopeMarkers.add(IsotopeMarker("Co-60", 1332f, colorCyan))
        invalidate()
    }

    fun setViewMode(mode: ViewMode) {
        viewMode = mode
        invalidate()
    }

    fun setShowIsotopes(show: Boolean) {
        showIsotopes = show
        invalidate()
    }

    fun setShowGrid(show: Boolean) {
        showGrid = show
        invalidate()
    }

    fun resetZoom() {
        scaleX = 1f
        offsetX = 0f
        invalidate()
    }

    private fun clampOffset() {
        val visibleWidth = chartRect.width() * scaleX
        val maxOffset = visibleWidth - chartRect.width()
        offsetX = offsetX.coerceIn(-maxOffset, 0f)
    }

    // Energy/channel conversions using calibration
    private fun channelToEnergy(channel: Float): Float {
        val (a0, a1, a2) = energyCalibration
        return a0 + a1 * channel + a2 * channel * channel
    }

    private fun energyToChannel(energy: Float): Int {
        // Inverse of quadratic: solve a2*x^2 + a1*x + (a0 - energy) = 0
        val (a0, a1, a2) = energyCalibration
        return if (a2 != 0f) {
            val discriminant = a1 * a1 - 4 * a2 * (a0 - energy)
            if (discriminant >= 0) {
                ((-a1 + kotlin.math.sqrt(discriminant)) / (2 * a2)).toInt()
            } else {
                ((energy - a0) / a1).toInt()
            }
        } else if (a1 != 0f) {
            ((energy - a0) / a1).toInt()
        } else {
            0
        }
    }

    private fun energyToScreen(energy: Float): Float {
        val normalized = (energy - minEnergy) / (maxEnergy - minEnergy)
        return chartRect.left + normalized * chartRect.width() * scaleX + offsetX
    }

    private fun screenToEnergy(screenX: Float): Float {
        val normalized = (screenX - chartRect.left - offsetX) / (chartRect.width() * scaleX)
        return minEnergy + normalized * (maxEnergy - minEnergy)
    }

    private fun countToScreen(count: Int): Float {
        val value = when (viewMode) {
            ViewMode.LINEAR -> count.toFloat() / maxCount
            ViewMode.LOG -> if (count > 0) kotlin.math.log10(count.toFloat() + 1) / kotlin.math.log10(maxCount.toFloat() + 1) else 0f
            ViewMode.SQRT -> kotlin.math.sqrt(count.toFloat()) / kotlin.math.sqrt(maxCount.toFloat())
        }
        return chartRect.bottom - value * chartRect.height()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Chart area
        canvas.drawRoundRect(chartRect, 8f, 8f, surfacePaint)
        canvas.drawRoundRect(chartRect, 8f, 8f, borderPaint)

        // Clip to chart area for spectrum drawing
        canvas.save()
        canvas.clipRect(chartRect)

        // Grid
        if (showGrid) {
            drawGrid(canvas)
        }

        // Spectrum
        if (spectrumData.isNotEmpty()) {
            drawSpectrum(canvas)
        }

        // Isotope markers
        if (showIsotopes && isotopeMarkers.isNotEmpty()) {
            drawIsotopeMarkers(canvas)
        }

        canvas.restore()

        // Axes labels
        drawAxesLabels(canvas)

        // Stats overlay
        if (showStats) {
            drawStats(canvas)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        // Vertical lines (energy)
        val energyStep = calculateGridStep(maxEnergy - minEnergy)
        var energy = (minEnergy / energyStep).toInt() * energyStep
        while (energy <= maxEnergy) {
            val x = energyToScreen(energy.toFloat())
            if (x in chartRect.left..chartRect.right) {
                canvas.drawLine(x, chartRect.top, x, chartRect.bottom, gridPaint)
            }
            energy += energyStep
        }

        // Horizontal lines (counts)
        for (i in 1..4) {
            val y = chartRect.top + chartRect.height() * i / 5
            canvas.drawLine(chartRect.left, y, chartRect.right, y, gridPaint)
        }
    }

    private fun calculateGridStep(range: Float): Int {
        val rawStep = range / 10
        return when {
            rawStep > 500 -> 500
            rawStep > 200 -> 200
            rawStep > 100 -> 100
            rawStep > 50 -> 50
            rawStep > 20 -> 20
            rawStep > 10 -> 10
            else -> 5
        }
    }

    private fun drawSpectrum(canvas: Canvas) {
        // Create spectrum path
        val path = Path()
        val fillPath = Path()

        var started = false
        for (i in spectrumData.indices) {
            val energy = channelToEnergy(i.toFloat())
            val x = energyToScreen(energy)
            val y = countToScreen(spectrumData[i])

            if (x < chartRect.left - 10) continue
            if (x > chartRect.right + 10) break

            if (!started) {
                path.moveTo(x, y)
                fillPath.moveTo(x, chartRect.bottom)
                fillPath.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Close fill path
        if (started) {
            fillPath.lineTo(energyToScreen(channelToEnergy(spectrumData.size.toFloat())), chartRect.bottom)
            fillPath.close()

            // Draw gradient fill
            spectrumFillPaint.shader = LinearGradient(
                0f, chartRect.top,
                0f, chartRect.bottom,
                Color.argb(60, 0, 229, 255),
                Color.argb(0, 0, 229, 255),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, spectrumFillPaint)

            // Draw line
            canvas.drawPath(path, spectrumPaint)
        }
    }

    private fun drawIsotopeMarkers(canvas: Canvas) {
        for (marker in isotopeMarkers) {
            val x = energyToScreen(marker.energy)
            if (x !in chartRect.left..chartRect.right) continue

            markerPaint.color = marker.color
            markerTextPaint.color = marker.color

            // Vertical line
            canvas.drawLine(x, chartRect.top, x, chartRect.bottom, markerPaint)

            // Label at top
            val labelWidth = markerTextPaint.measureText(marker.name)
            val labelX = (x - labelWidth / 2).coerceIn(chartRect.left, chartRect.right - labelWidth)
            canvas.drawText(marker.name, labelX, chartRect.top + 18f, markerTextPaint)

            // Energy label
            val energyText = "${marker.energy.toInt()} keV"
            val energyWidth = labelPaint.measureText(energyText)
            canvas.drawText(energyText, x - energyWidth / 2, chartRect.top + 36f, labelPaint)
        }
    }

    private fun drawAxesLabels(canvas: Canvas) {
        // X-axis (Energy)
        val energyStep = calculateGridStep((maxEnergy - minEnergy) / scaleX)
        var energy = (screenToEnergy(chartRect.left) / energyStep).toInt() * energyStep
        while (true) {
            val x = energyToScreen(energy.toFloat())
            if (x > chartRect.right + 50) break
            if (x >= chartRect.left - 20) {
                val text = energy.toString()
                val textWidth = labelPaint.measureText(text)
                canvas.drawText(text, x - textWidth / 2, chartRect.bottom + 20f, labelPaint)
            }
            energy += energyStep
        }

        // X-axis title
        val xTitle = "Energy (keV)"
        val xTitleWidth = textPaint.measureText(xTitle)
        canvas.drawText(xTitle, chartRect.centerX() - xTitleWidth / 2, height - 10f, textPaint)

        // Y-axis labels
        val modeLabel = when (viewMode) {
            ViewMode.LINEAR -> "Counts"
            ViewMode.LOG -> "Log(Counts)"
            ViewMode.SQRT -> "√Counts"
        }

        // Rotate for Y-axis title
        canvas.save()
        canvas.rotate(-90f, 20f, chartRect.centerY())
        canvas.drawText(modeLabel, 20f, chartRect.centerY(), textPaint)
        canvas.restore()
    }

    private fun drawStats(canvas: Canvas) {
        if (spectrumData.isEmpty()) return

        val totalCounts = spectrumData.sum().toLong()
        val statsText = listOf(
            "Channels: ${spectrumData.size}",
            "Total: ${formatCount(totalCounts)}",
            "Max: ${formatCount(maxCount.toLong())}",
            "Zoom: ${String.format("%.1f", scaleX)}x"
        )

        val boxWidth = 140f
        val boxHeight = statsText.size * 22f + 16f
        val boxLeft = chartRect.right - boxWidth - 8f
        val boxTop = chartRect.top + 8f

        // Background
        val boxRect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        val bgPaint = Paint().apply {
            color = Color.argb(200, 26, 26, 30)
        }
        canvas.drawRoundRect(boxRect, 6f, 6f, bgPaint)
        canvas.drawRoundRect(boxRect, 6f, 6f, borderPaint)

        // Text
        var y = boxTop + 20f
        for (line in statsText) {
            canvas.drawText(line, boxLeft + 8f, y, labelPaint)
            y += 22f
        }
    }

    private fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
