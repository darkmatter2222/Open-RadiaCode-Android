package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Professional-grade chart component with:
 * - Gradient fill under line
 * - Y-axis with auto-scaled labels
 * - X-axis with time labels
 * - Grid lines
 * - Threshold line (dashed)
 * - Peak marker
 * - Sticky tap marker (stays pinned as data updates)
 * - Delta spike markers (vertical lines at significant changes)
 * - Rolling average line (dotted)
 */
class ProChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Data - Primary series
    private var timestampsMs: List<Long> = emptyList()
    private var samples: List<Float> = emptyList()
    private var thresholdValue: Float = Float.NaN
    private var accentColor: Int = ContextCompat.getColor(context, R.color.pro_cyan)

    // Data - Secondary series (optional, for dual-axis mode)
    private var secondarySamples: List<Float> = emptyList()
    private var secondaryAccentColor: Int = ContextCompat.getColor(context, R.color.pro_magenta)
    private var secondaryLabel: String = ""

    // Interaction - Sticky marker
    private var selectedIndex: Int? = null
    private var stickyTimestampMs: Long? = null  // The timestamp the user pinned (survives data updates)
    private var isStickyMode = false

    // Delta spike detection
    private var deltaThresholdPercent: Float = 8f  // Spike if delta > 8% of current value
    private var spikeIndices: List<Int> = emptyList()

    // Rolling average
    private var rollingAvgSamples: List<Float> = emptyList()
    private var rollingAvgWindow: Int = 10  // Number of samples for rolling average

    // Dimensions (calculated in onSizeChanged)
    private var chartLeft = 0f
    private var chartTop = 0f
    private var chartRight = 0f
    private var chartBottom = 0f
    private var hasSecondaryAxis = false

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

    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        color = ContextCompat.getColor(context, R.color.pro_amber)
        pathEffect = DashPathEffect(floatArrayOf(density * 8f, density * 4f), 0f)
    }

    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = ContextCompat.getColor(context, R.color.pro_text_muted)
        alpha = 128
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pro_surface_elevated)
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 11f
        color = ContextCompat.getColor(context, R.color.pro_text_primary)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    // Delta spike marker paint
    private val spikePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        color = ContextCompat.getColor(context, R.color.pro_amber)
        alpha = 180
    }

    private val spikeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 6f
        color = ContextCompat.getColor(context, R.color.pro_amber)
        alpha = 50
    }

    // Rolling average paint (dotted line)
    private val rollingAvgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = ContextCompat.getColor(context, R.color.pro_text_muted)
        alpha = 200
        pathEffect = DashPathEffect(floatArrayOf(density * 4f, density * 4f), 0f)
    }

    // Sticky marker paint (brighter than regular crosshair)
    private val stickyMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = ContextCompat.getColor(context, R.color.pro_cyan)
        alpha = 200
    }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.US)

    // Gesture handling
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            // Long press enables sliding mode (clears on release)
            isStickyMode = false
            selectedIndex = pickIndexForX(e.x)
            invalidate()
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Tap toggles sticky marker
            val idx = pickIndexForX(e.x)
            if (idx != null) {
                if (isStickyMode && selectedIndex == idx) {
                    // Tapping same spot again clears it
                    clearStickyMarker()
                } else {
                    // Set sticky marker at this timestamp
                    isStickyMode = true
                    selectedIndex = idx
                    stickyTimestampMs = timestampsMs.getOrNull(idx)
                }
                invalidate()
            }
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Calculate chart bounds
        val yAxisWidth = density * 44f
        val rightAxisWidth = if (hasSecondaryAxis) density * 44f else density * 12f
        val xAxisHeight = density * 24f
        val padding = density * 12f

        chartLeft = yAxisWidth
        chartTop = padding
        chartRight = w - rightAxisWidth
        chartBottom = h - xAxisHeight
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        
        // Handle drag during long-press (non-sticky mode)
        if (!isStickyMode && (event.action == MotionEvent.ACTION_MOVE)) {
            selectedIndex = pickIndexForX(event.x)
            invalidate()
        }
        
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            // Only clear selection if NOT in sticky mode
            if (!isStickyMode && selectedIndex != null) {
                selectedIndex = null
                invalidate()
            }
        }
        return handled || super.onTouchEvent(event)
    }

    /** Clear the sticky marker */
    fun clearStickyMarker() {
        isStickyMode = false
        selectedIndex = null
        stickyTimestampMs = null
        invalidate()
    }

    /** Set the delta threshold for spike detection (percentage of value) */
    fun setDeltaThreshold(percent: Float) {
        deltaThresholdPercent = percent
        recalculateSpikes()
        invalidate()
    }

    /** Set the rolling average window size */
    fun setRollingAverageWindow(windowSize: Int) {
        rollingAvgWindow = windowSize.coerceAtLeast(2)
        recalculateRollingAverage()
        invalidate()
    }

    fun setAccentColor(color: Int) {
        accentColor = color
        linePaint.color = color
        peakPaint.color = color
        stickyMarkerPaint.color = color
        invalidate()
    }

    fun setSeries(timestampsMs: List<Long>, samples: List<Float>) {
        this.timestampsMs = timestampsMs
        this.samples = samples
        
        // Preserve sticky marker by finding closest timestamp
        if (isStickyMode && stickyTimestampMs != null) {
            selectedIndex = findIndexForTimestamp(stickyTimestampMs!!)
        } else if (!isStickyMode) {
            selectedIndex = null
        }
        
        // Recalculate derived data
        recalculateSpikes()
        recalculateRollingAverage()
        
        invalidate()
    }

    /** Find the index closest to a given timestamp */
    private fun findIndexForTimestamp(targetMs: Long): Int? {
        if (timestampsMs.isEmpty()) return null
        
        var bestIdx = 0
        var bestDiff = Long.MAX_VALUE
        
        for (i in timestampsMs.indices) {
            val diff = abs(timestampsMs[i] - targetMs)
            if (diff < bestDiff) {
                bestDiff = diff
                bestIdx = i
            }
        }
        
        // Only keep if within 5 seconds of original (data might scroll off)
        return if (bestDiff < 5000) bestIdx else null
    }

    /** Detect significant delta spikes */
    private fun recalculateSpikes() {
        if (samples.size < 3) {
            spikeIndices = emptyList()
            return
        }
        
        val spikes = mutableListOf<Int>()
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            
            // Avoid division by zero
            if (prev == 0f) continue
            
            val deltaPercent = abs((curr - prev) / prev) * 100f
            if (deltaPercent >= deltaThresholdPercent) {
                spikes.add(i)
            }
        }
        spikeIndices = spikes
    }

    /** Calculate rolling average */
    private fun recalculateRollingAverage() {
        if (samples.size < rollingAvgWindow) {
            rollingAvgSamples = emptyList()
            return
        }
        
        val avgList = mutableListOf<Float>()
        for (i in samples.indices) {
            val windowStart = max(0, i - rollingAvgWindow + 1)
            var sum = 0f
            for (j in windowStart..i) {
                sum += samples[j]
            }
            avgList.add(sum / (i - windowStart + 1))
        }
        rollingAvgSamples = avgList
    }

    fun setSecondarySeries(samples: List<Float>, color: Int, label: String = "") {
        this.secondarySamples = samples
        this.secondaryAccentColor = color
        this.secondaryLabel = label
        this.hasSecondaryAxis = samples.isNotEmpty()
        requestLayout() // Recalculate layout for right axis
        invalidate()
    }

    fun clearSecondarySeries() {
        this.secondarySamples = emptyList()
        this.hasSecondaryAxis = false
        requestLayout()
        invalidate()
    }

    fun setThreshold(threshold: Float?) {
        thresholdValue = threshold ?: Float.NaN
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (chartRight <= chartLeft || chartBottom <= chartTop) return

        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        // Calculate data range
        if (samples.size < 2) {
            drawEmptyState(canvas)
            return
        }

        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        var peakIndex = 0

        for (i in samples.indices) {
            val v = samples[i]
            if (v < minV) minV = v
            if (v > maxV) {
                maxV = v
                peakIndex = i
            }
        }

        // Include threshold in range
        if (thresholdValue.isFinite()) {
            minV = min(minV, thresholdValue)
            maxV = max(maxV, thresholdValue)
        }

        // Add padding to range
        val range = max(1e-6f, maxV - minV)
        val padding = range * 0.12f
        val yMin = minV - padding
        val yMax = maxV + padding
        val yRange = yMax - yMin

        // Draw grid and Y-axis
        drawGrid(canvas, yMin, yMax, chartHeight)

        // Draw X-axis time labels
        drawTimeAxis(canvas, chartWidth)

        // Create gradient for fill
        val gradient = LinearGradient(
            0f, chartTop, 0f, chartBottom,
            Color.argb(100, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = gradient
        linePaint.color = accentColor

        // Build paths
        val linePath = Path()
        val fillPath = Path()
        val n = samples.size

        for (i in 0 until n) {
            val x = chartLeft + chartWidth * (i.toFloat() / (n - 1))
            val yNorm = (samples[i] - yMin) / yRange
            val y = chartBottom - chartHeight * yNorm

            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, chartBottom)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(chartRight, chartBottom)
        fillPath.close()

        // Draw fill and line
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // Draw rolling average line (dotted)
        if (rollingAvgSamples.size == n) {
            drawRollingAverageLine(canvas, chartWidth, chartHeight, yMin, yRange, n)
        }

        // Draw threshold line
        if (thresholdValue.isFinite()) {
            val yNorm = (thresholdValue - yMin) / yRange
            val yPx = chartBottom - chartHeight * yNorm
            canvas.drawLine(chartLeft, yPx, chartRight, yPx, thresholdPaint)
        }

        // Draw delta spike markers (vertical lines at significant changes)
        drawSpikeMarkers(canvas, chartWidth, chartHeight, yMin, yRange, n)

        // Draw peak marker
        run {
            val x = chartLeft + chartWidth * (peakIndex.toFloat() / (n - 1))
            val yNorm = (samples[peakIndex] - yMin) / yRange
            val y = chartBottom - chartHeight * yNorm
            canvas.drawCircle(x, y, density * 5f, peakPaint)
        }

        // Draw secondary series if present
        if (secondarySamples.size >= 2 && secondarySamples.size == n) {
            drawSecondarySeries(canvas, chartWidth, chartHeight, n)
        }

        // Draw selection marker (sticky or regular crosshair)
        selectedIndex?.let { idx ->
            if (idx in samples.indices) {
                val x = chartLeft + chartWidth * (idx.toFloat() / (n - 1))
                val yNorm = (samples[idx] - yMin) / yRange
                val y = chartBottom - chartHeight * yNorm

                if (isStickyMode) {
                    // Sticky marker: brighter, with a pin indicator
                    drawStickyMarker(canvas, x, y, idx)
                } else {
                    // Regular crosshair
                    canvas.drawLine(x, chartTop, x, chartBottom, crosshairPaint)
                    canvas.drawCircle(x, y, density * 6f, peakPaint)
                    drawTooltip(canvas, x, y, idx)
                }
            }
        }
    }

    /** Draw rolling average as a dotted line */
    private fun drawRollingAverageLine(
        canvas: Canvas,
        chartWidth: Float,
        chartHeight: Float,
        yMin: Float,
        yRange: Float,
        n: Int
    ) {
        val avgPath = Path()
        for (i in 0 until n) {
            val x = chartLeft + chartWidth * (i.toFloat() / (n - 1))
            val yNorm = (rollingAvgSamples[i] - yMin) / yRange
            val y = chartBottom - chartHeight * yNorm

            if (i == 0) {
                avgPath.moveTo(x, y)
            } else {
                avgPath.lineTo(x, y)
            }
        }
        canvas.drawPath(avgPath, rollingAvgPaint)
    }

    /** Draw vertical spike markers at significant delta points */
    private fun drawSpikeMarkers(
        canvas: Canvas,
        chartWidth: Float,
        chartHeight: Float,
        yMin: Float,
        yRange: Float,
        n: Int
    ) {
        for (spikeIdx in spikeIndices) {
            if (spikeIdx !in samples.indices) continue
            if (spikeIdx == 0) continue  // Need previous sample
            
            val x = chartLeft + chartWidth * (spikeIdx.toFloat() / (n - 1))
            val yNorm = (samples[spikeIdx] - yMin) / yRange
            val y = chartBottom - chartHeight * yNorm
            
            val prev = samples[spikeIdx - 1]
            val curr = samples[spikeIdx]
            val deltaPercent = if (prev != 0f) ((curr - prev) / prev) * 100f else 0f
            val isIncrease = deltaPercent > 0

            // Draw full-height glow behind the spike line
            canvas.drawLine(x, chartTop, x, chartBottom, spikeGlowPaint)
            
            // Draw the spike marker line (full height)
            canvas.drawLine(x, chartTop, x, chartBottom, spikePaint)
            
            // Draw triangle indicator at the data point
            drawSpikeIndicator(canvas, x, y, isIncrease)
            
            // Draw percentage annotation at top
            drawSpikeAnnotation(canvas, x, deltaPercent)
        }
    }

    /** Draw percentage annotation for spike */
    private fun drawSpikeAnnotation(canvas: Canvas, x: Float, deltaPercent: Float) {
        val sign = if (deltaPercent > 0) "+" else ""
        val text = "$sign${String.format(Locale.US, "%.0f", deltaPercent)}%"
        
        val annotationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textSize = scaledDensity * 9f
            color = ContextCompat.getColor(context, R.color.pro_amber)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        
        // Background pill
        val textWidth = annotationPaint.measureText(text)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.pro_surface_elevated)
        }
        val bgRect = RectF(
            x - textWidth / 2 - density * 4f,
            chartTop + density * 2f,
            x + textWidth / 2 + density * 4f,
            chartTop + density * 2f + scaledDensity * 12f
        )
        canvas.drawRoundRect(bgRect, density * 4f, density * 4f, bgPaint)
        
        // Text
        canvas.drawText(text, x, chartTop + density * 2f + scaledDensity * 9f, annotationPaint)
    }

    /** Draw a small triangle indicating spike direction */
    private fun drawSpikeIndicator(canvas: Canvas, x: Float, y: Float, isIncrease: Boolean) {
        val size = density * 5f
        val trianglePath = Path()
        
        if (isIncrease) {
            // Upward triangle (increase)
            trianglePath.moveTo(x, y - size)
            trianglePath.lineTo(x - size, y + size / 2)
            trianglePath.lineTo(x + size, y + size / 2)
        } else {
            // Downward triangle (decrease)
            trianglePath.moveTo(x, y + size)
            trianglePath.lineTo(x - size, y - size / 2)
            trianglePath.lineTo(x + size, y - size / 2)
        }
        trianglePath.close()
        
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.pro_amber)
        }
        canvas.drawPath(trianglePath, fillPaint)
    }

    /** Draw sticky marker with pin indicator and persistent tooltip */
    private fun drawStickyMarker(canvas: Canvas, x: Float, y: Float, idx: Int) {
        // Vertical line with accent color
        canvas.drawLine(x, chartTop, x, chartBottom, stickyMarkerPaint)
        
        // Larger dot
        canvas.drawCircle(x, y, density * 7f, peakPaint)
        
        // Inner ring
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 2f
            color = ContextCompat.getColor(context, R.color.pro_background)
        }
        canvas.drawCircle(x, y, density * 4f, ringPaint)
        
        // Pin indicator at top
        drawPinIcon(canvas, x, chartTop + density * 8f)
        
        // Tooltip (always visible for sticky)
        drawTooltip(canvas, x, y, idx)
    }

    /** Draw a small pin icon to indicate sticky marker */
    private fun drawPinIcon(canvas: Canvas, x: Float, y: Float) {
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = accentColor
        }
        
        // Pin head (circle)
        canvas.drawCircle(x, y, density * 4f, pinPaint)
        
        // Pin needle (line down)
        val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 2f
            color = accentColor
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(x, y + density * 4f, x, y + density * 10f, needlePaint)
    }

    private fun drawEmptyState(canvas: Canvas) {
        val centerX = (chartLeft + chartRight) / 2
        val centerY = (chartTop + chartBottom) / 2
        axisTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Waiting for data…", centerX, centerY, axisTextPaint)
        axisTextPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawGrid(canvas: Canvas, yMin: Float, yMax: Float, chartHeight: Float) {
        val gridLines = 4
        val yRange = yMax - yMin

        axisTextPaint.textAlign = Paint.Align.RIGHT

        for (i in 0..gridLines) {
            val frac = i.toFloat() / gridLines
            val y = chartBottom - chartHeight * frac
            val value = yMin + yRange * frac

            // Grid line
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)

            // Y-axis label
            val label = formatValue(value)
            canvas.drawText(label, chartLeft - density * 6f, y + axisTextPaint.textSize / 3f, axisTextPaint)
        }

        axisTextPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawTimeAxis(canvas: Canvas, chartWidth: Float) {
        if (timestampsMs.size < 2) return

        val n = timestampsMs.size
        val labelCount = 4
        axisTextPaint.textAlign = Paint.Align.CENTER

        for (i in 0..labelCount) {
            val frac = i.toFloat() / labelCount
            val x = chartLeft + chartWidth * frac
            val idx = ((n - 1) * frac).toInt().coerceIn(0, n - 1)
            val ts = timestampsMs[idx]

            val time = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime()
            val label = timeFmt.format(time)

            canvas.drawText(label, x, chartBottom + density * 16f, axisTextPaint)
        }

        axisTextPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawTooltip(canvas: Canvas, x: Float, y: Float, idx: Int) {
        val value = samples[idx]
        val ts = timestampsMs.getOrNull(idx)

        val valueText = formatValue(value)
        val timeText = if (ts != null) {
            val time = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime()
            timeFmt.format(time)
        } else ""

        val text = if (timeText.isNotEmpty()) "$valueText @ $timeText" else valueText

        val textWidth = tooltipTextPaint.measureText(text)
        val textHeight = tooltipTextPaint.textSize
        val padH = density * 10f
        val padV = density * 6f

        // Position tooltip above the point, but keep on screen
        var tooltipX = x - textWidth / 2 - padH
        var tooltipY = y - textHeight - padV * 3

        if (tooltipX < chartLeft) tooltipX = chartLeft
        if (tooltipX + textWidth + padH * 2 > chartRight) tooltipX = chartRight - textWidth - padH * 2
        if (tooltipY < chartTop) tooltipY = chartTop

        val rect = RectF(
            tooltipX,
            tooltipY,
            tooltipX + textWidth + padH * 2,
            tooltipY + textHeight + padV * 2
        )

        canvas.drawRoundRect(rect, density * 6f, density * 6f, tooltipBgPaint)
        canvas.drawText(text, rect.left + padH, rect.top + padV + textHeight * 0.85f, tooltipTextPaint)
    }

    private fun drawSecondarySeries(canvas: Canvas, chartWidth: Float, chartHeight: Float, n: Int) {
        // Calculate range for secondary series
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (v in secondarySamples) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }

        val range = max(1e-6f, maxV - minV)
        val padding = range * 0.12f
        val yMin = minV - padding
        val yMax = maxV + padding
        val yRange = yMax - yMin

        // Draw right Y-axis labels
        val gridLines = 4
        axisTextPaint.textAlign = Paint.Align.LEFT
        axisTextPaint.color = secondaryAccentColor

        for (i in 0..gridLines) {
            val frac = i.toFloat() / gridLines
            val y = chartBottom - chartHeight * frac
            val value = yMin + yRange * frac
            val label = formatValue(value)
            canvas.drawText(label, chartRight + density * 6f, y + axisTextPaint.textSize / 3f, axisTextPaint)
        }

        axisTextPaint.color = ContextCompat.getColor(context, R.color.pro_text_muted)
        axisTextPaint.textAlign = Paint.Align.LEFT

        // Draw secondary line (no fill, just line for clarity)
        val secondaryLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = secondaryAccentColor
            alpha = 200
        }

        val path = Path()
        for (i in 0 until n) {
            val x = chartLeft + chartWidth * (i.toFloat() / (n - 1))
            val yNorm = (secondarySamples[i] - yMin) / yRange
            val y = chartBottom - chartHeight * yNorm

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, secondaryLinePaint)
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

    private fun pickIndexForX(xPx: Float): Int? {
        val n = samples.size
        if (n < 2) return null

        val chartWidth = chartRight - chartLeft
        if (chartWidth <= 0f) return null

        val frac = ((xPx - chartLeft) / chartWidth).coerceIn(0f, 1f)
        return (frac * (n - 1)).toInt().coerceIn(0, n - 1)
    }
}
