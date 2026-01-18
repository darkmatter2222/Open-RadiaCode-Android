package com.radiacode.ble.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.radiacode.ble.CumulativeDoseTracker
import com.radiacode.ble.R
import com.radiacode.ble.RadiationComparison

/**
 * Cumulative Dose Card View
 * Displays accumulated dose for session, today, week, and all-time.
 * Includes contextual comparisons and safety indicators.
 */
class CumulativeDoseCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    // Data
    private var cumulativeDose: CumulativeDoseTracker.CumulativeDose? = null
    private var currentDoseRate: Float = 0f
    private var isExpanded: Boolean = false

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pro_surface)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
        color = ContextCompat.getColor(context, R.color.pro_border)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 10f
        color = ContextCompat.getColor(context, R.color.pro_text_muted)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.1f
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 11f
        color = ContextCompat.getColor(context, R.color.pro_text_muted)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.1f
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 28f
        color = ContextCompat.getColor(context, R.color.pro_text_primary)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val smallValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 16f
        color = ContextCompat.getColor(context, R.color.pro_text_primary)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 12f
        color = ContextCompat.getColor(context, R.color.pro_text_secondary)
    }

    private val contextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 11f
        color = ContextCompat.getColor(context, R.color.pro_text_secondary)
    }

    private val accentCyan = ContextCompat.getColor(context, R.color.pro_cyan)
    private val accentGreen = ContextCompat.getColor(context, R.color.pro_green)
    private val accentAmber = ContextCompat.getColor(context, R.color.pro_amber)
    private val accentRed = ContextCompat.getColor(context, R.color.pro_red)
    
    private val cornerRadius = density * 16f
    private val padding = density * 12f
    
    private val cardRect = RectF()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        
        // Allow clicking to expand/collapse
        isClickable = true
        isFocusable = true
        setOnClickListener {
            isExpanded = !isExpanded
            requestLayout()
            invalidate()
        }
    }

    /**
     * Update the cumulative dose data
     */
    fun setData(dose: CumulativeDoseTracker.CumulativeDose, currentRate: Float) {
        cumulativeDose = dose
        currentDoseRate = currentRate
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Height depends on expanded state
        val height = if (isExpanded) {
            (density * 200).toInt()  // Expanded height
        } else {
            (density * 120).toInt()  // Collapsed height
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw card background
        cardRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

        val dose = cumulativeDose ?: return
        
        // Title
        var y = padding + titlePaint.textSize
        canvas.drawText("CUMULATIVE DOSE", padding, y, titlePaint)
        
        // Expand indicator
        val expandText = if (isExpanded) "▼" else "▶"
        val expandWidth = titlePaint.measureText(expandText)
        canvas.drawText(expandText, w - padding - expandWidth, y, titlePaint)

        y += density * 4

        // Session dose (main hero value)
        y += valuePaint.textSize + density * 4
        val sessionDoseText = CumulativeDoseTracker.formatDose(dose.sessionDoseUSv)
        valuePaint.color = accentCyan
        canvas.drawText(sessionDoseText, padding, y, valuePaint)
        
        // Duration next to dose
        val doseWidth = valuePaint.measureText(sessionDoseText)
        val durationText = "in ${CumulativeDoseTracker.formatDuration(dose.sessionDurationMs)}"
        unitPaint.color = ContextCompat.getColor(context, R.color.pro_text_muted)
        canvas.drawText(durationText, padding + doseWidth + density * 8, y - density * 4, unitPaint)

        // Contextual comparison (quick summary)
        y += contextPaint.textSize + density * 6
        val quickSummary = RadiationComparison.getQuickSummary(currentDoseRate)
        canvas.drawText(quickSummary, padding, y, contextPaint)

        // Time periods row
        y += density * 16
        val columnWidth = (w - padding * 2) / 3
        
        // Today
        drawTimeColumn(canvas, padding, y, "TODAY", dose.todayDoseUSv, columnWidth)
        
        // This Week  
        drawTimeColumn(canvas, padding + columnWidth, y, "WEEK", dose.weekDoseUSv, columnWidth)
        
        // All-time
        drawTimeColumn(canvas, padding + columnWidth * 2, y, "ALL TIME", dose.allTimeDoseUSv, columnWidth)

        // Expanded content
        if (isExpanded) {
            y += density * 50
            
            // Divider
            val dividerPaint = Paint().apply {
                color = ContextCompat.getColor(context, R.color.pro_border)
                strokeWidth = density
            }
            canvas.drawLine(padding, y, w - padding, y, dividerPaint)
            y += density * 12
            
            // Statistics row
            y += labelPaint.textSize
            canvas.drawText("SESSION STATS", padding, y, labelPaint)
            y += density * 8
            
            // Average and Peak
            val statsColumnWidth = (w - padding * 2) / 2
            
            // Average dose rate
            drawStatColumn(canvas, padding, y, "Avg Rate", 
                String.format("%.3f μSv/h", dose.averageDoseRate), statsColumnWidth)
            
            // Peak dose rate
            drawStatColumn(canvas, padding + statsColumnWidth, y, "Peak Rate",
                String.format("%.3f μSv/h", dose.peakDoseRate), statsColumnWidth)
            
            // Route recording indicator
            if (dose.isRouteRecording) {
                y += density * 40
                contextPaint.color = accentGreen
                canvas.drawText("● Recording Route: ${CumulativeDoseTracker.formatDose(dose.routeDoseUSv)}", 
                    padding, y, contextPaint)
            }
        }
    }
    
    private fun drawTimeColumn(canvas: Canvas, x: Float, y: Float, label: String, doseUSv: Double, width: Float) {
        // Label
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, x, y, labelPaint)
        
        // Value
        val valueY = y + smallValuePaint.textSize + density * 2
        smallValuePaint.color = ContextCompat.getColor(context, R.color.pro_text_primary)
        canvas.drawText(CumulativeDoseTracker.formatDose(doseUSv), x, valueY, smallValuePaint)
    }
    
    private fun drawStatColumn(canvas: Canvas, x: Float, y: Float, label: String, value: String, width: Float) {
        // Label
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, x, y, labelPaint)
        
        // Value
        val valueY = y + smallValuePaint.textSize + density * 2
        smallValuePaint.color = ContextCompat.getColor(context, R.color.pro_text_secondary)
        canvas.drawText(value, x, valueY, smallValuePaint)
    }
}
