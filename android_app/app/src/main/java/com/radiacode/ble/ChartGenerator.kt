package com.radiacode.ble

import android.content.Context
import android.graphics.*
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced Chart Generation Utility
 * 
 * Provides multiple chart types for widget display:
 * - Sparkline (default)
 * - Bar chart
 * - Candlestick chart
 * - Area chart
 */
object ChartGenerator {

    /**
     * Generate a sparkline chart bitmap
     */
    fun generateSparkline(
        context: Context,
        values: List<Float>,
        width: Int,
        height: Int,
        lineColor: Int,
        fillColor: Int,
        backgroundColor: Int = Color.TRANSPARENT,
        showGlow: Boolean = true
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (values.isEmpty() || values.size < 2) {
            drawEmptyChart(canvas, width, height, backgroundColor, lineColor)
            return bitmap
        }

        // Background
        if (backgroundColor != Color.TRANSPARENT) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = backgroundColor
            }
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
        }

        val padding = 4f
        val chartWidth = width - 2 * padding
        val chartHeight = height - 2 * padding

        val minVal = values.minOrNull() ?: 0f
        val maxVal = values.maxOrNull() ?: 1f
        val range = if (maxVal - minVal > 0.001f) maxVal - minVal else 1f

        // Create path
        val path = Path()
        val fillPath = Path()

        values.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range) * chartHeight

            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height.toFloat())
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Complete fill path
        fillPath.lineTo(width - padding, height.toFloat())
        fillPath.close()

        // Draw glow
        if (showGlow) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f
                color = lineColor
                alpha = 50
                maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawPath(path, glowPaint)
        }

        // Draw fill
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                fillColor, Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(fillPath, fillPaint)

        // Draw line
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = lineColor
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, linePaint)

        return bitmap
    }

    /**
     * Generate a bar chart bitmap
     */
    fun generateBarChart(
        context: Context,
        values: List<Float>,
        width: Int,
        height: Int,
        barColor: Int,
        backgroundColor: Int = Color.TRANSPARENT,
        showOutline: Boolean = true
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (values.isEmpty()) {
            drawEmptyChart(canvas, width, height, backgroundColor, barColor)
            return bitmap
        }

        // Background
        if (backgroundColor != Color.TRANSPARENT) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = backgroundColor
            }
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
        }

        val padding = 4f
        val chartWidth = width - 2 * padding
        val chartHeight = height - 2 * padding
        val barGap = 2f
        val barWidth = (chartWidth - barGap * (values.size - 1)) / values.size

        val minVal = 0f // Bar charts typically start from 0
        val maxVal = values.maxOrNull() ?: 1f
        val range = if (maxVal > 0.001f) maxVal else 1f

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = barColor
        }

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = barColor
            alpha = 100
        }

        values.forEachIndexed { index, value ->
            val x = padding + index * (barWidth + barGap)
            val barHeight = ((value - minVal) / range) * chartHeight
            val y = padding + chartHeight - barHeight

            val rect = RectF(x, y, x + barWidth, padding + chartHeight)
            canvas.drawRoundRect(rect, 2f, 2f, barPaint)

            if (showOutline) {
                canvas.drawRoundRect(rect, 2f, 2f, outlinePaint)
            }
        }

        return bitmap
    }

    /**
     * Generate a candlestick chart bitmap
     * 
     * Candlestick data structure:
     * Each "candle" represents a time period with OHLC (Open, High, Low, Close)
     * For radiation data, we interpret this as:
     * - Open: First reading in period
     * - Close: Last reading in period
     * - High: Max reading in period
     * - Low: Min reading in period
     */
    fun generateCandlestickChart(
        context: Context,
        candles: List<CandleData>,
        width: Int,
        height: Int,
        upColor: Int, // Close > Open (green)
        downColor: Int, // Close < Open (red)
        wickColor: Int,
        backgroundColor: Int = Color.TRANSPARENT
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (candles.isEmpty()) {
            drawEmptyChart(canvas, width, height, backgroundColor, upColor)
            return bitmap
        }

        // Background
        if (backgroundColor != Color.TRANSPARENT) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = backgroundColor
            }
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
        }

        val padding = 4f
        val chartWidth = width - 2 * padding
        val chartHeight = height - 2 * padding
        val candleGap = 2f
        val candleWidth = (chartWidth - candleGap * (candles.size - 1)) / candles.size
        val wickWidth = 1f

        val minVal = candles.minOfOrNull { it.low } ?: 0f
        val maxVal = candles.maxOfOrNull { it.high } ?: 1f
        val range = if (maxVal - minVal > 0.001f) maxVal - minVal else 1f

        val wickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = wickColor
            strokeWidth = wickWidth
        }

        candles.forEachIndexed { index, candle ->
            val centerX = padding + index * (candleWidth + candleGap) + candleWidth / 2

            // Normalize values to chart coordinates
            fun toY(value: Float) = padding + chartHeight - ((value - minVal) / range) * chartHeight

            val highY = toY(candle.high)
            val lowY = toY(candle.low)
            val openY = toY(candle.open)
            val closeY = toY(candle.close)

            // Draw wick (high to low)
            canvas.drawLine(centerX, highY, centerX, lowY, wickPaint)

            // Draw body
            val isUp = candle.close >= candle.open
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isUp) upColor else downColor
            }

            val bodyTop = min(openY, closeY)
            val bodyBottom = max(openY, closeY)
            val bodyLeft = padding + index * (candleWidth + candleGap)
            val bodyRight = bodyLeft + candleWidth

            // Ensure minimum body height for visibility
            val minBodyHeight = 2f
            val actualBottom = if (bodyBottom - bodyTop < minBodyHeight) bodyTop + minBodyHeight else bodyBottom

            canvas.drawRect(bodyLeft, bodyTop, bodyRight, actualBottom, bodyPaint)
        }

        return bitmap
    }

    /**
     * Generate an area chart (filled line chart)
     */
    fun generateAreaChart(
        context: Context,
        values: List<Float>,
        width: Int,
        height: Int,
        lineColor: Int,
        fillColor: Int,
        backgroundColor: Int = Color.TRANSPARENT
    ): Bitmap {
        // Area chart is essentially a sparkline with more prominent fill
        return generateSparkline(
            context, values, width, height, 
            lineColor, 
            Color.argb(100, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor)),
            backgroundColor,
            showGlow = false
        )
    }

    /**
     * Convert raw readings to candlestick data
     * Groups readings into time buckets and calculates OHLC values
     */
    fun convertToCandlesticks(
        readings: List<Pair<Long, Float>>, // timestamp, value pairs
        bucketCount: Int = 10
    ): List<CandleData> {
        if (readings.isEmpty()) return emptyList()
        if (readings.size < 4) {
            // Not enough data for meaningful candles, return simple candles
            return readings.map { (_, value) ->
                CandleData(value, value, value, value)
            }
        }

        val sortedReadings = readings.sortedBy { it.first }
        val bucketSize = (sortedReadings.size + bucketCount - 1) / bucketCount

        return sortedReadings.chunked(bucketSize).map { bucket ->
            if (bucket.isEmpty()) {
                CandleData(0f, 0f, 0f, 0f)
            } else {
                val values = bucket.map { it.second }
                CandleData(
                    open = values.first(),
                    close = values.last(),
                    high = values.maxOrNull() ?: 0f,
                    low = values.minOrNull() ?: 0f
                )
            }
        }
    }

    /**
     * Draw empty chart placeholder
     */
    private fun drawEmptyChart(canvas: Canvas, width: Int, height: Int, backgroundColor: Int, lineColor: Int) {
        if (backgroundColor != Color.TRANSPARENT) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = backgroundColor
            }
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
        }

        // Draw dashed line to indicate no data
        val centerY = height / 2f
        val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            alpha = 50
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        }
        canvas.drawLine(8f, centerY, width - 8f, centerY, dashPaint)
    }
}

/**
 * Data class for candlestick chart
 */
data class CandleData(
    val open: Float,
    val close: Float,
    val high: Float,
    val low: Float
)
