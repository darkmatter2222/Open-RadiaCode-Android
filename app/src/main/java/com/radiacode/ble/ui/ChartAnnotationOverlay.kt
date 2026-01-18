package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * Chart Annotations & Bookmarks View
 * Allows users to add timestamped notes/bookmarks on charts
 */
class ChartAnnotationOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    data class Annotation(
        val id: String = UUID.randomUUID().toString(),
        val timestampMs: Long,
        val text: String,
        val type: AnnotationType = AnnotationType.NOTE,
        val valueAtTime: Float? = null
    )

    enum class AnnotationType {
        NOTE,       // User note
        HOTSPOT,    // Marked hotspot
        LOCATION,   // Location change
        EVENT       // System event (spike, anomaly)
    }

    private val annotations = mutableListOf<Annotation>()
    private var timeRangeStartMs: Long = 0
    private var timeRangeEndMs: Long = System.currentTimeMillis()
    private var chartBounds: RectF = RectF()

    private var selectedAnnotation: Annotation? = null
    private var onAnnotationClick: ((Annotation) -> Unit)? = null
    private var onAddAnnotation: ((Long) -> Unit)? = null

    // Colors
    private val noteColor = ContextCompat.getColor(context, R.color.pro_cyan)
    private val hotspotColor = ContextCompat.getColor(context, R.color.pro_red)
    private val locationColor = ContextCompat.getColor(context, R.color.pro_green)
    private val eventColor = ContextCompat.getColor(context, R.color.pro_yellow)
    private val textColor = ContextCompat.getColor(context, R.color.pro_text_primary)
    private val bgColor = ContextCompat.getColor(context, R.color.pro_surface)

    // Paints
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        pathEffect = DashPathEffect(floatArrayOf(density * 4f, density * 2f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 10f
        color = textColor
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun setChartBounds(bounds: RectF) {
        chartBounds = bounds
        invalidate()
    }

    fun setTimeRange(startMs: Long, endMs: Long) {
        timeRangeStartMs = startMs
        timeRangeEndMs = endMs
        invalidate()
    }

    fun addAnnotation(annotation: Annotation) {
        annotations.add(annotation)
        invalidate()
    }

    fun removeAnnotation(id: String) {
        annotations.removeAll { it.id == id }
        invalidate()
    }

    fun clearAnnotations() {
        annotations.clear()
        invalidate()
    }

    fun getAnnotations(): List<Annotation> = annotations.toList()

    fun setOnAnnotationClickListener(listener: (Annotation) -> Unit) {
        onAnnotationClick = listener
    }

    fun setOnAddAnnotationListener(listener: (Long) -> Unit) {
        onAddAnnotation = listener
    }

    private fun getColorForType(type: AnnotationType): Int = when (type) {
        AnnotationType.NOTE -> noteColor
        AnnotationType.HOTSPOT -> hotspotColor
        AnnotationType.LOCATION -> locationColor
        AnnotationType.EVENT -> eventColor
    }

    private fun getIconForType(type: AnnotationType): String = when (type) {
        AnnotationType.NOTE -> "📝"
        AnnotationType.HOTSPOT -> "📍"
        AnnotationType.LOCATION -> "📍"
        AnnotationType.EVENT -> "⚠️"
    }

    private fun timestampToX(timestampMs: Long): Float {
        if (timeRangeEndMs <= timeRangeStartMs) return chartBounds.left
        val ratio = (timestampMs - timeRangeStartMs).toFloat() / (timeRangeEndMs - timeRangeStartMs)
        return chartBounds.left + ratio * chartBounds.width()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (chartBounds.isEmpty) return

        annotations.forEach { annotation ->
            val x = timestampToX(annotation.timestampMs)
            if (x < chartBounds.left || x > chartBounds.right) return@forEach

            val color = getColorForType(annotation.type)
            val isSelected = selectedAnnotation?.id == annotation.id

            // Vertical line
            linePaint.color = color
            linePaint.alpha = if (isSelected) 255 else 150
            canvas.drawLine(x, chartBounds.top, x, chartBounds.bottom, linePaint)

            // Marker circle at top
            markerPaint.color = color
            val markerRadius = if (isSelected) density * 10f else density * 8f
            canvas.drawCircle(x, chartBounds.top + markerRadius, markerRadius, markerPaint)

            // Icon
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = scaledDensity * 12f
            canvas.drawText(getIconForType(annotation.type), x, chartBounds.top + markerRadius + textPaint.textSize / 3f, textPaint)

            // Tooltip for selected annotation
            if (isSelected) {
                drawTooltip(canvas, x, annotation)
            }
        }
    }

    private fun drawTooltip(canvas: Canvas, x: Float, annotation: Annotation) {
        val padding = density * 8f
        val maxWidth = density * 150f

        textPaint.textSize = scaledDensity * 11f
        textPaint.textAlign = Paint.Align.LEFT

        val timeText = timeFormat.format(Date(annotation.timestampMs))
        val lines = mutableListOf<String>()
        lines.add(timeText)
        
        // Wrap text
        val words = annotation.text.split(" ")
        var currentLine = StringBuilder()
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (textPaint.measureText(testLine) < maxWidth - 2 * padding) {
                currentLine = StringBuilder(testLine)
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())

        annotation.valueAtTime?.let {
            lines.add(String.format("%.3f μSv/h", it))
        }

        val lineHeight = textPaint.textSize + density * 3f
        val tooltipHeight = lines.size * lineHeight + 2 * padding
        val tooltipWidth = (lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f) + 2 * padding

        // Position tooltip (avoid going off screen)
        var tooltipX = x - tooltipWidth / 2
        if (tooltipX < chartBounds.left) tooltipX = chartBounds.left
        if (tooltipX + tooltipWidth > chartBounds.right) tooltipX = chartBounds.right - tooltipWidth

        val tooltipY = chartBounds.top + density * 30f

        // Background
        val rect = RectF(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight)
        bgPaint.color = bgColor
        canvas.drawRoundRect(rect, density * 6f, density * 6f, bgPaint)

        // Border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 1f
            color = getColorForType(annotation.type)
        }
        canvas.drawRoundRect(rect, density * 6f, density * 6f, borderPaint)

        // Text
        textPaint.color = textColor
        lines.forEachIndexed { index, line ->
            val textY = tooltipY + padding + (index + 1) * lineHeight - density * 3f
            canvas.drawText(line, tooltipX + padding, textY, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check if tapping on a marker
                val tappedAnnotation = annotations.find { annotation ->
                    val x = timestampToX(annotation.timestampMs)
                    val markerY = chartBounds.top + density * 8f
                    val dx = event.x - x
                    val dy = event.y - markerY
                    dx * dx + dy * dy < (density * 20f) * (density * 20f)
                }

                if (tappedAnnotation != null) {
                    selectedAnnotation = if (selectedAnnotation?.id == tappedAnnotation.id) null else tappedAnnotation
                    invalidate()
                    return true
                }

                // Long-press detection would go here for adding annotations
                selectedAnnotation = null
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                selectedAnnotation?.let {
                    onAnnotationClick?.invoke(it)
                }
            }
        }

        return super.onTouchEvent(event)
    }
}
