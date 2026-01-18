package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import com.radiacode.ble.RadiationComparison

/**
 * Safety Context Card - "Is This Safe?" display
 * Shows traffic-light safety indicator with contextual comparisons
 */
class SafetyContextCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private var currentDoseUSvH: Float = 0f
    private var safetyStatus: RadiationComparison.SafetyStatus? = null
    private var quickSummary: String = ""
    private var comparisons: List<RadiationComparison.Comparison> = emptyList()

    // Colors
    private val bgColor = ContextCompat.getColor(context, R.color.pro_surface)
    private val borderColor = ContextCompat.getColor(context, R.color.pro_border)
    private val textPrimary = ContextCompat.getColor(context, R.color.pro_text_primary)
    private val textSecondary = ContextCompat.getColor(context, R.color.pro_text_secondary)
    private val textMuted = ContextCompat.getColor(context, R.color.pro_text_muted)
    private val greenColor = ContextCompat.getColor(context, R.color.pro_green)
    private val amberColor = ContextCompat.getColor(context, R.color.pro_yellow)
    private val redColor = ContextCompat.getColor(context, R.color.pro_red)

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = borderColor
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 11f
        color = textMuted
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.1f
    }

    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 28f
        color = greenColor
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 14f
        color = textSecondary
    }

    private val comparisonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 13f
        color = textPrimary
    }

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaledDensity * 16f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun updateReading(uSvPerHour: Float) {
        currentDoseUSvH = uSvPerHour
        safetyStatus = RadiationComparison.getSafetyStatus(uSvPerHour)
        quickSummary = RadiationComparison.getQuickSummary(uSvPerHour)
        comparisons = RadiationComparison.getComparisons(uSvPerHour).take(4)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (density * 180f).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = density * 12f
        val padding = density * 12f

        // Background
        val rect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // Header label
        canvas.drawText("IS THIS SAFE?", padding, padding + labelPaint.textSize, labelPaint)

        // Safety status with colored dot
        val status = safetyStatus ?: return
        val statusColor = Color.parseColor("#${status.colorHex}")
        
        // Large status dot
        dotPaint.color = statusColor
        val dotRadius = density * 10f
        val dotY = padding + labelPaint.textSize + density * 24f
        canvas.drawCircle(padding + dotRadius, dotY, dotRadius, dotPaint)
        
        // Glow effect
        dotPaint.maskFilter = BlurMaskFilter(density * 6f, BlurMaskFilter.Blur.NORMAL)
        dotPaint.alpha = 100
        canvas.drawCircle(padding + dotRadius, dotY, dotRadius + density * 3f, dotPaint)
        dotPaint.maskFilter = null
        dotPaint.alpha = 255

        // Status text
        statusPaint.color = statusColor
        canvas.drawText(
            status.shortDescription.uppercase(),
            padding + dotRadius * 2 + density * 12f,
            dotY + statusPaint.textSize / 3f,
            statusPaint
        )

        // Quick summary
        val summaryY = dotY + density * 32f
        canvas.drawText(quickSummary, padding, summaryY, summaryPaint)

        // Comparison items
        val compStartY = summaryY + density * 24f
        val lineHeight = density * 22f

        comparisons.forEachIndexed { index, comparison ->
            val y = compStartY + index * lineHeight
            
            // Emoji
            canvas.drawText(comparison.emoji, padding, y, emojiPaint)
            
            // Description
            canvas.drawText(
                comparison.description,
                padding + density * 28f,
                y,
                comparisonPaint
            )
        }
    }
}
