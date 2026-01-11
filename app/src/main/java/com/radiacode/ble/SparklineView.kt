package com.radiacode.ble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
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

    private var samples: List<Float> = emptyList()

    fun setSamples(samples: List<Float>) {
        this.samples = samples
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF00BCD4.toInt())
        val colorOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF.toInt())

        linePaint.color = colorPrimary
        fillPaint.color = colorPrimary
        gridPaint.color = colorOnSurface

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

        val n = samples.size
        for (i in 0 until n) {
            val x = left + (right - left) * (i.toFloat() / (n - 1))
            val yNorm = (samples[i] - minY) / denom
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
    }
}
