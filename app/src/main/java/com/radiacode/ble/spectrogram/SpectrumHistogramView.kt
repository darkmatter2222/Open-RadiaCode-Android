package com.radiacode.ble.spectrogram

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Spectrum Histogram View
 * 
 * Classic radiation spectrum display:
 * - X-axis: Energy (keV) derived from channel calibration
 * - Y-axis: Counts (linear or logarithmic scale)
 * - Bars represent counts per energy bin
 * 
 * Features:
 * - Linear/logarithmic Y-axis toggle
 * - Pan/zoom support
 * - Isotope marker overlays with labels
 * - Peak highlighting
 * - Background subtraction
 * - Energy cursor with count readout
 */
class SpectrumHistogramView @JvmOverloads constructor(
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
    private val colorWhite = Color.parseColor("#FFFFFF")
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var spectrumCounts: IntArray = IntArray(1024)
    private var backgroundCounts: IntArray? = null
    private var channelCount = 1024
    
    // Energy calibration: E(keV) = a0 + a1*channel + a2*channel^2
    private var calibrationA0 = 0f
    private var calibrationA1 = 3f  // ~3 keV per channel typical
    private var calibrationA2 = 0f
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DISPLAY SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var useLogScale = true  // Logarithmic Y-axis by default
    private var showIsotopeMarkers = true
    private var showBackgroundSubtraction = false
    private var showGrid = true
    private var fillBars = true  // Fill under curve vs line only
    
    // ═══════════════════════════════════════════════════════════════════════════
    // VIEW STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Zoom/pan state
    private var zoomX = 1f
    private var panX = 0f
    private var minZoom = 1f
    private var maxZoom = 20f
    
    // Visible energy range
    private var visibleMinEnergy = 0f
    private var visibleMaxEnergy = 3000f  // Default to 3 MeV
    
    // Touch/cursor
    private var cursorChannel = -1
    private var showCursor = false
    private var isDraggingCursor = false  // Track if user is dragging to follow
    private var touchStartTime = 0L       // For detecting tap vs drag
    private var isMultiTouch = false      // Track if multi-touch (pinch) is active
    
    // Smoothing
    private var smoothingFactor = 0        // 0 = no smoothing, higher = more smoothing
    private var smoothedCounts: IntArray? = null
    
    // Peak snapping
    private var peakChannels: List<Int> = emptyList()  // Cached peak positions
    
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
        IsotopeMarker("Co-60", 1332f, colorCyan),
        IsotopeMarker("Am-241", 59f, colorMagenta),
        IsotopeMarker("I-131", 364f, colorCyan),
        IsotopeMarker("Na-22", 511f, colorGreen),
        IsotopeMarker("Na-22", 1275f, colorGreen)
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PAINTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorCyan
        style = Paint.Style.FILL
    }
    
    private val spectrumLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorCyan
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 82, 82)  // Semi-transparent red
        style = Paint.Style.FILL
    }
    
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBorder
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorMuted
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = 28f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorWhite
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorYellow
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // GEOMETRY
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val chartRect = RectF()
    private val padding = 16f
    private val leftAxisWidth = 80f
    private val bottomAxisHeight = 60f
    private val topPadding = 40f
    
    // ═══════════════════════════════════════════════════════════════════════════
    // GESTURE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // Pan horizontally
            val energyRange = visibleMaxEnergy - visibleMinEnergy
            val pixelsPerKeV = chartRect.width() / energyRange
            val deltaEnergy = distanceX / pixelsPerKeV
            
            val newMin = visibleMinEnergy + deltaEnergy
            val newMax = visibleMaxEnergy + deltaEnergy
            
            val maxEnergy = channelToEnergy(channelCount - 1)
            if (newMin >= 0 && newMax <= maxEnergy) {
                visibleMinEnergy = newMin
                visibleMaxEnergy = newMax
                invalidate()
            }
            return true
        }
        
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Show cursor at tap position
            if (chartRect.contains(e.x, e.y)) {
                val energy = xToEnergy(e.x)
                cursorChannel = energyToChannel(energy)
                showCursor = true
                invalidate()
            } else {
                showCursor = false
                invalidate()
            }
            return true
        }
        
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Reset zoom
            visibleMinEnergy = 0f
            visibleMaxEnergy = channelToEnergy(channelCount - 1)
            zoomX = 1f
            panX = 0f
            invalidate()
            return true
        }
    })
    
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val focusX = detector.focusX
            
            // Calculate energy at focus point
            val focusEnergy = xToEnergy(focusX)
            
            // Apply zoom
            val currentRange = visibleMaxEnergy - visibleMinEnergy
            val newRange = (currentRange / scaleFactor).coerceIn(50f, channelToEnergy(channelCount - 1))
            
            // Adjust range to keep focus energy at same screen position
            val focusRatio = (focusX - chartRect.left) / chartRect.width()
            visibleMinEnergy = (focusEnergy - newRange * focusRatio).coerceAtLeast(0f)
            visibleMaxEnergy = visibleMinEnergy + newRange
            
            invalidate()
            return true
        }
    })
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Set the spectrum data to display.
     */
    fun setSpectrum(counts: IntArray, a0: Float, a1: Float, a2: Float) {
        this.spectrumCounts = counts.copyOf()
        this.channelCount = counts.size
        this.calibrationA0 = a0
        this.calibrationA1 = a1
        this.calibrationA2 = a2
        
        // Apply smoothing if set
        applySmoothing()
        
        // Reset view to show full range if not zoomed
        if (zoomX == 1f) {
            visibleMinEnergy = 0f
            visibleMaxEnergy = channelToEnergy(channelCount - 1)
        }
        
        invalidate()
    }
    
    /**
     * Set spectrum from a snapshot.
     */
    fun setSnapshot(snapshot: SpectrumSnapshot) {
        setSpectrum(
            snapshot.spectrumData.counts,
            snapshot.spectrumData.a0,
            snapshot.spectrumData.a1,
            snapshot.spectrumData.a2
        )
    }
    
    /**
     * Set background spectrum for subtraction display.
     */
    fun setBackground(counts: IntArray?) {
        this.backgroundCounts = counts?.copyOf()
        invalidate()
    }
    
    /**
     * Toggle logarithmic/linear Y-axis scale.
     */
    fun setLogScale(enabled: Boolean) {
        this.useLogScale = enabled
        invalidate()
    }
    
    fun isLogScale(): Boolean = useLogScale
    
    /**
     * Toggle isotope marker display.
     */
    fun setShowIsotopeMarkers(show: Boolean) {
        this.showIsotopeMarkers = show
        invalidate()
    }
    
    /**
     * Toggle background subtraction display.
     */
    fun setShowBackgroundSubtraction(show: Boolean) {
        this.showBackgroundSubtraction = show
        invalidate()
    }
    
    /**
     * Toggle grid display.
     */
    fun setShowGrid(show: Boolean) {
        this.showGrid = show
        invalidate()
    }
    
    /**
     * Toggle between filled bars and line only.
     */
    fun setFillBars(fill: Boolean) {
        this.fillBars = fill
        invalidate()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // COORDINATE CONVERSION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun channelToEnergy(channel: Int): Float {
        return calibrationA0 + calibrationA1 * channel + calibrationA2 * channel * channel
    }
    
    private fun energyToChannel(energy: Float): Int {
        // Inverse of calibration (simplified for small a2)
        if (calibrationA2 != 0f) {
            // Quadratic formula
            val a = calibrationA2
            val b = calibrationA1
            val c = calibrationA0 - energy
            val discriminant = b * b - 4 * a * c
            if (discriminant >= 0) {
                return ((-b + kotlin.math.sqrt(discriminant)) / (2 * a)).toInt().coerceIn(0, channelCount - 1)
            }
        }
        return ((energy - calibrationA0) / calibrationA1).toInt().coerceIn(0, channelCount - 1)
    }
    
    private fun energyToX(energy: Float): Float {
        val ratio = (energy - visibleMinEnergy) / (visibleMaxEnergy - visibleMinEnergy)
        return chartRect.left + ratio * chartRect.width()
    }
    
    private fun xToEnergy(x: Float): Float {
        val ratio = (x - chartRect.left) / chartRect.width()
        return visibleMinEnergy + ratio * (visibleMaxEnergy - visibleMinEnergy)
    }
    
    private fun countToY(count: Int): Float {
        if (count <= 0) return chartRect.bottom
        
        val maxCount = spectrumCounts.maxOrNull() ?: 1
        
        return if (useLogScale) {
            // Logarithmic scale
            val logMax = log10(maxCount.toFloat().coerceAtLeast(1f))
            val logCount = log10(count.toFloat().coerceAtLeast(1f))
            val ratio = logCount / logMax
            chartRect.bottom - ratio * chartRect.height()
        } else {
            // Linear scale
            val ratio = count.toFloat() / maxCount
            chartRect.bottom - ratio * chartRect.height()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DRAWING
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        // Calculate chart area
        chartRect.set(
            padding + leftAxisWidth,
            padding + topPadding,
            w - padding,
            h - padding - bottomAxisHeight
        )
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Background - use same color as main background for seamless look
        canvas.drawColor(colorBackground)
        
        // No separate chart area background - let it blend
        
        // Draw grid
        if (showGrid) {
            drawGrid(canvas)
        }
        
        // Draw background spectrum (if showing subtraction)
        if (showBackgroundSubtraction && backgroundCounts != null) {
            drawSpectrum(canvas, backgroundCounts!!, backgroundPaint, null)
        }
        
        // Draw main spectrum (use smoothed if available)
        val displayCounts = smoothedCounts ?: spectrumCounts
        drawSpectrum(canvas, displayCounts, spectrumPaint, spectrumLinePaint)
        
        // Draw isotope markers
        if (showIsotopeMarkers) {
            drawIsotopeMarkers(canvas)
        }
        
        // Draw axes
        drawAxes(canvas)
        
        // Draw cursor
        if (showCursor && cursorChannel >= 0) {
            drawCursor(canvas)
        }
        
        // Draw title/info
        drawInfo(canvas)
    }
    
    private fun drawGrid(canvas: Canvas) {
        // Vertical grid lines (energy)
        val energyStep = calculateNiceStep(visibleMaxEnergy - visibleMinEnergy, 8)
        var energy = (visibleMinEnergy / energyStep).toInt() * energyStep
        while (energy <= visibleMaxEnergy) {
            if (energy >= visibleMinEnergy) {
                val x = energyToX(energy)
                canvas.drawLine(x, chartRect.top, x, chartRect.bottom, gridPaint)
            }
            energy += energyStep
        }
        
        // Horizontal grid lines (counts)
        val maxCount = spectrumCounts.maxOrNull() ?: 1
        if (useLogScale && maxCount > 1) {
            // Log scale: draw at powers of 10
            var power = 0
            while (10f.pow(power) <= maxCount) {
                val count = 10f.pow(power).toInt()
                val y = countToY(count)
                if (y >= chartRect.top && y <= chartRect.bottom) {
                    canvas.drawLine(chartRect.left, y, chartRect.right, y, gridPaint)
                }
                power++
            }
        } else {
            // Linear scale
            val countStep = calculateNiceStep(maxCount.toFloat(), 6).toInt().coerceAtLeast(1)
            var count = 0
            while (count <= maxCount) {
                val y = countToY(count)
                if (y >= chartRect.top && y <= chartRect.bottom) {
                    canvas.drawLine(chartRect.left, y, chartRect.right, y, gridPaint)
                }
                count += countStep
            }
        }
    }
    
    private fun drawSpectrum(canvas: Canvas, counts: IntArray, fillPaint: Paint?, linePaint: Paint?) {
        if (counts.isEmpty()) return
        
        val path = Path()
        var started = false
        
        // Find visible channel range
        val minChannel = energyToChannel(visibleMinEnergy).coerceAtLeast(0)
        val maxChannel = energyToChannel(visibleMaxEnergy).coerceAtMost(counts.size - 1)
        
        // Determine how many channels per pixel for binning
        val pixelsAvailable = chartRect.width()
        val channelsVisible = maxChannel - minChannel + 1
        val binSize = (channelsVisible / pixelsAvailable).toInt().coerceAtLeast(1)
        
        // Draw as filled area or line
        for (ch in minChannel..maxChannel step binSize) {
            // Average counts in bin
            var binSum = 0
            var binCount = 0
            for (i in ch until min(ch + binSize, counts.size)) {
                binSum += counts[i]
                binCount++
            }
            val avgCount = if (binCount > 0) binSum / binCount else 0
            
            val energy = channelToEnergy(ch)
            val x = energyToX(energy)
            val y = countToY(avgCount)
            
            if (!started) {
                path.moveTo(x, chartRect.bottom)
                path.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        
        // Close path for fill
        if (started) {
            val lastEnergy = channelToEnergy(maxChannel)
            path.lineTo(energyToX(lastEnergy), chartRect.bottom)
            path.close()
        }
        
        // Draw filled area
        if (fillBars && fillPaint != null) {
            val gradientPaint = Paint(fillPaint)
            gradientPaint.shader = LinearGradient(
                0f, chartRect.top, 0f, chartRect.bottom,
                Color.argb(180, Color.red(fillPaint.color), Color.green(fillPaint.color), Color.blue(fillPaint.color)),
                Color.argb(40, Color.red(fillPaint.color), Color.green(fillPaint.color), Color.blue(fillPaint.color)),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, gradientPaint)
        }
        
        // Draw line on top
        if (linePaint != null) {
            val linePath = Path()
            started = false
            for (ch in minChannel..maxChannel step binSize) {
                var binSum = 0
                var binCount = 0
                for (i in ch until min(ch + binSize, counts.size)) {
                    binSum += counts[i]
                    binCount++
                }
                val avgCount = if (binCount > 0) binSum / binCount else 0
                
                val energy = channelToEnergy(ch)
                val x = energyToX(energy)
                val y = countToY(avgCount)
                
                if (!started) {
                    linePath.moveTo(x, y)
                    started = true
                } else {
                    linePath.lineTo(x, y)
                }
            }
            canvas.drawPath(linePath, linePaint)
        }
    }
    
    private fun drawIsotopeMarkers(canvas: Canvas) {
        for (marker in isotopeMarkers) {
            if (marker.energy < visibleMinEnergy || marker.energy > visibleMaxEnergy) continue
            
            val x = energyToX(marker.energy)
            markerPaint.color = marker.color
            
            // Draw vertical dashed line
            canvas.drawLine(x, chartRect.top, x, chartRect.bottom, markerPaint)
            
            // Draw label at top
            labelPaint.color = marker.color
            labelPaint.textSize = 20f
            
            // Rotate label for better fit
            canvas.save()
            canvas.rotate(-45f, x, chartRect.top - 5)
            canvas.drawText(marker.name, x, chartRect.top - 5, labelPaint)
            canvas.restore()
        }
    }
    
    private fun drawAxes(canvas: Canvas) {
        // X-axis (energy)
        canvas.drawLine(chartRect.left, chartRect.bottom, chartRect.right, chartRect.bottom, axisPaint)
        
        // Y-axis
        canvas.drawLine(chartRect.left, chartRect.top, chartRect.left, chartRect.bottom, axisPaint)
        
        // X-axis labels
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 24f
        val energyStep = calculateNiceStep(visibleMaxEnergy - visibleMinEnergy, 8)
        var energy = (visibleMinEnergy / energyStep).toInt() * energyStep
        while (energy <= visibleMaxEnergy) {
            if (energy >= visibleMinEnergy) {
                val x = energyToX(energy)
                val label = if (energy >= 1000) "${(energy / 1000).toInt()}M" else "${energy.toInt()}"
                canvas.drawText(label, x, chartRect.bottom + 30, textPaint)
            }
            energy += energyStep
        }
        
        // X-axis title
        textPaint.textSize = 28f
        canvas.drawText("Energy (keV)", chartRect.centerX(), height - padding, textPaint)
        
        // Y-axis labels
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 22f
        val maxCount = spectrumCounts.maxOrNull() ?: 1
        
        if (useLogScale && maxCount > 1) {
            var power = 0
            while (10f.pow(power) <= maxCount) {
                val count = 10f.pow(power).toInt()
                val y = countToY(count)
                if (y >= chartRect.top && y <= chartRect.bottom) {
                    val label = formatCount(count)
                    canvas.drawText(label, chartRect.left - 8, y + 8, textPaint)
                }
                power++
            }
        } else {
            val countStep = calculateNiceStep(maxCount.toFloat(), 6).toInt().coerceAtLeast(1)
            var count = 0
            while (count <= maxCount) {
                val y = countToY(count)
                if (y >= chartRect.top && y <= chartRect.bottom) {
                    canvas.drawText(formatCount(count), chartRect.left - 8, y + 8, textPaint)
                }
                count += countStep
            }
        }
        
        // Y-axis title (rotated)
        canvas.save()
        canvas.rotate(-90f, padding + 20, chartRect.centerY())
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 28f
        canvas.drawText("Counts", padding + 20, chartRect.centerY(), textPaint)
        canvas.restore()
    }
    
    private fun drawCursor(canvas: Canvas) {
        val energy = channelToEnergy(cursorChannel)
        if (energy < visibleMinEnergy || energy > visibleMaxEnergy) return
        
        val x = energyToX(energy)
        val displayCounts = smoothedCounts ?: spectrumCounts
        val count = displayCounts.getOrNull(cursorChannel) ?: 0
        val y = countToY(count)
        
        // Vertical line through full chart height
        canvas.drawLine(x, chartRect.top, x, chartRect.bottom, cursorPaint)
        
        // Crosshair circle at data point
        canvas.drawCircle(x, y, 8f, cursorPaint)
        canvas.drawCircle(x, y, 4f, Paint().apply { 
            color = colorYellow
            style = Paint.Style.FILL 
        })
        
        // Info box - position near the peak (above or below the data point)
        val infoPaint = Paint(textPaint).apply {
            textSize = 22f
            textAlign = Paint.Align.LEFT
            color = colorWhite
        }
        
        val boxPaint = Paint().apply {
            color = Color.argb(230, 26, 26, 30)
            style = Paint.Style.FILL
        }
        val boxBorderPaint = Paint().apply {
            color = colorYellow
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        
        // Measure actual text widths to size box properly
        val line1 = "${energy.toInt()} keV"
        val line2 = "$count counts"
        val line3 = "Ch: $cursorChannel"
        val maxTextWidth = maxOf(
            infoPaint.measureText(line1),
            infoPaint.measureText(line2),
            infoPaint.measureText(line3)
        )
        
        val boxWidth = maxTextWidth + 24f  // Add padding
        val boxHeight = 78f
        val boxPadding = 15f
        
        // Position box near peak: above if there's room, below if at top
        // Also shift left/right based on x position
        val boxX = if (x > chartRect.centerX()) {
            x - boxWidth - boxPadding  // Left of cursor
        } else {
            x + boxPadding  // Right of cursor
        }
        
        // Position vertically near the peak with some offset
        val boxY = if (y - boxHeight - boxPadding > chartRect.top + 20) {
            y - boxHeight - boxPadding  // Above the peak
        } else {
            y + boxPadding  // Below the peak
        }
        
        // Ensure box stays within chart bounds
        val clampedBoxX = boxX.coerceIn(chartRect.left + 5, chartRect.right - boxWidth - 5)
        val clampedBoxY = boxY.coerceIn(chartRect.top + 5, chartRect.bottom - boxHeight - 5)
        
        // Draw box with border
        val boxRect = RectF(clampedBoxX, clampedBoxY, clampedBoxX + boxWidth, clampedBoxY + boxHeight)
        canvas.drawRoundRect(boxRect, 8f, 8f, boxPaint)
        canvas.drawRoundRect(boxRect, 8f, 8f, boxBorderPaint)
        
        // Draw text inside box
        val textX = clampedBoxX + 12
        var textY = clampedBoxY + 22
        canvas.drawText(line1, textX, textY, infoPaint)
        textY += 24
        canvas.drawText(line2, textX, textY, infoPaint)
        textY += 24
        infoPaint.textSize = 18f
        infoPaint.color = colorMuted
        canvas.drawText(line3, textX, textY, infoPaint)
    }
    
    private fun drawInfo(canvas: Canvas) {
        // Scale indicator
        val scaleText = if (useLogScale) "LOG" else "LIN"
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 24f
        textPaint.color = colorMuted
        canvas.drawText("Y-Scale: $scaleText", chartRect.left + 10, chartRect.top + 25, textPaint)
        
        // Total counts
        val totalCounts = spectrumCounts.sum()
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Total: ${formatCount(totalCounts)}", chartRect.right - 10, chartRect.top + 25, textPaint)
        
        textPaint.color = colorText  // Reset
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun calculateNiceStep(range: Float, targetSteps: Int): Float {
        val roughStep = range / targetSteps
        val magnitude = 10f.pow(kotlin.math.floor(log10(roughStep)))
        val normalized = roughStep / magnitude
        
        val niceStep = when {
            normalized <= 1 -> 1f
            normalized <= 2 -> 2f
            normalized <= 5 -> 5f
            else -> 10f
        }
        
        return niceStep * magnitude
    }
    
    private fun formatCount(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
            count >= 1_000 -> String.format("%.1fK", count / 1_000f)
            else -> count.toString()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TOUCH HANDLING
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount
        
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartTime = System.currentTimeMillis()
                isMultiTouch = false
                isDraggingCursor = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger down - this is a pinch gesture
                isMultiTouch = true
                isDraggingCursor = false
                showCursor = false
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isMultiTouch && pointerCount == 1) {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    // Start cursor after 150ms to distinguish from pinch start
                    if (elapsed > 150 && chartRect.contains(event.x, event.y)) {
                        isDraggingCursor = true
                        updateCursorPositionWithSnapping(event.x, true)  // Snap to peak when dragging
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - touchStartTime
                // Quick tap - show cursor at exact position (no snapping)
                if (!isMultiTouch && elapsed < 200 && chartRect.contains(event.x, event.y)) {
                    updateCursorPositionWithSnapping(event.x, false)  // No snapping on tap
                }
                isDraggingCursor = false
                isMultiTouch = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isDraggingCursor = false
                isMultiTouch = false
            }
        }
        
        // Always pass to scale detector for pinch zoom
        scaleDetector.onTouchEvent(event)
        
        // Pass to gesture detector for scroll/double-tap, but not during cursor drag
        if (!isDraggingCursor) {
            gestureDetector.onTouchEvent(event)
        }
        
        return true
    }
    
    private fun updateCursorPositionWithSnapping(x: Float, snapToPeak: Boolean) {
        val energy = xToEnergy(x)
        var channel = energyToChannel(energy)
        
        if (snapToPeak && peakChannels.isNotEmpty()) {
            // Find nearest peak within snapping distance (e.g., 20 channels)
            val snapDistance = 20
            val nearestPeak = peakChannels.minByOrNull { kotlin.math.abs(it - channel) }
            if (nearestPeak != null && kotlin.math.abs(nearestPeak - channel) <= snapDistance) {
                channel = nearestPeak
            }
        }
        
        cursorChannel = channel
        showCursor = true
        invalidate()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SMOOTHING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Set the smoothing factor (0 = no smoothing, higher = more smoothing)
     * Uses a moving average window of (2*factor + 1) channels
     */
    fun setSmoothing(factor: Int) {
        smoothingFactor = factor.coerceIn(0, 20)
        applySmoothing()
        invalidate()
    }
    
    fun getSmoothing(): Int = smoothingFactor
    
    private fun applySmoothing() {
        if (smoothingFactor == 0) {
            smoothedCounts = null
            findPeaks(spectrumCounts)
            return
        }
        
        val windowSize = 2 * smoothingFactor + 1
        val result = IntArray(spectrumCounts.size)
        
        for (i in spectrumCounts.indices) {
            var sum = 0L
            var count = 0
            for (j in (i - smoothingFactor)..(i + smoothingFactor)) {
                if (j in spectrumCounts.indices) {
                    sum += spectrumCounts[j]
                    count++
                }
            }
            result[i] = (sum / count).toInt()
        }
        
        smoothedCounts = result
        findPeaks(result)
    }
    
    private fun findPeaks(counts: IntArray) {
        // Find local maxima that are significant peaks
        val peaks = mutableListOf<Int>()
        val minPeakHeight = counts.maxOrNull()?.let { it / 20 } ?: 10  // At least 5% of max
        
        for (i in 2 until counts.size - 2) {
            val current = counts[i]
            // Check if this is a local maximum
            if (current > counts[i - 1] && current > counts[i + 1] &&
                current > counts[i - 2] && current > counts[i + 2] &&
                current >= minPeakHeight) {
                peaks.add(i)
            }
        }
        
        peakChannels = peaks
    }
}
