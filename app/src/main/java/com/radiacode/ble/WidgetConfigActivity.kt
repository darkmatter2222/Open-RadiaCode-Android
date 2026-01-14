package com.radiacode.ble

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
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
import kotlin.math.max
import kotlin.math.min

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
    private lateinit var showBollingerBandsSwitch: SwitchMaterial
    private lateinit var dynamicColorSwitch: SwitchMaterial
    private lateinit var previewCard: MaterialCardView
    private lateinit var previewDoseValue: TextView
    private lateinit var previewCpsValue: TextView
    private lateinit var previewStatusDot: TextView
    private lateinit var previewDeviceName: TextView
    private lateinit var previewSparklineRow: LinearLayout
    private lateinit var previewDoseSparkline: ImageView
    private lateinit var previewCpsSparkline: ImageView
    private lateinit var previewDoseContainer: LinearLayout
    private lateinit var previewCpsContainer: LinearLayout
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
        showBollingerBandsSwitch = findViewById(R.id.showBollingerBandsSwitch)
        dynamicColorSwitch = findViewById(R.id.dynamicColorSwitch)
        previewCard = findViewById(R.id.previewCard)
        previewDoseValue = findViewById(R.id.previewDoseValue)
        previewCpsValue = findViewById(R.id.previewCpsValue)
        previewStatusDot = findViewById(R.id.previewStatusDot)
        previewDeviceName = findViewById(R.id.previewDeviceName)
        previewSparklineRow = findViewById(R.id.previewSparklineRow)
        previewDoseSparkline = findViewById(R.id.previewDoseSparkline)
        previewCpsSparkline = findViewById(R.id.previewCpsSparkline)
        previewDoseContainer = findViewById(R.id.previewDoseContainer)
        previewCpsContainer = findViewById(R.id.previewCpsContainer)
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
        adapter.setDropDownViewResource(R.layout.item_device_spinner_simple)
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
        showBollingerBandsSwitch.setOnCheckedChangeListener { _, _ -> updatePreview() }
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
        showBollingerBandsSwitch.isChecked = existingConfig.showBollingerBands
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
    
    /**
     * Update preview to EXACTLY match what the widget will look like.
     * This is the critical function - preview must be pixel-perfect representation.
     */
    private fun updatePreview() {
        // Get selected device
        val selectedDevice = devices.find { it.id == selectedDeviceId }
        
        // Update device name
        val deviceName = selectedDevice?.shortDisplayName ?: "Device"
        previewDeviceName.text = deviceName
        
        // === COLOR CALCULATION ===
        // Get primary color from color scheme
        val schemeColor = try {
            Color.parseColor("#${selectedColorScheme.lineColor}")
        } catch (_: Exception) {
            Color.parseColor("#00E5FF")
        }
        
        // Device color overrides scheme if available
        val doseColor = if (selectedDevice != null) {
            try {
                Color.parseColor("#${selectedDevice.colorHex}")
            } catch (_: Exception) { schemeColor }
        } else {
            schemeColor
        }
        
        // CPS color derived from color scheme
        val cpsColor = getSchemeSecondaryColor(selectedColorScheme)
        
        // Background color from scheme
        val bgColor = try {
            Color.parseColor("#${selectedColorScheme.backgroundColor}")
        } catch (_: Exception) {
            Color.parseColor("#1A1A1E")
        }
        
        // Update preview card background
        previewCard.setCardBackgroundColor(bgColor)
        
        // Get actual reading data
        val reading = if (selectedDeviceId != null) {
            Prefs.getDeviceLastReading(this, selectedDeviceId!!)
        } else {
            Prefs.getLastReading(this)
        }
        
        // Apply dynamic coloring if enabled
        var effectiveDoseColor = doseColor
        if (dynamicColorSwitch.isChecked && reading != null) {
            val thresholds = Prefs.getDynamicColorThresholds(this)
            effectiveDoseColor = Color.parseColor("#${thresholds.getColorForValue(reading.uSvPerHour)}")
        }
        
        // === APPLY COLORS TO TEXT ===
        previewDoseValue.setTextColor(effectiveDoseColor)
        previewCpsValue.setTextColor(cpsColor)
        
        // Update unit label colors to match scheme
        findViewById<TextView>(R.id.previewDoseUnit)?.setTextColor(
            Color.parseColor("#${selectedColorScheme.textSecondary}")
        )
        findViewById<TextView>(R.id.previewCpsUnit)?.setTextColor(
            Color.parseColor("#${selectedColorScheme.textSecondary}")
        )
        
        // === VISIBILITY BASED ON TEMPLATE AND SWITCHES ===
        val showDose = showDoseSwitch.isChecked
        val showCps = showCpsSwitch.isChecked
        val showCharts = showSparklineSwitch.isChecked && selectedTemplate.showSparkline
        val chartType = selectedTemplate.chartType
        
        // Show/hide containers
        previewDoseContainer.visibility = if (showDose) View.VISIBLE else View.GONE
        previewCpsContainer.visibility = if (showCps) View.VISIBLE else View.GONE
        previewSparklineRow.visibility = if (showCharts && chartType != ChartType.NONE) View.VISIBLE else View.GONE
        
        // Hide individual sparklines based on dose/cps visibility
        previewDoseSparkline.visibility = if (showDose && showCharts) View.VISIBLE else View.GONE
        previewCpsSparkline.visibility = if (showCps && showCharts) View.VISIBLE else View.GONE
        
        // === UPDATE VALUES ===
        if (reading != null) {
            val doseUnit = Prefs.getDoseUnit(this)
            val countUnit = Prefs.getCountUnit(this)
            
            val (doseStr, doseUnitStr) = when (doseUnit) {
                Prefs.DoseUnit.USV_H -> String.format("%.3f", reading.uSvPerHour) to "μSv/h"
                Prefs.DoseUnit.NSV_H -> String.format("%.1f", reading.uSvPerHour * 1000f) to "nSv/h"
            }
            val (cpsStr, cpsUnitStr) = when (countUnit) {
                Prefs.CountUnit.CPS -> String.format("%.1f", reading.cps) to "cps"
                Prefs.CountUnit.CPM -> String.format("%.0f", reading.cps * 60f) to "cpm"
            }
            
            previewDoseValue.text = doseStr
            previewCpsValue.text = cpsStr
            findViewById<TextView>(R.id.previewDoseUnit)?.text = doseUnitStr
            findViewById<TextView>(R.id.previewCpsUnit)?.text = cpsUnitStr
            previewStatusDot.text = "●"
            previewStatusDot.setTextColor(Color.parseColor("#69F0AE"))
        } else {
            previewDoseValue.text = "0.057"
            previewCpsValue.text = "8.2"
            previewStatusDot.text = "○"
            previewStatusDot.setTextColor(Color.parseColor("#FF5252"))
        }
        
        // === GENERATE CHARTS ===
        if (showCharts && chartType != ChartType.NONE) {
            generateChartPreviews(effectiveDoseColor, cpsColor, chartType, bgColor)
        }
    }
    
    /**
     * Get secondary color for CPS based on color scheme
     */
    private fun getSchemeSecondaryColor(scheme: ColorScheme): Int {
        return when (scheme) {
            ColorScheme.DEFAULT -> Color.parseColor("#E040FB")     // Magenta
            ColorScheme.CYBERPUNK -> Color.parseColor("#00FFFF")   // Cyan
            ColorScheme.FOREST -> Color.parseColor("#81C784")      // Light green
            ColorScheme.OCEAN -> Color.parseColor("#4FC3F7")       // Light blue
            ColorScheme.FIRE -> Color.parseColor("#FFAB91")        // Light orange
            ColorScheme.GRAYSCALE -> Color.parseColor("#9E9E9E")   // Gray
            ColorScheme.AMBER -> Color.parseColor("#FFE082")       // Light amber
            ColorScheme.PURPLE -> Color.parseColor("#CE93D8")      // Light purple
            ColorScheme.CUSTOM -> Color.parseColor("#E040FB")
        }
    }
    
    /**
     * Generate chart preview bitmaps based on selected chart type.
     * This generates the EXACT same chart that will appear on the widget.
     */
    private fun generateChartPreviews(doseColor: Int, cpsColor: Int, chartType: ChartType, bgColor: Int) {
        val width = 180
        val height = 56
        
        // Get recent readings or generate sample data
        val readings = if (selectedDeviceId != null) {
            Prefs.getDeviceRecentReadings(this, selectedDeviceId!!)
        } else {
            Prefs.getRecentReadings(this)
        }
        
        // Generate sample data if no real data
        val doseValues = if (readings.isNotEmpty()) {
            readings.map { it.uSvPerHour }
        } else {
            generateSampleData(30, 0.05f, 0.03f)
        }
        
        val cpsValues = if (readings.isNotEmpty()) {
            readings.map { it.cps }
        } else {
            generateSampleData(30, 8f, 4f)
        }
        
        // Generate charts based on type
        val showBollinger = showBollingerBandsSwitch.isChecked
        
        val doseBitmap = when (chartType) {
            ChartType.SPARKLINE -> createSparklineChart(width, height, doseValues, doseColor, bgColor, showBollinger)
            ChartType.LINE -> createLineChart(width, height, doseValues, doseColor, bgColor, showBollinger)
            ChartType.BAR -> createBarChart(width, height, doseValues, doseColor, bgColor)
            ChartType.CANDLE -> createCandlestickChart(width, height, doseValues, bgColor)
            ChartType.AREA -> createAreaChart(width, height, doseValues, doseColor, bgColor)
            ChartType.NONE -> Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        
        val cpsBitmap = when (chartType) {
            ChartType.SPARKLINE -> createSparklineChart(width, height, cpsValues, cpsColor, bgColor, showBollinger)
            ChartType.LINE -> createLineChart(width, height, cpsValues, cpsColor, bgColor, showBollinger)
            ChartType.BAR -> createBarChart(width, height, cpsValues, cpsColor, bgColor)
            ChartType.CANDLE -> createCandlestickChart(width, height, cpsValues, bgColor)
            ChartType.AREA -> createAreaChart(width, height, cpsValues, cpsColor, bgColor)
            ChartType.NONE -> Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        
        previewDoseSparkline.setImageBitmap(doseBitmap)
        previewCpsSparkline.setImageBitmap(cpsBitmap)
    }
    
    /**
     * Generate sample sine wave data for preview
     */
    private fun generateSampleData(count: Int, baseline: Float, amplitude: Float): List<Float> {
        return (0 until count).map { i ->
            baseline + amplitude * kotlin.math.sin(i * 0.35f).toFloat() + 
            amplitude * 0.3f * kotlin.math.sin(i * 0.7f + 1f).toFloat()
        }
    }
    
    /**
     * Create sparkline chart (minimal, no axes)
     */
    private fun createSparklineChart(width: Int, height: Int, values: List<Float>, color: Int, bgColor: Int, showBollinger: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
        
        if (values.size < 2) return bitmap
        
        val minVal = values.minOrNull() ?: 0f
        val maxVal = values.maxOrNull() ?: 1f
        val range = max(maxVal - minVal, 0.001f)
        
        val padding = 6f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        
        // Draw Bollinger Bands if enabled
        if (showBollinger && values.size >= 10) {
            drawBollingerBands(canvas, values, padding, chartWidth, chartHeight, minVal, range, color)
        }
        
        // Build line path
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range) * chartHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        
        // Glow effect
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = 50
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }
        canvas.drawPath(path, glowPaint)
        
        // Main line
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, linePaint)
        
        // Gradient fill
        val fillPath = Path(path)
        fillPath.lineTo(padding + chartWidth, padding + chartHeight)
        fillPath.lineTo(padding, padding + chartHeight)
        fillPath.close()
        
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, padding, 0f, padding + chartHeight,
                (color and 0x00FFFFFF) or 0x30000000,
                (color and 0x00FFFFFF) or 0x05000000,
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawPath(fillPath, fillPaint)
        
        return bitmap
    }
    
    /**
     * Create line chart with axis hints
     */
    private fun createLineChart(width: Int, height: Int, values: List<Float>, color: Int, bgColor: Int, showBollinger: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
        
        if (values.size < 2) return bitmap
        
        val minVal = values.minOrNull() ?: 0f
        val maxVal = values.maxOrNull() ?: 1f
        val range = max(maxVal - minVal, 0.001f)
        
        val padding = 8f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        
        // Draw grid lines
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(30, 255, 255, 255)
            strokeWidth = 1f
        }
        for (i in 0..3) {
            val y = padding + (i / 3f) * chartHeight
            canvas.drawLine(padding, y, padding + chartWidth, y, gridPaint)
        }
        
        // Bollinger bands
        if (showBollinger && values.size >= 10) {
            drawBollingerBands(canvas, values, padding, chartWidth, chartHeight, minVal, range, color)
        }
        
        // Line path
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range) * chartHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        
        // Main line with glow
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = 60
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        canvas.drawPath(path, glowPaint)
        
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(path, linePaint)
        
        // Data points at key positions
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        listOf(0, values.size / 2, values.size - 1).forEach { idx ->
            if (idx < values.size) {
                val x = padding + (idx.toFloat() / (values.size - 1)) * chartWidth
                val y = padding + chartHeight - ((values[idx] - minVal) / range) * chartHeight
                canvas.drawCircle(x, y, 3f, dotPaint)
            }
        }
        
        return bitmap
    }
    
    /**
     * Create bar chart
     */
    private fun createBarChart(width: Int, height: Int, values: List<Float>, color: Int, bgColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
        
        if (values.isEmpty()) return bitmap
        
        // Aggregate into bars (group every 3-5 values)
        val barCount = min(12, values.size)
        val groupSize = max(1, values.size / barCount)
        val barValues = values.chunked(groupSize).map { it.average().toFloat() }
        
        val minVal = barValues.minOrNull() ?: 0f
        val maxVal = barValues.maxOrNull() ?: 1f
        val range = max(maxVal - minVal, 0.001f)
        
        val padding = 8f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        val barGap = 3f
        val barWidth = (chartWidth - barGap * (barValues.size - 1)) / barValues.size
        
        // Draw bars
        barValues.forEachIndexed { index, value ->
            val barHeight = ((value - minVal) / range) * chartHeight
            val x = padding + index * (barWidth + barGap)
            val y = padding + chartHeight - barHeight
            
            // Bar gradient
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    x, y, x, padding + chartHeight,
                    color,
                    (color and 0x00FFFFFF) or 0x80000000.toInt(),
                    Shader.TileMode.CLAMP
                )
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(x, y, x + barWidth, padding + chartHeight, 2f, 2f, barPaint)
            
            // Bar highlight
            val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.argb(80, 255, 255, 255)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(x, y, x + barWidth * 0.3f, y + barHeight * 0.5f, 2f, 2f, highlightPaint)
        }
        
        return bitmap
    }
    
    /**
     * Create candlestick chart (OHLC)
     */
    private fun createCandlestickChart(width: Int, height: Int, values: List<Float>, bgColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
        
        if (values.size < 4) return bitmap
        
        // Create candles from data (group into OHLC)
        val candleCount = min(10, values.size / 3)
        val groupSize = max(3, values.size / candleCount)
        
        data class Candle(val open: Float, val high: Float, val low: Float, val close: Float)
        
        val candles = values.chunked(groupSize).map { group ->
            if (group.isEmpty()) Candle(0f, 0f, 0f, 0f)
            else Candle(
                open = group.first(),
                close = group.last(),
                high = group.maxOrNull() ?: 0f,
                low = group.minOrNull() ?: 0f
            )
        }
        
        if (candles.isEmpty()) return bitmap
        
        val minVal = candles.minOfOrNull { it.low } ?: 0f
        val maxVal = candles.maxOfOrNull { it.high } ?: 1f
        val range = max(maxVal - minVal, 0.001f)
        
        val padding = 8f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        val candleGap = 3f
        val candleWidth = (chartWidth - candleGap * (candles.size - 1)) / candles.size
        
        val upColor = Color.parseColor("#69F0AE")
        val downColor = Color.parseColor("#FF5252")
        val wickColor = Color.parseColor("#9E9E9E")
        
        candles.forEachIndexed { index, candle ->
            val centerX = padding + index * (candleWidth + candleGap) + candleWidth / 2
            
            fun toY(v: Float) = padding + chartHeight - ((v - minVal) / range) * chartHeight
            
            val highY = toY(candle.high)
            val lowY = toY(candle.low)
            val openY = toY(candle.open)
            val closeY = toY(candle.close)
            
            // Wick
            val wickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = wickColor
                strokeWidth = 1.5f
            }
            canvas.drawLine(centerX, highY, centerX, lowY, wickPaint)
            
            // Body
            val isUp = candle.close >= candle.open
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isUp) upColor else downColor
                style = Paint.Style.FILL
            }
            
            val bodyTop = min(openY, closeY)
            val bodyBottom = max(openY, closeY)
            val bodyHeight = max(bodyBottom - bodyTop, 3f)
            val bodyLeft = padding + index * (candleWidth + candleGap)
            
            canvas.drawRoundRect(
                bodyLeft, bodyTop, 
                bodyLeft + candleWidth, bodyTop + bodyHeight, 
                2f, 2f, bodyPaint
            )
        }
        
        return bitmap
    }
    
    /**
     * Create area chart (filled)
     */
    private fun createAreaChart(width: Int, height: Int, values: List<Float>, color: Int, bgColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint)
        
        if (values.size < 2) return bitmap
        
        val minVal = values.minOrNull() ?: 0f
        val maxVal = values.maxOrNull() ?: 1f
        val range = max(maxVal - minVal, 0.001f)
        
        val padding = 6f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        
        // Build filled area path
        val fillPath = Path()
        fillPath.moveTo(padding, padding + chartHeight)
        
        values.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range) * chartHeight
            fillPath.lineTo(x, y)
        }
        
        fillPath.lineTo(padding + chartWidth, padding + chartHeight)
        fillPath.close()
        
        // Gradient fill
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, padding, 0f, padding + chartHeight,
                (color and 0x00FFFFFF) or 0xA0000000.toInt(),
                (color and 0x00FFFFFF) or 0x20000000,
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawPath(fillPath, fillPaint)
        
        // Top edge line
        val linePath = Path()
        values.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range) * chartHeight
            if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }
        
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawPath(linePath, linePaint)
        
        return bitmap
    }
    
    /**
     * Draw Bollinger Bands on canvas
     */
    private fun drawBollingerBands(
        canvas: Canvas, 
        values: List<Float>, 
        padding: Float, 
        chartWidth: Float, 
        chartHeight: Float,
        minVal: Float,
        range: Float,
        color: Int
    ) {
        val period = min(20, values.size)
        if (values.size < period) return
        
        // Calculate moving average and std dev
        val upperBand = mutableListOf<Float>()
        val lowerBand = mutableListOf<Float>()
        
        for (i in values.indices) {
            val start = max(0, i - period + 1)
            val window = values.subList(start, i + 1)
            val mean = window.average().toFloat()
            val variance = window.map { (it - mean) * (it - mean) }.average().toFloat()
            val stdDev = kotlin.math.sqrt(variance)
            upperBand.add(mean + 2 * stdDev)
            lowerBand.add(mean - 2 * stdDev)
        }
        
        // Draw band as filled area
        val bandPath = Path()
        upperBand.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((value - minVal) / range).coerceIn(0f, 1f) * chartHeight
            if (index == 0) bandPath.moveTo(x, y) else bandPath.lineTo(x, y)
        }
        
        for (i in lowerBand.indices.reversed()) {
            val x = padding + (i.toFloat() / (values.size - 1)) * chartWidth
            val y = padding + chartHeight - ((lowerBand[i] - minVal) / range).coerceIn(0f, 1f) * chartHeight
            bandPath.lineTo(x, y)
        }
        bandPath.close()
        
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = (color and 0x00FFFFFF) or 0x20000000
            style = Paint.Style.FILL
        }
        canvas.drawPath(bandPath, bandPaint)
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
            showBollingerBands = showBollingerBandsSwitch.isChecked,
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
