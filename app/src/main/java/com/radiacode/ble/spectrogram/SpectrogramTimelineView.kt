package com.radiacode.ble.spectrogram

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.*

/**
 * Timeline View for Spectrogram Sessions
 * 
 * Displays a horizontal timeline showing:
 * - Connected periods (green)
 * - Disconnected periods (red gaps)
 * - Recovered data periods (yellow)
 * - Time labels
 * 
 * Users can tap on segments to jump to that time period in the waterfall view.
 */
class SpectrogramTimelineView @JvmOverloads constructor(
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
    private val colorGreen = Color.parseColor("#69F0AE")   // Connected
    private val colorRed = Color.parseColor("#FF5252")     // Disconnected
    private val colorYellow = Color.parseColor("#FFD600")  // Recovered data
    private val colorCyan = Color.parseColor("#00E5FF")    // Selection/current

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val segments = mutableListOf<ConnectionSegment>()
    private var timeRangeStartMs = 0L
    private var timeRangeEndMs = 0L
    private var currentTimeMs = 0L
    private var selectedSegmentIndex = -1
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LAYOUT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val trackRect = RectF()
    private val paddingHorizontal = 40f
    private val paddingVertical = 20f
    private val trackHeight = 30f
    private val minHeight = 80f
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PAINTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val backgroundPaint = Paint().apply {
        color = colorBackground
        style = Paint.Style.FILL
    }
    
    private val trackPaint = Paint().apply {
        color = colorSurface
        style = Paint.Style.FILL
    }
    
    private val borderPaint = Paint().apply {
        color = colorBorder
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    
    private val connectedPaint = Paint().apply {
        color = colorGreen
        style = Paint.Style.FILL
    }
    
    private val disconnectedPaint = Paint().apply {
        color = colorRed
        style = Paint.Style.FILL
    }
    
    private val recoveredPaint = Paint().apply {
        color = colorYellow
        style = Paint.Style.FILL
    }
    
    private val currentTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorCyan
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    
    private val selectedPaint = Paint().apply {
        color = Color.argb(80, 0, 229, 255)  // Semi-transparent cyan
        style = Paint.Style.FILL
    }
    
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = 20f
        typeface = Typeface.MONOSPACE
    }
    
    private val timeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorMuted
        textSize = 18f
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CALLBACKS
    // ═══════════════════════════════════════════════════════════════════════════
    
    var onSegmentSelectedListener: ((segment: ConnectionSegment) -> Unit)? = null
    var onTimeSelectedListener: ((timestampMs: Long) -> Unit)? = null
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (paddingVertical * 2 + trackHeight + 30f).toInt()  // 30f for labels
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize).coerceAtLeast(minHeight.toInt())
            else -> desiredHeight.coerceAtLeast(minHeight.toInt())
        }
        
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateLayout()
    }
    
    private fun updateLayout() {
        trackRect.set(
            paddingHorizontal,
            paddingVertical,
            width - paddingHorizontal,
            paddingVertical + trackHeight
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Set connection segments to display.
     */
    fun setSegments(data: List<ConnectionSegment>) {
        segments.clear()
        segments.addAll(data)
        updateTimeRange()
        invalidate()
    }
    
    /**
     * Set the current playback/view time.
     */
    fun setCurrentTime(timestampMs: Long) {
        currentTimeMs = timestampMs
        invalidate()
    }
    
    /**
     * Set the selected segment by index.
     */
    fun setSelectedSegment(index: Int) {
        selectedSegmentIndex = index
        invalidate()
    }
    
    /**
     * Clear selection.
     */
    fun clearSelection() {
        selectedSegmentIndex = -1
        invalidate()
    }
    
    /**
     * Get the segment at a specific index.
     */
    fun getSegment(index: Int): ConnectionSegment? {
        return segments.getOrNull(index)
    }
    
    /**
     * Get all segments.
     */
    fun getSegments(): List<ConnectionSegment> = segments.toList()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateTimeRange() {
        if (segments.isEmpty()) {
            timeRangeStartMs = 0L
            timeRangeEndMs = 0L
            return
        }
        
        timeRangeStartMs = segments.minOf { it.startMs }
        timeRangeEndMs = segments.maxOf { it.endMs ?: System.currentTimeMillis() }
    }
    
    private fun timeToX(timestampMs: Long): Float {
        if (timeRangeEndMs <= timeRangeStartMs) return trackRect.left
        val normalized = (timestampMs - timeRangeStartMs).toFloat() / (timeRangeEndMs - timeRangeStartMs)
        return trackRect.left + normalized * trackRect.width()
    }
    
    private fun xToTime(x: Float): Long {
        val normalized = ((x - trackRect.left) / trackRect.width()).coerceIn(0f, 1f)
        return timeRangeStartMs + (normalized * (timeRangeEndMs - timeRangeStartMs)).toLong()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TOUCH HANDLING
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.action) {
            android.view.MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y
                
                // Check if tap is within track area
                if (trackRect.contains(x, y)) {
                    val tappedTime = xToTime(x)
                    
                    // Find which segment was tapped
                    var tappedSegment: ConnectionSegment? = null
                    var tappedIndex = -1
                    
                    for ((index, segment) in segments.withIndex()) {
                        val segmentEnd = segment.endMs ?: System.currentTimeMillis()
                        if (tappedTime >= segment.startMs && tappedTime <= segmentEnd) {
                            tappedSegment = segment
                            tappedIndex = index
                            break
                        }
                    }
                    
                    if (tappedSegment != null) {
                        selectedSegmentIndex = tappedIndex
                        onSegmentSelectedListener?.invoke(tappedSegment)
                        invalidate()
                    } else {
                        // Tapped on a gap - notify with the time
                        onTimeSelectedListener?.invoke(tappedTime)
                    }
                    
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DRAWING
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Track background
        canvas.drawRoundRect(trackRect, 8f, 8f, trackPaint)
        canvas.drawRoundRect(trackRect, 8f, 8f, borderPaint)
        
        if (segments.isEmpty()) {
            // No data message
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No recording data", trackRect.centerX(), trackRect.centerY() + 6f, labelPaint)
            return
        }
        
        // Draw segments
        canvas.save()
        canvas.clipRect(trackRect)
        
        for ((index, segment) in segments.withIndex()) {
            val startX = timeToX(segment.startMs)
            val endX = timeToX(segment.endMs ?: System.currentTimeMillis())
            
            // Choose paint based on segment state and selection
            val isConnected = segment.type == ConnectionSegmentType.CONNECTED
            val hasRecoveredData = segment.type == ConnectionSegmentType.DISCONNECTED_RECOVERED
            val paint = when {
                index == selectedSegmentIndex -> selectedPaint
                isConnected -> connectedPaint
                hasRecoveredData -> recoveredPaint
                else -> disconnectedPaint
            }
            
            // Draw segment bar
            val segmentRect = RectF(startX, trackRect.top + 4f, endX, trackRect.bottom - 4f)
            canvas.drawRoundRect(segmentRect, 4f, 4f, paint)
            
            // Draw border on selected segment
            if (index == selectedSegmentIndex) {
                val selectionBorderPaint = Paint().apply {
                    color = colorCyan
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                canvas.drawRoundRect(segmentRect, 4f, 4f, selectionBorderPaint)
            }
        }
        
        canvas.restore()
        
        // Draw current time indicator
        if (currentTimeMs in timeRangeStartMs..timeRangeEndMs) {
            val currentX = timeToX(currentTimeMs)
            canvas.drawLine(currentX, trackRect.top - 5f, currentX, trackRect.bottom + 5f, currentTimePaint)
            
            // Draw small triangle at top
            val trianglePath = Path().apply {
                moveTo(currentX, trackRect.top - 5f)
                lineTo(currentX - 6f, trackRect.top - 12f)
                lineTo(currentX + 6f, trackRect.top - 12f)
                close()
            }
            val trianglePaint = Paint().apply {
                color = colorCyan
                style = Paint.Style.FILL
            }
            canvas.drawPath(trianglePath, trianglePaint)
        }
        
        // Draw time labels
        drawTimeLabels(canvas)
        
        // Draw legend
        drawLegend(canvas)
    }
    
    private fun drawTimeLabels(canvas: Canvas) {
        if (timeRangeStartMs == 0L && timeRangeEndMs == 0L) return
        
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        timeLabelPaint.textAlign = Paint.Align.CENTER
        
        // Calculate number of labels based on width
        val labelCount = (trackRect.width() / 100f).toInt().coerceIn(2, 6)
        val timeStep = (timeRangeEndMs - timeRangeStartMs) / (labelCount - 1)
        
        for (i in 0 until labelCount) {
            val time = timeRangeStartMs + i * timeStep
            val x = timeToX(time)
            val label = sdf.format(Date(time))
            
            canvas.drawText(label, x, trackRect.bottom + 20f, timeLabelPaint)
        }
    }
    
    private fun drawLegend(canvas: Canvas) {
        val legendY = height - 8f
        val legendStartX = paddingHorizontal
        val itemWidth = 80f
        val dotRadius = 5f
        
        timeLabelPaint.textAlign = Paint.Align.LEFT
        
        // Connected
        connectedPaint.style = Paint.Style.FILL
        canvas.drawCircle(legendStartX + dotRadius, legendY - 5f, dotRadius, connectedPaint)
        canvas.drawText("Connected", legendStartX + dotRadius * 2 + 8f, legendY, timeLabelPaint)
        
        // Disconnected
        val disconnectedX = legendStartX + itemWidth
        disconnectedPaint.style = Paint.Style.FILL
        canvas.drawCircle(disconnectedX + dotRadius, legendY - 5f, dotRadius, disconnectedPaint)
        canvas.drawText("Gap", disconnectedX + dotRadius * 2 + 8f, legendY, timeLabelPaint)
        
        // Recovered
        val recoveredX = disconnectedX + itemWidth * 0.6f
        recoveredPaint.style = Paint.Style.FILL
        canvas.drawCircle(recoveredX + dotRadius, legendY - 5f, dotRadius, recoveredPaint)
        canvas.drawText("Recovered", recoveredX + dotRadius * 2 + 8f, legendY, timeLabelPaint)
    }
}
