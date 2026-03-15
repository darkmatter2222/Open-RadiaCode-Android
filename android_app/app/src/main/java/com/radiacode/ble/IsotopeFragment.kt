package com.radiacode.ble

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiacode.ble.ui.IsotopeChartView
import com.radiacode.ble.ui.StackedAreaChartView
import com.radiacode.ble.ui.IsotopeBarChartView

/**
 * Isotope identification tab (Point 4): dedicated panel for isotope detection,
 * real-time streaming, scan, and chart visualization.
 * Also hosts the VEGA Intelligence card (moved from dashboard - Point 2).
 */
class IsotopeFragment : Fragment() {

    // Isotope panel views
    private lateinit var isotopePanel: LinearLayout
    private lateinit var isotopeChartTitle: TextView
    private lateinit var isotopeAccumulationModeToggle: TextView
    private lateinit var isotopeDisplayModeToggle: TextView
    private lateinit var isotopeHideBackgroundToggle: TextView
    private lateinit var isotopeChartTypeBtn: ImageButton
    private lateinit var isotopeSettingsBtn: ImageButton
    private lateinit var isotopeScanBtn: MaterialButton
    private lateinit var isotopeRealtimeSwitch: SwitchMaterial
    private lateinit var isotopeStatusLabel: TextView
    private lateinit var isotopeChartContainer: FrameLayout
    private lateinit var isotopeMultiLineChart: IsotopeChartView
    private lateinit var isotopeStackedChart: StackedAreaChartView
    private lateinit var isotopeBarChart: IsotopeBarChartView
    private lateinit var isotopeScanResultContainer: LinearLayout
    private lateinit var isotopeScanResultText: TextView
    private lateinit var isotopeScanProgress: ProgressBar
    private lateinit var isotopeQuickView: LinearLayout
    private lateinit var isotopeTopResult: TextView

    // Intelligence card views
    private lateinit var intelligenceCard: LinearLayout
    private lateinit var intelligenceSummary: TextView
    private lateinit var intelligenceAlertBadge: TextView
    private lateinit var doseTrendLabel: TextView
    private lateinit var predictedDoseLabel: TextView
    private lateinit var anomalyCountLabel: TextView
    private lateinit var intelligenceInfoButton: ImageView
    private lateinit var stabilityIndicator: TextView
    private lateinit var dataQualityLabel: TextView
    private lateinit var predictionConfidenceLabel: TextView
    private lateinit var anomalyDetailLabel: TextView
    private lateinit var intelligenceExpandedSection: LinearLayout
    private lateinit var intelligenceExpandButton: LinearLayout
    private lateinit var intelligenceExpandText: TextView
    private lateinit var intelligenceExpandArrow: ImageView
    private lateinit var statsRangeLabel: TextView
    private lateinit var statsStdDevLabel: TextView
    private lateinit var statsCvLabel: TextView
    private lateinit var statsBackgroundLabel: TextView
    private lateinit var statsVsBackgroundLabel: TextView
    private lateinit var statsZScoreLabel: TextView
    private var intelligenceExpanded = false

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_isotope, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupIsotopePanel()
        setupIntelligenceCard()
    }

    private fun bindViews(view: View) {
        // Isotope panel
        isotopePanel = view.findViewById(R.id.isotopePanel)
        isotopeChartTitle = view.findViewById(R.id.isotopeChartTitle)
        isotopeAccumulationModeToggle = view.findViewById(R.id.isotopeAccumulationModeToggle)
        isotopeDisplayModeToggle = view.findViewById(R.id.isotopeDisplayModeToggle)
        isotopeHideBackgroundToggle = view.findViewById(R.id.isotopeHideBackgroundToggle)
        isotopeChartTypeBtn = view.findViewById(R.id.isotopeChartTypeBtn)
        isotopeSettingsBtn = view.findViewById(R.id.isotopeSettingsBtn)
        isotopeScanBtn = view.findViewById(R.id.isotopeScanBtn)
        isotopeRealtimeSwitch = view.findViewById(R.id.isotopeRealtimeSwitch)
        isotopeStatusLabel = view.findViewById(R.id.isotopeStatusLabel)
        isotopeChartContainer = view.findViewById(R.id.isotopeChartContainer)
        isotopeMultiLineChart = view.findViewById(R.id.isotopeMultiLineChart)
        isotopeStackedChart = view.findViewById(R.id.isotopeStackedChart)
        isotopeBarChart = view.findViewById(R.id.isotopeBarChart)
        isotopeScanResultContainer = view.findViewById(R.id.isotopeScanResultContainer)
        isotopeScanResultText = view.findViewById(R.id.isotopeScanResultText)
        isotopeScanProgress = view.findViewById(R.id.isotopeScanProgress)
        isotopeQuickView = view.findViewById(R.id.isotopeQuickView)
        isotopeTopResult = view.findViewById(R.id.isotopeTopResult)

        // Intelligence card
        intelligenceCard = view.findViewById(R.id.intelligenceCard)
        intelligenceSummary = view.findViewById(R.id.intelligenceSummary)
        intelligenceAlertBadge = view.findViewById(R.id.intelligenceAlertBadge)
        doseTrendLabel = view.findViewById(R.id.doseTrendLabel)
        predictedDoseLabel = view.findViewById(R.id.predictedDoseLabel)
        anomalyCountLabel = view.findViewById(R.id.anomalyCountLabel)
        intelligenceInfoButton = view.findViewById(R.id.intelligenceInfoButton)
        stabilityIndicator = view.findViewById(R.id.stabilityIndicator)
        dataQualityLabel = view.findViewById(R.id.dataQualityLabel)
        predictionConfidenceLabel = view.findViewById(R.id.predictionConfidenceLabel)
        anomalyDetailLabel = view.findViewById(R.id.anomalyDetailLabel)
        intelligenceExpandedSection = view.findViewById(R.id.intelligenceExpandedSection)
        intelligenceExpandButton = view.findViewById(R.id.intelligenceExpandButton)
        intelligenceExpandText = view.findViewById(R.id.intelligenceExpandText)
        intelligenceExpandArrow = view.findViewById(R.id.intelligenceExpandArrow)
        statsRangeLabel = view.findViewById(R.id.statsRangeLabel)
        statsStdDevLabel = view.findViewById(R.id.statsStdDevLabel)
        statsCvLabel = view.findViewById(R.id.statsCvLabel)
        statsBackgroundLabel = view.findViewById(R.id.statsBackgroundLabel)
        statsVsBackgroundLabel = view.findViewById(R.id.statsVsBackgroundLabel)
        statsZScoreLabel = view.findViewById(R.id.statsZScoreLabel)
    }

    private fun setupIsotopePanel() {
        val ctx = requireContext()

        // Apply saved settings
        val realtimeEnabled = Prefs.isIsotopeRealtimeEnabled(ctx)
        isotopeRealtimeSwitch.isChecked = realtimeEnabled

        val displayMode = Prefs.getIsotopeDisplayMode(ctx)
        updateDisplayModeToggle(displayMode)

        val accumulationMode = Prefs.getIsotopeAccumulationMode(ctx)
        updateAccumulationModeToggle(accumulationMode)

        val hideBackground = Prefs.isIsotopeHideBackground(ctx)
        updateHideBackgroundToggle(hideBackground)

        val chartMode = Prefs.getIsotopeChartMode(ctx)
        updateChartMode(chartMode)

        // Click listeners
        isotopeScanBtn.setOnClickListener { performIsotopeScan() }

        isotopeRealtimeSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setIsotopeRealtimeEnabled(ctx, isChecked)
            mainActivity?.setIsotopeRealtimeActive(isChecked)
            // Clear accumulated spectra when toggling
            mainActivity?.clearAccumulatedSpectra()
            updatePanelState()
        }

        isotopeDisplayModeToggle.setOnClickListener {
            val current = Prefs.getIsotopeDisplayMode(ctx)
            val next = when (current) {
                Prefs.IsotopeDisplayMode.PROBABILITY -> Prefs.IsotopeDisplayMode.FRACTION
                Prefs.IsotopeDisplayMode.FRACTION -> Prefs.IsotopeDisplayMode.PROBABILITY
            }
            Prefs.setIsotopeDisplayMode(ctx, next)
            updateDisplayModeToggle(next)
            refreshCharts()
        }

        isotopeAccumulationModeToggle.setOnClickListener {
            val current = Prefs.getIsotopeAccumulationMode(ctx)
            val next = when (current) {
                Prefs.IsotopeAccumulationMode.FULL_DURATION -> Prefs.IsotopeAccumulationMode.INTERVAL
                Prefs.IsotopeAccumulationMode.INTERVAL -> Prefs.IsotopeAccumulationMode.FULL_DURATION
            }
            Prefs.setIsotopeAccumulationMode(ctx, next)
            updateAccumulationModeToggle(next)
            mainActivity?.clearAccumulatedSpectra()
        }

        isotopeHideBackgroundToggle.setOnClickListener {
            val current = Prefs.isIsotopeHideBackground(ctx)
            Prefs.setIsotopeHideBackground(ctx, !current)
            updateHideBackgroundToggle(!current)
            refreshCharts()
        }

        isotopeChartTypeBtn.setOnClickListener { showChartTypeDialog() }

        isotopeSettingsBtn.setOnClickListener {
            startActivity(Intent(ctx, IsotopeSettingsActivity::class.java))
        }

        updatePanelState()
    }

    private fun setupIntelligenceCard() {
        intelligenceInfoButton.setOnClickListener { showIntelligenceHelpDialog() }
        intelligenceExpandButton.setOnClickListener { toggleIntelligenceExpanded() }
    }

    // ── Isotope display toggle helpers ───────────────────────────────

    private fun updateDisplayModeToggle(mode: Prefs.IsotopeDisplayMode) {
        isotopeDisplayModeToggle.text = when (mode) {
            Prefs.IsotopeDisplayMode.PROBABILITY -> "PROB"
            Prefs.IsotopeDisplayMode.FRACTION -> "FRAC"
        }
    }

    private fun updateAccumulationModeToggle(mode: Prefs.IsotopeAccumulationMode) {
        val ctx = requireContext()
        isotopeAccumulationModeToggle.text = when (mode) {
            Prefs.IsotopeAccumulationMode.FULL_DURATION -> "FULL"
            Prefs.IsotopeAccumulationMode.INTERVAL -> "INT"
        }
        isotopeAccumulationModeToggle.setTextColor(ContextCompat.getColor(ctx, when (mode) {
            Prefs.IsotopeAccumulationMode.FULL_DURATION -> R.color.pro_magenta
            Prefs.IsotopeAccumulationMode.INTERVAL -> R.color.pro_cyan
        }))
    }

    private fun updateHideBackgroundToggle(hide: Boolean) {
        val ctx = requireContext()
        isotopeHideBackgroundToggle.text = "BKG"
        isotopeHideBackgroundToggle.setTextColor(ContextCompat.getColor(ctx,
            if (hide) R.color.pro_text_muted else R.color.pro_green))
        isotopeHideBackgroundToggle.paintFlags = if (hide) {
            isotopeHideBackgroundToggle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            isotopeHideBackgroundToggle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }

    private fun updateChartMode(mode: Prefs.IsotopeChartMode) {
        isotopeMultiLineChart.visibility = View.GONE
        isotopeStackedChart.visibility = View.GONE
        isotopeBarChart.visibility = View.GONE
        isotopeScanResultContainer.visibility = View.GONE

        when (mode) {
            Prefs.IsotopeChartMode.MULTI_LINE -> isotopeMultiLineChart.visibility = View.VISIBLE
            Prefs.IsotopeChartMode.STACKED_AREA -> isotopeStackedChart.visibility = View.VISIBLE
            Prefs.IsotopeChartMode.ANIMATED_BAR -> isotopeBarChart.visibility = View.VISIBLE
        }
    }

    private fun updatePanelState() {
        val ctx = requireContext()
        val ma = mainActivity ?: return
        val history = ma.getCurrentIsotopeHistory()
        val realtimeActive = Prefs.isIsotopeRealtimeEnabled(ctx)

        if (realtimeActive) {
            isotopeStatusLabel.text = "Streaming"
            isotopeStatusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_green))
            val mode = Prefs.getIsotopeChartMode(ctx)
            updateChartMode(mode)
        } else {
            if (history.isEmpty) {
                isotopeStatusLabel.text = "Idle"
                isotopeStatusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_text_muted))
                isotopeScanResultContainer.visibility = View.VISIBLE
                isotopeScanResultText.text = "Press SCAN to identify isotopes"
                isotopeMultiLineChart.visibility = View.GONE
                isotopeStackedChart.visibility = View.GONE
                isotopeBarChart.visibility = View.GONE
            }
        }
    }

    // ── Scan flow ────────────────────────────────────────────────────

    private fun performIsotopeScan() {
        val ctx = requireContext()
        val ma = mainActivity ?: return

        // Disable button during scan for visual feedback
        isotopeScanBtn.isEnabled = false
        isotopeScanBtn.text = "Scanning..."

        // Show scanning state
        isotopeScanResultContainer.visibility = View.VISIBLE
        isotopeMultiLineChart.visibility = View.GONE
        isotopeStackedChart.visibility = View.GONE
        isotopeBarChart.visibility = View.GONE
        isotopeScanResultText.text = "Reading spectrum..."
        isotopeScanProgress.visibility = View.VISIBLE
        isotopeStatusLabel.text = "Scanning..."
        isotopeStatusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_amber))

        RadiaCodeForegroundService.requestSpectrum(ctx)

        // Wait for spectrum data (fallback after timeout)
        view?.postDelayed({
            if (!isAdded) return@postDelayed

            // Re-enable button
            isotopeScanBtn.isEnabled = true
            isotopeScanBtn.text = "SCAN"

            val spectrum = ma.getLastSpectrumData()
            if (spectrum == null) {
                isotopeScanResultText.text = "No spectrum data available.\nConnect to a device first."
                isotopeScanProgress.visibility = View.GONE
                isotopeStatusLabel.text = "Error"
                isotopeStatusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_red))
                return@postDelayed
            }

            val detector = ma.getIsotopeDetector() ?: return@postDelayed
            val result = detector.analyze(spectrum)

            isotopeScanProgress.visibility = View.GONE
            isotopeStatusLabel.text = "Complete"
            isotopeStatusLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_green))

            displayScanResults(result)
        }, 1500)
    }

    private fun displayScanResults(result: IsotopeDetector.AnalysisResult) {
        val ctx = requireContext()
        val topFive = result.topFive
        if (topFive.isEmpty()) {
            isotopeScanResultText.text = "No isotopes detected"
            return
        }

        val displayMode = Prefs.getIsotopeDisplayMode(ctx)
        val sb = StringBuilder()
        for ((i, pred) in topFive.withIndex()) {
            val value = if (displayMode == Prefs.IsotopeDisplayMode.PROBABILITY) {
                "${(pred.probability * 100).toInt()}%"
            } else {
                "%.1f%%".format(pred.fraction * 100)
            }
            val icon = when (i) { 0 -> "#1"; 1 -> "#2"; 2 -> "#3"; else -> "  " }
            sb.append("$icon ${pred.name}: $value\n")
        }
        isotopeScanResultText.text = sb.toString().trim()
        isotopeScanResultContainer.visibility = View.VISIBLE

        val top = topFive.firstOrNull()
        if (top != null) {
            val topValue = if (displayMode == Prefs.IsotopeDisplayMode.PROBABILITY) {
                "${(top.probability * 100).toInt()}%"
            } else {
                "%.1f%%".format(top.fraction * 100)
            }
            isotopeTopResult.text = "${top.name}: $topValue"
        }
    }

    // ── Chart refreshing ─────────────────────────────────────────────

    /**
     * Called by MainActivity when real-time isotope results arrive.
     */
    fun refreshCharts() {
        if (!isAdded || view == null) return
        val ctx = requireContext()
        val ma = mainActivity ?: return
        val isotopeHistory = ma.getCurrentIsotopeHistory()
        if (isotopeHistory.isEmpty) return

        val hideBackground = Prefs.isIsotopeHideBackground(ctx)
        val displayMode = Prefs.getIsotopeDisplayMode(ctx)
        val showProbability = displayMode == Prefs.IsotopeDisplayMode.PROBABILITY

        val topPredictions = isotopeHistory.getCurrentTop(if (hideBackground) 6 else 5)
            .filter { if (hideBackground) it.isotopeId != "Unknown" else true }
            .take(5)
        val topIds = topPredictions.map { it.isotopeId }

        val history = if (hideBackground) {
            isotopeHistory.getAll().map { result ->
                val filteredPredictions = result.predictions.filter { it.isotopeId != "Unknown" }
                val filteredTopFive = result.topFive.filter { it.isotopeId != "Unknown" }
                IsotopeDetector.AnalysisResult(
                    predictions = filteredPredictions,
                    topFive = filteredTopFive,
                    timestampMs = result.timestampMs,
                    totalCounts = result.totalCounts,
                    durationSeconds = result.durationSeconds
                )
            }
        } else {
            isotopeHistory.getAll()
        }

        isotopeMultiLineChart.setData(history, topIds, showProbability)
        isotopeStackedChart.setData(history, topIds)
        isotopeBarChart.setData(history, showProbability)

        val top = topPredictions.firstOrNull()
        if (top != null) {
            val value = if (showProbability) {
                "${(top.probability * 100).toInt()}%"
            } else {
                "%.1f%%".format(top.fraction * 100)
            }
            isotopeTopResult.text = "${top.name}: $value"
        }
    }

    private fun showChartTypeDialog() {
        val ctx = requireContext()
        val options = arrayOf("Multi-Line Chart", "Stacked Area Chart", "Animated Bars")
        val current = Prefs.getIsotopeChartMode(ctx).ordinal

        AlertDialog.Builder(ctx, R.style.DarkDialogTheme)
            .setTitle("Chart Type")
            .setSingleChoiceItems(options, current) { dialog, which ->
                val mode = Prefs.IsotopeChartMode.values()[which]
                Prefs.setIsotopeChartMode(ctx, mode)
                updateChartMode(mode)
                refreshCharts()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Intelligence card ────────────────────────────────────────────

    /**
     * Called by MainActivity on the UI loop to update intelligence card.
     */
    fun updateIntelligenceCard() {
        if (!isAdded || view == null) return
        val ctx = requireContext()
        val ma = mainActivity ?: return
        if (ma.sampleCount < 10) {
            intelligenceCard.visibility = View.GONE
            return
        }

        val currentDeviceId = Prefs.getSelectedDeviceId(ctx)
        val report = IntelligenceEngine.analyzeReadings(ctx, currentDeviceId)

        if (!report.hasEnoughData) {
            intelligenceCard.visibility = View.GONE
            return
        }

        intelligenceCard.visibility = View.VISIBLE
        intelligenceSummary.text = report.summary

        // Stability
        val (stabilityText, stabilityColor) = when (report.stability) {
            StabilityLevel.STABLE -> "STABLE" to ContextCompat.getColor(ctx, R.color.pro_green)
            StabilityLevel.VARIABLE -> "VARIABLE" to ContextCompat.getColor(ctx, R.color.pro_amber)
            StabilityLevel.ERRATIC -> "ERRATIC" to ContextCompat.getColor(ctx, R.color.pro_red)
        }
        stabilityIndicator.text = stabilityText
        stabilityIndicator.setTextColor(stabilityColor)

        // Data quality
        dataQualityLabel.text = when (report.dataQuality) {
            DataQuality.EXCELLENT -> "Excellent (${report.sampleCount})"
            DataQuality.GOOD -> "Good (${report.sampleCount})"
            DataQuality.FAIR -> "Fair (${report.sampleCount})"
            DataQuality.LIMITED -> "Limited (${report.sampleCount})"
            DataQuality.INSUFFICIENT -> "Collecting..."
        }

        // Alert badge
        val alertCount = report.alerts.size
        if (alertCount > 0) {
            intelligenceAlertBadge.visibility = View.VISIBLE
            intelligenceAlertBadge.text = alertCount.toString()
            val hasHighAlert = report.alerts.any { it.severity == AlertSeverity.HIGH }
            intelligenceAlertBadge.background.setTint(
                ContextCompat.getColor(ctx, if (hasHighAlert) R.color.pro_red else R.color.pro_amber)
            )
        } else {
            intelligenceAlertBadge.visibility = View.GONE
        }

        // Trend
        val trendDesc = report.doseTrendDescription
        if (trendDesc != null) {
            val (trendText, trendColor) = when (trendDesc.direction) {
                TrendDirection.INCREASING -> "Rising" to ContextCompat.getColor(ctx, R.color.pro_amber)
                TrendDirection.DECREASING -> "Falling" to ContextCompat.getColor(ctx, R.color.pro_cyan)
                TrendDirection.STABLE -> "Stable" to ContextCompat.getColor(ctx, R.color.pro_green)
            }
            doseTrendLabel.text = trendText
            doseTrendLabel.setTextColor(trendColor)
        }

        // Prediction
        val dosePrediction = report.predictions.firstOrNull { it.type == PredictionType.NEXT_DOSE }
        if (dosePrediction != null) {
            val doseUnit = Prefs.getDoseUnit(ctx, Prefs.DoseUnit.USV_H)
            predictedDoseLabel.text = when (doseUnit) {
                Prefs.DoseUnit.USV_H -> String.format("%.3f uSv/h", dosePrediction.predictedValue)
                Prefs.DoseUnit.NSV_H -> String.format("%.0f nSv/h", dosePrediction.predictedValue * 1000)
            }
            predictionConfidenceLabel.text = String.format("%.0f%% conf", dosePrediction.confidence)
            predictionConfidenceLabel.setTextColor(ContextCompat.getColor(ctx, when (dosePrediction.confidenceLevel) {
                ConfidenceLevel.HIGH -> R.color.pro_green
                ConfidenceLevel.MEDIUM -> R.color.pro_amber
                ConfidenceLevel.LOW -> R.color.pro_text_muted
            }))
        }

        // Anomalies
        val activeCount = report.activeAnomalyCount
        val recentCount = report.recentAnomalyCount
        when {
            activeCount > 0 -> {
                anomalyCountLabel.text = activeCount.toString()
                anomalyCountLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_red))
                anomalyDetailLabel.text = "$activeCount active"
                anomalyDetailLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_red))
            }
            recentCount > 0 -> {
                anomalyCountLabel.text = recentCount.toString()
                anomalyCountLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_amber))
                anomalyDetailLabel.text = "$recentCount recent"
                anomalyDetailLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_text_muted))
            }
            else -> {
                anomalyCountLabel.text = "0"
                anomalyCountLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_green))
                anomalyDetailLabel.text = "none"
                anomalyDetailLabel.setTextColor(ContextCompat.getColor(ctx, R.color.pro_text_muted))
            }
        }

        if (intelligenceExpanded) {
            updateExpandedStats(report)
        }
    }

    private fun updateExpandedStats(report: IntelligenceReport) {
        val ctx = requireContext()
        val doseStats = report.doseStatistics ?: return
        val doseUnit = Prefs.getDoseUnit(ctx, Prefs.DoseUnit.USV_H)

        statsRangeLabel.text = when (doseUnit) {
            Prefs.DoseUnit.USV_H -> String.format("%.3f - %.3f", doseStats.min, doseStats.max)
            Prefs.DoseUnit.NSV_H -> String.format("%.0f - %.0f", doseStats.min * 1000, doseStats.max * 1000)
        }
        statsStdDevLabel.text = when (doseUnit) {
            Prefs.DoseUnit.USV_H -> String.format("+/-%.4f", doseStats.stdDev)
            Prefs.DoseUnit.NSV_H -> String.format("+/-%.1f", doseStats.stdDev * 1000)
        }
        statsCvLabel.text = String.format("%.1f%%", doseStats.coefficientOfVariation)
        statsCvLabel.setTextColor(ContextCompat.getColor(ctx, when {
            doseStats.coefficientOfVariation < 15 -> R.color.pro_green
            doseStats.coefficientOfVariation < 35 -> R.color.pro_amber
            else -> R.color.pro_red
        }))
        statsBackgroundLabel.text = when (doseUnit) {
            Prefs.DoseUnit.USV_H -> String.format("%.3f uSv/h", report.estimatedBackground)
            Prefs.DoseUnit.NSV_H -> String.format("%.0f nSv/h", report.estimatedBackground * 1000)
        }
        statsVsBackgroundLabel.text = String.format("%.0f%%", report.currentVsBackground)
        statsVsBackgroundLabel.setTextColor(ContextCompat.getColor(ctx, when {
            report.currentVsBackground > 150 -> R.color.pro_red
            report.currentVsBackground > 120 -> R.color.pro_amber
            else -> R.color.pro_green
        }))
        statsZScoreLabel.text = String.format("%.2f s", report.currentZScore)
        statsZScoreLabel.setTextColor(ContextCompat.getColor(ctx, when {
            kotlin.math.abs(report.currentZScore) > 3 -> R.color.pro_red
            kotlin.math.abs(report.currentZScore) > 2 -> R.color.pro_amber
            else -> R.color.pro_green
        }))
    }

    private fun toggleIntelligenceExpanded() {
        val ctx = requireContext()
        intelligenceExpanded = !intelligenceExpanded
        if (intelligenceExpanded) {
            intelligenceExpandedSection.visibility = View.VISIBLE
            intelligenceExpandText.text = "Hide Details"
            intelligenceExpandArrow.rotation = 180f
            val currentDeviceId = Prefs.getSelectedDeviceId(ctx)
            val report = IntelligenceEngine.analyzeReadings(ctx, currentDeviceId)
            updateExpandedStats(report)
        } else {
            intelligenceExpandedSection.visibility = View.GONE
            intelligenceExpandText.text = "Show Details"
            intelligenceExpandArrow.rotation = 0f
        }
    }

    private fun showIntelligenceHelpDialog() {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_intelligence_help, null)
        AlertDialog.Builder(ctx, R.style.DarkDialogTheme)
            .setView(dialogView)
            .setPositiveButton("Got it", null)
            .show()
    }
}
