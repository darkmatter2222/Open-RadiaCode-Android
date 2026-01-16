package com.radiacode.ble.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.radiacode.ble.IsotopeDetector
import com.radiacode.ble.R
import kotlin.math.max
import kotlin.math.min

/**
 * Animated horizontal bar chart for isotope identification display.
 * Shows current top 5 isotopes with animated bars and sparkline history.
 * Great for real-time "what's detected now" visualization.
 */
class IsotopeBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    companion object {
        private val ISOTOPE_COLORS = listOf(
            0xFF00E5FF.toInt(),  // Cyan
            0xFFE040FB.toInt(),  // Magenta  
            0xFF69F0AE.toInt(),  // Green
            0xFFFFD600.toInt(),  // Yellow/Amber
            0xFFFF5252.toInt()   // Red
        )
        
        private const val MAX_BARS = 5
        private const val ANIMATION_DURATION_MS = 300L
        private const val SPARKLINE_POINTS = 30
    }
    
    /**
     * Data for a single bar.
     */
    data class BarData(
        val isotopeId: String,
        val name: String,
        val currentValue: Float,      // 0-1 (probability or fraction)
        val targetValue: Float,       // Value animating towards
        val history: List<Float>,     // Recent history for sparkline
        val color: Int
    )
    
    // Data
    private var bars: List<BarData> = emptyList()
    private var animatedValues: FloatArray = FloatArray(MAX_BARS)
    private var showProbability: Boolean = true
    
    // Animation
    private var animator: ValueAnimator? = null
    
    // Dimensions
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    
    private val barHeight = density * 28f
    private val barSpacing = density * 8f
    private val labelWidth = density * 90f
    private val valueWidth = density * 45f
    private val sparklineWidth = density * 50f
    
    // Paints
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.pro_border)
        alpha = 80
    }
    
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 11f
        color = ContextCompat.getColor(context, R.color.pro_text_secondary)
    }
    
    private val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = scaledDensity * 12f
        color = ContextCompat.getColor(context, R.color.pro_text_primary)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    
    private val sparklinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    
    private val sparklinePath = Path()
    
    /**
     * Update the bar chart with new predictions.
     */
    fun setData(
        predictions: List<IsotopeDetector.Prediction>,
        history: Map<String, List<Float>> = emptyMap(),
        showProbability: Boolean = true
    ) {
        this.showProbability = showProbability
        
        val newBars = predictions.take(MAX_BARS).mapIndexed { index, pred ->
            val value = if (showProbability) pred.probability else pred.fraction
            val historyData = history[pred.isotopeId] ?: emptyList()
            
            BarData(
                isotopeId = pred.isotopeId,
                name = pred.name,
                currentValue = animatedValues.getOrElse(index) { 0f },
                targetValue = value,
                history = historyData.takeLast(SPARKLINE_POINTS),
                color = ISOTOPE_COLORS.getOrElse(index) { ISOTOPE_COLORS[0] }
            )
        }
        
        bars = newBars
        animateToTargets()
        invalidate()
    }
    
    /**
     * Update from analysis result history.
     */
    fun setData(
        history: List<IsotopeDetector.AnalysisResult>,
        showProbability: Boolean = true
    ) {
        if (history.isEmpty()) {
            clear()
            return
        }
        
        this.showProbability = showProbability
        
        val latest = history.last()
        val topFive = latest.topFive
        
        // Build history map for each isotope
        val historyMap = mutableMapOf<String, MutableList<Float>>()
        for (result in history.takeLast(SPARKLINE_POINTS)) {
            for (pred in result.predictions) {
                val values = historyMap.getOrPut(pred.isotopeId) { mutableListOf() }
                values.add(if (showProbability) pred.probability else pred.fraction)
            }
        }
        
        setData(topFive, historyMap, showProbability)
    }
    
    /**
     * Clear all data.
     */
    fun clear() {
        bars = emptyList()
        animatedValues = FloatArray(MAX_BARS)
        animator?.cancel()
        invalidate()
    }
    
    private fun animateToTargets() {
        animator?.cancel()
        
        val startValues = animatedValues.copyOf()
        val targetValues = FloatArray(MAX_BARS) { i -> bars.getOrNull(i)?.targetValue ?: 0f }
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                for (i in 0 until MAX_BARS) {
                    animatedValues[i] = startValues[i] + (targetValues[i] - startValues[i]) * fraction
                }
                invalidate()
            }
            start()
        }
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = ((barHeight + barSpacing) * MAX_BARS + density * 8f).toInt()
        
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> min(desiredWidth, MeasureSpec.getSize(widthMeasureSpec))
            else -> desiredWidth
        }
        
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(desiredHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight
        }
        
        setMeasuredDimension(width, height)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width == 0 || height == 0) return
        
        val startX = paddingLeft.toFloat()
        var y = paddingTop + density * 4f
        
        val availableBarWidth = width - paddingLeft - paddingRight - labelWidth - valueWidth - sparklineWidth - density * 16f
        
        for ((index, bar) in bars.withIndex()) {
            if (index >= MAX_BARS) break
            
            val animValue = animatedValues.getOrElse(index) { bar.currentValue }
            
            // Draw label (truncated if needed)
            val displayName = if (bar.name.length > 12) bar.name.take(10) + "…" else bar.name
            labelTextPaint.color = bar.color
            canvas.drawText(displayName, startX, y + barHeight / 2f + labelTextPaint.textSize / 3f, labelTextPaint)
            
            // Draw bar background
            val barX = startX + labelWidth
            val barRight = barX + availableBarWidth
            val cornerRadius = barHeight / 3f
            
            canvas.drawRoundRect(
                barX, y, barRight, y + barHeight,
                cornerRadius, cornerRadius, barBgPaint
            )
            
            // Draw filled bar
            val fillWidth = availableBarWidth * animValue.coerceIn(0f, 1f)
            if (fillWidth > 0) {
                barPaint.color = bar.color
                barPaint.alpha = 200
                canvas.drawRoundRect(
                    barX, y, barX + fillWidth, y + barHeight,
                    cornerRadius, cornerRadius, barPaint
                )
            }
            
            // Draw value text
            val valueText = if (showProbability) {
                "${(animValue * 100).toInt()}%"
            } else {
                "%.2f".format(animValue)
            }
            val valueX = barRight + density * 8f
            valueTextPaint.color = bar.color
            canvas.drawText(valueText, valueX, y + barHeight / 2f + valueTextPaint.textSize / 3f, valueTextPaint)
            
            // Draw sparkline
            if (bar.history.size > 1) {
                drawSparkline(canvas, bar, valueX + valueWidth, y, sparklineWidth, barHeight)
            }
            
            y += barHeight + barSpacing
        }
        
        // Draw "No data" if empty
        if (bars.isEmpty()) {
            val noDataPaint = Paint(labelTextPaint)
            noDataPaint.color = ContextCompat.getColor(context, R.color.pro_text_muted)
            noDataPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "No isotope data",
                width / 2f,
                height / 2f,
                noDataPaint
            )
        }
    }
    
    private fun drawSparkline(
        canvas: Canvas,
        bar: BarData,
        x: Float,
        y: Float,
        w: Float,
        h: Float
    ) {
        if (bar.history.size < 2) return
        
        sparklinePath.reset()
        sparklinePaint.color = bar.color
        sparklinePaint.alpha = 180
        
        val padding = density * 2f
        val chartX = x + padding
        val chartY = y + padding
        val chartW = w - padding * 2
        val chartH = h - padding * 2
        
        val minVal = bar.history.minOrNull() ?: 0f
        val maxVal = bar.history.maxOrNull() ?: 1f
        val range = max(0.01f, maxVal - minVal)
        
        for ((i, v) in bar.history.withIndex()) {
            val px = chartX + (i.toFloat() / (bar.history.size - 1)) * chartW
            val py = chartY + chartH - ((v - minVal) / range) * chartH
            
            if (i == 0) {
                sparklinePath.moveTo(px, py)
            } else {
                sparklinePath.lineTo(px, py)
            }
        }
        
        canvas.drawPath(sparklinePath, sparklinePaint)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
