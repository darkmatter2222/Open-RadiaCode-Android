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
    private var primaryLabel: String = ""  // Label for left Y-axis

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

    // Sigma bands (statistical bands)
    private var sigmaBandLevel: Int = 0  // 0 = off, 1/2/3 = ±1σ/2σ/3σ
    private var showMeanLine: Boolean = false
    
    // Alert markers (from triggered alerts)
    data class AlertMarker(
        val triggerTimestampMs: Long,        // When the alert notification fired
        val durationWindowStartMs: Long,     // When the condition first became true
        val cooldownEndMs: Long,             // When the cooldown period ends
        val color: String,                   // Color hex string like "#FF5252"
        val icon: String,                    // Emoji icon
        val name: String                     // Alert name for tooltip
    )
    private var alertMarkers: List<AlertMarker> = emptyList()

    // Zoom and Pan state
    private var zoomLevel: Float = 1f           // 1.0 = no zoom, 2.0 = 2x zoom
    private var panOffset: Float = 0f           // 0.0 = start, 1.0 = end (normalized)
    private var isZoomPanEnabled: Boolean = true
    private var isPanning = false
    private var lastPanX = 0f
    private var isFollowingRealTime: Boolean = true  // If true, auto-scroll to show newest data
    
    // Anchor timestamp for preserving position when not following realtime
    private var anchorTimestampMs: Long? = null  // The timestamp at center of view when not following realtime
    
    // Display options
    private var showSpikeMarkers: Boolean = true
    private var showSpikePercentages: Boolean = true
    private var showSpikeDottedLines: Boolean = false  // Off by default - just show triangles

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

    // Mean line paint (solid horizontal line)
    private val meanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        color = ContextCompat.getColor(context, R.color.pro_amber)
        alpha = 180
    }

    // Sigma band paint (semi-transparent fill)
    private val sigmaBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pro_cyan)
        alpha = 25
    }

    private val sigmaBandBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = ContextCompat.getColor(context, R.color.pro_cyan)
        alpha = 60
        pathEffect = DashPathEffect(floatArrayOf(density * 6f, density * 3f), 0f)
    }

    // Sticky marker paint (brighter than regular crosshair)
    private val stickyMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = ContextCompat.getColor(context, R.color.pro_cyan)
        alpha = 200
    }
    
    // Alert marker paints
    private val alertLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        pathEffect = DashPathEffect(floatArrayOf(density * 6f, density * 4f), 0f)
    }
    
    private val alertRangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 40
    }
    
    private val alertIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 14f
        textAlign = Paint.Align.CENTER
    }
    
    private val alertLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pro_surface_elevated)
        alpha = 220
    }
    
    private val alertLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 9f
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.US)
    
    // Touch interaction timing
    private var touchDownTime: Long = 0L
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private val markerDelayMs = 400L  // Don't show marker until held for 400ms
    private val tapSlop = density * 12f  // Movement threshold to cancel tap
    private var isWaitingForMarker = false
    
    // Fling/momentum scrolling
    private var flingVelocity: Float = 0f
    private var lastFlingTime: Long = 0L
    private val flingDecay = 0.95f  // How quickly fling slows down (higher = more momentum)
    private val flingMinVelocity = density * 3f  // Stop fling below this velocity
    
    private val flingRunnable = object : Runnable {
        override fun run() {
            if (abs(flingVelocity) < flingMinVelocity || zoomLevel <= 1f) {
                flingVelocity = 0f
                // Update anchor when fling stops
                updateAnchorTimestamp()
                return
            }
            
            val chartWidth = chartRight - chartLeft
            if (chartWidth > 0 && samples.size > 1) {
                val visibleFrac = 1f / zoomLevel
                val panDelta = flingVelocity / chartWidth * visibleFrac
                val newPan = (panOffset + panDelta).coerceIn(0f, 1f - visibleFrac)
                
                // Check if we've reached the end (real-time)
                val wasFollowing = isFollowingRealTime
                isFollowingRealTime = newPan >= (1f - visibleFrac - 0.001f)
                if (wasFollowing != isFollowingRealTime) {
                    notifyRealTimeStateChanged()
                    if (isFollowingRealTime) {
                        anchorTimestampMs = null  // Clear anchor when following realtime
                    }
                }
                
                panOffset = newPan
                flingVelocity *= flingDecay
                invalidate()
                postOnAnimation(this)
            }
        }
    }
    
    private val markerDelayRunnable = Runnable {
        if (isWaitingForMarker && !scaleGestureDetector.isInProgress) {
            selectedIndex = pickIndexForX(touchDownX)
            if (selectedIndex != null) {
                isStickyMode = true
                stickyTimestampMs = timestampsMs.getOrNull(selectedIndex!!)
                invalidate()
            }
        }
        isWaitingForMarker = false
    }

    // Scale gesture detector for pinch-zoom (with amplified sensitivity)
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!isZoomPanEnabled) return false
            
            // Amplify scale factor for more responsive zooming
            val rawScale = detector.scaleFactor
            val amplifiedScale = if (rawScale > 1f) {
                1f + (rawScale - 1f) * 2.5f  // Zoom in faster
            } else {
                1f - (1f - rawScale) * 2.5f  // Zoom out faster
            }
            val oldZoom = zoomLevel
            val newZoom = (zoomLevel * amplifiedScale).coerceIn(1f, 20f)
            
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
            
            // Cancel any pending marker when zooming
            cancelMarkerDelay()
            
            // Notify listener if zoom changed
            if (oldZoom != zoomLevel) {
                notifyZoomChanged()
            }
            
            invalidate()
            return true
        }
        
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Cancel marker when starting to zoom
            cancelMarkerDelay()
            flingVelocity = 0f  // Stop any ongoing fling
            return true
        }
    })

    // Gesture handling
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // Stop any ongoing fling
            flingVelocity = 0f
            removeCallbacks(flingRunnable)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // Cancel marker when scrolling
            cancelMarkerDelay()
            
            if (!isZoomPanEnabled) return false
            
            // Only allow panning when zoomed in
            if (zoomLevel <= 1f) return false
            
            // Pan the view
            val chartWidth = chartRight - chartLeft
            if (chartWidth > 0 && samples.size > 1) {
                val visibleFrac = 1f / zoomLevel
                val panDelta = distanceX / chartWidth * visibleFrac
                val newPan = (panOffset + panDelta).coerceIn(0f, 1f - visibleFrac)
                
                // Check if we've scrolled away from or back to real-time
                val wasFollowing = isFollowingRealTime
                isFollowingRealTime = newPan >= (1f - visibleFrac - 0.001f)
                if (wasFollowing != isFollowingRealTime) {
                    notifyRealTimeStateChanged()
                    if (isFollowingRealTime) {
                        anchorTimestampMs = null  // Clear anchor when back to realtime
                    }
                }
                
                panOffset = newPan
                
                // Update anchor timestamp as we scroll
                if (!isFollowingRealTime) {
                    updateAnchorTimestamp()
                }
                
                invalidate()
                return true
            }
            return false
        }
        
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!isZoomPanEnabled || zoomLevel <= 1f) return false
            
            // Start momentum scrolling (negative because we pan opposite to swipe)
            // Increased sensitivity for faster response
            flingVelocity = -velocityX / 40f  // Less division = more speed
            lastFlingTime = System.currentTimeMillis()
            postOnAnimation(flingRunnable)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // Long press immediately enables sliding mode
            cancelMarkerDelay()
            isStickyMode = true  // Make it sticky so it persists
            selectedIndex = pickIndexForX(e.x)
            stickyTimestampMs = selectedIndex?.let { timestampsMs.getOrNull(it) }
            selectedIndex?.let { notifyDataPointSelected(it) }
            invalidate()
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Single tap selects point (for range selection mode)
            val idx = pickIndexForX(e.x)
            if (idx != null) {
                notifyDataPointSelected(idx)
            }
            // If we have a sticky marker, clear it
            if (isStickyMode) {
                clearStickyMarker()
            }
            return true
        }
        
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double-tap to reset zoom and go to real-time
            cancelMarkerDelay()
            if (zoomLevel > 1f || !isFollowingRealTime) {
                zoomLevel = 1f
                panOffset = 0f
                val wasFollowing = isFollowingRealTime
                isFollowingRealTime = true
                if (!wasFollowing) {
                    notifyRealTimeStateChanged()
                }
                notifyZoomChanged()
                invalidate()
                return true
            }
            return false
        }
    })
    
    private fun cancelMarkerDelay() {
        isWaitingForMarker = false
        removeCallbacks(markerDelayRunnable)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        
        // Disable quick-scale to make pinch-zoom more responsive
        // Quick-scale adds a delay for double-tap-and-drag zoom which interferes
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            scaleGestureDetector.isQuickScaleEnabled = false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Calculate chart bounds
        val yAxisWidth = density * 44f
        val rightAxisWidth = if (hasSecondaryAxis) density * 100f else density * 12f  // Extra wide for secondary axis labels
        val xAxisHeight = density * 24f
        val padding = density * 12f

        chartLeft = yAxisWidth
        chartTop = padding
        chartRight = w - rightAxisWidth
        chartBottom = h - xAxisHeight
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTime = System.currentTimeMillis()
                touchDownX = event.x
                touchDownY = event.y
                isWaitingForMarker = true
                // Start delayed marker timer
                postDelayed(markerDelayRunnable, markerDelayMs)
            }
            MotionEvent.ACTION_MOVE -> {
                // Cancel marker if moved too far
                val dx = abs(event.x - touchDownX)
                val dy = abs(event.y - touchDownY)
                if (dx > tapSlop || dy > tapSlop) {
                    cancelMarkerDelay()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelMarkerDelay()
                // Clear non-sticky selection on release
                if (!isStickyMode && selectedIndex != null) {
                    selectedIndex = null
                    invalidate()
                }
            }
        }
        
        // Handle scale gestures first
        var handled = scaleGestureDetector.onTouchEvent(event)
        
        // Only handle other gestures if not scaling
        if (!scaleGestureDetector.isInProgress) {
            handled = gestureDetector.onTouchEvent(event) || handled
            
            // Handle drag during long-press - for sliding marker
            if (isStickyMode && selectedIndex != null && event.action == MotionEvent.ACTION_MOVE) {
                selectedIndex = pickIndexForX(event.x)
                stickyTimestampMs = selectedIndex?.let { timestampsMs.getOrNull(it) }
                invalidate()
            }
        }
        
        return handled || super.onTouchEvent(event)
    }
    
    /** Listener for zoom level changes */
    interface OnZoomChangeListener {
        fun onZoomChanged(zoomLevel: Float)
    }
    
    /** Listener for real-time following state changes */
    interface OnRealTimeStateListener {
        fun onRealTimeStateChanged(isFollowingRealTime: Boolean)
    }
    
    /** Listener for data point selection (tap/hold) */
    interface OnDataPointSelectedListener {
        fun onDataPointSelected(index: Int, timestampMs: Long, value: Float)
    }
    
    private var zoomChangeListener: OnZoomChangeListener? = null
    private var realTimeStateListener: OnRealTimeStateListener? = null
    private var dataPointSelectedListener: OnDataPointSelectedListener? = null
    
    fun setOnZoomChangeListener(listener: OnZoomChangeListener?) {
        zoomChangeListener = listener
    }
    
    fun setOnRealTimeStateListener(listener: OnRealTimeStateListener?) {
        realTimeStateListener = listener
    }
    
    fun setOnDataPointSelectedListener(listener: OnDataPointSelectedListener?) {
        dataPointSelectedListener = listener
    }
    
    private fun notifyZoomChanged() {
        zoomChangeListener?.onZoomChanged(zoomLevel)
    }
    
    private fun notifyRealTimeStateChanged() {
        realTimeStateListener?.onRealTimeStateChanged(isFollowingRealTime)
    }
    
    private fun notifyDataPointSelected(index: Int) {
        if (index in samples.indices && index in timestampsMs.indices) {
            dataPointSelectedListener?.onDataPointSelected(index, timestampsMs[index], samples[index])
        }
    }
    
    /** Check if chart is following real-time (showing newest data on right edge) */
    fun isFollowingRealTime(): Boolean = isFollowingRealTime
    
    /** Jump to real-time (newest data) without changing zoom level */
    fun goToRealTime() {
        if (zoomLevel > 1f) {
            val visibleFrac = 1f / zoomLevel
            panOffset = 1f - visibleFrac  // Pan to show the rightmost data
        } else {
            panOffset = 0f
        }
        val wasFollowing = isFollowingRealTime
        isFollowingRealTime = true
        anchorTimestampMs = null  // Clear anchor when going to realtime
        if (!wasFollowing) {
            notifyRealTimeStateChanged()
        }
        invalidate()
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
            anchorTimestampMs = null  // Clear anchor
            val wasFollowing = isFollowingRealTime
            isFollowingRealTime = true
            if (!wasFollowing) notifyRealTimeStateChanged()
            notifyZoomChanged()
            invalidate()
        }
    }

    /** Reset zoom to default (1x) and go to real-time */
    fun resetZoom() {
        zoomLevel = 1f
        panOffset = 0f
        anchorTimestampMs = null  // Clear anchor
        val wasFollowing = isFollowingRealTime
        isFollowingRealTime = true
        if (!wasFollowing) notifyRealTimeStateChanged()
        notifyZoomChanged()
        invalidate()
    }

    /** Get current zoom level */
    fun getZoomLevel(): Float = zoomLevel
    
    /** Enable or disable spike markers (dotted lines with percentage) */
    fun setShowSpikeMarkers(show: Boolean) {
        showSpikeMarkers = show
        invalidate()
    }

    fun setShowSpikePercentages(show: Boolean) {
        showSpikePercentages = show
        invalidate()
    }

    fun setShowSpikeDottedLines(show: Boolean) {
        showSpikeDottedLines = show
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

    /** Set the rolling average window size (0 to disable) */
    fun setRollingAverageWindow(windowSize: Int) {
        rollingAvgWindow = if (windowSize <= 0) 0 else windowSize.coerceAtLeast(2)
        recalculateRollingAverage()
        invalidate()
    }

    /** Set sigma band level (0=off, 1/2/3 = ±1σ/2σ/3σ) */
    fun setSigmaBandLevel(level: Int) {
        sigmaBandLevel = level.coerceIn(0, 3)
        invalidate()
    }

    /** Show or hide mean line */
    fun setShowMeanLine(show: Boolean) {
        showMeanLine = show
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
        
        // Handle zoom/pan state based on whether we're following realtime
        if (zoomLevel > 1f) {
            if (isFollowingRealTime) {
                // Following realtime: keep view at the end (newest data)
                val visibleFrac = 1f / zoomLevel
                panOffset = (1f - visibleFrac).coerceAtLeast(0f)
            } else if (anchorTimestampMs != null && timestampsMs.isNotEmpty()) {
                // NOT following realtime: preserve position based on anchor timestamp
                // Find where our anchor timestamp falls in the new data
                val anchorIdx = findIndexForTimestamp(anchorTimestampMs!!) ?: 0
                val n = timestampsMs.size
                if (n > 1) {
                    // Calculate pan offset to keep anchor near center
                    val anchorFrac = anchorIdx.toFloat() / (n - 1)
                    val visibleFrac = 1f / zoomLevel
                    panOffset = (anchorFrac - visibleFrac / 2).coerceIn(0f, 1f - visibleFrac)
                }
            }
            // If not following and no anchor, keep current panOffset (don't move)
        }
        
        // Recalculate derived data
        recalculateSpikes()
        recalculateRollingAverage()
        
        invalidate()
    }
    
    /** Update anchor timestamp based on current view center */
    private fun updateAnchorTimestamp() {
        if (timestampsMs.isEmpty() || zoomLevel <= 1f) {
            anchorTimestampMs = null
            return
        }
        
        // Calculate the center of the visible range
        val visibleFrac = 1f / zoomLevel
        val centerFrac = panOffset + visibleFrac / 2
        val centerIdx = (centerFrac * (timestampsMs.size - 1)).toInt().coerceIn(0, timestampsMs.size - 1)
        anchorTimestampMs = timestampsMs[centerIdx]
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
        if (rollingAvgWindow <= 0 || samples.size < rollingAvgWindow) {
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

    fun setSecondarySeries(samples: List<Float>, color: Int, label: String = "", primaryAxisLabel: String = "") {
        this.secondarySamples = samples
        this.secondaryAccentColor = color
        this.secondaryLabel = label
        this.primaryLabel = primaryAxisLabel
        this.hasSecondaryAxis = samples.isNotEmpty()
        requestLayout() // Recalculate layout for right axis
        invalidate()
    }
    
    /**
     * Set alert markers to display on the chart.
     * These appear as dashed vertical lines with optional shaded duration regions.
     */
    fun setAlertMarkers(markers: List<AlertMarker>) {
        this.alertMarkers = markers
        if (markers.isNotEmpty()) {
            android.util.Log.d("ProChartView", "setAlertMarkers: received ${markers.size} markers")
            markers.forEach { m ->
                android.util.Log.d("ProChartView", "  Marker: trigger=${m.triggerTimestampMs}, durationStart=${m.durationWindowStartMs}, cooldownEnd=${m.cooldownEndMs}, name=${m.name}")
            }
        }
        invalidate()
    }
    
    /**
     * Add a single alert marker.
     */
    fun addAlertMarker(marker: AlertMarker) {
        this.alertMarkers = this.alertMarkers + marker
        invalidate()
    }
    
    /**
     * Clear all alert markers.
     */
    fun clearAlertMarkers() {
        this.alertMarkers = emptyList()
        invalidate()
    }

    fun clearSecondarySeries() {
        this.secondarySamples = emptyList()
        this.secondaryLabel = ""
        this.primaryLabel = ""
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

        // Threshold line removed - no longer including in range calculation

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

        // Calculate statistics for sigma bands and mean line
        val mean = visibleSamples.average().toFloat()
        val variance = visibleSamples.map { (it - mean) * (it - mean) }.average()
        val stdDev = kotlin.math.sqrt(variance).toFloat()

        // Draw sigma bands (before main line so it appears behind)
        if (sigmaBandLevel > 0) {
            drawSigmaBands(canvas, chartHeight, yMin, yRange, mean, stdDev, sigmaBandLevel)
        }

        // Draw mean line (before main line)
        if (showMeanLine) {
            val meanY = chartBottom - chartHeight * ((mean - yMin) / yRange)
            canvas.drawLine(chartLeft, meanY, chartRight, meanY, meanLinePaint)
        }

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

        // Threshold line removed - now using Smart Alerts system instead

        // Draw delta spike markers for visible portion (if enabled)
        if (showSpikeMarkers) {
            drawSpikeMarkers(canvas, chartWidth, chartHeight, yMin, yRange, visibleSamples, visibleStart)
        }

        // Draw alert markers (dashed lines, shaded regions, icons)
        drawAlertMarkers(canvas, chartWidth, visibleStart, visibleEnd)

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

    /** Draw sigma bands as horizontal shaded regions */
    private fun drawSigmaBands(
        canvas: Canvas,
        chartHeight: Float,
        yMin: Float,
        yRange: Float,
        mean: Float,
        stdDev: Float,
        level: Int
    ) {
        // Calculate the Y positions for the bands
        val meanY = chartBottom - chartHeight * ((mean - yMin) / yRange)
        
        for (sigmaLevel in 1..level) {
            val upperBound = mean + (sigmaLevel * stdDev)
            val lowerBound = mean - (sigmaLevel * stdDev)
            
            val upperY = chartBottom - chartHeight * ((upperBound - yMin) / yRange)
            val lowerY = chartBottom - chartHeight * ((lowerBound - yMin) / yRange)
            
            // Clamp to chart bounds
            val clampedUpperY = upperY.coerceIn(chartTop, chartBottom)
            val clampedLowerY = lowerY.coerceIn(chartTop, chartBottom)
            
            // Draw filled band with decreasing opacity for outer bands
            val bandAlpha = when (sigmaLevel) {
                1 -> 35
                2 -> 25
                3 -> 15
                else -> 20
            }
            sigmaBandPaint.alpha = bandAlpha
            
            val rect = RectF(chartLeft, clampedUpperY, chartRight, clampedLowerY)
            canvas.drawRect(rect, sigmaBandPaint)
            
            // Draw dashed border lines at band edges
            if (clampedUpperY > chartTop) {
                canvas.drawLine(chartLeft, clampedUpperY, chartRight, clampedUpperY, sigmaBandBorderPaint)
            }
            if (clampedLowerY < chartBottom) {
                canvas.drawLine(chartLeft, clampedLowerY, chartRight, clampedLowerY, sigmaBandBorderPaint)
            }
        }
        
        // Draw sigma labels on right edge
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = scaledDensity * 9f
            color = ContextCompat.getColor(context, R.color.pro_cyan)
            alpha = 180
            textAlign = Paint.Align.RIGHT
        }
        
        for (sigmaLevel in 1..level) {
            val upperBound = mean + (sigmaLevel * stdDev)
            val lowerBound = mean - (sigmaLevel * stdDev)
            
            val upperY = chartBottom - chartHeight * ((upperBound - yMin) / yRange)
            val lowerY = chartBottom - chartHeight * ((lowerBound - yMin) / yRange)
            
            if (upperY > chartTop + density * 12) {
                canvas.drawText("+${sigmaLevel}σ", chartRight - density * 4, upperY + density * 3, labelPaint)
            }
            if (lowerY < chartBottom - density * 12) {
                canvas.drawText("-${sigmaLevel}σ", chartRight - density * 4, lowerY + density * 3, labelPaint)
            }
        }
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

            // Only draw dotted lines if enabled (off by default now)
            if (showSpikeDottedLines) {
                val glowPaint = if (isIncrease) spikeGlowGreenPaint else spikeGlowRedPaint
                val linePaint = if (isIncrease) spikeGreenPaint else spikeRedPaint
                canvas.drawLine(x, chartTop, x, chartBottom, glowPaint)
                canvas.drawLine(x, chartTop, x, chartBottom, linePaint)
            }
            
            // Always draw triangle indicator at the data point
            drawSpikeIndicator(canvas, x, y, isIncrease)
            
            // Draw percentage annotation only if enabled
            if (showSpikePercentages) {
                drawSpikeAnnotation(canvas, x, deltaPercent)
            }
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

    /**
     * Draw alert markers on the chart showing when alerts were triggered.
     * Shows:
     * - Duration window: solid shaded region BEFORE the trigger (when condition was met)
     * - Trigger line: dashed vertical line at the exact moment the alert fired
     * - Cooldown window: gradient fade-out AFTER the trigger (when alert won't fire again)
     * - Severity icon at top of trigger line
     */
    private fun drawAlertMarkers(
        canvas: Canvas,
        chartWidth: Float,
        visibleStart: Int,
        visibleEnd: Int
    ) {
        if (alertMarkers.isEmpty() || timestampsMs.isEmpty()) {
            if (alertMarkers.isNotEmpty()) {
                android.util.Log.d("ProChartView", "drawAlertMarkers: have ${alertMarkers.size} markers but timestampsMs is empty (${timestampsMs.size})")
            }
            return
        }
        
        val n = visibleEnd - visibleStart + 1
        if (n < 2) return
        
        // Get visible time range
        val visibleStartTime = timestampsMs.getOrNull(visibleStart) ?: return
        val visibleEndTime = timestampsMs.getOrNull(visibleEnd) ?: return
        val timeRange = (visibleEndTime - visibleStartTime).toFloat()
        if (timeRange <= 0) return
        
        android.util.Log.d("ProChartView", "drawAlertMarkers: visible time range $visibleStartTime to $visibleEndTime, checking ${alertMarkers.size} markers")
        
        for (marker in alertMarkers) {
            // Check if any part of the alert (duration window to cooldown end) overlaps visible range
            val markerStart = marker.durationWindowStartMs
            val markerEnd = marker.cooldownEndMs
            
            android.util.Log.d("ProChartView", "  Marker '${marker.name}': range $markerStart to $markerEnd vs visible $visibleStartTime to $visibleEndTime")
            
            // Skip if completely outside visible range
            if (markerEnd < visibleStartTime || markerStart > visibleEndTime) {
                android.util.Log.d("ProChartView", "  -> SKIPPED (outside visible range)")
                continue
            }
            
            android.util.Log.d("ProChartView", "  -> DRAWING marker")
            
            // Parse color
            val markerColor = try {
                Color.parseColor(marker.color)
            } catch (e: Exception) {
                ContextCompat.getColor(context, R.color.pro_cyan)
            }
            
            // Helper to convert timestamp to X position
            fun timestampToX(timestamp: Long): Float {
                return when {
                    timestamp < visibleStartTime -> chartLeft
                    timestamp > visibleEndTime -> chartRight
                    else -> chartLeft + chartWidth * ((timestamp - visibleStartTime).toFloat() / timeRange)
                }
            }
            
            // Calculate X positions
            val durationStartX = timestampToX(marker.durationWindowStartMs)
            val triggerX = timestampToX(marker.triggerTimestampMs)
            val cooldownEndX = timestampToX(marker.cooldownEndMs)
            
            // 1. Draw duration window (solid semi-transparent before trigger)
            // This shows the time period where the condition was met
            if (durationStartX < triggerX && marker.durationWindowStartMs < marker.triggerTimestampMs) {
                alertRangePaint.color = Color.argb(50, Color.red(markerColor), Color.green(markerColor), Color.blue(markerColor))
                alertRangePaint.shader = null
                canvas.drawRect(durationStartX, chartTop, triggerX, chartBottom, alertRangePaint)
                
                // Draw a subtle border on the duration window
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = density * 1f
                    color = Color.argb(80, Color.red(markerColor), Color.green(markerColor), Color.blue(markerColor))
                }
                canvas.drawRect(durationStartX, chartTop, triggerX, chartBottom, borderPaint)
            }
            
            // 2. Draw cooldown window (gradient fade-out after trigger)
            // This shows when the alert won't fire again
            if (triggerX < cooldownEndX && marker.triggerTimestampMs < marker.cooldownEndMs) {
                val cooldownGradient = LinearGradient(
                    triggerX, 0f, cooldownEndX, 0f,
                    Color.argb(35, Color.red(markerColor), Color.green(markerColor), Color.blue(markerColor)),
                    Color.argb(0, Color.red(markerColor), Color.green(markerColor), Color.blue(markerColor)),
                    Shader.TileMode.CLAMP
                )
                alertRangePaint.shader = cooldownGradient
                canvas.drawRect(triggerX, chartTop, cooldownEndX, chartBottom, alertRangePaint)
                alertRangePaint.shader = null
            }
            
            // 3. Draw dashed vertical line at trigger point
            alertLinePaint.color = markerColor
            canvas.drawLine(triggerX, chartTop, triggerX, chartBottom, alertLinePaint)
            
            // 4. Draw small glow effect behind the trigger line
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = density * 6f
                color = Color.argb(40, Color.red(markerColor), Color.green(markerColor), Color.blue(markerColor))
            }
            canvas.drawLine(triggerX, chartTop, triggerX, chartBottom, glowPaint)
            
            // 5. Draw severity icon at top of trigger line
            val iconSize = scaledDensity * 16f
            val iconY = chartTop + density * 6f
            
            // Background circle for icon (with border)
            alertLabelBgPaint.color = ContextCompat.getColor(context, R.color.pro_surface_elevated)
            canvas.drawCircle(triggerX, iconY + iconSize / 2, iconSize / 2 + density * 4f, alertLabelBgPaint)
            
            // Border ring around icon
            val iconBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = density * 2f
                color = markerColor
            }
            canvas.drawCircle(triggerX, iconY + iconSize / 2, iconSize / 2 + density * 3f, iconBorderPaint)
            
            // Icon text
            alertIconPaint.color = markerColor
            alertIconPaint.textSize = iconSize
            alertIconPaint.textAlign = Paint.Align.CENTER
            val iconBounds = Rect()
            alertIconPaint.getTextBounds(marker.icon, 0, marker.icon.length, iconBounds)
            canvas.drawText(marker.icon, triggerX, iconY + iconSize / 2 + iconBounds.height() / 2, alertIconPaint)
            
            // 6. Draw alert name label below icon
            if (marker.name.isNotEmpty()) {
                alertLabelTextPaint.color = Color.WHITE
                alertLabelTextPaint.textSize = scaledDensity * 9f
                alertLabelTextPaint.textAlign = Paint.Align.CENTER
                alertLabelTextPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                
                val labelY = iconY + iconSize + density * 16f
                val textWidth = alertLabelTextPaint.measureText(marker.name)
                
                // Only draw if it fits reasonably
                if (textWidth < chartWidth * 0.4f) {
                    // Background pill with alert color
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = markerColor
                    }
                    val bgRect = RectF(
                        triggerX - textWidth / 2 - density * 6f,
                        labelY - scaledDensity * 9f,
                        triggerX + textWidth / 2 + density * 6f,
                        labelY + density * 4f
                    )
                    canvas.drawRoundRect(bgRect, density * 6f, density * 6f, bgPaint)
                    canvas.drawText(marker.name, triggerX, labelY, alertLabelTextPaint)
                }
            }
        }
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

        // Draw left Y-axis title if in comparison mode
        if (hasSecondaryAxis && primaryLabel.isNotEmpty()) {
            val labelPaint = Paint(axisTextPaint).apply {
                textAlign = Paint.Align.LEFT
                color = accentColor
                textSize = density * 10f
            }
            canvas.drawText(primaryLabel, chartLeft - density * 4f, chartTop - density * 4f, labelPaint)
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
        
        // Draw right Y-axis title
        if (secondaryLabel.isNotEmpty()) {
            val labelPaint = Paint(axisTextPaint).apply {
                textAlign = Paint.Align.RIGHT
                color = secondaryAccentColor
                textSize = density * 10f
            }
            canvas.drawText(secondaryLabel, chartRight + density * 94f, chartTop - density * 4f, labelPaint)
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
