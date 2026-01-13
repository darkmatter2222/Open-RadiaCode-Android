package com.radiacode.ble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiacode.ble.ui.MetricCardView
import com.radiacode.ble.ui.ProChartView
import com.radiacode.ble.ui.StatRowView
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "RadiaCode"
        private const val MAX_CHART_POINTS = 800
    }

    // Navigation
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: MaterialToolbar

    // Panels
    private lateinit var panelDashboard: View
    private lateinit var panelDevice: View
    private lateinit var panelSettings: View
    private lateinit var panelLogs: View

    // Toolbar status
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var statusContainer: View

    // Dashboard - Device Selector
    private lateinit var deviceSelector: com.radiacode.ble.ui.DeviceSelectorView
    private lateinit var allDevicesOverlay: View

    // Dashboard - Metric cards
    private lateinit var doseCard: MetricCardView
    private lateinit var cpsCard: MetricCardView

    // Dashboard - Chart Panels (for click handling)
    private lateinit var doseChartPanel: View
    private lateinit var cpsChartPanel: View

    // Dashboard - Charts
    private lateinit var doseChartTitle: TextView
    private lateinit var doseChart: ProChartView
    private lateinit var doseChartReset: android.widget.ImageButton
    private lateinit var doseChartGoRealtime: android.widget.ImageButton
    private lateinit var doseStats: StatRowView

    private lateinit var cpsChartTitle: TextView
    private lateinit var cpsChart: ProChartView
    private lateinit var cpsChartReset: android.widget.ImageButton
    private lateinit var cpsChartGoRealtime: android.widget.ImageButton
    private lateinit var cpsStats: StatRowView

    private lateinit var sessionInfo: TextView

    // Dashboard - Time window chips
    private lateinit var timeWindowChips: ChipGroup
    
    // Dashboard - Unit chips
    private lateinit var doseUnitChips: ChipGroup
    private lateinit var countUnitChips: ChipGroup

    // Device panel
    private lateinit var connectionDot: View
    private lateinit var connectionStatus: TextView
    private lateinit var preferredDeviceText: TextView
    private lateinit var autoConnectSwitch: SwitchMaterial
    private lateinit var findDevicesButton: MaterialButton
    private lateinit var reconnectButton: MaterialButton
    private lateinit var stopServiceButton: MaterialButton
    private lateinit var deviceListContainer: android.widget.LinearLayout
    private lateinit var noDevicesText: TextView

    // Settings panel
    private lateinit var rowWindow: View
    private lateinit var rowSmoothing: View
    private lateinit var rowSpikeMarkers: View
    private lateinit var rowUnits: View
    private lateinit var rowPause: View
    private lateinit var rowSpikePercentages: View
    private lateinit var rowSmartAlerts: View

    private lateinit var valueWindow: TextView
    private lateinit var valueSmoothing: TextView
    private lateinit var valueSpikeMarkers: TextView
    private lateinit var valueSpikePercentages: TextView
    private lateinit var valueUnits: TextView
    private lateinit var valuePause: TextView
    private lateinit var valueSmartAlerts: TextView
    
    // Settings sections (expandable)
    private lateinit var sectionChartHeader: View
    private lateinit var sectionChartContent: View
    private lateinit var sectionChartArrow: android.widget.ImageView
    private lateinit var sectionDisplayHeader: View
    private lateinit var sectionDisplayContent: View
    private lateinit var sectionDisplayArrow: android.widget.ImageView
    private lateinit var sectionAlertsHeader: View
    private lateinit var sectionAlertsContent: View
    private lateinit var sectionAlertsArrow: android.widget.ImageView
    private lateinit var sectionAdvancedHeader: View
    private lateinit var sectionAdvancedContent: View
    private lateinit var sectionAdvancedArrow: android.widget.ImageView

    // Logs panel
    private lateinit var shareCsvButton: MaterialButton

    private val mainHandler = Handler(Looper.getMainLooper())

    private var uiRunnable: Runnable? = null
    private var lastReadingTimestampMs: Long = 0L
    private var sessionStartMs: Long = System.currentTimeMillis()
    private var sampleCount: Int = 0

    private val doseHistory = SampleHistory(4000)
    private val cpsHistory = SampleHistory(4000)

    private var pausedSnapshotDose: SampleHistory.Series? = null
    private var pausedSnapshotCps: SampleHistory.Series? = null

    private var lastShownReading: Prefs.LastReading? = null

    // Trend tracking
    private var previousDose: Float = 0f
    private var previousCps: Float = 0f

    private val readingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != RadiaCodeForegroundService.ACTION_READING) return
            val paused = Prefs.isPauseLiveEnabled(this@MainActivity)
            if (paused) return
            lastReadingTimestampMs = 0L
        }
    }

    private val findDevicesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val address = result.data?.getStringExtra(FindDevicesActivity.EXTRA_DEVICE_ADDRESS) ?: return@registerForActivityResult
        
        // Multi-device: Add to device list instead of just setting preferred
        val existingDevice = Prefs.getDeviceByMac(this, address)
        if (existingDevice == null) {
            val newDevice = DeviceConfig(
                macAddress = address,
                enabled = true
            )
            Prefs.addDevice(this, newDevice)
        } else if (!existingDevice.enabled) {
            // Re-enable if it was disabled
            Prefs.updateDevice(this, existingDevice.copy(enabled = true))
        }
        
        // Also set as preferred for backward compatibility
        Prefs.setPreferredAddress(this, address)
        Prefs.setAutoConnectEnabled(this, true)
        
        // Tell the service to reload device list (picks up the new device)
        RadiaCodeForegroundService.reloadDevices(this)

        doseHistory.clear()
        cpsHistory.clear()
        lastReadingTimestampMs = 0L
        pausedSnapshotDose = null
        pausedSnapshotCps = null
        sessionStartMs = System.currentTimeMillis()
        sampleCount = 0
        updateStatus(true, "Connecting")
    }

    private val requiredPermissions: Array<String>
        get() {
            val perms = ArrayList<String>(3)
            if (Build.VERSION.SDK_INT >= 31) {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_CONNECT
            } else {
                perms += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= 33) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Migrate single device to multi-device if needed
        Prefs.migrateToMultiDevice(this)

        bindViews()
        setupNavigation()
        setupDevicePanel()
        setupSettingsPanel()
        setupLogsPanel()
        setupMetricCards()
        setupCharts()
        setupTimeWindowChips()
        setupUnitChips()
        setupToolbarDeviceSelector()

        refreshSettingsRows()
        updateChartTitles()
        updateStatus(false, "Starting")

        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
        } else {
            startServiceIfConfigured()
        }
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        panelDashboard = findViewById(R.id.panelDashboard)
        panelDevice = findViewById(R.id.panelDevice)
        panelSettings = findViewById(R.id.panelSettings)
        panelLogs = findViewById(R.id.panelLogs)

        statusDot = findViewById(R.id.statusDot)
        statusLabel = findViewById(R.id.statusLabel)
        statusContainer = statusLabel.parent as View

        // Device selector for multi-device dashboard
        deviceSelector = findViewById(R.id.deviceSelector)
        allDevicesOverlay = findViewById(R.id.allDevicesOverlay)

        doseCard = findViewById(R.id.doseCard)
        cpsCard = findViewById(R.id.cpsCard)

        doseChartPanel = findViewById(R.id.doseChartPanel)
        cpsChartPanel = findViewById(R.id.cpsChartPanel)

        doseChartTitle = findViewById(R.id.doseChartTitle)
        doseChart = findViewById(R.id.doseChart)
        doseChartReset = findViewById(R.id.doseChartReset)
        doseChartGoRealtime = findViewById(R.id.doseChartGoRealtime)
        doseStats = findViewById(R.id.doseStats)

        cpsChartTitle = findViewById(R.id.cpsChartTitle)
        cpsChart = findViewById(R.id.cpsChart)
        cpsChartReset = findViewById(R.id.cpsChartReset)
        cpsChartGoRealtime = findViewById(R.id.cpsChartGoRealtime)
        cpsStats = findViewById(R.id.cpsStats)

        sessionInfo = findViewById(R.id.sessionInfo)

        timeWindowChips = findViewById(R.id.timeWindowChips)
        doseUnitChips = findViewById(R.id.doseUnitChips)
        countUnitChips = findViewById(R.id.countUnitChips)

        connectionDot = findViewById(R.id.connectionDot)
        connectionStatus = findViewById(R.id.connectionStatus)
        preferredDeviceText = findViewById(R.id.preferredDeviceText)
        autoConnectSwitch = findViewById(R.id.autoConnectSwitch)
        findDevicesButton = findViewById(R.id.findDevicesButton)
        reconnectButton = findViewById(R.id.reconnectButton)
        stopServiceButton = findViewById(R.id.stopServiceButton)
        deviceListContainer = findViewById(R.id.deviceListContainer)
        noDevicesText = findViewById(R.id.noDevicesText)

        rowWindow = findViewById(R.id.rowWindow)
        rowSmoothing = findViewById(R.id.rowSmoothing)
        rowSpikeMarkers = findViewById(R.id.rowSpikeMarkers)
        rowSpikePercentages = findViewById(R.id.rowSpikePercentages)
        rowUnits = findViewById(R.id.rowUnits)
        rowPause = findViewById(R.id.rowPause)
        rowSmartAlerts = findViewById(R.id.rowSmartAlerts)

        valueWindow = findViewById(R.id.valueWindow)
        valueSmoothing = findViewById(R.id.valueSmoothing)
        valueSpikeMarkers = findViewById(R.id.valueSpikeMarkers)
        valueSpikePercentages = findViewById(R.id.valueSpikePercentages)
        valueUnits = findViewById(R.id.valueUnits)
        valuePause = findViewById(R.id.valuePause)
        valueSmartAlerts = findViewById(R.id.valueSmartAlerts)
        
        // Expandable section headers and content
        sectionChartHeader = findViewById(R.id.sectionChartHeader)
        sectionChartContent = findViewById(R.id.sectionChartContent)
        sectionChartArrow = findViewById(R.id.sectionChartArrow)
        sectionDisplayHeader = findViewById(R.id.sectionDisplayHeader)
        sectionDisplayContent = findViewById(R.id.sectionDisplayContent)
        sectionDisplayArrow = findViewById(R.id.sectionDisplayArrow)
        sectionAlertsHeader = findViewById(R.id.sectionAlertsHeader)
        sectionAlertsContent = findViewById(R.id.sectionAlertsContent)
        sectionAlertsArrow = findViewById(R.id.sectionAlertsArrow)
        sectionAdvancedHeader = findViewById(R.id.sectionAdvancedHeader)
        sectionAdvancedContent = findViewById(R.id.sectionAdvancedContent)
        sectionAdvancedArrow = findViewById(R.id.sectionAdvancedArrow)

        shareCsvButton = findViewById(R.id.shareCsvButton)
    }

    private fun setupNavigation() {
        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close,
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> setPanel(Panel.Dashboard)
                R.id.nav_device -> setPanel(Panel.Device)
                R.id.nav_settings -> setPanel(Panel.Settings)
                R.id.nav_logs -> setPanel(Panel.Logs)
            }
            item.isChecked = true
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        navView.setCheckedItem(R.id.nav_dashboard)
        setPanel(Panel.Dashboard)
    }

    private fun setupDevicePanel() {
        autoConnectSwitch.isChecked = Prefs.isAutoConnectEnabled(this)
        autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAutoConnectEnabled(this, isChecked)
            if (isChecked) {
                RadiaCodeForegroundService.start(this)
            } else {
                RadiaCodeForegroundService.stop(this)
            }
            lastReadingTimestampMs = 0L
        }

        findDevicesButton.setOnClickListener {
            findDevicesLauncher.launch(android.content.Intent(this, FindDevicesActivity::class.java))
        }

        reconnectButton.setOnClickListener {
            RadiaCodeForegroundService.reconnect(this)
            lastReadingTimestampMs = 0L
        }

        stopServiceButton.setOnClickListener {
            RadiaCodeForegroundService.stop(this)
            lastReadingTimestampMs = 0L
        }
        
        // Setup device list manager
        setupDeviceListManager()
        refreshDeviceList()
    }

    private fun setupSettingsPanel() {
        // Setup expandable sections
        setupExpandableSection(sectionChartHeader, sectionChartContent, sectionChartArrow, expanded = true)
        setupExpandableSection(sectionDisplayHeader, sectionDisplayContent, sectionDisplayArrow, expanded = true)
        setupExpandableSection(sectionAlertsHeader, sectionAlertsContent, sectionAlertsArrow, expanded = true)
        setupExpandableSection(sectionAdvancedHeader, sectionAdvancedContent, sectionAdvancedArrow, expanded = false)
        
        rowWindow.setOnClickListener {
            val next = when (Prefs.getWindowSeconds(this, 60)) {
                10 -> 60
                60 -> 600
                600 -> 3600
                else -> 10
            }
            Prefs.setWindowSeconds(this, next)
            refreshSettingsRows()
            updateChartTitles()
            lastReadingTimestampMs = 0L
        }

        rowSmoothing.setOnClickListener {
            val next = when (Prefs.getSmoothSeconds(this, 0)) {
                0 -> 5
                5 -> 30
                else -> 0
            }
            Prefs.setSmoothSeconds(this, next)
            refreshSettingsRows()
            lastReadingTimestampMs = 0L
        }
        
        rowSpikeMarkers.setOnClickListener {
            val next = !Prefs.isShowSpikeMarkersEnabled(this)
            Prefs.setShowSpikeMarkersEnabled(this, next)
            doseChart.setShowSpikeMarkers(next)
            cpsChart.setShowSpikeMarkers(next)
            refreshSettingsRows()
        }

        rowSpikePercentages.setOnClickListener {
            val next = !Prefs.isShowSpikePercentagesEnabled(this)
            Prefs.setShowSpikePercentagesEnabled(this, next)
            doseChart.setShowSpikePercentages(next)
            cpsChart.setShowSpikePercentages(next)
            refreshSettingsRows()
        }

        rowUnits.setOnClickListener {
            val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
            val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
            val next = when {
                du == Prefs.DoseUnit.USV_H && cu == Prefs.CountUnit.CPS -> Prefs.DoseUnit.NSV_H to Prefs.CountUnit.CPS
                du == Prefs.DoseUnit.NSV_H && cu == Prefs.CountUnit.CPS -> Prefs.DoseUnit.USV_H to Prefs.CountUnit.CPM
                du == Prefs.DoseUnit.USV_H && cu == Prefs.CountUnit.CPM -> Prefs.DoseUnit.NSV_H to Prefs.CountUnit.CPM
                else -> Prefs.DoseUnit.USV_H to Prefs.CountUnit.CPS
            }
            Prefs.setDoseUnit(this, next.first)
            Prefs.setCountUnit(this, next.second)
            refreshSettingsRows()
            updateChartTitles()
            lastReadingTimestampMs = 0L
        }

        rowPause.setOnClickListener {
            val next = !Prefs.isPauseLiveEnabled(this)
            Prefs.setPauseLiveEnabled(this, next)
            if (next) {
                pausedSnapshotDose = currentWindowSeriesDose()
                pausedSnapshotCps = currentWindowSeriesCps()
            } else {
                pausedSnapshotDose = null
                pausedSnapshotCps = null
            }
            refreshSettingsRows()
            lastReadingTimestampMs = 0L
        }

        rowSmartAlerts.setOnClickListener {
            startActivity(Intent(this, AlertConfigActivity::class.java))
        }
    }
    
    private fun setupExpandableSection(header: View, content: View, arrow: android.widget.ImageView, expanded: Boolean) {
        // Set initial state
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        arrow.rotation = if (expanded) 180f else 0f
        
        header.setOnClickListener {
            val isCurrentlyExpanded = content.visibility == View.VISIBLE
            if (isCurrentlyExpanded) {
                content.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction { content.visibility = View.GONE }
                    .start()
                arrow.animate().rotation(0f).setDuration(150).start()
            } else {
                content.alpha = 0f
                content.visibility = View.VISIBLE
                content.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
                arrow.animate().rotation(180f).setDuration(150).start()
            }
        }
    }

    private fun setupLogsPanel() {
        shareCsvButton.setOnClickListener { shareCsv() }
    }

    private fun setupMetricCards() {
        val cyanColor = ContextCompat.getColor(this, R.color.pro_cyan)
        val magentaColor = ContextCompat.getColor(this, R.color.pro_magenta)

        doseCard.setLabel("DELTA DOSE RATE")
        doseCard.setAccentColor(cyanColor)
        doseCard.setValueText("—")
        doseCard.setTrend(0f)

        cpsCard.setLabel("DELTA COUNT RATE")
        cpsCard.setAccentColor(magentaColor)
        cpsCard.setValueText("—")
        cpsCard.setTrend(0f)
    }

    private fun setupCharts() {
        val cyanColor = ContextCompat.getColor(this, R.color.pro_cyan)
        val magentaColor = ContextCompat.getColor(this, R.color.pro_magenta)

        doseChart.setAccentColor(cyanColor)
        cpsChart.setAccentColor(magentaColor)

        // Enable rolling average line on charts
        doseChart.setRollingAverageWindow(10)
        cpsChart.setRollingAverageWindow(10)
        
        // Apply spike markers setting
        val showSpikes = Prefs.isShowSpikeMarkersEnabled(this)
        doseChart.setShowSpikeMarkers(showSpikes)
        cpsChart.setShowSpikeMarkers(showSpikes)

        // Apply spike percentages setting
        val showSpikePercent = Prefs.isShowSpikePercentagesEnabled(this)
        doseChart.setShowSpikePercentages(showSpikePercent)
        cpsChart.setShowSpikePercentages(showSpikePercent)

        // Chart panel click handlers (opens detailed analysis view)
        doseChartPanel.setOnClickListener { openDetailedChart("dose") }
        cpsChartPanel.setOnClickListener { openDetailedChart("cps") }
        
        // Setup zoom change listeners to show/hide reset buttons
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
        
        // Reset button click handlers
        doseChartReset.setOnClickListener { doseChart.resetZoom() }
        cpsChartReset.setOnClickListener { cpsChart.resetZoom() }
        
        // Go-to-realtime button click handlers
        doseChartGoRealtime.setOnClickListener { doseChart.goToRealTime() }
        cpsChartGoRealtime.setOnClickListener { cpsChart.goToRealTime() }
        
        // Setup real-time state listeners to show/hide go-to-realtime buttons
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
    }

    private fun setupTimeWindowChips() {
        val chipToSeconds = mapOf(
            R.id.chip30s to 30,
            R.id.chip1m to 60,
            R.id.chip5m to 300,
            R.id.chip15m to 900,
            R.id.chip1h to 3600
        )

        val currentWindow = Prefs.getWindowSeconds(this, 60)
        when (currentWindow) {
            30 -> findViewById<Chip>(R.id.chip30s).isChecked = true
            60 -> findViewById<Chip>(R.id.chip1m).isChecked = true
            300 -> findViewById<Chip>(R.id.chip5m).isChecked = true
            900 -> findViewById<Chip>(R.id.chip15m).isChecked = true
            3600 -> findViewById<Chip>(R.id.chip1h).isChecked = true
            else -> findViewById<Chip>(R.id.chip1m).isChecked = true
        }

        timeWindowChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chipId = checkedIds.first()
            val seconds = chipToSeconds[chipId] ?: 60
            
            Prefs.setWindowSeconds(this, seconds)
            refreshSettingsRows()
            updateChartTitles()
            lastReadingTimestampMs = 0L
            
            doseChart.resetZoom()
            cpsChart.resetZoom()
        }
    }

    private fun setupUnitChips() {
        val currentDoseUnit = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
        val currentCountUnit = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
        
        when (currentDoseUnit) {
            Prefs.DoseUnit.USV_H -> findViewById<Chip>(R.id.chipUsvH).isChecked = true
            Prefs.DoseUnit.NSV_H -> findViewById<Chip>(R.id.chipNsvH).isChecked = true
        }
        
        when (currentCountUnit) {
            Prefs.CountUnit.CPS -> findViewById<Chip>(R.id.chipCps).isChecked = true
            Prefs.CountUnit.CPM -> findViewById<Chip>(R.id.chipCpm).isChecked = true
        }

        doseUnitChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chipId = checkedIds.first()
            val unit = when (chipId) {
                R.id.chipNsvH -> Prefs.DoseUnit.NSV_H
                else -> Prefs.DoseUnit.USV_H
            }
            Prefs.setDoseUnit(this, unit)
            refreshSettingsRows()
            updateChartTitles()
            lastReadingTimestampMs = 0L
        }
        
        countUnitChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chipId = checkedIds.first()
            val unit = when (chipId) {
                R.id.chipCpm -> Prefs.CountUnit.CPM
                else -> Prefs.CountUnit.CPS
            }
            Prefs.setCountUnit(this, unit)
            refreshSettingsRows()
            updateChartTitles()
            lastReadingTimestampMs = 0L
        }
    }

    /**
     * Setup the toolbar status indicator to show device selector popup when clicked.
     */
    private fun setupToolbarDeviceSelector() {
        statusContainer.setOnClickListener {
            val devices = Prefs.getDevices(this)
            if (devices.size <= 1) return@setOnClickListener
            
            showDeviceSelectorDialog(devices)
        }
    }
    
    private fun showDeviceSelectorDialog(devices: List<DeviceConfig>) {
        val selectedId = Prefs.getSelectedDeviceId(this)
        val items = mutableListOf("All Devices")
        items.addAll(devices.map { it.displayName })
        
        val currentSelection = if (selectedId == null) 0 else {
            val idx = devices.indexOfFirst { it.id == selectedId }
            if (idx >= 0) idx + 1 else 0
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Select Device")
            .setSingleChoiceItems(items.toTypedArray(), currentSelection) { dialog, which ->
                val newSelectedId = if (which == 0) null else devices.getOrNull(which - 1)?.id
                Prefs.setSelectedDeviceId(this, newSelectedId)
                
                // Clear and reload data for selected device
                doseHistory.clear()
                cpsHistory.clear()
                sampleCount = 0
                lastReadingTimestampMs = 0L
                sessionStartMs = System.currentTimeMillis()
                
                if (newSelectedId != null) {
                    val history = Prefs.getDeviceChartHistory(this, newSelectedId)
                    for (reading in history) {
                        doseHistory.add(reading.timestampMs, reading.uSvPerHour)
                        cpsHistory.add(reading.timestampMs, reading.cps)
                        sampleCount++
                    }
                }
                
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // Device list manager for Device panel
    private var deviceListManager: DeviceListManager? = null
    
    private fun setupDeviceListManager() {
        if (deviceListManager != null) return
        
        deviceListManager = DeviceListManager(
            context = this,
            container = deviceListContainer,
            noDevicesView = noDevicesText,
            onDevicesChanged = {
                // Refresh UI when devices are changed
                refreshDeviceList()
            }
        )
    }
    
    private fun refreshDeviceList() {
        val devices = Prefs.getDevices(this)
        android.util.Log.d("RadiaCode", "refreshDeviceList: ${devices.size} devices")
        deviceListManager?.refresh()
    }

    override fun onResume() {
        super.onResume()
        registerReadingReceiver()
        reloadChartHistoryForSelectedDevice()
        startUiLoop()
        refreshSettingsRows()
    }
    
    private fun reloadChartHistoryForSelectedDevice() {
        val devices = Prefs.getDevices(this)
        var selectedDeviceId = Prefs.getSelectedDeviceId(this)
        
        // Clear existing data
        doseHistory.clear()
        cpsHistory.clear()
        sampleCount = 0
        
        // If only one device exists, auto-select it
        if (selectedDeviceId == null && devices.size == 1) {
            selectedDeviceId = devices.first().id
        }
        
        if (selectedDeviceId == null) {
            // All devices mode - no chart data, show overlay
            android.util.Log.d("RadiaCode", "All devices mode - no chart data to load")
            if (devices.size > 1) {
                allDevicesOverlay.visibility = View.VISIBLE
                panelDashboard.visibility = View.INVISIBLE
            }
            return
        }
        
        // Single device mode - hide overlay
        allDevicesOverlay.visibility = View.GONE
        panelDashboard.visibility = View.VISIBLE
        
        // Load history for selected device only
        val chartHistory = Prefs.getDeviceChartHistory(this, selectedDeviceId)
        android.util.Log.d("RadiaCode", "Loading ${chartHistory.size} readings for device $selectedDeviceId")
        
        for (reading in chartHistory) {
            doseHistory.add(reading.timestampMs, reading.uSvPerHour)
            cpsHistory.add(reading.timestampMs, reading.cps)
            sampleCount++
        }
        
        lastReadingTimestampMs = 0L
    }

    override fun onPause() {
        super.onPause()
        unregisterReadingReceiver()
        stopUiLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUiLoop()
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && hasAllPermissions()) {
            startServiceIfConfigured()
        } else {
            updateStatus(false, "Permissions")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    private fun shareCsv() {
        val file = File(filesDir, "readings.csv")
        if (!file.exists() || file.length() == 0L) return

        val uri = try {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        } catch (_: Throwable) { return }

        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(share, "Share readings"))
    }

    private fun startServiceIfConfigured() {
        // Multi-device: Check if we have any enabled devices
        val devices = Prefs.getDevices(this)
        val hasEnabledDevices = devices.any { it.enabled }
        
        // Fall back to legacy preferred address check
        val preferred = Prefs.getPreferredAddress(this)
        
        if (Prefs.isAutoConnectEnabled(this) && (hasEnabledDevices || !preferred.isNullOrBlank())) {
            RadiaCodeForegroundService.start(this)
        }
        refreshSettingsRows()
        updateChartTitles()

        ensureHistoryCapacity()
        
        val chartHistory = Prefs.getChartHistory(this)
        if (chartHistory.isNotEmpty()) {
            for (reading in chartHistory) {
                doseHistory.add(reading.timestampMs, reading.uSvPerHour)
                cpsHistory.add(reading.timestampMs, reading.cps)
            }
            sampleCount = chartHistory.size
            sessionStartMs = chartHistory.first().timestampMs
        } else {
            Prefs.getLastReading(this)?.let {
                doseHistory.add(it.timestampMs, it.uSvPerHour)
                cpsHistory.add(it.timestampMs, it.cps)
            }
        }
        startUiLoop()
    }

    private fun startUiLoop() {
        if (uiRunnable != null) return
        
        // Setup device selector callback
        deviceSelector.setOnDeviceSelectedListener(object : com.radiacode.ble.ui.DeviceSelectorView.OnDeviceSelectedListener {
            override fun onDeviceSelected(deviceId: String?) {
                android.util.Log.d("RadiaCode", "Device selected: $deviceId")
                
                // Clear history and reload for selected device
                doseHistory.clear()
                cpsHistory.clear()
                sampleCount = 0
                lastReadingTimestampMs = 0L
                sessionStartMs = System.currentTimeMillis()
                
                // Update overlay visibility
                if (deviceId == null) {
                    // All devices mode - show overlay
                    allDevicesOverlay.visibility = View.VISIBLE
                    panelDashboard.visibility = View.INVISIBLE
                } else {
                    // Single device mode - hide overlay and load data
                    allDevicesOverlay.visibility = View.GONE
                    panelDashboard.visibility = View.VISIBLE
                    
                    val history = Prefs.getDeviceChartHistory(this@MainActivity, deviceId)
                    android.util.Log.d("RadiaCode", "Loaded ${history.size} readings for device $deviceId")
                    for (reading in history) {
                        doseHistory.add(reading.timestampMs, reading.uSvPerHour)
                        cpsHistory.add(reading.timestampMs, reading.cps)
                        sampleCount++
                    }
                }
            }
        })
        
        val r = object : Runnable {
            override fun run() {
                val preferred = Prefs.getPreferredAddress(this@MainActivity)
                val auto = Prefs.isAutoConnectEnabled(this@MainActivity)
                val svc = Prefs.getServiceStatus(this@MainActivity)
                val paused = Prefs.isPauseLiveEnabled(this@MainActivity)

                // Get devices and selected device
                val devices = Prefs.getDevices(this@MainActivity)
                val enabledCount = devices.count { it.enabled }
                val selectedDeviceId = Prefs.getSelectedDeviceId(this@MainActivity)
                
                // Update device selector
                deviceSelector.setDevices(devices)
                
                // Update overlay visibility based on selection
                val isAllDevicesMode = selectedDeviceId == null && devices.size > 1
                if (isAllDevicesMode) {
                    allDevicesOverlay.visibility = View.VISIBLE
                    panelDashboard.visibility = View.INVISIBLE
                } else {
                    allDevicesOverlay.visibility = View.GONE
                    panelDashboard.visibility = View.VISIBLE
                }
                
                // Get reading ONLY for selected device (no mixing!)
                val last = if (selectedDeviceId != null) {
                    val deviceReadings = Prefs.getDeviceRecentReadings(this@MainActivity, selectedDeviceId, 1)
                    deviceReadings.firstOrNull()?.let { r ->
                        Prefs.LastReading(r.uSvPerHour, r.cps, r.timestampMs)
                    }
                } else if (devices.size == 1) {
                    // Auto-select single device
                    val singleDevice = devices.first()
                    val deviceReadings = Prefs.getDeviceRecentReadings(this@MainActivity, singleDevice.id, 1)
                    deviceReadings.firstOrNull()?.let { r ->
                        Prefs.LastReading(r.uSvPerHour, r.cps, r.timestampMs)
                    }
                } else {
                    // All devices mode - no data
                    null
                }
                
                // Update device panel
                preferredDeviceText.text = when {
                    enabledCount > 1 -> "$enabledCount devices"
                    enabledCount == 1 -> devices.first { it.enabled }.displayName
                    !preferred.isNullOrBlank() -> preferred
                    else -> "Not set"
                }
                
                if (autoConnectSwitch.isChecked != auto) {
                    autoConnectSwitch.isChecked = auto
                }

                val hasRecentData = last != null && (System.currentTimeMillis() - last.timestampMs) < 10000
                val statusIndicatesConnected = svc?.message?.contains("connected", ignoreCase = true) == true
                val statusIndicatesLiveData = svc?.message?.contains("μSv/h", ignoreCase = true) == true
                val isConnected = hasRecentData || statusIndicatesConnected || statusIndicatesLiveData
                
                // Show device status on device panel
                val deviceStatusMsg = when {
                    svc?.message?.isNotBlank() == true -> svc.message
                    enabledCount > 1 && isConnected -> "$enabledCount devices connected"
                    enabledCount == 1 && isConnected -> "Connected"
                    enabledCount > 0 -> "Connecting…"
                    else -> "No devices configured"
                }
                updateConnectionStatus(isConnected, deviceStatusMsg)

                val statusText = when {
                    !auto -> "OFF"
                    enabledCount == 0 && preferred.isNullOrBlank() -> "NO DEVICE"
                    paused -> "PAUSED"
                    isConnected && enabledCount > 1 -> "LIVE ($enabledCount)"
                    isConnected -> "LIVE"
                    else -> "CONNECTING"
                }
                updateStatus(isConnected && !paused, statusText)

                val shouldUpdateReading = !paused && !isAllDevicesMode
                if (!shouldUpdateReading) {
                    val frozen = lastShownReading
                    if (frozen != null) {
                        updateMetricCards(frozen)
                    }
                } else if (last == null) {
                    showEmptyMetrics()
                } else {
                    lastShownReading = last
                    updateMetricCards(last)
                }

                // Only add to history if we have a specific device selected (not all-devices mode)
                if (last != null && !paused && !isAllDevicesMode && last.timestampMs != lastReadingTimestampMs) {
                    lastReadingTimestampMs = last.timestampMs
                    ensureHistoryCapacity()
                    doseHistory.add(last.timestampMs, last.uSvPerHour)
                    cpsHistory.add(last.timestampMs, last.cps)
                    sampleCount++
                }

                if (paused && (pausedSnapshotDose == null || pausedSnapshotCps == null)) {
                    pausedSnapshotDose = currentWindowSeriesDose()
                    pausedSnapshotCps = currentWindowSeriesCps()
                }
                val doseSeries = if (paused) pausedSnapshotDose else currentWindowSeriesDose()
                val cpsSeries = if (paused) pausedSnapshotCps else currentWindowSeriesCps()
                updateCharts(doseSeries, cpsSeries)

                updateSessionInfo()

                mainHandler.postDelayed(this, 1000)
            }
        }
        uiRunnable = r
        mainHandler.post(r)
    }

    private fun stopUiLoop() {
        uiRunnable?.let { mainHandler.removeCallbacks(it) }
        uiRunnable = null
    }

    private fun updateStatus(live: Boolean, text: String) {
        mainHandler.post {
            statusLabel.text = text
            val color = if (live) {
                ContextCompat.getColor(this, R.color.pro_status_live)
            } else {
                ContextCompat.getColor(this, R.color.pro_text_muted)
            }
            statusLabel.setTextColor(color)
            (statusDot.background as? GradientDrawable)?.setColor(color)
        }
    }

    private fun updateConnectionStatus(connected: Boolean, message: String) {
        mainHandler.post {
            connectionStatus.text = message
            val color = if (connected) {
                ContextCompat.getColor(this, R.color.pro_status_live)
            } else {
                ContextCompat.getColor(this, R.color.pro_text_muted)
            }
            (connectionDot.background as? GradientDrawable)?.setColor(color)
        }
    }

    private fun showEmptyMetrics() {
        mainHandler.post {
            doseCard.setValueText("—")
            doseCard.setTrend(0f)
            doseCard.setSparkline(emptyList())

            cpsCard.setValueText("—")
            cpsCard.setTrend(0f)
            cpsCard.setSparkline(emptyList())

            doseStats.setStats(StatRowView.Stats(0f, 0f, 0f, 0f, "—"))
            cpsStats.setStats(StatRowView.Stats(0f, 0f, 0f, 0f, "—"))
        }
    }

    private fun updateMetricCards(last: Prefs.LastReading) {
        val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
        val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)

        val dose = when (du) {
            Prefs.DoseUnit.USV_H -> last.uSvPerHour
            Prefs.DoseUnit.NSV_H -> last.uSvPerHour * 1000.0f
        }
        val cpsOrCpm = when (cu) {
            Prefs.CountUnit.CPS -> last.cps
            Prefs.CountUnit.CPM -> last.cps * 60.0f
        }

        val doseUnit = when (du) {
            Prefs.DoseUnit.USV_H -> "μSv/h"
            Prefs.DoseUnit.NSV_H -> "nSv/h"
        }
        val cpsUnit = when (cu) {
            Prefs.CountUnit.CPS -> "cps"
            Prefs.CountUnit.CPM -> "cpm"
        }

        val doseTrend = if (previousDose > 0f) ((dose - previousDose) / previousDose) * 100f else 0f
        val cpsTrend = if (previousCps > 0f) ((cpsOrCpm - previousCps) / previousCps) * 100f else 0f
        previousDose = dose
        previousCps = cpsOrCpm

        mainHandler.post {
            doseCard.setLabel("DELTA DOSE RATE · $doseUnit")
            doseCard.setValue(dose, doseUnit)
            doseCard.setTrend(doseTrend)

            cpsCard.setLabel("DELTA COUNT RATE · $cpsUnit")
            cpsCard.setValue(cpsOrCpm, cpsUnit)
            cpsCard.setTrend(cpsTrend)
        }
    }

    private fun updateCharts(dose: SampleHistory.Series?, cps: SampleHistory.Series?) {
        val smoothSeconds = Prefs.getSmoothSeconds(this, 0)
        val poll = Prefs.getPollIntervalMs(this, 1000L)
        val smoothSamples = if (smoothSeconds <= 0) 0 else max(1, ((smoothSeconds * 1000L) / max(1L, poll)).toInt())

        val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
        val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)

        val doseV = dose?.values.orEmpty().let { applySmoothing(it, smoothSamples) }.let { convertDose(it, du) }
        val cpsV = cps?.values.orEmpty().let { applySmoothing(it, smoothSamples) }.let { convertCount(it, cu) }

        val doseT = dose?.timestampsMs.orEmpty()
        val cpsT = cps?.timestampsMs.orEmpty()

        val doseDec = decimate(doseT, doseV)
        val cpsDec = decimate(cpsT, cpsV)

        mainHandler.post {
            doseChart.setSeries(doseDec.first, doseDec.second)
            cpsChart.setSeries(cpsDec.first, cpsDec.second)

            val sparkDose = doseDec.second.takeLast(20)
            val sparkCps = cpsDec.second.takeLast(20)
            doseCard.setSparkline(sparkDose)
            cpsCard.setSparkline(sparkCps)
        }

        updateStats(doseDec.second, cpsDec.second, du, cu)
    }

    private fun updateStats(doseValues: List<Float>, cpsValues: List<Float>, du: Prefs.DoseUnit, cu: Prefs.CountUnit) {
        mainHandler.post {
            val doseUnit = when (du) {
                Prefs.DoseUnit.USV_H -> "μSv/h"
                Prefs.DoseUnit.NSV_H -> "nSv/h"
            }
            val cpsUnit = when (cu) {
                Prefs.CountUnit.CPS -> "cps"
                Prefs.CountUnit.CPM -> "cpm"
            }

            if (doseValues.isEmpty()) {
                doseStats.setStats(StatRowView.Stats(0f, 0f, 0f, 0f, doseUnit))
            } else {
                var minV = Float.POSITIVE_INFINITY
                var maxV = Float.NEGATIVE_INFINITY
                var sum = 0.0f
                for (v in doseValues) {
                    minV = min(minV, v)
                    maxV = max(maxV, v)
                    sum += v
                }
                val avg = sum / max(1, doseValues.size)
                val delta = maxV - minV
                doseStats.setStats(StatRowView.Stats(minV, avg, maxV, delta, doseUnit))
            }

            if (cpsValues.isEmpty()) {
                cpsStats.setStats(StatRowView.Stats(0f, 0f, 0f, 0f, cpsUnit))
            } else {
                var minV = Float.POSITIVE_INFINITY
                var maxV = Float.NEGATIVE_INFINITY
                var sum = 0.0f
                for (v in cpsValues) {
                    minV = min(minV, v)
                    maxV = max(maxV, v)
                    sum += v
                }
                val avg = sum / max(1, cpsValues.size)
                val delta = maxV - minV
                cpsStats.setStats(StatRowView.Stats(minV, avg, maxV, delta, cpsUnit))
            }
        }
    }

    private fun updateSessionInfo() {
        val elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000L
        val timeStr = when {
            elapsed < 60 -> "${elapsed}s"
            elapsed < 3600 -> "${elapsed / 60}m ${elapsed % 60}s"
            else -> "${elapsed / 3600}h ${(elapsed % 3600) / 60}m"
        }
        mainHandler.post {
            sessionInfo.text = "SESSION: $timeStr • $sampleCount samples"
        }
    }

    private fun updateChartTitles() {
        val windowSeconds = Prefs.getWindowSeconds(this, 60)
        val label = when (windowSeconds) {
            10 -> "10s"
            30 -> "30s"
            60 -> "1m"
            300 -> "5m"
            600 -> "10m"
            900 -> "15m"
            3600 -> "1h"
            else -> "${windowSeconds}s"
        }
        
        val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
        val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
        val doseUnit = doseUnitLabel(du)
        val countUnit = countUnitLabel(cu)
        
        doseChartTitle.text = "REAL TIME DOSE RATE ($doseUnit) — Last $label"
        cpsChartTitle.text = "REAL TIME COUNT RATE ($countUnit) — Last $label"
    }

    private fun refreshSettingsRows() {
        val windowSeconds = Prefs.getWindowSeconds(this, 60)
        valueWindow.text = when (windowSeconds) {
            10 -> "10s"
            60 -> "1m"
            600 -> "10m"
            3600 -> "1h"
            else -> "${windowSeconds}s"
        }

        val smoothSeconds = Prefs.getSmoothSeconds(this, 0)
        valueSmoothing.text = when (smoothSeconds) {
            0 -> "Off"
            else -> "${smoothSeconds}s"
        }

        val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
        val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
        valueUnits.text = "${doseUnitLabel(du)} • ${countUnitLabel(cu)}"
        
        val showSpikes = Prefs.isShowSpikeMarkersEnabled(this)
        valueSpikeMarkers.text = if (showSpikes) "On" else "Off"
        valueSpikeMarkers.setTextColor(ContextCompat.getColor(this, 
            if (showSpikes) R.color.pro_green else R.color.pro_text_muted))

        val showSpikePercent = Prefs.isShowSpikePercentagesEnabled(this)
        valueSpikePercentages.text = if (showSpikePercent) "On" else "Off"
        valueSpikePercentages.setTextColor(ContextCompat.getColor(this, 
            if (showSpikePercent) R.color.pro_green else R.color.pro_text_muted))

        valuePause.text = if (Prefs.isPauseLiveEnabled(this)) "On" else "Off"

        val alerts = Prefs.getSmartAlerts(this)
        val enabledCount = alerts.count { it.enabled }
        valueSmartAlerts.text = if (enabledCount == 0) "None" else "$enabledCount active"
        valueSmartAlerts.setTextColor(ContextCompat.getColor(this,
            if (enabledCount > 0) R.color.pro_amber else R.color.pro_text_muted))
    }

    private enum class Panel { Dashboard, Device, Settings, Logs }

    private fun setPanel(panel: Panel) {
        panelDashboard.visibility = if (panel == Panel.Dashboard) View.VISIBLE else View.GONE
        panelDevice.visibility = if (panel == Panel.Device) View.VISIBLE else View.GONE
        panelSettings.visibility = if (panel == Panel.Settings) View.VISIBLE else View.GONE
        panelLogs.visibility = if (panel == Panel.Logs) View.VISIBLE else View.GONE
    }

    private fun doseUnitLabel(du: Prefs.DoseUnit): String = when (du) {
        Prefs.DoseUnit.USV_H -> "μSv/h"
        Prefs.DoseUnit.NSV_H -> "nSv/h"
    }

    private fun countUnitLabel(cu: Prefs.CountUnit): String = when (cu) {
        Prefs.CountUnit.CPS -> "cps"
        Prefs.CountUnit.CPM -> "cpm"
    }

    private fun ensureHistoryCapacity() {
        val poll = Prefs.getPollIntervalMs(this, 1000L)
        val cap = ((3600_000L / max(1L, poll)) + 10).toInt().coerceIn(1000, 20000)
        doseHistory.ensureCapacity(cap)
        cpsHistory.ensureCapacity(cap)
    }

    private fun currentWindowSeriesDose(): SampleHistory.Series {
        val poll = Prefs.getPollIntervalMs(this, 1000L)
        val window = Prefs.getWindowSeconds(this, 60)
        val n = ((window * 1000L) / max(1L, poll)).toInt().coerceAtLeast(2)
        return doseHistory.lastN(n)
    }

    private fun currentWindowSeriesCps(): SampleHistory.Series {
        val poll = Prefs.getPollIntervalMs(this, 1000L)
        val window = Prefs.getWindowSeconds(this, 60)
        val n = ((window * 1000L) / max(1L, poll)).toInt().coerceAtLeast(2)
        return cpsHistory.lastN(n)
    }

    private fun applySmoothing(values: List<Float>, windowSamples: Int): List<Float> {
        if (windowSamples <= 1 || values.size < 3) return values
        val out = ArrayList<Float>(values.size)
        var sum = 0.0f
        val q = ArrayDeque<Float>(windowSamples)
        for (v in values) {
            q.addLast(v)
            sum += v
            if (q.size > windowSamples) {
                sum -= q.removeFirst()
            }
            out.add(sum / q.size)
        }
        return out
    }

    private fun convertDose(values: List<Float>, unit: Prefs.DoseUnit): List<Float> {
        if (unit == Prefs.DoseUnit.USV_H) return values
        return values.map { it * 1000.0f }
    }

    private fun convertCount(values: List<Float>, unit: Prefs.CountUnit): List<Float> {
        if (unit == Prefs.CountUnit.CPS) return values
        return values.map { it * 60.0f }
    }

    private fun decimate(timestamps: List<Long>, values: List<Float>): Pair<List<Long>, List<Float>> {
        if (timestamps.isEmpty() || values.isEmpty() || timestamps.size != values.size) return emptyList<Long>() to emptyList()
        if (values.size <= MAX_CHART_POINTS) return timestamps to values
        val step = max(1, values.size / MAX_CHART_POINTS)
        val outT = ArrayList<Long>(values.size / step + 1)
        val outV = ArrayList<Float>(values.size / step + 1)
        var i = 0
        while (i < values.size) {
            outT.add(timestamps[i])
            outV.add(values[i])
            i += step
        }
        return outT to outV
    }

    private fun openDetailedChart(kind: String) {
        val paused = Prefs.isPauseLiveEnabled(this)
        val doseSeries = if (paused) pausedSnapshotDose else currentWindowSeriesDose()
        val cpsSeries = if (paused) pausedSnapshotCps else currentWindowSeriesCps()

        val (ts, v) = if (kind == "cps") {
            val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
            val smooth = Prefs.getSmoothSeconds(this, 0)
            val poll = Prefs.getPollIntervalMs(this, 1000L)
            val smoothSamples = if (smooth <= 0) 0 else max(1, ((smooth * 1000L) / max(1L, poll)).toInt())
            val vals = convertCount(applySmoothing(cpsSeries?.values.orEmpty(), smoothSamples), cu)
            decimate(cpsSeries?.timestampsMs.orEmpty(), vals)
        } else {
            val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
            val smooth = Prefs.getSmoothSeconds(this, 0)
            val poll = Prefs.getPollIntervalMs(this, 1000L)
            val smoothSamples = if (smooth <= 0) 0 else max(1, ((smooth * 1000L) / max(1L, poll)).toInt())
            val vals = convertDose(applySmoothing(doseSeries?.values.orEmpty(), smoothSamples), du)
            decimate(doseSeries?.timestampsMs.orEmpty(), vals)
        }

        val secondaryV = if (kind == "cps") {
            val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
            val smooth = Prefs.getSmoothSeconds(this, 0)
            val poll = Prefs.getPollIntervalMs(this, 1000L)
            val smoothSamples = if (smooth <= 0) 0 else max(1, ((smooth * 1000L) / max(1L, poll)).toInt())
            convertDose(applySmoothing(doseSeries?.values.orEmpty(), smoothSamples), du)
        } else {
            val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
            val smooth = Prefs.getSmoothSeconds(this, 0)
            val poll = Prefs.getPollIntervalMs(this, 1000L)
            val smoothSamples = if (smooth <= 0) 0 else max(1, ((smooth * 1000L) / max(1L, poll)).toInt())
            convertCount(applySmoothing(cpsSeries?.values.orEmpty(), smoothSamples), cu)
        }
        
        val (_, secondaryDecimated) = decimate(ts.indices.map { doseSeries?.timestampsMs?.getOrNull(it) ?: 0L }, secondaryV)

        val i = Intent(this, DetailedChartActivity::class.java)
            .putExtra(DetailedChartActivity.EXTRA_KIND, kind)
            .putExtra("ts", ts.toLongArray())
            .putExtra("v", v.toFloatArray())
            .putExtra("secondary_v", secondaryDecimated.toFloatArray())
        startActivity(i)
    }

    private fun registerReadingReceiver() {
        val filter = android.content.IntentFilter(RadiaCodeForegroundService.ACTION_READING)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(readingReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(readingReceiver, filter)
            }
        } catch (_: Throwable) {}
    }

    private fun unregisterReadingReceiver() {
        try {
            unregisterReceiver(readingReceiver)
        } catch (_: Throwable) {}
    }
}
