package com.radiacode.ble.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.radiacode.ble.R

/**
 * Data Resolution Selector
 * 
 * Allows users to select chart time windows and data resolution.
 * Options: 1m, 5m, 15m, 1h, 4h, All
 */
class DataResolutionSelector @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Resolution(
        val label: String,
        val minutes: Int,
        val maxPoints: Int,
        val aggregationSeconds: Int
    ) {
        ONE_MIN("1m", 1, 60, 1),
        FIVE_MIN("5m", 5, 150, 2),
        FIFTEEN_MIN("15m", 15, 180, 5),
        ONE_HOUR("1h", 60, 360, 10),
        FOUR_HOUR("4h", 240, 480, 30),
        ALL("All", Int.MAX_VALUE, 600, 60)
    }

    interface OnResolutionChangedListener {
        fun onResolutionChanged(resolution: Resolution)
    }

    private var currentResolution = Resolution.FIVE_MIN
    private var listener: OnResolutionChangedListener? = null
    private val buttons = mutableListOf<TextView>()

    init {
        setupView()
    }

    private fun setupView() {
        // Create horizontal button strip
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            setBackgroundResource(R.drawable.resolution_selector_bg)
        }

        Resolution.values().forEach { resolution ->
            val button = createButton(resolution)
            buttons.add(button)
            container.addView(button)
        }

        addView(container)
        updateSelection()
    }

    private fun createButton(resolution: Resolution): TextView {
        return TextView(context).apply {
            text = resolution.label
            textSize = 12f
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = 2.dp
                marginEnd = 2.dp
            }
            
            gravity = android.view.Gravity.CENTER
            
            setOnClickListener {
                if (currentResolution != resolution) {
                    currentResolution = resolution
                    updateSelection()
                    listener?.onResolutionChanged(resolution)
                }
            }
            
            tag = resolution
        }
    }

    private fun updateSelection() {
        buttons.forEach { button ->
            val resolution = button.tag as Resolution
            val isSelected = resolution == currentResolution
            
            if (isSelected) {
                button.setBackgroundResource(R.drawable.resolution_button_selected)
                button.setTextColor(ContextCompat.getColor(context, R.color.pro_background))
            } else {
                button.setBackgroundResource(R.drawable.resolution_button_unselected)
                button.setTextColor(ContextCompat.getColor(context, R.color.pro_text_secondary))
            }
        }
    }

    fun setResolution(resolution: Resolution) {
        if (currentResolution != resolution) {
            currentResolution = resolution
            updateSelection()
        }
    }

    fun getResolution() = currentResolution

    fun setOnResolutionChangedListener(listener: OnResolutionChangedListener) {
        this.listener = listener
    }

    fun setOnResolutionChanged(block: (Resolution) -> Unit) {
        this.listener = object : OnResolutionChangedListener {
            override fun onResolutionChanged(resolution: Resolution) {
                block(resolution)
            }
        }
    }

    /**
     * Filter data points based on current resolution.
     * Returns a list of data points aggregated to the selected time window.
     */
    fun filterData(data: List<TimestampedValue>, currentTimeMs: Long = System.currentTimeMillis()): List<Float> {
        if (data.isEmpty()) return emptyList()

        val resolution = currentResolution
        val cutoffMs = if (resolution.minutes == Int.MAX_VALUE) {
            0L
        } else {
            currentTimeMs - (resolution.minutes * 60 * 1000L)
        }

        // Filter by time window
        val filtered = data.filter { it.timestampMs >= cutoffMs }
        if (filtered.isEmpty()) return emptyList()

        // Aggregate if needed
        return if (resolution.aggregationSeconds > 1 && filtered.size > resolution.maxPoints) {
            aggregateData(filtered, resolution.aggregationSeconds)
        } else {
            filtered.takeLast(resolution.maxPoints).map { it.value }
        }
    }

    private fun aggregateData(data: List<TimestampedValue>, aggregationSeconds: Int): List<Float> {
        if (data.isEmpty()) return emptyList()

        val bucketMs = aggregationSeconds * 1000L
        val aggregated = mutableListOf<Float>()
        var bucketStart = data.first().timestampMs
        var bucketValues = mutableListOf<Float>()

        data.forEach { point ->
            if (point.timestampMs - bucketStart >= bucketMs) {
                // Save current bucket
                if (bucketValues.isNotEmpty()) {
                    aggregated.add(bucketValues.average().toFloat())
                }
                // Start new bucket
                bucketStart = point.timestampMs
                bucketValues = mutableListOf(point.value)
            } else {
                bucketValues.add(point.value)
            }
        }

        // Don't forget the last bucket
        if (bucketValues.isNotEmpty()) {
            aggregated.add(bucketValues.average().toFloat())
        }

        return aggregated
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()

    /**
     * Data point with timestamp for time-based filtering.
     */
    data class TimestampedValue(
        val value: Float,
        val timestampMs: Long
    )

    companion object {
        /**
         * Convert a simple float list to timestamped values assuming 1-second intervals.
         */
        fun createTimestampedData(values: List<Float>, endTimeMs: Long = System.currentTimeMillis()): List<TimestampedValue> {
            return values.mapIndexed { index, value ->
                TimestampedValue(
                    value = value,
                    timestampMs = endTimeMs - ((values.size - 1 - index) * 1000L)
                )
            }
        }
    }
}
