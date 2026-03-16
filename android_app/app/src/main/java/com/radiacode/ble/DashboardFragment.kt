package com.radiacode.ble

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.radiacode.ble.ui.MetricCardView
import com.radiacode.ble.ui.ProChartView
import com.radiacode.ble.ui.StatRowView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Simplified Dashboard tab.
 * Shows only: metric cards (dose + count rate), two time-series charts, session info.
 * Time window chips are inline (Point 19 - settings where they're used).
 */
class DashboardFragment : Fragment() {

    private companion object {
        private const val MAX_CHART_POINTS = 800
    }

    // Metric cards
    private lateinit var doseCard: MetricCardView
    private lateinit var cpsCard: MetricCardView

    // Charts
    private lateinit var doseChart: ProChartView
    private lateinit var doseChartTitle: TextView
    private lateinit var doseChartTrend: TextView
    private lateinit var doseChartReset: ImageButton
    private lateinit var doseChartGoRealtime: ImageButton
    private lateinit var doseStats: StatRowView
    private lateinit var doseChartPanel: View

    private lateinit var cpsChart: ProChartView
    private lateinit var cpsChartTitle: TextView
    private lateinit var cpsChartTrend: TextView
    private lateinit var cpsChartReset: ImageButton
    private lateinit var cpsChartGoRealtime: ImageButton
    private lateinit var cpsStats: StatRowView
    private lateinit var cpsChartPanel: View

    // Charts container (orientation adapted)
    private lateinit var chartsContainer: LinearLayout

    // Time window chips
    private lateinit var chipWindow10s: TextView
    private lateinit var chipWindow1m: TextView
    private lateinit var chipWindow10m: TextView
    private lateinit var chipWindow1h: TextView

    // Session info
    private lateinit var sessionInfo: TextView

    // Empty state
    private var emptyStateContainer: LinearLayout? = null
    private var emptyStateText: TextView? = null
    private var hasReceivedFirstReading = false

    // Device selector
    private lateinit var deviceSelector: com.radiacode.ble.ui.DeviceSelectorView

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupMetricCards()
        setupCharts()
        setupTimeWindowChips()
        adjustLayoutForOrientation(resources.configuration.orientation)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adjustLayoutForOrientation(newConfig.orientation)
    }

    /**
     * Adapt chart layout for current orientation.
     * Portrait: charts stacked vertically full-width.
     * Landscape: charts side-by-side, reduced heights.
     */
    private fun adjustLayoutForOrientation(orientation: Int) {
        if (!isAdded || view == null) return
        val density = resources.displayMetrics.density
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE

        chartsContainer.orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        val chartHeight = if (isLandscape) (140 * density).toInt() else (180 * density).toInt()
        val statsHeight = if (isLandscape) (40 * density).toInt() else (52 * density).toInt()
        val cardHeight = if (isLandscape) (130 * density).toInt() else (160 * density).toInt()

        // Adjust metric card heights
        val doseCardLp = doseCard.layoutParams as LinearLayout.LayoutParams
        doseCardLp.height = cardHeight
        doseCard.layoutParams = doseCardLp

        val cpsCardLp = cpsCard.layoutParams as LinearLayout.LayoutParams
        cpsCardLp.height = cardHeight
        cpsCard.layoutParams = cpsCardLp

        // Adjust chart panel layout params
        if (isLandscape) {
            doseChartPanel.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (4 * density).toInt()
                topMargin = (4 * density).toInt()
            }
            cpsChartPanel.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * density).toInt()
                topMargin = (4 * density).toInt()
            }
        } else {
            doseChartPanel.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
            cpsChartPanel.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        }

        // Adjust chart and stats heights
        doseChart.layoutParams = (doseChart.layoutParams).apply {
            height = chartHeight
        }
        cpsChart.layoutParams = (cpsChart.layoutParams).apply {
            height = chartHeight
        }
        doseStats.layoutParams = (doseStats.layoutParams).apply {
            height = statsHeight
        }
        cpsStats.layoutParams = (cpsStats.layoutParams).apply {
            height = statsHeight
        }

        chartsContainer.requestLayout()
    }

    private fun bindViews(view: View) {
        doseCard = view.findViewById(R.id.doseCard)
        cpsCard = view.findViewById(R.id.cpsCard)

        doseChartPanel = view.findViewById(R.id.doseChartPanel)
        doseChart = view.findViewById(R.id.doseChart)
        doseChartTitle = view.findViewById(R.id.doseChartTitle)
        doseChartTrend = view.findViewById(R.id.doseChartTrend)
        doseChartReset = view.findViewById(R.id.doseChartReset)
        doseChartGoRealtime = view.findViewById(R.id.doseChartGoRealtime)
        doseStats = view.findViewById(R.id.doseStats)

        cpsChartPanel = view.findViewById(R.id.cpsChartPanel)
        cpsChart = view.findViewById(R.id.cpsChart)
        cpsChartTitle = view.findViewById(R.id.cpsChartTitle)
        cpsChartTrend = view.findViewById(R.id.cpsChartTrend)
        cpsChartReset = view.findViewById(R.id.cpsChartReset)
        cpsChartGoRealtime = view.findViewById(R.id.cpsChartGoRealtime)
        cpsStats = view.findViewById(R.id.cpsStats)

        chartsContainer = view.findViewById(R.id.chartsContainer)

        chipWindow10s = view.findViewById(R.id.chipWindow10s)
        chipWindow1m = view.findViewById(R.id.chipWindow1m)
        chipWindow10m = view.findViewById(R.id.chipWindow10m)
        chipWindow1h = view.findViewById(R.id.chipWindow1h)

        sessionInfo = view.findViewById(R.id.sessionInfo)
        deviceSelector = view.findViewById(R.id.deviceSelector)

        // Empty state (optional - only in updated layouts)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        emptyStateText = view.findViewById(R.id.emptyStateText)
    }

    private fun setupMetricCards() {
        val ctx = requireContext()
        val cyanColor = ContextCompat.getColor(ctx, R.color.pro_cyan)
        val magentaColor = ContextCompat.getColor(ctx, R.color.pro_magenta)

        doseCard.setLabel("DELTA DOSE RATE")
        doseCard.setAccentColor(cyanColor)
        doseCard.setValueText("\u2014")
        doseCard.setTrend(0f)

        cpsCard.setLabel("DELTA COUNT RATE")
        cpsCard.setAccentColor(magentaColor)
        cpsCard.setValueText("\u2014")
        cpsCard.setTrend(0f)

        val showTrend = Prefs.isShowTrendArrowsEnabled(ctx)
        doseCard.setShowTrendArrows(showTrend)
        cpsCard.setShowTrendArrows(showTrend)
    }

    private fun setupCharts() {
        val ctx = requireContext()
        val cyanColor = ContextCompat.getColor(ctx, R.color.pro_cyan)
        val magentaColor = ContextCompat.getColor(ctx, R.color.pro_magenta)

        doseChart.setAccentColor(cyanColor)
        cpsChart.setAccentColor(magentaColor)

        doseChart.setRollingAverageWindow(10)
        cpsChart.setRollingAverageWindow(10)

        val showSpikes = Prefs.isShowSpikeMarkersEnabled(ctx)
        doseChart.setShowSpikeMarkers(showSpikes)
        cpsChart.setShowSpikeMarkers(showSpikes)

        val showSpikePercent = Prefs.isShowSpikePercentagesEnabled(ctx)
        doseChart.setShowSpikePercentages(showSpikePercent)
        cpsChart.setShowSpikePercentages(showSpikePercent)

        // Open detailed chart
        doseChartPanel.setOnClickListener { mainActivity?.openDetailedChart("dose") }
        cpsChartPanel.setOnClickListener { mainActivity?.openDetailedChart("cps") }

        // Zoom controls
        doseChart.setOnZoomChangeListener(object : ProChartView.OnZoomChangeListener {
            override fun onZoomChanged(zoomLevel: Float) {
                doseChartReset.visibility = if (zoomLevel > 1.01f) View.VISIBLE else View.GONE
            }
        })
        cpsChart.setOnZoomChangeListener(object : ProChartView.OnZoomChangeListener {
            override fun onZoomChanged(zoomLevel: Float) {
                cpsChartReset.visibility = if (zoomLevel > 1.01f) View.VISIBLE else View.GONE
            }
        })

        doseChartReset.setOnClickListener { doseChart.resetZoom() }
        cpsChartReset.setOnClickListener { cpsChart.resetZoom() }

        doseChartGoRealtime.setOnClickListener { doseChart.goToRealTime() }
        cpsChartGoRealtime.setOnClickListener { cpsChart.goToRealTime() }

        doseChart.setOnRealTimeStateListener(object : ProChartView.OnRealTimeStateListener {
            override fun onRealTimeStateChanged(isFollowingRealTime: Boolean) {
                doseChartGoRealtime.visibility = if (isFollowingRealTime) View.GONE else View.VISIBLE
            }
        })
        cpsChart.setOnRealTimeStateListener(object : ProChartView.OnRealTimeStateListener {
            override fun onRealTimeStateChanged(isFollowingRealTime: Boolean) {
                cpsChartGoRealtime.visibility = if (isFollowingRealTime) View.GONE else View.VISIBLE
            }
        })

        updateChartTitles()
    }

    private fun setupTimeWindowChips() {
        val chips = listOf(
            chipWindow10s to 10,
            chipWindow1m to 60,
            chipWindow10m to 600,
            chipWindow1h to 3600
        )

        val currentWindow = Prefs.getWindowSeconds(requireContext(), 60)
        updateTimeWindowChipHighlight(currentWindow)

        for ((chip, seconds) in chips) {
            chip.setOnClickListener {
                Prefs.setWindowSeconds(requireContext(), seconds)
                updateTimeWindowChipHighlight(seconds)
                updateChartTitles()
                // Trigger chart refresh from activity
                mainActivity?.onTimeWindowChanged()
            }
        }
    }

    private fun updateTimeWindowChipHighlight(selectedSeconds: Int) {
        val ctx = requireContext()
        val active = ContextCompat.getColor(ctx, R.color.pro_cyan)
        val inactive = ContextCompat.getColor(ctx, R.color.pro_text_muted)

        chipWindow10s.setTextColor(if (selectedSeconds == 10) active else inactive)
        chipWindow1m.setTextColor(if (selectedSeconds == 60) active else inactive)
        chipWindow10m.setTextColor(if (selectedSeconds == 600) active else inactive)
        chipWindow1h.setTextColor(if (selectedSeconds == 3600) active else inactive)
    }

    fun updateChartTitles() {
        if (!isAdded || view == null) return
        val ctx = context ?: return
        val windowSec = Prefs.getWindowSeconds(ctx, 60)
        val windowLabel = when (windowSec) {
            10 -> "10s"
            60 -> "1m"
            600 -> "10m"
            3600 -> "1h"
            else -> "${windowSec}s"
        }
        val doseUnit = if (Prefs.isDoseNanoMode(ctx)) "nSv/h" else "\u00B5Sv/h"
        val countUnit = if (Prefs.isCountCpmMode(ctx)) "CPM" else "CPS"

        doseChartTitle.text = "REAL TIME DOSE RATE — $doseUnit — Last $windowLabel"
        cpsChartTitle.text = "REAL TIME COUNT RATE — $countUnit — Last $windowLabel"
    }

    /**
     * Called by MainActivity when new data arrives.
     */
    fun updateMetricCards(uSvH: Float, cps: Float, trendDose: Float, trendCps: Float) {
        if (!isAdded || view == null) return
        val ctx = context ?: return

        // Hide empty state on first data
        if (!hasReceivedFirstReading) {
            hasReceivedFirstReading = true
            emptyStateContainer?.visibility = View.GONE
        }

        if (Prefs.isDoseNanoMode(ctx)) {
            doseCard.setValue(uSvH * 1000f, "nSv/h")
        } else {
            doseCard.setValue(uSvH, "\u00B5Sv/h")
        }
        doseCard.setTrend(trendDose)

        // Color-code dose card based on radiation level
        val doseColor = when {
            uSvH >= 1.0f -> ContextCompat.getColor(ctx, R.color.pro_red)      // High
            uSvH >= 0.3f -> ContextCompat.getColor(ctx, R.color.pro_amber)    // Elevated
            else -> ContextCompat.getColor(ctx, R.color.pro_cyan)              // Normal
        }
        doseCard.setAccentColor(doseColor)

        if (Prefs.isCountCpmMode(ctx)) {
            cpsCard.setValue(cps * 60f, "CPM")
        } else {
            cpsCard.setValue(cps, "CPS")
        }
        cpsCard.setTrend(trendCps)

        // Update chart trend arrows if enabled
        if (Prefs.isShowTrendArrowsEnabled(ctx)) {
            updateChartTrendArrow(doseChartTrend, doseCard.getZScore(), trendDose)
            updateChartTrendArrow(cpsChartTrend, cpsCard.getZScore(), trendCps)
        }
    }

    /**
     * Update a chart panel trend arrow based on z-score (statistical significance)
     * and trend percentage. Shows green arrows for rising, red for falling.
     */
    private fun updateChartTrendArrow(trendView: TextView, zScore: Float, trendPct: Float) {
        val ctx = context ?: return
        val absZ = kotlin.math.abs(zScore)

        val (arrow, colorRes) = when {
            absZ > 2f && zScore > 0 -> "\u25B2\u25B2" to R.color.pro_green     // Very high
            absZ > 1f && zScore > 0 -> "\u25B2" to R.color.pro_green           // High
            absZ > 2f && zScore < 0 -> "\u25BC\u25BC" to R.color.pro_red       // Very low
            absZ > 1f && zScore < 0 -> "\u25BC" to R.color.pro_red             // Low
            else -> "\u2500" to R.color.pro_text_muted                          // Stable
        }

        val text = if (absZ > 1f && kotlin.math.abs(trendPct) >= 0.1f) {
            val sign = if (trendPct > 0) "+" else ""
            "$arrow ${sign}${String.format(Locale.US, "%.1f", trendPct)}%"
        } else {
            arrow
        }

        trendView.text = text
        trendView.setTextColor(ContextCompat.getColor(ctx, colorRes))
        trendView.visibility = View.VISIBLE
    }

    fun showEmptyMetrics() {
        if (!isAdded || view == null) return
        doseCard.setValueText("\u2014")
        doseCard.setTrend(0f)
        cpsCard.setValueText("\u2014")
        cpsCard.setTrend(0f)
        doseChartTrend.visibility = View.GONE
        cpsChartTrend.visibility = View.GONE
    }

    /**
     * Called by MainActivity when charts need updating from history data.
     */
    fun updateCharts(
        doseTimestamps: List<Long>, doseValues: List<Float>,
        cpsTimestamps: List<Long>, cpsValues: List<Float>,
        doseMarkers: List<ProChartView.AlertMarker>? = null,
        cpsMarkers: List<ProChartView.AlertMarker>? = null
    ) {
        if (!isAdded || view == null) return

        doseChart.setSeries(doseTimestamps, doseValues)
        cpsChart.setSeries(cpsTimestamps, cpsValues)

        if (doseMarkers != null) doseChart.setAlertMarkers(doseMarkers)
        if (cpsMarkers != null) cpsChart.setAlertMarkers(cpsMarkers)

        // Update sparklines on metric cards (last 20 points)
        if (doseValues.size >= 2) {
            val sparkLen = min(20, doseValues.size)
            doseCard.setSparkline(doseValues.subList(doseValues.size - sparkLen, doseValues.size))
        }
        if (cpsValues.size >= 2) {
            val sparkLen = min(20, cpsValues.size)
            cpsCard.setSparkline(cpsValues.subList(cpsValues.size - sparkLen, cpsValues.size))
        }
    }

    /**
     * Called by MainActivity to update statistics row.
     */
    fun updateStats(
        doseMin: Float, doseAvg: Float, doseMax: Float, doseDelta: Float,
        cpsMin: Float, cpsAvg: Float, cpsMax: Float, cpsDelta: Float
    ) {
        if (!isAdded || view == null) return
        val ctx = context ?: return

        val doseUnit = if (Prefs.isDoseNanoMode(ctx)) "nSv/h" else "\u00B5Sv/h"
        val countUnit = if (Prefs.isCountCpmMode(ctx)) "CPM" else "CPS"

        doseStats.setStats(StatRowView.Stats(doseMin, doseAvg, doseMax, doseDelta, doseUnit))
        cpsStats.setStats(StatRowView.Stats(cpsMin, cpsAvg, cpsMax, cpsDelta, countUnit))
    }

    fun updateSessionInfo(elapsed: String, samples: Int) {
        if (!isAdded || view == null) return
        sessionInfo.text = "SESSION  $elapsed  |  $samples samples"
    }

    /**
     * Show a brief Snackbar confirming device connection.
     */
    fun showConnectedSnackbar(deviceName: String) {
        if (!isAdded || view == null) return
        val v = view ?: return
        Snackbar.make(v, "Connected to $deviceName", Snackbar.LENGTH_SHORT)
            .setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.pro_surface_elevated))
            .setTextColor(ContextCompat.getColor(requireContext(), R.color.pro_green))
            .show()
    }

    /**
     * Set the forecast data on the dose chart.
     */
    fun setDoseForecast(points: List<ProChartView.ForecastPoint>) {
        if (!isAdded || view == null) return
        doseChart.setForecast(points)
    }

    fun clearDoseForecast() {
        if (!isAdded || view == null) return
        doseChart.clearForecast()
    }

    /**
     * Refresh chart settings (called after settings change).
     */
    fun refreshChartSettings() {
        if (!isAdded || view == null) return
        val ctx = context ?: return

        val showSpikes = Prefs.isShowSpikeMarkersEnabled(ctx)
        doseChart.setShowSpikeMarkers(showSpikes)
        cpsChart.setShowSpikeMarkers(showSpikes)

        val showSpikePercent = Prefs.isShowSpikePercentagesEnabled(ctx)
        doseChart.setShowSpikePercentages(showSpikePercent)
        cpsChart.setShowSpikePercentages(showSpikePercent)

        val showTrend = Prefs.isShowTrendArrowsEnabled(ctx)
        doseCard.setShowTrendArrows(showTrend)
        cpsCard.setShowTrendArrows(showTrend)
        if (!showTrend) {
            doseChartTrend.visibility = View.GONE
            cpsChartTrend.visibility = View.GONE
        }

        val windowSec = Prefs.getWindowSeconds(ctx, 60)
        updateTimeWindowChipHighlight(windowSec)
        updateChartTitles()
    }

    /**
     * Called from MainActivity to refresh charts from current history data.
     * Pulls history from MainActivity and re-renders.
     */
    fun refreshCharts() {
        if (!isAdded || view == null) return
        val ma = mainActivity ?: return
        val ctx = context ?: return

        val du = Prefs.getDoseUnit(ctx, Prefs.DoseUnit.USV_H)
        val cu = Prefs.getCountUnit(ctx, Prefs.CountUnit.CPS)
        val smooth = Prefs.getSmoothSeconds(ctx, 0)
        val poll = Prefs.getPollIntervalMs(ctx, 1000L)
        val smoothSamples = if (smooth <= 0) 0 else max(1, ((smooth * 1000L) / max(1L, poll)).toInt())
        val window = Prefs.getWindowSeconds(ctx, 60)
        val nSamples = ((window * 1000L) / max(1L, poll)).toInt().coerceAtLeast(2)

        val doseSeries = ma.getDoseHistory().lastN(nSamples)
        val cpsSeries = ma.getCpsHistory().lastN(nSamples)

        val doseVals = ma.convertDose(ma.applySmoothing(doseSeries.values, smoothSamples), du)
        val cpsVals = ma.convertCount(ma.applySmoothing(cpsSeries.values, smoothSamples), cu)

        val (doseTs, doseDec) = ma.decimate(doseSeries.timestampsMs, doseVals)
        val (cpsTs, cpsDec) = ma.decimate(cpsSeries.timestampsMs, cpsVals)

        updateCharts(doseTs, doseDec, cpsTs, cpsDec)

        // Stats
        if (doseDec.isNotEmpty()) {
            val dMin = doseDec.min()
            val dMax = doseDec.max()
            val dAvg = doseDec.average().toFloat()
            val dDelta = if (doseDec.size >= 2) doseDec.last() - doseDec.first() else 0f

            val cMin = cpsDec.min()
            val cMax = cpsDec.max()
            val cAvg = cpsDec.average().toFloat()
            val cDelta = if (cpsDec.size >= 2) cpsDec.last() - cpsDec.first() else 0f

            updateStats(dMin, dAvg, dMax, dDelta, cMin, cAvg, cMax, cDelta)
        }
    }

    /**
     * Called from MainActivity when a VEGA statistical update broadcast is received.
     */
    fun onStatisticalUpdate(intent: Intent) {
        if (!isAdded || view == null) return
        val hasForecast = intent.getBooleanExtra("has_forecast", false)
        if (hasForecast) {
            val forecastSeconds = intent.getIntArrayExtra("forecast_seconds")
            val forecastPredicted = intent.getFloatArrayExtra("forecast_predicted")
            val forecastLower = intent.getFloatArrayExtra("forecast_lower")
            val forecastUpper = intent.getFloatArrayExtra("forecast_upper")
            if (forecastSeconds != null && forecastPredicted != null &&
                forecastLower != null && forecastUpper != null) {
                val points = forecastSeconds.indices.map { i ->
                    ProChartView.ForecastPoint(
                        secondsAhead = forecastSeconds[i],
                        predicted = forecastPredicted[i],
                        lowerBound = forecastLower[i],
                        upperBound = forecastUpper[i]
                    )
                }
                setDoseForecast(points)
            }
        } else {
            clearDoseForecast()
        }
    }

    /**
     * No-arg version: computes session elapsed time and sample count from MainActivity.
     */
    fun updateSessionInfo() {
        if (!isAdded || view == null) return
        val ma = mainActivity ?: return
        val elapsed = System.currentTimeMillis() - ma.sessionStartMs
        val secs = (elapsed / 1000).toInt()
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        val timeStr = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        updateSessionInfo(timeStr, ma.sampleCount)

        // Show empty state if no data yet
        if (ma.sampleCount == 0 && !hasReceivedFirstReading) {
            emptyStateContainer?.visibility = View.VISIBLE
        }
    }
}
