package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R

/**
 * Chart legend component showing color-coded series labels
 */
class ChartLegendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    data class LegendItem(
        val label: String,
        val color: Int,
        val value: String = ""
    )

    private var items: List<LegendItem> = emptyList()

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 12f
        color = ContextCompat.getColor(context, R.color.pro_text_secondary)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 12f
        color = ContextCompat.getColor(context, R.color.pro_text_primary)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun setItems(items: List<LegendItem>) {
        this.items = items
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (density * 24f).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (items.isEmpty()) return

        val dotRadius = density * 5f
        val spacing = density * 24f
        val dotTextGap = density * 8f

        var x = density * 8f
        val centerY = height / 2f

        for (item in items) {
            // Draw dot
            dotPaint.color = item.color
            canvas.drawCircle(x + dotRadius, centerY, dotRadius, dotPaint)
            x += dotRadius * 2 + dotTextGap

            // Draw label
            canvas.drawText(item.label, x, centerY + labelPaint.textSize / 3f, labelPaint)
            x += labelPaint.measureText(item.label)

            // Draw value if present
            if (item.value.isNotEmpty()) {
                x += density * 4f
                canvas.drawText(item.value, x, centerY + valuePaint.textSize / 3f, valuePaint)
                x += valuePaint.measureText(item.value)
            }

            x += spacing
        }
    }
}
