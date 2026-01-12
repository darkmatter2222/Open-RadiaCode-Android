package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
 * - Pinch-to-zoom and pan support
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

    // Zoom and Pan state
    private var zoomLevel: Float = 1f           // 1.0 = no zoom, 2.0 = 2x zoom
    private var panOffset: Float = 0f           // 0.0 = start, 1.0 = end (normalized)
    private var isZoomPanEnabled: Boolean = true
    private var isPanning = false
    private var lastPanX = 0f
    
    // Display options
    private var showSpikeMarkers: Boolean = true

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

    // Delta spike marker paints (dotted, semi-transparent for subtlety - alarms will be more prominent)
    private val spikeGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = ContextCompat.getColor(context, R.color.pro_green)
        alpha = 120
        pathEffect = DashPathEffect(floatArrayOf(density * 4f, density * 4f), 0f)
    }

    private val spikeRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = ContextCompat.getColor(context, R.color.pro_red)
        alpha = 120
        pathEffect = DashPathEffect(floatArrayOf(density * 4f, density * 4f), 0f)
    }

    private val spikeGlowGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 4f
        color = ContextCompat.getColor(context, R.color.pro_green)
        alpha = 30
    }

    private val spikeGlowRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 4f
        color = ContextCompat.getColor(context, R.color.pro_red)
        alpha = 30
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

    // Scale gesture detector for pinch-zoom
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!isZoomPanEnabled) return false
            
            val scaleFactor = detector.scaleFactor
            val newZoom = (zoomLevel * scaleFactor).coerceIn(1f, 10f)
            
            // Adjust pan offset to keep the zoom centered on the focal point
            val chartWidth = chartRight - chartLeft
            if (chartWidth > 0) {
                val focalX = detector.focusX
                val focalFrac = ((focalX - chartLeft) / chartWidth).coerceIn(0f, 1f)
                
                // Calculate the data fraction at focal point before zoom
                val visibleFrac = 1f / zoomLevel
                val dataFracAtFocal = panOffset + focalFrac * visibleFrac
                
                // Update zoom
                zoomLevel = newZoom
                
                // Recalculate pan to keep focal point at same data position
                val newVisibleFrac = 1f / zoomLevel
                panOffset = (dataFracAtFocal - focalFrac * newVisibleFrac).coerceIn(0f, 1f - newVisibleFrac)
            } else {
                zoomLevel = newZoom
            }
            
            invalidate()
            return true
        }
    })

    // Gesture handling
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!isZoomPanEnabled || zoomLevel <= 1f) return false
            
            // Pan the view
            val chartWidth = chartRight - chartLeft
            if (chartWidth > 0 && samples.size > 1) {
                val visibleFrac = 1f / zoomLevel
                val panDelta = distanceX / chartWidth * visibleFrac
                panOffset = (panOffset + panDelta).coerceIn(0f, 1f - visibleFrac)
                invalidate()
                return true
            }
            return false
        }

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
        
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double-tap to reset zoom
            if (zoomLevel > 1f) {
                zoomLevel = 1f
                panOffset = 0f
                invalidate()
                return true
            }
            return false
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
        // Handle scale gestures first
        var handled = scaleGestureDetector.onTouchEvent(event)
        
        // Only handle other gestures if not scaling
        if (!scaleGestureDetector.isInProgress) {
            handled = gestureDetector.onTouchEvent(event) || handled
            
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

    /** Enable or disable zoom and pan gestures */
    fun setZoomPanEnabled(enabled: Boolean) {
        isZoomPanEnabled = enabled
        if (!enabled) {
            zoomLevel = 1f
            panOffset = 0f
            invalidate()
        }
    }

    /** Reset zoom to default (1x) */
    fun resetZoom() {
        zoomLevel = 1f
        panOffset = 0f
        invalidate()
    }

    /** Get current zoom level */
    fun getZoomLevel(): Float = zoomLevel
    
    /** Enable or disable spike markers (dotted lines with percentage) */
    fun setShowSpikeMarkers(show: Boolean) {
        showSpikeMarkers = show
        invalidate()
    }

    /** Get visible data range as [startIndex, endIndex] */
    private fun getVisibleRange(): Pair<Int, Int> {
        val n = samples.size
        if (n < 2 || zoomLevel <= 1f) return 0 to n - 1
        
        val visibleFrac = 1f / zoomLevel
        val startFrac = panOffset.coerceIn(0f, 1f - visibleFrac)
        val endFrac = (startFrac + visibleFrac).coerceIn(0f, 1f)
        
        val startIdx = (startFrac * (n - 1)).toInt().coerceIn(0, n - 1)
        val endIdx = (endFrac * (n - 1)).toInt().coerceIn(0, n - 1)
        
        return startIdx to endIdx
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

        // Get visible range based on zoom/pan
        val (visibleStart, visibleEnd) = getVisibleRange()
        val visibleSamples = samples.subList(visibleStart, visibleEnd + 1)
        val visibleTimestamps = if (timestampsMs.size > visibleEnd) timestampsMs.subList(visibleStart, visibleEnd + 1) else emptyList()

        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        var peakIndex = 0
        var peakValue = Float.NEGATIVE_INFINITY

        for (i in visibleSamples.indices) {
            val v = visibleSamples[i]
            if (v < minV) minV = v
            if (v > peakValue) {
                peakValue = v
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

        // Draw X-axis time labels (zoom-aware)
        drawTimeAxis(canvas, chartWidth, visibleStart, visibleEnd)

        // Create gradient for fill
        val gradient = LinearGradient(
            0f, chartTop, 0f, chartBottom,
            Color.argb(100, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = gradient
        linePaint.color = accentColor

        // Build paths using visible samples
        val linePath = Path()
        val fillPath = Path()
        val n = visibleSamples.size

        for (i in 0 until n) {
            val x = chartLeft + chartWidth * (i.toFloat() / max(1, n - 1))
            val yNorm = (visibleSamples[i] - yMin) / yRange
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

        // Draw rolling average line (dotted) for visible portion
        if (rollingAvgSamples.size >= samples.size && visibleSamples.isNotEmpty()) {
            val visibleAvg = rollingAvgSamples.subList(visibleStart, visibleEnd + 1)
            drawRollingAverageLine(canvas, chartWidth, chartHeight, yMin, yRange, visibleAvg)
        }

        // Draw threshold line
        if (thresholdValue.isFinite()) {
            val yNorm = (thresholdValue - yMin) / yRange
            val yPx = chartBottom - chartHeight * yNorm
            canvas.drawLine(chartLeft, yPx, chartRight, yPx, thresholdPaint)
        }

        // Draw delta spike markers for visible portion (if enabled)
        if (showSpikeMarkers) {
            drawSpikeMarkers(canvas, chartWidth, chartHeight, yMin, yRange, visibleSamples, visibleStart)
        }

        // Draw peak marker
        if (n > 0) {
            val x = chartLeft + chartWidth * (peakIndex.toFloat() / max(1, n - 1))
            val yNorm = (visibleSamples[peakIndex] - yMin) / yRange
            val y = chartBottom - chartHeight * yNorm
            canvas.drawCircle(x, y, density * 5f, peakPaint)
        }

        // Draw secondary series if present
        if (secondarySamples.size >= 2 && secondarySamples.size == samples.size) {
            val visibleSecondary = secondarySamples.subList(visibleStart, visibleEnd + 1)
            drawSecondarySeries(canvas, chartWidth, chartHeight, visibleSecondary)
        }

        // Draw zoom indicator if zoomed
        if (zoomLevel > 1f) {
            drawZoomIndicator(canvas)
        }

        // Draw selection marker (sticky or regular crosshair)
        selectedIndex?.let { idx ->
            // Convert global index to visible index
            if (idx in visibleStart..visibleEnd) {
                val localIdx = idx - visibleStart
                val x = chartLeft + chartWidth * (localIdx.toFloat() / max(1, n - 1))
                val yNorm = (samples[idx] - yMin) / yRange
                val y = chartBottom - chartHeight * yNorm

                if (isStickyMode) {
                    drawStickyMarker(canvas, x, y, idx)
                } else {
                    canvas.drawLine(x, chartTop, x, chartBottom, crosshairPaint)
                    canvas.drawCircle(x, y, density * 6f, peakPaint)
                    drawTooltip(canvas, x, y, idx)
                }
            }
        }
    }

    /** Draw zoom indicator showing current zoom level */
    private fun drawZoomIndicator(canvas: Canvas) {
        val text = String.format(Locale.US, "%.1fx", zoomLevel)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pro_surface_elevated)
            alpha = 200
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = scaledDensity * 10f
            color = ContextCompat.getColor(context, R.color.pro_text_secondary)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        
        val textWidth = textPaint.measureText(text)
        val padH = density * 6f
        val padV = density * 3f
        val x = chartRight - textWidth - padH * 3
        val y = chartTop + padV
        
        val rect = RectF(x - padH, y - padV, x + textWidth + padH, y + textPaint.textSize + padV)
        canvas.drawRoundRect(rect, density * 4f, density * 4f, bgPaint)
        canvas.drawText(text, x, y + textPaint.textSize * 0.85f, textPaint)
    }

    /** Draw rolling average as a dotted line */
    private fun drawRollingAverageLine(
        canvas: Canvas,
        chartWidth: Float,
        chartHeight: Float,
        yMin: Float,
        yRange: Float,
        visibleAvg: List<Float>
    ) {
        val avgPath = Path()
        val n = visibleAvg.size
        for (i in 0 until n) {
            val x = chartLeft + chartWidth * (i.toFloat() / max(1, n - 1))
            val yNorm = (visibleAvg[i] - yMin) / yRange
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
        visibleSamples: List<Float>,
        visibleStart: Int
    ) {
        val n = visibleSamples.size
        for (spikeIdx in spikeIndices) {
            // Check if spike is in visible range
            if (spikeIdx < visibleStart || spikeIdx > visibleStart + n - 1) continue
            if (spikeIdx !in samples.indices) continue
            if (spikeIdx == 0) continue  // Need previous sample
            
            // Convert to local index within visible range
            val localIdx = spikeIdx - visibleStart
            val x = chartLeft + chartWidth * (localIdx.toFloat() / max(1, n - 1))
            val yNorm = (samples[spikeIdx] - yMin) / yRange
            val y = chartBottom - chartHeight * yNorm
            
            val prev = samples[spikeIdx - 1]
            val curr = samples[spikeIdx]
            val deltaPercent = if (prev != 0f) ((curr - prev) / prev) * 100f else 0f
            val isIncrease = deltaPercent > 0

            // Select paint based on direction (green for increase, red for decrease)
            val glowPaint = if (isIncrease) spikeGlowGreenPaint else spikeGlowRedPaint
            val linePaint = if (isIncrease) spikeGreenPaint else spikeRedPaint

            // Draw full-height glow behind the spike line
            canvas.drawLine(x, chartTop, x, chartBottom, glowPaint)
            
            // Draw the spike marker line (full height, dotted)
            canvas.drawLine(x, chartTop, x, chartBottom, linePaint)
            
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
        val isIncrease = deltaPercent > 0
        
        val annotationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textSize = scaledDensity * 9f
            color = if (isIncrease) 
                ContextCompat.getColor(context, R.color.pro_green)
            else 
                ContextCompat.getColor(context, R.color.pro_red)
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
            color = if (isIncrease) 
                ContextCompat.getColor(context, R.color.pro_green)
            else 
                ContextCompat.getColor(context, R.color.pro_red)
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

    private fun drawTimeAxis(canvas: Canvas, chartWidth: Float, visibleStart: Int, visibleEnd: Int) {
        if (timestampsMs.size < 2) return

        val visibleCount = visibleEnd - visibleStart + 1
        val labelCount = 4
        axisTextPaint.textAlign = Paint.Align.CENTER

        for (i in 0..labelCount) {
            val frac = i.toFloat() / labelCount
            val x = chartLeft + chartWidth * frac
            val idx = (visibleStart + (visibleCount - 1) * frac).toInt().coerceIn(0, timestampsMs.size - 1)
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

    private fun drawSecondarySeries(canvas: Canvas, chartWidth: Float, chartHeight: Float, visibleSecondary: List<Float>) {
        // Calculate range for visible secondary series
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (v in visibleSecondary) {
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

        val n = visibleSecondary.size
        val path = Path()
        for (i in 0 until n) {
            val x = chartLeft + chartWidth * (i.toFloat() / max(1, n - 1))
            val yNorm = (visibleSecondary[i] - yMin) / yRange
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

        // Account for zoom/pan when picking index
        val (visibleStart, visibleEnd) = getVisibleRange()
        val visibleCount = visibleEnd - visibleStart + 1
        
        val frac = ((xPx - chartLeft) / chartWidth).coerceIn(0f, 1f)
        val localIdx = (frac * (visibleCount - 1)).toInt()
        return (visibleStart + localIdx).coerceIn(0, n - 1)
    }
}
