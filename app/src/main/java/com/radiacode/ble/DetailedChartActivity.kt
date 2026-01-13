package com.radiacode.ble

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.radiacode.ble.ui.ProChartView
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detailed Chart View - A rich analysis view for data scientists
 * 
 * Features:
 * 1. Range Selection - Select a time range for detailed statistics
 * 2. Statistical Overlays - Show mean line and ±1σ/2σ/3σ bands
 * 3. Export - Export visible/selected data as CSV
 * 4. Comparison Mode - Overlay dose and count rate on same chart
 * 5. Live Updates - Continues receiving real-time data
 */
class DetailedChartActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind" // "dose" | "cps"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var detailChart: ProChartView
    
    // Stats display
    private lateinit var statMin: TextView
    private lateinit var statMax: TextView
    private lateinit var statMean: TextView
    private lateinit var statStdDev: TextView
    private lateinit var statSamples: TextView
    
    // Advanced stats
    private lateinit var statMedian: TextView
    private lateinit var statP95: TextView
    private lateinit var statP5: TextView
    private lateinit var statTrend: TextView
    private lateinit var statCV: TextView
    
    // Selection overlay
    private lateinit var selectionOverlay: LinearLayout
    private lateinit var selectionStats: TextView
    private lateinit var zoomIndicator: TextView
    
    // Control chips
    private lateinit var chipSigma1: Chip
    private lateinit var chipSigma2: Chip
    private lateinit var chipSigma3: Chip
    private lateinit var chipMeanLine: Chip
    private lateinit var chipRollingAvg: Chip
    private lateinit var chipCompare: Chip
    private lateinit var chipExport: Chip
    private lateinit var chipSelectRange: Chip
    private lateinit var chipClearSelection: Chip
    private lateinit var chipResetZoom: Chip
    private lateinit var chipGoRealtime: Chip
    
    private var kind: String = "dose"
    private var unit: String = "μSv/h"
    
    // Data
    private var timestampsMs: List<Long> = emptyList()
    private var values: List<Float> = emptyList()
    private var secondaryValues: List<Float> = emptyList() // For comparison mode
    
    // Selection state
    private var isSelectionMode = false
    private var selectionStartIdx: Int? = null
    private var selectionEndIdx: Int? = null
    
    // Live update
    private val mainHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    
    // Sigma bands state
    private var currentSigma: Int = 0 // 0 = off, 1, 2, or 3
    private var showMeanLine: Boolean = false
    private var showRollingAvg: Boolean = true
    private var isCompareMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force landscape orientation for detailed analysis
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        
        setContentView(R.layout.activity_detailed_chart)
        
        bindViews()
        setupToolbar()
        setupControls()
        
        kind = intent?.getStringExtra(EXTRA_KIND)?.lowercase(Locale.US) ?: "dose"
        
        // Set title and unit based on kind
        if (kind == "cps") {
            toolbar.title = "Count Rate Detail"
            unit = when (Prefs.getCountUnit(this, Prefs.CountUnit.CPS)) {
                Prefs.CountUnit.CPS -> "cps"
                Prefs.CountUnit.CPM -> "cpm"
            }
            detailChart.setAccentColor(ContextCompat.getColor(this, R.color.pro_magenta))
        } else {
            toolbar.title = "Dose Rate Detail"
            unit = when (Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)) {
                Prefs.DoseUnit.USV_H -> "μSv/h"
                Prefs.DoseUnit.NSV_H -> "nSv/h"
            }
            detailChart.setAccentColor(ContextCompat.getColor(this, R.color.pro_cyan))
        }
        
        // Load initial data from intent
        loadDataFromIntent()
        
        // Start live updates
        startLiveUpdates()
    }
    
    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        detailChart = findViewById(R.id.detailChart)
        
        statMin = findViewById(R.id.statMin)
        statMax = findViewById(R.id.statMax)
        statMean = findViewById(R.id.statMean)
        statStdDev = findViewById(R.id.statStdDev)
        statSamples = findViewById(R.id.statSamples)
        
        // Advanced stats
        statMedian = findViewById(R.id.statMedian)
        statP95 = findViewById(R.id.statP95)
        statP5 = findViewById(R.id.statP5)
        statTrend = findViewById(R.id.statTrend)
        statCV = findViewById(R.id.statCV)
        
        selectionOverlay = findViewById(R.id.selectionOverlay)
        selectionStats = findViewById(R.id.selectionStats)
        zoomIndicator = findViewById(R.id.zoomIndicator)
        
        chipSigma1 = findViewById(R.id.chipSigma1)
        chipSigma2 = findViewById(R.id.chipSigma2)
        chipSigma3 = findViewById(R.id.chipSigma3)
        chipMeanLine = findViewById(R.id.chipMeanLine)
        chipRollingAvg = findViewById(R.id.chipRollingAvg)
        chipCompare = findViewById(R.id.chipCompare)
        chipExport = findViewById(R.id.chipExport)
        chipSelectRange = findViewById(R.id.chipSelectRange)
        chipClearSelection = findViewById(R.id.chipClearSelection)
        chipResetZoom = findViewById(R.id.chipResetZoom)
        chipGoRealtime = findViewById(R.id.chipGoRealtime)
    }
    
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
    }
    
    private fun setupControls() {
        // Zoom change listener
        detailChart.setOnZoomChangeListener(object : ProChartView.OnZoomChangeListener {
            override fun onZoomChanged(zoomLevel: Float) {
                if (zoomLevel > 1.01f) {
                    zoomIndicator.text = String.format(Locale.US, "%.1fx", zoomLevel)
                    zoomIndicator.visibility = View.VISIBLE
                    chipResetZoom.isEnabled = true
                    chipResetZoom.alpha = 1f
                } else {
                    zoomIndicator.visibility = View.GONE
                    chipResetZoom.alpha = 0.5f
                }
            }
        })
        
        // Real-time state listener
        detailChart.setOnRealTimeStateListener(object : ProChartView.OnRealTimeStateListener {
            override fun onRealTimeStateChanged(isFollowingRealTime: Boolean) {
                chipGoRealtime.alpha = if (isFollowingRealTime) 0.5f else 1f
            }
        })
        
        // Data point selection listener (for range selection)
        detailChart.setOnDataPointSelectedListener(object : ProChartView.OnDataPointSelectedListener {
            override fun onDataPointSelected(index: Int, timestampMs: Long, value: Float) {
                if (isSelectionMode) {
                    handleRangeSelection(index)
                }
            }
        })
        
        // Navigation chips
        chipGoRealtime.setOnClickListener { detailChart.goToRealTime() }
        chipResetZoom.setOnClickListener { detailChart.resetZoom() }
        
        // Selection mode chips
        chipSelectRange.setOnCheckedChangeListener { _, isChecked ->
            isSelectionMode = isChecked
            if (isChecked) {
                selectionStartIdx = null
                selectionEndIdx = null
                chipSelectRange.text = "Selecting…"
            } else {
                chipSelectRange.text = "Select"
            }
        }
        chipClearSelection.setOnClickListener { clearSelection() }
        
        // Sigma bands - only one can be active
        chipSigma1.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentSigma = 1
                chipSigma2.isChecked = false
                chipSigma3.isChecked = false
            } else if (currentSigma == 1) {
                currentSigma = 0
            }
            updateChartOverlays()
        }
        
        chipSigma2.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentSigma = 2
                chipSigma1.isChecked = false
                chipSigma3.isChecked = false
            } else if (currentSigma == 2) {
                currentSigma = 0
            }
            updateChartOverlays()
        }
        
        chipSigma3.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentSigma = 3
                chipSigma1.isChecked = false
                chipSigma2.isChecked = false
            } else if (currentSigma == 3) {
                currentSigma = 0
            }
            updateChartOverlays()
        }
        
        // Mean line toggle
        chipMeanLine.setOnCheckedChangeListener { _, isChecked ->
            showMeanLine = isChecked
            updateChartOverlays()
        }
        
        // Rolling average toggle
        chipRollingAvg.setOnCheckedChangeListener { _, isChecked ->
            showRollingAvg = isChecked
            detailChart.setRollingAverageWindow(if (isChecked) 10 else 0)
        }
        
        // Comparison mode toggle
        chipCompare.setOnCheckedChangeListener { _, isChecked ->
            isCompareMode = isChecked
            updateComparisonMode()
        }
        
        // Export button
        chipExport.setOnClickListener { exportData() }
        
        // Initial states
        detailChart.setRollingAverageWindow(10) // Rolling avg on by default
        chipResetZoom.alpha = 0.5f
        chipGoRealtime.alpha = 0.5f
    }
    
    private fun loadDataFromIntent() {
        val ts = intent?.getLongArrayExtra("ts")?.toList() ?: emptyList()
        val v = intent?.getFloatArrayExtra("v")?.toList() ?: emptyList()
        val secondaryV = intent?.getFloatArrayExtra("secondary_v")?.toList() ?: emptyList()
        
        if (ts.isNotEmpty() && v.isNotEmpty() && ts.size == v.size) {
            timestampsMs = ts
            values = v
            secondaryValues = secondaryV
            
            detailChart.setSeries(timestampsMs, values)
            updateStats(values)
        }
    }
    
    private fun startLiveUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                refreshData()
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.postDelayed(updateRunnable!!, 1000)
    }
    
    private fun refreshData() {
        // Get fresh data from chart history
        val chartHistory = Prefs.getChartHistory(this)
        if (chartHistory.isEmpty()) return
        
        val windowSeconds = Prefs.getWindowSeconds(this, 60)
        val cutoffMs = System.currentTimeMillis() - (windowSeconds * 1000L)
        
        val filteredHistory = chartHistory.filter { it.timestampMs >= cutoffMs }
        
        val newTs = filteredHistory.map { it.timestampMs }
        val newValues = if (kind == "cps") {
            val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
            filteredHistory.map { 
                when (cu) {
                    Prefs.CountUnit.CPS -> it.cps
                    Prefs.CountUnit.CPM -> it.cps * 60f
                }
            }
        } else {
            val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
            filteredHistory.map {
                when (du) {
                    Prefs.DoseUnit.USV_H -> it.uSvPerHour
                    Prefs.DoseUnit.NSV_H -> it.uSvPerHour * 1000f
                }
            }
        }
        
        // Also get secondary values for comparison
        val newSecondary = if (isCompareMode) {
            if (kind == "cps") {
                val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
                filteredHistory.map {
                    when (du) {
                        Prefs.DoseUnit.USV_H -> it.uSvPerHour
                        Prefs.DoseUnit.NSV_H -> it.uSvPerHour * 1000f
                    }
                }
            } else {
                val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
                filteredHistory.map {
                    when (cu) {
                        Prefs.CountUnit.CPS -> it.cps
                        Prefs.CountUnit.CPM -> it.cps * 60f
                    }
                }
            }
        } else {
            emptyList()
        }
        
        if (newTs.isNotEmpty() && newValues.isNotEmpty()) {
            timestampsMs = newTs
            values = newValues
            secondaryValues = newSecondary
            
            detailChart.setSeries(timestampsMs, values)
            
            if (isCompareMode && secondaryValues.isNotEmpty()) {
                val secondaryColor = if (kind == "cps") {
                    ContextCompat.getColor(this, R.color.pro_cyan)
                } else {
                    ContextCompat.getColor(this, R.color.pro_magenta)
                }
                val secondaryLabel = if (kind == "cps") "Dose Rate" else "Count Rate"
                val primaryLabel = if (kind == "cps") "Count Rate" else "Dose Rate"
                detailChart.setSecondarySeries(secondaryValues, secondaryColor, secondaryLabel, primaryLabel)
            }
            
            updateStats(values)
            updateChartOverlays()
        }
    }
    
    private fun updateStats(data: List<Float>) {
        if (data.isEmpty()) {
            statMin.text = "—"
            statMax.text = "—"
            statMean.text = "—"
            statStdDev.text = "—"
            statSamples.text = "—"
            statMedian.text = "—"
            statP95.text = "—"
            statP5.text = "—"
            statTrend.text = "—"
            statCV.text = "—"
            return
        }
        
        val stats = calculateStats(data)
        
        statMin.text = formatValue(stats.min)
        statMax.text = formatValue(stats.max)
        statMean.text = formatValue(stats.mean)
        statStdDev.text = formatValue(stats.stdDev)
        statSamples.text = stats.count.toString()
        
        // Advanced stats
        statMedian.text = formatValue(stats.median)
        statP95.text = formatValue(stats.p95)
        statP5.text = formatValue(stats.p5)
        statCV.text = String.format(Locale.US, "%.1f%%", stats.cv)
        
        // Trend (slope of linear regression)
        val trendSymbol = when {
            stats.trend > 0.01f -> "↑"
            stats.trend < -0.01f -> "↓"
            else -> "→"
        }
        val trendColor = when {
            stats.trend > 0.01f -> ContextCompat.getColor(this, R.color.pro_red)
            stats.trend < -0.01f -> ContextCompat.getColor(this, R.color.pro_green)
            else -> ContextCompat.getColor(this, R.color.pro_text_primary)
        }
        statTrend.text = trendSymbol
        statTrend.setTextColor(trendColor)
    }
    
    data class Statistics(
        val min: Float,
        val max: Float,
        val mean: Float,
        val stdDev: Float,
        val median: Float,
        val p95: Float,
        val p5: Float,
        val cv: Float,  // Coefficient of variation
        val trend: Float, // Slope normalized by mean
        val count: Int
    )
    
    private fun calculateStats(data: List<Float>): Statistics {
        if (data.isEmpty()) return Statistics(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0)
        
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        var sum = 0.0
        
        for (v in data) {
            minV = min(minV, v)
            maxV = max(maxV, v)
            sum += v
        }
        
        val mean = (sum / data.size).toFloat()
        
        // Calculate standard deviation
        var sumSquaredDiff = 0.0
        for (v in data) {
            val diff = v - mean
            sumSquaredDiff += diff * diff
        }
        val variance = sumSquaredDiff / data.size
        val stdDev = sqrt(variance).toFloat()
        
        // Calculate percentiles
        val sorted = data.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else {
            sorted[sorted.size / 2]
        }
        
        val p5Index = (sorted.size * 0.05).toInt().coerceIn(0, sorted.size - 1)
        val p95Index = (sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)
        val p5 = sorted[p5Index]
        val p95 = sorted[p95Index]
        
        // Coefficient of variation (CV) = stdDev / mean * 100
        val cv = if (mean != 0f) (stdDev / kotlin.math.abs(mean) * 100f) else 0f
        
        // Calculate trend (linear regression slope normalized by mean)
        val trend = if (data.size > 1 && mean != 0f) {
            var sumX = 0.0
            var sumY = 0.0
            var sumXY = 0.0
            var sumXX = 0.0
            val n = data.size.toDouble()
            
            for (i in data.indices) {
                sumX += i
                sumY += data[i]
                sumXY += i * data[i]
                sumXX += i * i
            }
            
            val slope = (n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX)
            (slope / mean).toFloat()  // Normalize by mean
        } else {
            0f
        }
        
        return Statistics(minV, maxV, mean, stdDev, median, p95, p5, cv, trend, data.size)
    }
    
    private fun formatValue(value: Float): String {
        return when {
            value >= 1000 -> String.format(Locale.US, "%.0f", value)
            value >= 100 -> String.format(Locale.US, "%.1f", value)
            value >= 10 -> String.format(Locale.US, "%.2f", value)
            value >= 1 -> String.format(Locale.US, "%.3f", value)
            else -> String.format(Locale.US, "%.4f", value)
        }
    }
    
    private fun updateChartOverlays() {
        // Update sigma bands level
        detailChart.setSigmaBandLevel(currentSigma)
        
        // Update mean line
        detailChart.setShowMeanLine(showMeanLine)
    }
    
    private fun updateComparisonMode() {
        if (isCompareMode && secondaryValues.isNotEmpty()) {
            val secondaryColor = if (kind == "cps") {
                ContextCompat.getColor(this, R.color.pro_cyan)
            } else {
                ContextCompat.getColor(this, R.color.pro_magenta)
            }
            val secondaryLabel = if (kind == "cps") {
                when (Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)) {
                    Prefs.DoseUnit.USV_H -> "Dose (μSv/h)"
                    Prefs.DoseUnit.NSV_H -> "Dose (nSv/h)"
                }
            } else {
                when (Prefs.getCountUnit(this, Prefs.CountUnit.CPS)) {
                    Prefs.CountUnit.CPS -> "Count (cps)"
                    Prefs.CountUnit.CPM -> "Count (cpm)"
                }
            }
            val primaryLabel = if (kind == "cps") {
                when (Prefs.getCountUnit(this, Prefs.CountUnit.CPS)) {
                    Prefs.CountUnit.CPS -> "Count (cps)"
                    Prefs.CountUnit.CPM -> "Count (cpm)"
                }
            } else {
                when (Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)) {
                    Prefs.DoseUnit.USV_H -> "Dose (μSv/h)"
                    Prefs.DoseUnit.NSV_H -> "Dose (nSv/h)"
                }
            }
            detailChart.setSecondarySeries(secondaryValues, secondaryColor, secondaryLabel, primaryLabel)
        } else {
            detailChart.clearSecondarySeries()
        }
    }
    
    private fun handleRangeSelection(index: Int) {
        if (selectionStartIdx == null) {
            // First point
            selectionStartIdx = index
            chipSelectRange.text = "Tap end…"
            Toast.makeText(this, "Start point selected. Tap end point.", Toast.LENGTH_SHORT).show()
        } else {
            // Second point - complete selection
            selectionEndIdx = index
            isSelectionMode = false
            chipSelectRange.isChecked = false
            chipSelectRange.text = "Select"
            
            // Show statistics for selected range
            updateSelectionStats(selectionStartIdx!!, selectionEndIdx!!)
            
            // Update the main stats panel to show selected data
            val start = min(selectionStartIdx!!, selectionEndIdx!!)
            val end = max(selectionStartIdx!!, selectionEndIdx!!)
            if (start >= 0 && end < values.size) {
                val selectedData = values.subList(start, end + 1)
                updateStats(selectedData)
            }
        }
    }
    
    private fun clearSelection() {
        selectionStartIdx = null
        selectionEndIdx = null
        selectionOverlay.visibility = View.GONE
        chipClearSelection.visibility = View.GONE
        detailChart.clearStickyMarker()
        
        // Reset selection mode
        isSelectionMode = false
        chipSelectRange.isChecked = false
        chipSelectRange.text = "Select"
        
        // Update stats to show full data
        updateStats(values)
    }
    
    private fun updateSelectionStats(startIdx: Int, endIdx: Int) {
        val start = min(startIdx, endIdx)
        val end = max(startIdx, endIdx)
        
        if (start < 0 || end >= values.size) return
        
        val selectedData = values.subList(start, end + 1)
        val selectedTs = timestampsMs.subList(start, end + 1)
        val stats = calculateStats(selectedData)
        
        val startTime = Instant.ofEpochMilli(timestampsMs[start])
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val endTime = Instant.ofEpochMilli(timestampsMs[end])
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        
        val durationMs = timestampsMs[end] - timestampsMs[start]
        val durationSec = durationMs / 1000.0
        val durationHours = durationSec / 3600.0
        
        // Calculate total exposure (integral of dose rate over time)
        // For dose rate in μSv/h, integrate over hours to get μSv
        val totalExposure = if (kind == "dose") {
            var exposure = 0.0
            for (i in 1 until selectedData.size) {
                val dt = (selectedTs[i] - selectedTs[i-1]) / 3600000.0 // hours
                val avgRate = (selectedData[i] + selectedData[i-1]) / 2.0 // trapezoidal
                exposure += avgRate * dt
            }
            exposure
        } else {
            // For count rate, calculate total counts
            var totalCounts = 0.0
            for (i in 1 until selectedData.size) {
                val dt = (selectedTs[i] - selectedTs[i-1]) / 1000.0 // seconds
                val avgRate = (selectedData[i] + selectedData[i-1]) / 2.0
                totalCounts += avgRate * dt
            }
            totalCounts
        }
        
        // Calculate rate of change (slope per minute)
        val rateOfChange = if (selectedData.size > 1 && durationSec > 0) {
            ((selectedData.last() - selectedData.first()) / durationSec) * 60.0 // per minute
        } else 0.0
        
        // Z-score of max value (how many σ above mean)
        val maxZScore = if (stats.stdDev > 0) (stats.max - stats.mean) / stats.stdDev else 0f
        
        // Build comprehensive stats display
        val exposureUnit = if (kind == "dose") "μSv" else "counts"
        val rateChangeUnit = if (kind == "dose") "μSv/h/min" else "${unit}/min"
        
        selectionStats.text = buildString {
            appendLine("⏱ $startTime → $endTime")
            appendLine("Duration: ${formatDuration(durationMs)}")
            appendLine("─────────────")
            appendLine("n: ${stats.count} samples")
            appendLine("Min: ${formatValue(stats.min)} $unit")
            appendLine("Max: ${formatValue(stats.max)} $unit")
            appendLine("Mean: ${formatValue(stats.mean)} $unit")
            appendLine("Median: ${formatValue(stats.median)} $unit")
            appendLine("σ: ${formatValue(stats.stdDev)} $unit")
            appendLine("─────────────")
            appendLine("∫ Total: ${formatValue(totalExposure.toFloat())} $exposureUnit")
            appendLine("Δ/min: ${String.format(Locale.US, "%+.3f", rateOfChange)} $rateChangeUnit")
            appendLine("P5→P95: ${formatValue(stats.p5)}→${formatValue(stats.p95)}")
            appendLine("CV: ${String.format(Locale.US, "%.1f%%", stats.cv)}")
            append("Max Z: ${String.format(Locale.US, "%.1fσ", maxZScore)}")
        }
        
        selectionOverlay.visibility = View.VISIBLE
        chipClearSelection.visibility = View.VISIBLE
    }
    
    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
    
    private fun exportData() {
        if (values.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val fileName = "radiacode_${kind}_${System.currentTimeMillis()}.csv"
            val file = File(filesDir, fileName)
            
            file.bufferedWriter().use { writer ->
                // Header
                writer.appendLine("timestamp_ms,timestamp_iso,$kind ($unit)")
                
                // Data rows
                for (i in values.indices) {
                    val ts = timestampsMs.getOrNull(i) ?: continue
                    val v = values[i]
                    val isoTime = Instant.ofEpochMilli(ts)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    writer.appendLine("$ts,$isoTime,$v")
                }
            }
            
            // Share the file
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Export $kind data"))
            
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateRunnable?.let { mainHandler.post(it) }
    }
    
    override fun onPause() {
        super.onPause()
        updateRunnable?.let { mainHandler.removeCallbacks(it) }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        updateRunnable?.let { mainHandler.removeCallbacks(it) }
        updateRunnable = null
    }
}
