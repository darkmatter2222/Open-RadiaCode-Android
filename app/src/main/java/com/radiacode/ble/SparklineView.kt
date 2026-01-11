package com.radiacode.ble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.google.android.material.color.MaterialColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 40
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1f
        alpha = 40
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        alpha = 180
    }

    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 220
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 220
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = resources.displayMetrics.scaledDensity * 12f
    }

    private var timestampsMs: List<Long> = emptyList()
    private var samples: List<Float> = emptyList()

    // Viewport controls (x-axis). 1.0 = full range.
    private var zoomX: Float = 1.0f
    private var panX: Float = 0.0f // 0..1, fraction of range

    private var selectedIndex: Int? = null

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.US)

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                selectedIndex = pickIndexForX(e.x)
                invalidate()
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                selectedIndex = null
                invalidate()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (samples.size < 2) return false
                // Pan by horizontal drag.
                val w = width.toFloat().coerceAtLeast(1f)
                val frac = distanceX / w
                setViewport(zoomX = zoomX, panX = panX + frac)
                return true
            }
        }
    )

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (!factor.isFinite() || factor <= 0f) return false
                val newZoom = (zoomX / factor).coerceIn(1.0f, 20.0f)
                setViewport(zoomX = newZoom, panX = panX)
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        return scaled || gestured || super.onTouchEvent(event)
    }

    fun setSamples(samples: List<Float>) {
        // Back-compat path (no timestamps)
        this.timestampsMs = emptyList()
        this.samples = samples
        this.selectedIndex = null
        this.zoomX = 1.0f
        this.panX = 0.0f
        invalidate()
    }

    fun setSeries(timestampsMs: List<Long>, samples: List<Float>) {
        this.timestampsMs = timestampsMs
        this.samples = samples
        this.selectedIndex = null
        this.zoomX = 1.0f
        this.panX = 0.0f
        invalidate()
    }

    private fun setViewport(zoomX: Float, panX: Float) {
        this.zoomX = zoomX
        val maxPan = 1.0f - (1.0f / zoomX)
        this.panX = panX.coerceIn(0.0f, max(0.0f, maxPan))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF00BCD4.toInt())
        val colorOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF.toInt())

        val colorSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFF000000.toInt())

        linePaint.color = colorPrimary
        fillPaint.color = colorPrimary
        gridPaint.color = colorOnSurface
        markerPaint.color = colorOnSurface
        markerFillPaint.color = colorPrimary
        tooltipBgPaint.color = colorSurface
        tooltipTextPaint.color = colorOnSurface

        // Padding inside the view so the line isn't clipped.
        val pad = resources.displayMetrics.density * 8f
        val left = pad
        val top = pad
        val right = w - pad
        val bottom = h - pad

        // Simple grid.
        val rows = 2
        for (i in 1..rows) {
            val y = top + (bottom - top) * (i.toFloat() / (rows + 1))
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        if (samples.size < 2) return

        val (startIndex, endIndex) = visibleRange()
        if (endIndex - startIndex < 2) return

        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (v in samples) {
            minV = min(minV, v)
            maxV = max(maxV, v)
        }

        // Avoid divide-by-zero and keep some breathing room.
        val range = max(1e-6f, (maxV - minV))
        val vPad = range * 0.1f
        val minY = minV - vPad
        val maxY = maxV + vPad
        val denom = max(1e-6f, (maxY - minY))

        val path = Path()
        val fill = Path()

        val n = endIndex - startIndex
        for (i in 0 until n) {
            val idx = startIndex + i
            val x = left + (right - left) * (i.toFloat() / (n - 1))
            val yNorm = (samples[idx] - minY) / denom
            val y = bottom - (bottom - top) * yNorm
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, bottom)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }

        fill.lineTo(right, bottom)
        fill.close()

        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)

        selectedIndex?.let { sel ->
            val clamped = sel.coerceIn(startIndex, endIndex - 1)
            val i = clamped - startIndex
            val x = left + (right - left) * (i.toFloat() / (n - 1))
            val yNorm = (samples[clamped] - minY) / denom
            val y = bottom - (bottom - top) * yNorm

            canvas.drawLine(x, top, x, bottom, markerPaint)
            canvas.drawCircle(x, y, resources.displayMetrics.density * 4f, markerFillPaint)

            val ts = timestampsMs.getOrNull(clamped)
            val value = samples[clamped]
            val label = if (ts != null) {
                val t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime()
                String.format(Locale.US, "%.4f @ %s", value, timeFmt.format(t))
            } else {
                String.format(Locale.US, "%.4f", value)
            }

            val padText = resources.displayMetrics.density * 6f
            val textW = tooltipTextPaint.measureText(label)
            val textH = tooltipTextPaint.textSize
            val r = RectF(left, top, min(right, left + textW + padText * 2f), top + textH + padText * 2f)
            canvas.drawRoundRect(r, resources.displayMetrics.density * 6f, resources.displayMetrics.density * 6f, tooltipBgPaint)
            canvas.drawText(label, r.left + padText, r.top + padText + textH * 0.8f, tooltipTextPaint)
        }
    }

    private fun visibleRange(): Pair<Int, Int> {
        val total = samples.size
        if (total <= 0) return 0 to 0

        val span = max(2, (total.toFloat() / zoomX).toInt())
        val maxStart = max(0, total - span)
        val start = (panX * maxStart.toFloat()).toInt().coerceIn(0, maxStart)
        val end = (start + span).coerceIn(0, total)
        return start to end
    }

    private fun pickIndexForX(xPx: Float): Int? {
        val total = samples.size
        if (total < 2) return null
        val w = width.toFloat()
        if (w <= 0f) return null
        val pad = resources.displayMetrics.density * 8f
        val left = pad
        val right = w - pad
        val fx = ((xPx - left) / max(1f, (right - left))).coerceIn(0f, 1f)

        val (start, end) = visibleRange()
        val n = max(2, end - start)
        val i = (fx * (n - 1)).toInt().coerceIn(0, n - 1)
        return start + i
    }
}
