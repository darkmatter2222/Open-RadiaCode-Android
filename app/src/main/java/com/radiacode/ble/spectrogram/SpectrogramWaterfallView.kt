package com.radiacode.ble.spectrogram

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Spectrogram Waterfall View
 * 
 * Displays spectrum data over time as a heatmap:
 * - X-axis: Energy (keV) or Channel number
 * - Y-axis: Time (oldest at top, newest at bottom)
 * - Color intensity: Count rate
 * 
 * Features:
 * - Pan/zoom support
 * - Connection gap visualization
 * - Background subtraction display
 * - Isotope marker overlays
 * - Real-time updates
 */
class SpectrogramWaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ═══════════════════════════════════════════════════════════════════════════
    // COLORS
    // ═══════════════════════════════════════════════════════════════════════════
    
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val snapshots = mutableListOf<SpectrumSnapshot>()
    private val connectionSegments = mutableListOf<ConnectionSegment>()
    private var backgroundSpectrum: BackgroundSpectrum? = null
    
    // Energy calibration: E(keV) = a0 + a1*channel + a2*channel^2
    private var calibrationA0 = 0f
    private var calibrationA1 = 3f  // ~3 keV per channel typical
    private var calibrationA2 = 0f
    
    // Data range
    private var globalMaxCount = 1
    private var channelCount = 1024
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DISPLAY SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var colorScheme = SpectrogramPrefs.ColorScheme.PRO_THEME
    private var showIsotopeMarkers = false
    private var showBackgroundSubtraction = false
    private var showConnectionGaps = true
    private var displayMode = SpectrogramDisplayMode.COLOR_CODED_SEGMENTS
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ISOTOPE MARKERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class IsotopeMarker(
        val name: String,
        val energy: Float,  // keV
        val color: Int
    )
    
    private val isotopeMarkers = listOf(
        IsotopeMarker("K-40", 1461f, colorGreen),
        IsotopeMarker("Cs-137", 662f, colorRed),
        IsotopeMarker("Ra-226", 186f, colorYellow),
        IsotopeMarker("Bi-214", 609f, colorYellow),
        IsotopeMarker("Bi-214", 1120f, colorYellow),
        IsotopeMarker("Pb-214", 352f, colorYellow),
        IsotopeMarker("Tl-208", 583f, colorMagenta),
        IsotopeMarker("Tl-208", 2614f, colorMagenta),
        IsotopeMarker("Co-60", 1173f, colorCyan),
        IsotopeMarker("Co-60", 1332f, colorCyan)
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ZOOM & PAN
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var scaleX = 1f  // Horizontal zoom (energy axis)
    private var scaleY = 1f  // Vertical zoom (time axis)
    private var panX = 0f    // Horizontal pan offset
    private var panY = 0f    // Vertical pan offset
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LAYOUT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val chartRect = RectF()
    private val colorBarRect = RectF()
    private val paddingLeft = 80f   // Space for time labels
    private val paddingRight = 50f  // Space for color bar
    private val paddingTop = 40f    // Space for title/energy labels
    private val paddingBottom = 60f // Space for energy axis labels
    private val colorBarWidth = 30f
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CACHED BITMAP FOR PERFORMANCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var heatmapBitmap: Bitmap? = null
    private var heatmapCanvas: Canvas? = null
    private var isDirty = true
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PAINTS
    // ═══════════════════════════════════════════════════════════════════════════
    
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
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
    }
    
    private val gapPaint = Paint().apply {
        color = Color.parseColor("#1A1A1E")
        style = Paint.Style.FILL
    }
    
    private val gapStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // GESTURE DETECTORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                
                // Scale horizontally
                scaleX *= scaleFactor
                scaleX = scaleX.coerceIn(0.5f, 10f)
                
                // Optionally scale vertically (can be disabled for time-only view)
                // scaleY *= scaleFactor
                // scaleY = scaleY.coerceIn(0.5f, 10f)
                
                clampPan()
                invalidate()
                return true
            }
        }
    )
    
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                panX -= distanceX
                panY -= distanceY
                clampPan()
                invalidate()
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Reset zoom on double tap
                scaleX = 1f
                scaleY = 1f
                panX = 0f
                panY = 0f
                invalidate()
                return true
            }
            
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }
        }
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CALLBACKS
    // ═══════════════════════════════════════════════════════════════════════════
    
    var onSnapshotTapListener: ((snapshot: SpectrumSnapshot, energy: Float) -> Unit)? = null
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateLayout()
        recreateHeatmapBitmap()
    }
    
    private fun updateLayout() {
        chartRect.set(
            paddingLeft,
            paddingTop,
            width - paddingRight - colorBarWidth - 10f,
            height - paddingBottom
        )
        
        colorBarRect.set(
            width - paddingRight - colorBarWidth,
            paddingTop,
            width - paddingRight,
            height - paddingBottom
        )
    }
    
    private fun recreateHeatmapBitmap() {
        val bitmapWidth = chartRect.width().toInt().coerceAtLeast(1)
        val bitmapHeight = chartRect.height().toInt().coerceAtLeast(1)
        
        heatmapBitmap?.recycle()
        heatmapBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        heatmapCanvas = Canvas(heatmapBitmap!!)
        isDirty = true
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        heatmapBitmap?.recycle()
        heatmapBitmap = null
        heatmapCanvas = null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Set the spectrum snapshots to display.
     */
    fun setSnapshots(data: List<SpectrumSnapshot>) {
        snapshots.clear()
        snapshots.addAll(data)
        updateGlobalMaxCount()
        isDirty = true
        invalidate()
    }
    
    /**
     * Add a new snapshot (for real-time updates).
     */
    fun addSnapshot(snapshot: SpectrumSnapshot) {
        snapshots.add(snapshot)
        
        // Update max count
        val snapshotMax = snapshot.spectrumData.counts.maxOrNull() ?: 0
        if (snapshotMax > globalMaxCount) {
            globalMaxCount = snapshotMax
        }
        
        isDirty = true
        invalidate()
    }
    
    /**
     * Set connection segments for gap visualization.
     */
    fun setConnectionSegments(segments: List<ConnectionSegment>) {
        connectionSegments.clear()
        connectionSegments.addAll(segments)
        isDirty = true
        invalidate()
    }
    
    /**
     * Set energy calibration coefficients.
     */
    fun setCalibration(a0: Float, a1: Float, a2: Float) {
        calibrationA0 = a0
        calibrationA1 = a1
        calibrationA2 = a2
        isDirty = true
        invalidate()
    }
    
    /**
     * Set the color scheme.
     */
    fun setColorScheme(scheme: SpectrogramPrefs.ColorScheme) {
        colorScheme = scheme
        isDirty = true
        invalidate()
    }
    
    /**
     * Toggle isotope markers.
     */
    fun setShowIsotopeMarkers(show: Boolean) {
        showIsotopeMarkers = show
        invalidate()
    }
    
    /**
     * Set background spectrum for subtraction.
     */
    fun setBackgroundSpectrum(background: BackgroundSpectrum?) {
        backgroundSpectrum = background
        isDirty = true
        invalidate()
    }
    
    /**
     * Toggle background subtraction display.
     */
    fun setShowBackgroundSubtraction(show: Boolean) {
        showBackgroundSubtraction = show
        isDirty = true
        invalidate()
    }
    
    /**
     * Set display mode for connection segments.
     */
    fun setDisplayMode(mode: SpectrogramDisplayMode) {
        displayMode = mode
        isDirty = true
        invalidate()
    }
    
    /**
     * Reset zoom and pan.
     */
    fun resetView() {
        scaleX = 1f
        scaleY = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }
    
    /**
     * Clear all data.
     */
    fun clear() {
        snapshots.clear()
        connectionSegments.clear()
        globalMaxCount = 1
        isDirty = true
        invalidate()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT HANDLING
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
    
    private fun clampPan() {
        val scaledWidth = chartRect.width() * scaleX
        val scaledHeight = chartRect.height() * scaleY
        
        val maxPanX = max(0f, scaledWidth - chartRect.width())
        val maxPanY = max(0f, scaledHeight - chartRect.height())
        
        panX = panX.coerceIn(-maxPanX, 0f)
        panY = panY.coerceIn(-maxPanY, 0f)
    }
    
    private fun handleTap(x: Float, y: Float) {
        if (!chartRect.contains(x, y) || snapshots.isEmpty()) return
        
        // Convert tap to energy and time
        val energy = screenXToEnergy(x)
        val rowIndex = screenYToRowIndex(y)
        
        if (rowIndex in snapshots.indices) {
            val snapshot = snapshots[rowIndex]
            onSnapshotTapListener?.invoke(snapshot, energy)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // COORDINATE CONVERSIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun channelToEnergy(channel: Float): Float {
        return calibrationA0 + calibrationA1 * channel + calibrationA2 * channel * channel
    }
    
    private fun energyToChannel(energy: Float): Int {
        // Inverse of quadratic: solve a2*x^2 + a1*x + (a0 - energy) = 0
        return if (calibrationA2 != 0f) {
            val discriminant = calibrationA1 * calibrationA1 - 4 * calibrationA2 * (calibrationA0 - energy)
            if (discriminant >= 0) {
                ((-calibrationA1 + kotlin.math.sqrt(discriminant)) / (2 * calibrationA2)).toInt()
            } else {
                ((energy - calibrationA0) / calibrationA1).toInt()
            }
        } else if (calibrationA1 != 0f) {
            ((energy - calibrationA0) / calibrationA1).toInt()
        } else {
            0
        }
    }
    
    private fun energyToScreenX(energy: Float): Float {
        val maxEnergy = channelToEnergy(channelCount.toFloat())
        val normalized = energy / maxEnergy
        return chartRect.left + normalized * chartRect.width() * scaleX + panX
    }
    
    private fun screenXToEnergy(screenX: Float): Float {
        val maxEnergy = channelToEnergy(channelCount.toFloat())
        val normalized = (screenX - chartRect.left - panX) / (chartRect.width() * scaleX)
        return normalized * maxEnergy
    }
    
    private fun rowIndexToScreenY(index: Int): Float {
        if (snapshots.isEmpty()) return chartRect.top
        val normalized = index.toFloat() / snapshots.size
        return chartRect.top + normalized * chartRect.height() * scaleY + panY
    }
    
    private fun screenYToRowIndex(screenY: Float): Int {
        if (snapshots.isEmpty()) return 0
        val normalized = (screenY - chartRect.top - panY) / (chartRect.height() * scaleY)
        return (normalized * snapshots.size).toInt().coerceIn(0, snapshots.size - 1)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DRAWING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateGlobalMaxCount() {
        globalMaxCount = snapshots.maxOfOrNull { snapshot ->
            snapshot.spectrumData.counts.maxOrNull() ?: 0
        } ?: 1
        if (globalMaxCount <= 0) globalMaxCount = 1
        
        // Also get channel count from first snapshot
        if (snapshots.isNotEmpty()) {
            channelCount = snapshots.first().spectrumData.counts.size
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Chart area background
        canvas.drawRoundRect(chartRect, 8f, 8f, surfacePaint)
        canvas.drawRoundRect(chartRect, 8f, 8f, borderPaint)
        
        // Render heatmap if dirty
        if (isDirty && heatmapBitmap != null && snapshots.isNotEmpty()) {
            renderHeatmap()
            isDirty = false
        }
        
        // Draw heatmap bitmap
        heatmapBitmap?.let { bitmap ->
            canvas.save()
            canvas.clipRect(chartRect)
            
            // Apply zoom and pan transformation
            val matrix = Matrix()
            matrix.postScale(scaleX, scaleY)
            matrix.postTranslate(chartRect.left + panX, chartRect.top + panY)
            canvas.drawBitmap(bitmap, matrix, null)
            
            canvas.restore()
        }
        
        // Draw connection gaps overlay
        if (showConnectionGaps && connectionSegments.isNotEmpty()) {
            drawConnectionGaps(canvas)
        }
        
        // Draw isotope markers
        if (showIsotopeMarkers) {
            drawIsotopeMarkers(canvas)
        }
        
        // Draw axes labels
        drawAxesLabels(canvas)
        
        // Draw color bar
        drawColorBar(canvas)
        
        // Draw title/status
        drawTitle(canvas)
    }
    
    private fun renderHeatmap() {
        val bitmap = heatmapBitmap ?: return
        val canvas = heatmapCanvas ?: return
        
        // Clear bitmap
        canvas.drawColor(SpectrogramColorMapper.getBackgroundColor(colorScheme))
        
        if (snapshots.isEmpty()) return
        
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        val rowHeight = bitmapHeight.toFloat() / snapshots.size
        
        // Pre-calculate background counts per second if needed
        val bgCountsPerSecond = if (showBackgroundSubtraction && backgroundSpectrum != null) {
            backgroundSpectrum!!.countsPerSecond
        } else {
            null
        }
        
        // Render each row (spectrum snapshot)
        for ((rowIndex, snapshot) in snapshots.withIndex()) {
            val y = (rowIndex * rowHeight).toInt()
            val nextY = ((rowIndex + 1) * rowHeight).toInt().coerceAtMost(bitmapHeight)
            val rowHeightPx = nextY - y
            if (rowHeightPx <= 0) continue
            
            val channelCounts = snapshot.spectrumData.counts
            val snapshotDuration = maxOf(1, snapshot.spectrumData.durationSeconds)
            val pixelsPerChannel = bitmapWidth.toFloat() / channelCounts.size
            
            for (channelIndex in channelCounts.indices) {
                val x = (channelIndex * pixelsPerChannel).toInt()
                val nextX = ((channelIndex + 1) * pixelsPerChannel).toInt().coerceAtMost(bitmapWidth)
                val colWidth = nextX - x
                if (colWidth <= 0) continue
                
                val count = channelCounts[channelIndex]
                val color = if (bgCountsPerSecond != null && channelIndex < bgCountsPerSecond.size) {
                    // Convert count to CPS and subtract background CPS, then convert back to count scale
                    val snapshotCps = count.toFloat() / snapshotDuration
                    val bgCps = bgCountsPerSecond[channelIndex]
                    val subtractedCps = maxOf(0f, snapshotCps - bgCps)
                    val subtractedCount = (subtractedCps * snapshotDuration).toInt()
                    SpectrogramColorMapper.mapCountsToColor(subtractedCount, globalMaxCount, colorScheme)
                } else {
                    SpectrogramColorMapper.mapCountsToColor(count, globalMaxCount, colorScheme)
                }
                
                // Fill the pixel block
                for (py in y until nextY) {
                    for (px in x until nextX) {
                        bitmap.setPixel(px, py, color)
                    }
                }
            }
        }
    }
    
    private fun drawConnectionGaps(canvas: Canvas) {
        // DISABLED: The user wants contiguous readings without temporal gap visualization.
        // The spectrogram should show each reading in sequence regardless of time gaps.
        // This function previously drew hatched bars for timestamp gaps > 2x expected interval,
        // but that made the visualization look different from the RadiaCode app.
        return
        
        /*
        if (snapshots.size < 2) return
        
        canvas.save()
        canvas.clipRect(chartRect)
        
        // Find gaps based on timestamp jumps (more than 2x the expected interval)
        var expectedInterval = 5000L  // Default 5 seconds
        if (snapshots.size > 1) {
            expectedInterval = snapshots[1].timestampMs - snapshots[0].timestampMs
        }
        
        for (i in 1 until snapshots.size) {
            val prevTime = snapshots[i - 1].timestampMs
            val currTime = snapshots[i].timestampMs
            val gap = currTime - prevTime
            
            // If gap is more than 2x expected, draw a gap indicator
            if (gap > expectedInterval * 2) {
                val y1 = rowIndexToScreenY(i - 1)
                val y2 = rowIndexToScreenY(i)
                
                // Draw hatched gap area
                val gapRect = RectF(chartRect.left, y1, chartRect.right, y2)
                canvas.drawRect(gapRect, gapPaint)
                
                // Draw diagonal stripes
                val stripeSpacing = 20f
                var stripeX = chartRect.left
                while (stripeX < chartRect.right + (y2 - y1)) {
                    canvas.drawLine(stripeX, y2, stripeX + (y2 - y1), y1, gapStripePaint)
                    stripeX += stripeSpacing
                }
            }
        }
        
        canvas.restore()
        */
    }
    
    private fun drawIsotopeMarkers(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(chartRect)
        
        for (marker in isotopeMarkers) {
            val x = energyToScreenX(marker.energy)
            if (x < chartRect.left || x > chartRect.right) continue
            
            markerPaint.color = marker.color
            canvas.drawLine(x, chartRect.top, x, chartRect.bottom, markerPaint)
            
            // Draw label at top
            markerTextPaint.color = marker.color
            val textWidth = markerTextPaint.measureText(marker.name)
            val labelX = (x - textWidth / 2).coerceIn(chartRect.left, chartRect.right - textWidth)
            canvas.drawText(marker.name, labelX, chartRect.top + 15f, markerTextPaint)
        }
        
        canvas.restore()
    }
    
    private fun drawAxesLabels(canvas: Canvas) {
        val maxEnergy = channelToEnergy(channelCount.toFloat())
        
        // Energy axis (bottom)
        labelPaint.textAlign = Paint.Align.CENTER
        val energyStep = calculateEnergyStep(maxEnergy)
        var energy = 0f
        while (energy <= maxEnergy) {
            val x = energyToScreenX(energy)
            if (x >= chartRect.left && x <= chartRect.right) {
                canvas.drawText("${energy.toInt()}", x, chartRect.bottom + 25f, labelPaint)
            }
            energy += energyStep
        }
        
        // Energy axis label
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Energy (keV)", chartRect.centerX(), height - 10f, textPaint)
        
        // Time axis (left) - show reading index (contiguous), not timestamps
        // This matches the RadiaCode app style where Y shows readings in sequence
        if (snapshots.isNotEmpty()) {
            labelPaint.textAlign = Paint.Align.RIGHT
            val totalReadings = snapshots.size
            val step = max(1, totalReadings / 5)
            for (i in snapshots.indices step step) {
                val y = rowIndexToScreenY(i)
                if (y >= chartRect.top && y <= chartRect.bottom) {
                    // Show reading index (1-based)
                    val readingLabel = "${i + 1}"
                    canvas.drawText(readingLabel, chartRect.left - 8f, y + 6f, labelPaint)
                }
            }
            // Always show the last reading index
            if (totalReadings > 1) {
                val y = rowIndexToScreenY(totalReadings - 1)
                if (y >= chartRect.top && y <= chartRect.bottom) {
                    canvas.drawText("$totalReadings", chartRect.left - 8f, y + 6f, labelPaint)
                }
            }
        }
    }
    
    private fun calculateEnergyStep(maxEnergy: Float): Float {
        val rawStep = maxEnergy / 6
        return when {
            rawStep > 500 -> 500f
            rawStep > 250 -> 250f
            rawStep > 100 -> 100f
            rawStep > 50 -> 50f
            else -> 25f
        }
    }
    
    private fun formatTimestamp(timestampMs: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(timestampMs))
    }
    
    private fun drawColorBar(canvas: Canvas) {
        // Color bar background
        canvas.drawRoundRect(colorBarRect, 4f, 4f, surfacePaint)
        canvas.drawRoundRect(colorBarRect, 4f, 4f, borderPaint)
        
        // Draw gradient
        val gradientSteps = colorBarRect.height().toInt()
        val colors = SpectrogramColorMapper.generateColorBar(gradientSteps, colorScheme)
        
        val stepHeight = colorBarRect.height() / gradientSteps
        val paint = Paint()
        for (i in colors.indices) {
            paint.color = colors[gradientSteps - 1 - i]  // Flip so high is at top
            val y = colorBarRect.top + i * stepHeight
            canvas.drawRect(
                colorBarRect.left + 2,
                y,
                colorBarRect.right - 2,
                y + stepHeight + 1,
                paint
            )
        }
        
        // Labels
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("High", colorBarRect.right + 5f, colorBarRect.top + 15f, labelPaint)
        canvas.drawText("Low", colorBarRect.right + 5f, colorBarRect.bottom, labelPaint)
    }
    
    private fun drawTitle(canvas: Canvas) {
        val snapshotCount = snapshots.size
        val title = if (snapshotCount > 0) {
            "Spectrogram ($snapshotCount samples)"
        } else {
            "Spectrogram (No data)"
        }
        
        titlePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(title, paddingLeft, 28f, titlePaint)
        
        // Zoom indicator
        if (scaleX != 1f || scaleY != 1f) {
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("Zoom: ${String.format("%.1fx", scaleX)}", width - paddingRight - colorBarWidth - 20f, 28f, labelPaint)
        }
    }
}
