package com.radiacode.ble

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Configuration activity for widget instances.
 * Shown when user adds a widget to their home screen.
 * Allows binding to specific device and customizing appearance.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    
    // UI elements
    private lateinit var deviceSpinner: Spinner
    private lateinit var templateChips: ChipGroup
    private lateinit var colorSchemeChips: ChipGroup
    private lateinit var updateIntervalChips: ChipGroup
    private lateinit var timeWindowChips: ChipGroup
    private lateinit var showDoseSwitch: SwitchMaterial
    private lateinit var showCpsSwitch: SwitchMaterial
    private lateinit var showTimeSwitch: SwitchMaterial
    private lateinit var showSparklineSwitch: SwitchMaterial
    private lateinit var showIntelligenceSwitch: SwitchMaterial
    private lateinit var dynamicColorSwitch: SwitchMaterial
    private lateinit var previewCard: MaterialCardView
    private lateinit var previewDoseValue: TextView
    private lateinit var previewCpsValue: TextView
    private lateinit var previewStatusDot: TextView
    private lateinit var previewDeviceName: TextView
    private lateinit var confirmButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    
    private var devices: List<DeviceConfig> = emptyList()
    private var selectedDeviceId: String? = null
    private var selectedTemplate: LayoutTemplate = LayoutTemplate.DUAL_SPARKLINE
    private var selectedColorScheme: ColorScheme = ColorScheme.DEFAULT
    private var selectedUpdateInterval: Int = 1
    private var selectedTimeWindow: Int = 60
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set the result to CANCELED in case the user backs out
        setResult(RESULT_CANCELED)
        
        // Get the widget ID from the intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        
        setContentView(R.layout.activity_widget_config)
        
        bindViews()
        setupDeviceSpinner()
        setupTemplateChips()
        setupColorSchemeChips()
        setupIntervalChips()
        setupTimeWindowChips()
        setupSwitches()
        setupButtons()
        
        // Load existing config if editing
        loadExistingConfig()
        
        updatePreview()
    }
    
    private fun bindViews() {
        deviceSpinner = findViewById(R.id.deviceSpinner)
        templateChips = findViewById(R.id.templateChips)
        colorSchemeChips = findViewById(R.id.colorSchemeChips)
        updateIntervalChips = findViewById(R.id.updateIntervalChips)
        timeWindowChips = findViewById(R.id.timeWindowChips)
        showDoseSwitch = findViewById(R.id.showDoseSwitch)
        showCpsSwitch = findViewById(R.id.showCpsSwitch)
        showTimeSwitch = findViewById(R.id.showTimeSwitch)
        showSparklineSwitch = findViewById(R.id.showSparklineSwitch)
        showIntelligenceSwitch = findViewById(R.id.showIntelligenceSwitch)
        dynamicColorSwitch = findViewById(R.id.dynamicColorSwitch)
        previewCard = findViewById(R.id.previewCard)
        previewDoseValue = findViewById(R.id.previewDoseValue)
        previewCpsValue = findViewById(R.id.previewCpsValue)
        previewStatusDot = findViewById(R.id.previewStatusDot)
        previewDeviceName = findViewById(R.id.previewDeviceName)
        confirmButton = findViewById(R.id.confirmButton)
        cancelButton = findViewById(R.id.cancelButton)
    }
    
    private fun setupDeviceSpinner() {
        devices = Prefs.getDevices(this)
        
        // If no devices, show placeholder
        if (devices.isEmpty()) {
            val adapter = ArrayAdapter(this, R.layout.item_device_spinner_simple, listOf("No devices paired"))
            deviceSpinner.adapter = adapter
            deviceSpinner.isEnabled = false
            return
        }
        
        // Build device list - no aggregate option, just real devices
        val deviceNames = devices.map { it.displayName }
        
        val adapter = ArrayAdapter(this, R.layout.item_device_spinner_simple, deviceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = adapter
        
        // Default to first device
        selectedDeviceId = devices.firstOrNull()?.id
        
        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDeviceId = devices.getOrNull(position)?.id
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun setupTemplateChips() {
        templateChips.removeAllViews()
        
        LayoutTemplate.values().forEach { template ->
            val chip = Chip(this).apply {
                text = template.displayName
                isCheckable = true
                isChecked = template == selectedTemplate
                tag = template
                setOnClickListener {
                    selectedTemplate = template
                    applyTemplate(template)
                    updatePreview()
                }
            }
            templateChips.addView(chip)
        }
    }
    
    private fun setupColorSchemeChips() {
        colorSchemeChips.removeAllViews()
        
        ColorScheme.values().filter { it != ColorScheme.CUSTOM }.forEach { scheme ->
            val chip = Chip(this).apply {
                text = scheme.displayName
                isCheckable = true
                isChecked = scheme == selectedColorScheme
                tag = scheme
                
                // Set chip color indicator
                try {
                    chipIconTint = android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#${scheme.lineColor}")
                    )
                } catch (_: Exception) {}
                
                setOnClickListener {
                    selectedColorScheme = scheme
                    updateColorSchemeSelection()
                    updatePreview()
                }
            }
            colorSchemeChips.addView(chip)
        }
    }
    
    private fun setupIntervalChips() {
        val intervals = listOf(
            1 to "Real-time",
            5 to "5 sec",
            30 to "30 sec",
            60 to "1 min",
            300 to "5 min"
        )
        
        updateIntervalChips.removeAllViews()
        intervals.forEach { (seconds, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = seconds == selectedUpdateInterval
                tag = seconds
                setOnClickListener {
                    selectedUpdateInterval = seconds
                    updateIntervalSelection()
                }
            }
            updateIntervalChips.addView(chip)
        }
    }
    
    private fun setupTimeWindowChips() {
        val windows = listOf(
            30 to "30s",
            60 to "1m",
            300 to "5m",
            900 to "15m",
            3600 to "1h"
        )
        
        timeWindowChips.removeAllViews()
        windows.forEach { (seconds, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = seconds == selectedTimeWindow
                tag = seconds
                setOnClickListener {
                    selectedTimeWindow = seconds
                    updateTimeWindowSelection()
                }
            }
            timeWindowChips.addView(chip)
        }
    }
    
    private fun setupSwitches() {
        showDoseSwitch.setOnCheckedChangeListener { _, _ -> updatePreview() }
        showCpsSwitch.setOnCheckedChangeListener { _, _ -> updatePreview() }
        showTimeSwitch.setOnCheckedChangeListener { _, _ -> updatePreview() }
        showSparklineSwitch.setOnCheckedChangeListener { _, _ -> updatePreview() }
        dynamicColorSwitch.setOnCheckedChangeListener { _, _ -> updatePreview() }
    }
    
    private fun setupButtons() {
        confirmButton.setOnClickListener {
            saveConfigAndFinish()
        }
        
        cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }
    
    private fun loadExistingConfig() {
        val existingConfig = Prefs.getWidgetConfig(this, appWidgetId) ?: return
        
        // Restore device selection
        selectedDeviceId = existingConfig.deviceId
        val deviceIndex = if (selectedDeviceId == null) 0 
            else devices.indexOfFirst { it.id == selectedDeviceId }
        if (deviceIndex >= 0) deviceSpinner.setSelection(deviceIndex)
        
        // Restore template
        selectedTemplate = existingConfig.layoutTemplate
        updateTemplateSelection()
        
        // Restore color scheme
        selectedColorScheme = existingConfig.colorScheme
        updateColorSchemeSelection()
        
        // Restore intervals
        selectedUpdateInterval = existingConfig.updateIntervalSeconds
        updateIntervalSelection()
        
        selectedTimeWindow = existingConfig.timeWindowSeconds
        updateTimeWindowSelection()
        
        // Restore switches
        showDoseSwitch.isChecked = existingConfig.showDose
        showCpsSwitch.isChecked = existingConfig.showCps
        showTimeSwitch.isChecked = existingConfig.showTime
        showSparklineSwitch.isChecked = existingConfig.showSparkline
        showIntelligenceSwitch.isChecked = existingConfig.showIntelligence
        dynamicColorSwitch.isChecked = existingConfig.dynamicColorEnabled
    }
    
    private fun applyTemplate(template: LayoutTemplate) {
        showDoseSwitch.isChecked = template.showDose
        showCpsSwitch.isChecked = template.showCps
        showTimeSwitch.isChecked = template.showTime
        showSparklineSwitch.isChecked = template.showSparkline
        updateTemplateSelection()
    }
    
    private fun updateTemplateSelection() {
        for (i in 0 until templateChips.childCount) {
            val chip = templateChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag == selectedTemplate
        }
    }
    
    private fun updateColorSchemeSelection() {
        for (i in 0 until colorSchemeChips.childCount) {
            val chip = colorSchemeChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag == selectedColorScheme
        }
    }
    
    private fun updateIntervalSelection() {
        for (i in 0 until updateIntervalChips.childCount) {
            val chip = updateIntervalChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag == selectedUpdateInterval
        }
    }
    
    private fun updateTimeWindowSelection() {
        for (i in 0 until timeWindowChips.childCount) {
            val chip = timeWindowChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag == selectedTimeWindow
        }
    }
    
    private fun updatePreview() {
        // Get selected device
        val selectedDevice = devices.find { it.id == selectedDeviceId }
        
        // Update device name
        val deviceName = selectedDevice?.shortDisplayName ?: "Device"
        previewDeviceName.text = deviceName
        
        // Get the device's color
        val deviceColor = try {
            Color.parseColor("#${selectedDevice?.colorHex ?: selectedColorScheme.lineColor}")
        } catch (_: Exception) {
            Color.parseColor("#00E5FF")
        }
        
        // Get actual reading data if available
        val reading = if (selectedDeviceId != null) {
            Prefs.getDeviceLastReading(this, selectedDeviceId!!)
        } else {
            Prefs.getLastReading(this)
        }
        
        // Update colors - use device color as primary
        var doseColor = deviceColor
        
        // Preview dynamic coloring if enabled
        if (dynamicColorSwitch.isChecked && reading != null) {
            val thresholds = Prefs.getDynamicColorThresholds(this)
            val doseValue = reading.uSvPerHour
            doseColor = Color.parseColor("#${thresholds.getColorForValue(doseValue)}")
        }
        previewDoseValue.setTextColor(doseColor)
        
        // CPS uses scheme color or default magenta
        try {
            previewCpsValue.setTextColor(Color.parseColor("#E040FB"))
        } catch (_: Exception) {}
        
        // Show/hide elements based on switches
        previewDoseValue.visibility = if (showDoseSwitch.isChecked) View.VISIBLE else View.GONE
        previewCpsValue.visibility = if (showCpsSwitch.isChecked) View.VISIBLE else View.GONE
        
        // Update preview values with actual data if available, else sample
        if (reading != null) {
            val doseUnit = Prefs.getDoseUnit(this)
            val countUnit = Prefs.getCountUnit(this)
            // Format dose value
            val (doseStr, _) = when (doseUnit) {
                Prefs.DoseUnit.USV_H -> String.format("%.3f", reading.uSvPerHour) to "μSv/h"
                Prefs.DoseUnit.NSV_H -> String.format("%.1f", reading.uSvPerHour * 1000f) to "nSv/h"
            }
            // Format count value
            val (cpsStr, _) = when (countUnit) {
                Prefs.CountUnit.CPS -> String.format("%.1f", reading.cps) to "cps"
                Prefs.CountUnit.CPM -> String.format("%.0f", reading.cps * 60f) to "cpm"
            }
            previewDoseValue.text = doseStr
            previewCpsValue.text = cpsStr
            previewStatusDot.text = "●"
            previewStatusDot.setTextColor(Color.parseColor("#69F0AE"))
        } else {
            previewDoseValue.text = "0.057"
            previewCpsValue.text = "8.2"
            previewStatusDot.text = "○"
            previewStatusDot.setTextColor(Color.parseColor("#FF5252"))
        }
    }
    
    private fun saveConfigAndFinish() {
        val config = WidgetConfig(
            widgetId = appWidgetId,
            deviceId = selectedDeviceId,
            chartType = selectedTemplate.chartType,
            showDose = showDoseSwitch.isChecked,
            showCps = showCpsSwitch.isChecked,
            showTime = showTimeSwitch.isChecked,
            showStatus = true,
            showSparkline = showSparklineSwitch.isChecked,
            showIntelligence = showIntelligenceSwitch.isChecked,
            updateIntervalSeconds = selectedUpdateInterval,
            timeWindowSeconds = selectedTimeWindow,
            colorScheme = selectedColorScheme,
            customColors = null,
            layoutTemplate = selectedTemplate,
            dynamicColorEnabled = dynamicColorSwitch.isChecked
        )
        
        Prefs.setWidgetConfig(this, config)
        
        // Update the widget immediately
        val appWidgetManager = AppWidgetManager.getInstance(this)
        
        // Determine which provider this widget belongs to and update it
        // For now, update all providers - the buildViews will use the saved config
        ChartWidgetProvider.updateWidget(this, appWidgetId)
        SimpleWidgetProvider.updateWidget(this, appWidgetId)
        RadiaCodeWidgetProvider.updateWidget(this, appWidgetId)
        
        // Return success
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
