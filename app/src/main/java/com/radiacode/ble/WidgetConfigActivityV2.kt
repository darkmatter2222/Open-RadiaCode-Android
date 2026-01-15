package com.radiacode.ble

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Widget Configuration Activity V2
 * 
 * SIMPLIFIED, INTUITIVE widget configuration with:
 * - Live preview that EXACTLY matches widget output (using shared WidgetRenderer)
 * - Simple toggle switches for data sources
 * - Easy chart type selection per metric
 * - Color theme picker
 * - Advanced options in collapsible section
 * 
 * KEY PRINCIPLE: The preview uses THE EXACT SAME rendering code as the widget.
 * This guarantees pixel-perfect matching between what user sees and what they get.
 */
class WidgetConfigActivityV2 : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    // UI Elements
    private lateinit var previewContent: View
    private lateinit var deviceSpinner: Spinner
    private lateinit var showDoseSwitch: SwitchMaterial
    private lateinit var showCountSwitch: SwitchMaterial
    private lateinit var showTimestampSwitch: SwitchMaterial
    private lateinit var doseChartOptions: LinearLayout
    private lateinit var countChartOptions: LinearLayout
    private lateinit var doseChartTypeChips: ChipGroup
    private lateinit var countChartTypeChips: ChipGroup
    private lateinit var colorThemeChips: ChipGroup
    private lateinit var timeWindowChips: ChipGroup
    private lateinit var advancedHeader: LinearLayout
    private lateinit var advancedContent: LinearLayout
    private lateinit var advancedExpandIcon: ImageView
    private lateinit var showTrendSwitch: SwitchMaterial
    private lateinit var showAnomalySwitch: SwitchMaterial
    private lateinit var dynamicColorSwitch: SwitchMaterial
    private lateinit var bollingerSwitch: SwitchMaterial
    private lateinit var cancelButton: MaterialButton
    private lateinit var createButton: MaterialButton

    // State
    private var devices: List<DeviceConfig> = emptyList()
    private var selectedDeviceId: String? = null
    private var advancedExpanded = false

    // Current configuration (builds up as user makes changes)
    private var showDose = true
    private var showCount = true
    private var showTimestamp = true
    private var doseChartType = ChartType.SPARKLINE
    private var countChartType = ChartType.SPARKLINE
    private var colorScheme = ColorScheme.DEFAULT
    private var timeWindowSeconds = 60
    private var showTrend = false
    private var showAnomaly = false
    private var dynamicColor = false
    private var bollingerBands = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set result to CANCELED in case user backs out
        setResult(RESULT_CANCELED)

        // Get widget ID
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_config_v2)

        bindViews()
        setupDeviceSpinner()
        setupDataSourceSwitches()
        setupChartTypeChips()
        setupColorThemeChips()
        setupTimeWindowChips()
        setupAdvancedSection()
        setupButtons()

        // Load existing config if editing
        loadExistingConfig()

        // Initial preview render
        updatePreview()
    }

    private fun bindViews() {
        previewContent = findViewById(R.id.previewContent)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        showDoseSwitch = findViewById(R.id.showDoseSwitch)
        showCountSwitch = findViewById(R.id.showCountSwitch)
        showTimestampSwitch = findViewById(R.id.showTimestampSwitch)
        doseChartOptions = findViewById(R.id.doseChartOptions)
        countChartOptions = findViewById(R.id.countChartOptions)
        doseChartTypeChips = findViewById(R.id.doseChartTypeChips)
        countChartTypeChips = findViewById(R.id.countChartTypeChips)
        colorThemeChips = findViewById(R.id.colorThemeChips)
        timeWindowChips = findViewById(R.id.timeWindowChips)
        advancedHeader = findViewById(R.id.advancedHeader)
        advancedContent = findViewById(R.id.advancedContent)
        advancedExpandIcon = findViewById(R.id.advancedExpandIcon)
        showTrendSwitch = findViewById(R.id.showTrendSwitch)
        showAnomalySwitch = findViewById(R.id.showAnomalySwitch)
        dynamicColorSwitch = findViewById(R.id.dynamicColorSwitch)
        bollingerSwitch = findViewById(R.id.bollingerSwitch)
        cancelButton = findViewById(R.id.cancelButton)
        createButton = findViewById(R.id.createButton)
    }

    private fun setupDeviceSpinner() {
        devices = Prefs.getDevices(this)

        if (devices.isEmpty()) {
            val adapter = ArrayAdapter(this, R.layout.item_device_spinner_simple, listOf("No devices paired"))
            deviceSpinner.adapter = adapter
            deviceSpinner.isEnabled = false
            return
        }

        val deviceNames = devices.map { it.displayName }
        val adapter = ArrayAdapter(this, R.layout.item_device_spinner_simple, deviceNames)
        adapter.setDropDownViewResource(R.layout.item_device_spinner_simple)
        deviceSpinner.adapter = adapter

        selectedDeviceId = devices.firstOrNull()?.id

        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDeviceId = devices.getOrNull(position)?.id
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupDataSourceSwitches() {
        showDoseSwitch.setOnCheckedChangeListener { _, isChecked ->
            showDose = isChecked
            doseChartOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            
            // If both are off, force at least one on
            if (!isChecked && !showCount) {
                showCountSwitch.isChecked = true
            }
            updatePreview()
        }

        showCountSwitch.setOnCheckedChangeListener { _, isChecked ->
            showCount = isChecked
            countChartOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            
            // If both are off, force at least one on
            if (!isChecked && !showDose) {
                showDoseSwitch.isChecked = true
            }
            updatePreview()
        }

        showTimestampSwitch.setOnCheckedChangeListener { _, isChecked ->
            showTimestamp = isChecked
            updatePreview()
        }
    }

    private fun setupChartTypeChips() {
        // Dose chart type selection
        doseChartTypeChips.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            doseChartType = when (checkedIds.first()) {
                R.id.doseChartNone -> ChartType.NONE
                R.id.doseChartSparkline -> ChartType.SPARKLINE
                R.id.doseChartLine -> ChartType.LINE
                R.id.doseChartBar -> ChartType.BAR
                else -> ChartType.SPARKLINE
            }
            updatePreview()
        }

        // Count chart type selection
        countChartTypeChips.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            countChartType = when (checkedIds.first()) {
                R.id.countChartNone -> ChartType.NONE
                R.id.countChartSparkline -> ChartType.SPARKLINE
                R.id.countChartLine -> ChartType.LINE
                R.id.countChartBar -> ChartType.BAR
                else -> ChartType.SPARKLINE
            }
            updatePreview()
        }

        // Set default selections
        findViewById<Chip>(R.id.doseChartSparkline).isChecked = true
        findViewById<Chip>(R.id.countChartSparkline).isChecked = true
    }

    private fun setupColorThemeChips() {
        colorThemeChips.removeAllViews()

        val themes = listOf(
            ColorScheme.DEFAULT to "Cyan/Magenta",
            ColorScheme.OCEAN to "Ocean",
            ColorScheme.FOREST to "Forest",
            ColorScheme.FIRE to "Fire",
            ColorScheme.AMBER to "Amber",
            ColorScheme.PURPLE to "Purple",
            ColorScheme.GRAYSCALE to "Grayscale"
        )

        themes.forEach { (scheme, name) ->
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = scheme == colorScheme
                tag = scheme

                // Color indicator
                try {
                    chipIconTint = android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#${scheme.lineColor}")
                    )
                } catch (_: Exception) {}

                setOnClickListener {
                    colorScheme = scheme
                    updateColorThemeSelection()
                    updatePreview()
                }
            }
            colorThemeChips.addView(chip)
        }
    }

    private fun setupTimeWindowChips() {
        val windows = listOf(
            30 to "30s",
            60 to "1m",
            300 to "5m",
            900 to "15m"
        )

        timeWindowChips.removeAllViews()
        windows.forEach { (seconds, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = seconds == timeWindowSeconds
                tag = seconds
                setOnClickListener {
                    timeWindowSeconds = seconds
                    updateTimeWindowSelection()
                    updatePreview()
                }
            }
            timeWindowChips.addView(chip)
        }
    }

    private fun setupAdvancedSection() {
        advancedHeader.setOnClickListener {
            advancedExpanded = !advancedExpanded
            advancedContent.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            advancedExpandIcon.rotation = if (advancedExpanded) 180f else 0f
        }

        showTrendSwitch.setOnCheckedChangeListener { _, isChecked ->
            showTrend = isChecked
            updatePreview()
        }

        showAnomalySwitch.setOnCheckedChangeListener { _, isChecked ->
            showAnomaly = isChecked
            updatePreview()
        }

        dynamicColorSwitch.setOnCheckedChangeListener { _, isChecked ->
            dynamicColor = isChecked
            updatePreview()
        }

        bollingerSwitch.setOnCheckedChangeListener { _, isChecked ->
            bollingerBands = isChecked
            updatePreview()
        }
    }

    private fun setupButtons() {
        cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        createButton.setOnClickListener {
            saveConfigAndFinish()
        }
    }

    private fun loadExistingConfig() {
        val existingConfig = Prefs.getWidgetConfig(this, appWidgetId) ?: return

        // Restore device selection
        selectedDeviceId = existingConfig.deviceId
        val deviceIndex = devices.indexOfFirst { it.id == selectedDeviceId }
        if (deviceIndex >= 0) deviceSpinner.setSelection(deviceIndex)

        // Restore toggles
        showDose = existingConfig.showDose
        showCount = existingConfig.showCps
        showTimestamp = existingConfig.showTime
        showDoseSwitch.isChecked = showDose
        showCountSwitch.isChecked = showCount
        showTimestampSwitch.isChecked = showTimestamp

        // Restore chart types
        doseChartType = if (existingConfig.showSparkline) existingConfig.chartType else ChartType.NONE
        countChartType = if (existingConfig.showSparkline) existingConfig.chartType else ChartType.NONE
        updateDoseChartTypeSelection()
        updateCountChartTypeSelection()

        // Restore color scheme
        colorScheme = existingConfig.colorScheme
        updateColorThemeSelection()

        // Restore time window
        timeWindowSeconds = existingConfig.timeWindowSeconds
        updateTimeWindowSelection()

        // Restore advanced options
        showTrend = existingConfig.showIntelligence
        showAnomaly = existingConfig.showIntelligence
        dynamicColor = existingConfig.dynamicColorEnabled
        bollingerBands = existingConfig.showBollingerBands
        showTrendSwitch.isChecked = showTrend
        showAnomalySwitch.isChecked = showAnomaly
        dynamicColorSwitch.isChecked = dynamicColor
        bollingerSwitch.isChecked = bollingerBands

        // Update button text for editing
        createButton.text = "Save Widget"
    }

    private fun updateColorThemeSelection() {
        for (i in 0 until colorThemeChips.childCount) {
            val chip = colorThemeChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag == colorScheme
        }
    }

    private fun updateTimeWindowSelection() {
        for (i in 0 until timeWindowChips.childCount) {
            val chip = timeWindowChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag == timeWindowSeconds
        }
    }

    private fun updateDoseChartTypeSelection() {
        when (doseChartType) {
            ChartType.NONE -> findViewById<Chip>(R.id.doseChartNone).isChecked = true
            ChartType.SPARKLINE -> findViewById<Chip>(R.id.doseChartSparkline).isChecked = true
            ChartType.LINE -> findViewById<Chip>(R.id.doseChartLine).isChecked = true
            ChartType.BAR -> findViewById<Chip>(R.id.doseChartBar).isChecked = true
            else -> findViewById<Chip>(R.id.doseChartSparkline).isChecked = true
        }
    }

    private fun updateCountChartTypeSelection() {
        when (countChartType) {
            ChartType.NONE -> findViewById<Chip>(R.id.countChartNone).isChecked = true
            ChartType.SPARKLINE -> findViewById<Chip>(R.id.countChartSparkline).isChecked = true
            ChartType.LINE -> findViewById<Chip>(R.id.countChartLine).isChecked = true
            ChartType.BAR -> findViewById<Chip>(R.id.countChartBar).isChecked = true
            else -> findViewById<Chip>(R.id.countChartSparkline).isChecked = true
        }
    }

    /**
     * Update preview using the SAME rendering code as the actual widget.
     * This is the KEY to pixel-perfect preview matching.
     */
    private fun updatePreview() {
        // Build config from current UI state
        val config = buildConfig()

        // Get data (real or sample)
        val data = if (selectedDeviceId != null) {
            val realData = WidgetRenderer.loadWidgetData(this, selectedDeviceId)
            if (realData.doseValue != null) realData else WidgetRenderer.generateSampleData()
        } else {
            WidgetRenderer.generateSampleData()
        }

        // Render using shared renderer - EXACT same code as widget uses
        WidgetRenderer.renderToPreview(this, config, data, previewContent)
    }

    private fun buildConfig(): WidgetConfig {
        // Determine chart type: use dose chart type as primary, or count if dose is off
        val primaryChartType = when {
            showDose && doseChartType != ChartType.NONE -> doseChartType
            showCount && countChartType != ChartType.NONE -> countChartType
            else -> ChartType.NONE
        }

        return WidgetConfig(
            widgetId = appWidgetId,
            deviceId = selectedDeviceId,
            chartType = primaryChartType,
            showDose = showDose,
            showCps = showCount,
            showTime = showTimestamp,
            showStatus = true,
            showSparkline = primaryChartType != ChartType.NONE,
            showIntelligence = showTrend || showAnomaly,
            showBollingerBands = bollingerBands,
            updateIntervalSeconds = 1,
            timeWindowSeconds = timeWindowSeconds,
            colorScheme = colorScheme,
            customColors = null,
            layoutTemplate = LayoutTemplate.DUAL_SPARKLINE, // Ignored in V2
            aggregationSeconds = 1,
            dynamicColorEnabled = dynamicColor
        )
    }

    private fun saveConfigAndFinish() {
        val config = buildConfig()
        Prefs.setWidgetConfig(this, config)

        // Update the widget immediately using unified renderer
        ChartWidgetProvider.updateWidget(this, appWidgetId)
        SimpleWidgetProvider.updateWidget(this, appWidgetId)
        RadiaCodeWidgetProvider.updateWidget(this, appWidgetId)

        // Return success
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
