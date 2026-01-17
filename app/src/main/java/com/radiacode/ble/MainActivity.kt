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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
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
import com.radiacode.ble.ui.IsotopeChartView
import com.radiacode.ble.ui.StackedAreaChartView
import com.radiacode.ble.ui.IsotopeBarChartView
import com.radiacode.ble.dashboard.DashboardGridLayout
import com.radiacode.ble.dashboard.DashboardLayout
import com.radiacode.ble.dashboard.DashboardReorderHelper
import com.radiacode.ble.dashboard.PanelType
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
    private var currentPanel: Panel = Panel.Dashboard

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

    // Isotope detection panel
    private lateinit var isotopePanel: LinearLayout
    private lateinit var isotopeChartTitle: TextView
    private lateinit var isotopeAccumulationModeToggle: TextView
    private lateinit var isotopeDisplayModeToggle: TextView
    private lateinit var isotopeHideBackgroundToggle: TextView
    private lateinit var isotopeChartTypeBtn: android.widget.ImageButton
    private lateinit var isotopeSettingsBtn: android.widget.ImageButton
    private lateinit var isotopeScanBtn: MaterialButton
    private lateinit var isotopeRealtimeSwitch: SwitchMaterial
    private lateinit var isotopeStatusLabel: TextView
    private lateinit var isotopeChartContainer: android.widget.FrameLayout
    private lateinit var isotopeMultiLineChart: IsotopeChartView
    private lateinit var isotopeStackedChart: StackedAreaChartView
    private lateinit var isotopeBarChart: IsotopeBarChartView
    private lateinit var isotopeScanResultContainer: LinearLayout
    private lateinit var isotopeScanResultText: TextView
    private lateinit var isotopeScanProgress: android.widget.ProgressBar
    private lateinit var isotopeQuickView: LinearLayout
    private lateinit var isotopeTopResult: TextView
    
    // Isotope detection state
    private var isotopeDetector: IsotopeDetector? = null
    private val isotopePredictionHistories = mutableMapOf<String, IsotopePredictionHistory>()  // per-device history
    private var isIsotopeRealtimeActive = false
    private var lastSpectrumData: SpectrumData? = null
    private var lastSpectrumDeviceId: String? = null
    
    // Spectrum accumulation for real-time mode (since differential spectrum has few counts)
    private val accumulatedSpectra = mutableMapOf<String, SpectrumData>()
    // In FULL_DURATION mode, we never reset; in INTERVAL mode, we use the chart time window
    private val spectrumAccumulationStart = mutableMapOf<String, Long>()

    private lateinit var sessionInfo: TextView
    
    // Intelligence card
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
    private lateinit var rowTrendArrows: View
    private lateinit var rowIsotopeSettings: View

    private lateinit var valueWindow: TextView
    private lateinit var valueSmoothing: TextView
    private lateinit var valueSpikeMarkers: TextView
    private lateinit var valueSpikePercentages: TextView
    private lateinit var valueUnits: TextView
    private lateinit var valuePause: TextView
    private lateinit var valueSmartAlerts: TextView
    private lateinit var valueTrendArrows: TextView
    private lateinit var valueNotificationStyle: TextView
    private lateinit var valueIsotopeSettings: TextView
    
    // Settings sections (expandable)
    private lateinit var sectionAppHeader: View
    private lateinit var sectionAppContent: View
    private lateinit var sectionAppArrow: android.widget.ImageView
    private lateinit var sectionChartHeader: View
    private lateinit var sectionChartContent: View
    private lateinit var sectionChartArrow: android.widget.ImageView
    private lateinit var sectionDisplayHeader: View
    private lateinit var sectionDisplayContent: View
    private lateinit var sectionDisplayArrow: android.widget.ImageView
    private lateinit var sectionAlertsHeader: View
    private lateinit var sectionAlertsContent: View
    private lateinit var sectionAlertsArrow: android.widget.ImageView
    private lateinit var sectionDetectionHeader: View
    private lateinit var sectionDetectionContent: View
    private lateinit var sectionDetectionArrow: android.widget.ImageView
    private lateinit var sectionAdvancedHeader: View
    private lateinit var sectionAdvancedContent: View
    private lateinit var sectionAdvancedArrow: android.widget.ImageView
    
    // Dashboard settings section
    private lateinit var sectionDashboardHeader: View
    private lateinit var sectionDashboardContent: View
    private lateinit var sectionDashboardArrow: android.widget.ImageView
    private lateinit var rowEditDashboard: View
    private lateinit var rowResetDashboard: View
    
    // Application settings rows
    private lateinit var rowNotificationSettings: View
    
    // Dashboard edit mode
    private lateinit var fabEditDashboard: FloatingActionButton
    private lateinit var editModeToolbar: LinearLayout
    private lateinit var btnResetDashboard: MaterialButton
    private lateinit var btnDoneEditing: MaterialButton
    private var isDashboardEditMode: Boolean = false
    private var dashboardReorderHelper: DashboardReorderHelper? = null
    
    // Dashboard section wrappers for drag reordering
    private lateinit var metricsCardsRow: LinearLayout
    private lateinit var dashboardContainer: LinearLayout

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
    
    // Track device connection states from service broadcasts
    private val deviceConnectionStates = mutableMapOf<String, DeviceConnectionState>()

    private val readingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != RadiaCodeForegroundService.ACTION_READING) return
            val paused = Prefs.isPauseLiveEnabled(this@MainActivity)
            if (paused) return
            lastReadingTimestampMs = 0L
        }
    }
    
    private val deviceStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != RadiaCodeForegroundService.ACTION_DEVICE_STATE_CHANGED) return
            val deviceId = intent.getStringExtra(RadiaCodeForegroundService.EXTRA_DEVICE_ID) ?: return
            val stateName = intent.getStringExtra(RadiaCodeForegroundService.EXTRA_CONNECTION_STATE) ?: return
            val state = try {
                DeviceConnectionState.valueOf(stateName)
            } catch (_: Exception) {
                DeviceConnectionState.DISCONNECTED
            }
            deviceConnectionStates[deviceId] = state
            // Update device selector with new states
            updateDeviceSelectorStates()
        }
    }
    
    private val spectrumReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != RadiaCodeForegroundService.ACTION_SPECTRUM_DATA) return
            val counts = intent.getIntArrayExtra(RadiaCodeForegroundService.EXTRA_SPECTRUM_COUNTS) ?: return
            val deviceId = intent.getStringExtra(RadiaCodeForegroundService.EXTRA_DEVICE_ID)
            val a0 = intent.getFloatExtra(RadiaCodeForegroundService.EXTRA_CALIB_A0, 0f)
            val a1 = intent.getFloatExtra(RadiaCodeForegroundService.EXTRA_CALIB_A1, 3.0f)
            val a2 = intent.getFloatExtra(RadiaCodeForegroundService.EXTRA_CALIB_A2, 0f)
            val isRealtime = intent.getBooleanExtra(RadiaCodeForegroundService.EXTRA_IS_REALTIME, false)
            
            val spectrum = SpectrumData(
                durationSeconds = 0,  // Not tracked in broadcast
                counts = counts,
                a0 = a0,
                a1 = a1,
                a2 = a2,
                timestampMs = System.currentTimeMillis()
            )
            
            // Only update lastSpectrumData for scans (for the SCAN button)
            if (!isRealtime) {
                lastSpectrumData = spectrum
                lastSpectrumDeviceId = deviceId
            }
            
            // Only feed realtime data to the realtime handler
            if (isRealtime) {
                onRealtimeSpectrumReceived(spectrum, deviceId)
            }
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
        setupIsotopePanel()
        setupToolbarDeviceSelector()
        setupDashboardEditMode()

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
        
        // Dashboard container for reordering
        dashboardContainer = panelDashboard as LinearLayout
        metricsCardsRow = findViewById(R.id.metricsCardsRow)

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

        // Isotope detection panel
        isotopePanel = findViewById(R.id.isotopePanel)
        isotopeChartTitle = findViewById(R.id.isotopeChartTitle)
        isotopeAccumulationModeToggle = findViewById(R.id.isotopeAccumulationModeToggle)
        isotopeDisplayModeToggle = findViewById(R.id.isotopeDisplayModeToggle)
        isotopeHideBackgroundToggle = findViewById(R.id.isotopeHideBackgroundToggle)
        isotopeChartTypeBtn = findViewById(R.id.isotopeChartTypeBtn)
        isotopeSettingsBtn = findViewById(R.id.isotopeSettingsBtn)
        isotopeScanBtn = findViewById(R.id.isotopeScanBtn)
        isotopeRealtimeSwitch = findViewById(R.id.isotopeRealtimeSwitch)
        isotopeStatusLabel = findViewById(R.id.isotopeStatusLabel)
        isotopeChartContainer = findViewById(R.id.isotopeChartContainer)
        isotopeMultiLineChart = findViewById(R.id.isotopeMultiLineChart)
        isotopeStackedChart = findViewById(R.id.isotopeStackedChart)
        isotopeBarChart = findViewById(R.id.isotopeBarChart)
        isotopeScanResultContainer = findViewById(R.id.isotopeScanResultContainer)
        isotopeScanResultText = findViewById(R.id.isotopeScanResultText)
        isotopeScanProgress = findViewById(R.id.isotopeScanProgress)
        isotopeQuickView = findViewById(R.id.isotopeQuickView)
        isotopeTopResult = findViewById(R.id.isotopeTopResult)

        sessionInfo = findViewById(R.id.sessionInfo)
        
        // Intelligence card
        intelligenceCard = findViewById(R.id.intelligenceCard)
        intelligenceSummary = findViewById(R.id.intelligenceSummary)
        intelligenceAlertBadge = findViewById(R.id.intelligenceAlertBadge)
        doseTrendLabel = findViewById(R.id.doseTrendLabel)
        predictedDoseLabel = findViewById(R.id.predictedDoseLabel)
        anomalyCountLabel = findViewById(R.id.anomalyCountLabel)
        intelligenceInfoButton = findViewById(R.id.intelligenceInfoButton)
        stabilityIndicator = findViewById(R.id.stabilityIndicator)
        dataQualityLabel = findViewById(R.id.dataQualityLabel)
        predictionConfidenceLabel = findViewById(R.id.predictionConfidenceLabel)
        anomalyDetailLabel = findViewById(R.id.anomalyDetailLabel)
        intelligenceExpandedSection = findViewById(R.id.intelligenceExpandedSection)
        intelligenceExpandButton = findViewById(R.id.intelligenceExpandButton)
        intelligenceExpandText = findViewById(R.id.intelligenceExpandText)
        intelligenceExpandArrow = findViewById(R.id.intelligenceExpandArrow)
        statsRangeLabel = findViewById(R.id.statsRangeLabel)
        statsStdDevLabel = findViewById(R.id.statsStdDevLabel)
        statsCvLabel = findViewById(R.id.statsCvLabel)
        statsBackgroundLabel = findViewById(R.id.statsBackgroundLabel)
        statsVsBackgroundLabel = findViewById(R.id.statsVsBackgroundLabel)
        statsZScoreLabel = findViewById(R.id.statsZScoreLabel)
        
        // Intelligence card interactions
        intelligenceInfoButton.setOnClickListener { showIntelligenceHelpDialog() }
        intelligenceExpandButton.setOnClickListener { toggleIntelligenceExpanded() }

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
        rowTrendArrows = findViewById(R.id.rowTrendArrows)
        rowIsotopeSettings = findViewById(R.id.rowIsotopeSettings)

        valueWindow = findViewById(R.id.valueWindow)
        valueSmoothing = findViewById(R.id.valueSmoothing)
        valueSpikeMarkers = findViewById(R.id.valueSpikeMarkers)
        valueSpikePercentages = findViewById(R.id.valueSpikePercentages)
        valueUnits = findViewById(R.id.valueUnits)
        valuePause = findViewById(R.id.valuePause)
        valueSmartAlerts = findViewById(R.id.valueSmartAlerts)
        valueTrendArrows = findViewById(R.id.valueTrendArrows)
        valueIsotopeSettings = findViewById(R.id.valueIsotopeSettings)
        valueNotificationStyle = findViewById(R.id.valueNotificationStyle)
        
        // Expandable section headers and content
        sectionAppHeader = findViewById(R.id.sectionAppHeader)
        sectionAppContent = findViewById(R.id.sectionAppContent)
        sectionAppArrow = findViewById(R.id.sectionAppArrow)
        sectionChartHeader = findViewById(R.id.sectionChartHeader)
        sectionChartContent = findViewById(R.id.sectionChartContent)
        sectionChartArrow = findViewById(R.id.sectionChartArrow)
        sectionDisplayHeader = findViewById(R.id.sectionDisplayHeader)
        sectionDisplayContent = findViewById(R.id.sectionDisplayContent)
        sectionDisplayArrow = findViewById(R.id.sectionDisplayArrow)
        sectionAlertsHeader = findViewById(R.id.sectionAlertsHeader)
        sectionAlertsContent = findViewById(R.id.sectionAlertsContent)
        sectionAlertsArrow = findViewById(R.id.sectionAlertsArrow)
        sectionDetectionHeader = findViewById(R.id.sectionDetectionHeader)
        sectionDetectionContent = findViewById(R.id.sectionDetectionContent)
        sectionDetectionArrow = findViewById(R.id.sectionDetectionArrow)
        sectionAdvancedHeader = findViewById(R.id.sectionAdvancedHeader)
        sectionAdvancedContent = findViewById(R.id.sectionAdvancedContent)
        sectionAdvancedArrow = findViewById(R.id.sectionAdvancedArrow)
        
        // Dashboard settings section
        sectionDashboardHeader = findViewById(R.id.sectionDashboardHeader)
        sectionDashboardContent = findViewById(R.id.sectionDashboardContent)
        sectionDashboardArrow = findViewById(R.id.sectionDashboardArrow)
        rowEditDashboard = findViewById(R.id.rowEditDashboard)
        rowResetDashboard = findViewById(R.id.rowResetDashboard)
        
        // Application settings rows
        rowNotificationSettings = findViewById(R.id.rowNotificationSettings)
        
        // Dashboard edit mode
        fabEditDashboard = findViewById(R.id.fabEditDashboard)
        editModeToolbar = findViewById(R.id.editModeToolbar)
        btnResetDashboard = findViewById(R.id.btnResetDashboard)
        btnDoneEditing = findViewById(R.id.btnDoneEditing)

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
                R.id.nav_widget_crafter -> {
                    startActivity(Intent(this, WidgetCrafterActivity::class.java))
                }
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
        setupExpandableSection(sectionAppHeader, sectionAppContent, sectionAppArrow, expanded = true)
        setupExpandableSection(sectionDashboardHeader, sectionDashboardContent, sectionDashboardArrow, expanded = true)
        setupExpandableSection(sectionChartHeader, sectionChartContent, sectionChartArrow, expanded = true)
        setupExpandableSection(sectionDisplayHeader, sectionDisplayContent, sectionDisplayArrow, expanded = true)
        setupExpandableSection(sectionAlertsHeader, sectionAlertsContent, sectionAlertsArrow, expanded = true)
        setupExpandableSection(sectionDetectionHeader, sectionDetectionContent, sectionDetectionArrow, expanded = true)
        setupExpandableSection(sectionAdvancedHeader, sectionAdvancedContent, sectionAdvancedArrow, expanded = false)
        
        // Application settings
        rowNotificationSettings.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }
        
        // Dashboard settings
        rowEditDashboard.setOnClickListener {
            setPanel(Panel.Dashboard)
            mainHandler.postDelayed({ enterDashboardEditMode() }, 300)
        }
        
        rowResetDashboard.setOnClickListener {
            showResetDashboardConfirmation()
        }
        
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
        
        rowTrendArrows.setOnClickListener {
            val next = !Prefs.isShowTrendArrowsEnabled(this)
            Prefs.setShowTrendArrowsEnabled(this, next)
            refreshSettingsRows()
            applyTrendArrowsSettings()
        }

        rowSmartAlerts.setOnClickListener {
            startActivity(Intent(this, AlertConfigActivity::class.java))
        }
        
        rowIsotopeSettings.setOnClickListener {
            startActivity(Intent(this, IsotopeSettingsActivity::class.java))
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
        
        // Apply trend arrows setting
        applyTrendArrowsSettings()
    }
    
    private fun applyTrendArrowsSettings() {
        val showTrend = Prefs.isShowTrendArrowsEnabled(this)
        doseCard.setShowTrendArrows(showTrend)
        cpsCard.setShowTrendArrows(showTrend)
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

    private fun setupIsotopePanel() {
        // Initialize the isotope detector with enabled isotopes
        val enabledIsotopes = Prefs.getEnabledIsotopes(this)
        isotopeDetector = IsotopeDetector(enabledIsotopes)
        
        // Show/hide panel based on connection state (will be updated dynamically)
        isotopePanel.visibility = View.VISIBLE
        
        // Apply saved settings
        val realtimeEnabled = Prefs.isIsotopeRealtimeEnabled(this)
        isotopeRealtimeSwitch.isChecked = realtimeEnabled
        isIsotopeRealtimeActive = realtimeEnabled
        
        val displayMode = Prefs.getIsotopeDisplayMode(this)
        updateIsotopeDisplayModeToggle(displayMode)
        
        val accumulationMode = Prefs.getIsotopeAccumulationMode(this)
        updateIsotopeAccumulationModeToggle(accumulationMode)
        
        val hideBackground = Prefs.isIsotopeHideBackground(this)
        updateIsotopeHideBackgroundToggle(hideBackground)
        
        val chartMode = Prefs.getIsotopeChartMode(this)
        updateIsotopeChartMode(chartMode)
        
        // Setup click listeners
        isotopeScanBtn.setOnClickListener { performIsotopeScan() }
        
        isotopeRealtimeSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setIsotopeRealtimeEnabled(this, isChecked)
            isIsotopeRealtimeActive = isChecked
            // Clear accumulated spectra when starting/stopping real-time mode
            accumulatedSpectra.clear()
            spectrumAccumulationStart.clear()
            updateIsotopePanelState()
        }
        
        isotopeDisplayModeToggle.setOnClickListener {
            val current = Prefs.getIsotopeDisplayMode(this)
            val next = when (current) {
                Prefs.IsotopeDisplayMode.PROBABILITY -> Prefs.IsotopeDisplayMode.FRACTION
                Prefs.IsotopeDisplayMode.FRACTION -> Prefs.IsotopeDisplayMode.PROBABILITY
            }
            Prefs.setIsotopeDisplayMode(this, next)
            updateIsotopeDisplayModeToggle(next)
            refreshIsotopeCharts()
        }
        
        isotopeAccumulationModeToggle.setOnClickListener {
            val current = Prefs.getIsotopeAccumulationMode(this)
            val next = when (current) {
                Prefs.IsotopeAccumulationMode.FULL_DURATION -> Prefs.IsotopeAccumulationMode.INTERVAL
                Prefs.IsotopeAccumulationMode.INTERVAL -> Prefs.IsotopeAccumulationMode.FULL_DURATION
            }
            Prefs.setIsotopeAccumulationMode(this, next)
            updateIsotopeAccumulationModeToggle(next)
            // Clear accumulated spectra when changing mode
            accumulatedSpectra.clear()
            spectrumAccumulationStart.clear()
        }
        
        isotopeHideBackgroundToggle.setOnClickListener {
            val current = Prefs.isIsotopeHideBackground(this)
            val next = !current
            Prefs.setIsotopeHideBackground(this, next)
            updateIsotopeHideBackgroundToggle(next)
            refreshIsotopeCharts()
        }
        
        isotopeChartTypeBtn.setOnClickListener { showIsotopeChartTypeDialog() }
        
        isotopeSettingsBtn.setOnClickListener {
            startActivity(Intent(this, IsotopeSettingsActivity::class.java))
        }
        
        updateIsotopePanelState()
    }
    
    private fun updateIsotopeDisplayModeToggle(mode: Prefs.IsotopeDisplayMode) {
        isotopeDisplayModeToggle.text = when (mode) {
            Prefs.IsotopeDisplayMode.PROBABILITY -> "PROB"
            Prefs.IsotopeDisplayMode.FRACTION -> "FRAC"
        }
    }
    
    private fun updateIsotopeAccumulationModeToggle(mode: Prefs.IsotopeAccumulationMode) {
        isotopeAccumulationModeToggle.text = when (mode) {
            Prefs.IsotopeAccumulationMode.FULL_DURATION -> "FULL"
            Prefs.IsotopeAccumulationMode.INTERVAL -> "INT"
        }
        isotopeAccumulationModeToggle.setTextColor(
            ContextCompat.getColor(this, when (mode) {
                Prefs.IsotopeAccumulationMode.FULL_DURATION -> R.color.pro_magenta
                Prefs.IsotopeAccumulationMode.INTERVAL -> R.color.pro_cyan
            })
        )
    }
    
    private fun updateIsotopeHideBackgroundToggle(hide: Boolean) {
        isotopeHideBackgroundToggle.text = if (hide) "BKG" else "BKG"
        isotopeHideBackgroundToggle.setTextColor(
            ContextCompat.getColor(this, if (hide) R.color.pro_text_muted else R.color.pro_green)
        )
        // Show strikethrough when hidden
        isotopeHideBackgroundToggle.paintFlags = if (hide) {
            isotopeHideBackgroundToggle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            isotopeHideBackgroundToggle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }
    
    private fun updateIsotopeChartMode(mode: Prefs.IsotopeChartMode) {
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
    
    private fun updateIsotopePanelState() {
        val history = getCurrentIsotopeHistory()
        if (isIsotopeRealtimeActive) {
            isotopeStatusLabel.text = "Streaming"
            isotopeStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.pro_green))
            val mode = Prefs.getIsotopeChartMode(this)
            updateIsotopeChartMode(mode)
        } else {
            if (history.isEmpty) {
                isotopeStatusLabel.text = "Idle"
                isotopeStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.pro_text_muted))
                isotopeScanResultContainer.visibility = View.VISIBLE
                isotopeScanResultText.text = "Press SCAN to identify isotopes"
                isotopeMultiLineChart.visibility = View.GONE
                isotopeStackedChart.visibility = View.GONE
                isotopeBarChart.visibility = View.GONE
            }
        }
    }
    
    private fun showIsotopeChartTypeDialog() {
        val options = arrayOf("Multi-Line Chart", "Stacked Area Chart", "Animated Bars")
        val current = Prefs.getIsotopeChartMode(this).ordinal
        
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Chart Type")
            .setSingleChoiceItems(options, current) { dialog, which ->
                val mode = Prefs.IsotopeChartMode.values()[which]
                Prefs.setIsotopeChartMode(this, mode)
                updateIsotopeChartMode(mode)
                refreshIsotopeCharts()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performIsotopeScan() {
        // Show scanning state immediately
        isotopeScanResultContainer.visibility = View.VISIBLE
        isotopeMultiLineChart.visibility = View.GONE
        isotopeStackedChart.visibility = View.GONE
        isotopeBarChart.visibility = View.GONE
        isotopeScanResultText.text = "Reading spectrum…"
        isotopeScanProgress.visibility = View.VISIBLE
        isotopeStatusLabel.text = "Scanning…"
        isotopeStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.pro_amber))
        
        // Request fresh spectrum data from service
        RadiaCodeForegroundService.requestSpectrum(this)
        
        // Wait a bit for response (the spectrum receiver will update lastSpectrumData)
        // If we get data via broadcast, onSpectrumDataReceived will be called
        // Fall back to existing data after timeout
        mainHandler.postDelayed({
            val spectrum = lastSpectrumData
            if (spectrum == null) {
                isotopeScanResultText.text = "No spectrum data available.\nConnect to a device first."
                isotopeScanProgress.visibility = View.GONE
                isotopeStatusLabel.text = "Error"
                isotopeStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.pro_red))
                return@postDelayed
            }
            
            // Analyze the spectrum
            val detector = isotopeDetector ?: return@postDelayed
            val result = detector.analyze(spectrum)
            
            isotopeScanProgress.visibility = View.GONE
            isotopeStatusLabel.text = "Complete"
            isotopeStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.pro_green))
            
            // Display results
            displayScanResults(result)
        }, 1500) // Give time for BLE read to complete
    }
    
    private fun displayScanResults(result: IsotopeDetector.AnalysisResult) {
        val topFive = result.topFive
        if (topFive.isEmpty()) {
            isotopeScanResultText.text = "No isotopes detected"
            return
        }
        
        val displayMode = Prefs.getIsotopeDisplayMode(this)
        val sb = StringBuilder()
        
        for ((i, pred) in topFive.withIndex()) {
            val value = if (displayMode == Prefs.IsotopeDisplayMode.PROBABILITY) {
                "${(pred.probability * 100).toInt()}%"
            } else {
                "%.1f%%".format(pred.fraction * 100)
            }
            val icon = when (i) {
                0 -> "🥇"
                1 -> "🥈"
                2 -> "🥉"
                else -> "  "
            }
            sb.append("$icon ${pred.name}: $value\n")
        }
        
        isotopeScanResultText.text = sb.toString().trim()
        isotopeScanResultContainer.visibility = View.VISIBLE
        
        // Update top result quick view
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
    
    private fun refreshIsotopeCharts() {
        val isotopeHistory = getCurrentIsotopeHistory()
        if (isotopeHistory.isEmpty) return
        
        val hideBackground = Prefs.isIsotopeHideBackground(this)
        val displayMode = Prefs.getIsotopeDisplayMode(this)
        val showProbability = displayMode == Prefs.IsotopeDisplayMode.PROBABILITY
        
        // Get top isotopes, excluding "Unknown" if hide background is enabled
        val topPredictions = isotopeHistory.getCurrentTop(if (hideBackground) 6 else 5)
            .filter { if (hideBackground) it.isotopeId != "Unknown" else true }
            .take(5)
        val topIds = topPredictions.map { it.isotopeId }
        
        // Filter history entries to exclude Unknown when hide background is enabled
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
        
        // Update top result (respect hide background here too)
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
    
    /**
     * Get or create the isotope prediction history for a device.
     */
    private fun getIsotopeHistory(deviceId: String?): IsotopePredictionHistory {
        val key = deviceId ?: "global"
        return isotopePredictionHistories.getOrPut(key) { IsotopePredictionHistory(300) }
    }
    
    /**
     * Get the current selected device's isotope history.
     */
    private fun getCurrentIsotopeHistory(): IsotopePredictionHistory {
        val selectedId = Prefs.getSelectedDeviceId(this)
        return getIsotopeHistory(selectedId)
    }
    
    /**
     * Called when new DIFFERENTIAL spectrum data is received (real-time mode).
     * This will be called frequently when real-time mode is active.
     * Since differential spectrum has few counts per read, we accumulate
     * over a time window to get meaningful data for isotope identification.
     * 
     * Accumulation modes:
     * - FULL_DURATION: Never reset, accumulate ALL counts since streaming started
     * - INTERVAL: Use the chart time window setting for accumulation window
     */
    private fun onRealtimeSpectrumReceived(spectrum: SpectrumData, deviceId: String? = null) {
        if (!isIsotopeRealtimeActive) return
        
        val effectiveDeviceId = deviceId ?: "unknown"
        val detector = isotopeDetector ?: return
        
        // Accumulate differential spectrum
        val now = System.currentTimeMillis()
        val startTime = spectrumAccumulationStart[effectiveDeviceId] ?: now
        val existingSpectrum = accumulatedSpectra[effectiveDeviceId]
        
        val accumulationMode = Prefs.getIsotopeAccumulationMode(this)
        
        when (accumulationMode) {
            Prefs.IsotopeAccumulationMode.FULL_DURATION -> {
                // Never reset - just keep accumulating
                if (existingSpectrum != null) {
                    accumulatedSpectra[effectiveDeviceId] = existingSpectrum + spectrum
                } else {
                    accumulatedSpectra[effectiveDeviceId] = spectrum
                    spectrumAccumulationStart[effectiveDeviceId] = now
                }
            }
            Prefs.IsotopeAccumulationMode.INTERVAL -> {
                // Use chart time window for accumulation interval
                val windowMs = Prefs.getWindowSeconds(this, 60) * 1000L
                
                if (now - startTime > windowMs) {
                    // Window expired - reset accumulation
                    accumulatedSpectra[effectiveDeviceId] = spectrum
                    spectrumAccumulationStart[effectiveDeviceId] = now
                    android.util.Log.d("MainActivity", "Reset spectrum accumulation (INTERVAL mode, window=${windowMs}ms)")
                } else if (existingSpectrum != null) {
                    accumulatedSpectra[effectiveDeviceId] = existingSpectrum + spectrum
                } else {
                    accumulatedSpectra[effectiveDeviceId] = spectrum
                    spectrumAccumulationStart[effectiveDeviceId] = now
                }
            }
        }
        
        val accumulatedSpectrum = accumulatedSpectra[effectiveDeviceId] ?: spectrum
        val elapsedSec = (now - (spectrumAccumulationStart[effectiveDeviceId] ?: now)) / 1000
        android.util.Log.d("MainActivity", "Accumulated spectrum ($accumulationMode mode, ${elapsedSec}s): " +
            "totalCounts=${accumulatedSpectrum.totalCounts}")
        
        // Only analyze if we have enough counts (at least 50 for meaningful analysis)
        if (accumulatedSpectrum.totalCounts < 50) {
            android.util.Log.d("MainActivity", "Skipping analysis - only ${accumulatedSpectrum.totalCounts} counts accumulated")
            return
        }
        
        val result = detector.analyze(accumulatedSpectrum)
        
        // Add to the correct device's history
        val history = getIsotopeHistory(deviceId)
        history.add(result)
        
        // Only refresh charts if this is the currently selected device
        val selectedId = Prefs.getSelectedDeviceId(this)
        if (deviceId == selectedId || (selectedId == null && deviceId != null)) {
            refreshIsotopeCharts()
        }
    }

    /**
     * Show the chart settings dialog (time window + units).
     */
    private fun showChartSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_chart_settings, null)
        
        // Time Window chips
        val timeWindowChips = dialogView.findViewById<ChipGroup>(R.id.dialogTimeWindowChips)
        val currentWindow = Prefs.getWindowSeconds(this, 60)
        when (currentWindow) {
            30 -> dialogView.findViewById<Chip>(R.id.dialogChip30s).isChecked = true
            60 -> dialogView.findViewById<Chip>(R.id.dialogChip1m).isChecked = true
            300 -> dialogView.findViewById<Chip>(R.id.dialogChip5m).isChecked = true
            900 -> dialogView.findViewById<Chip>(R.id.dialogChip15m).isChecked = true
            3600 -> dialogView.findViewById<Chip>(R.id.dialogChip1h).isChecked = true
            else -> dialogView.findViewById<Chip>(R.id.dialogChip1m).isChecked = true
        }
        
        // Dose Unit chips
        val doseUnitChips = dialogView.findViewById<ChipGroup>(R.id.dialogDoseUnitChips)
        val currentDoseUnit = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
        when (currentDoseUnit) {
            Prefs.DoseUnit.USV_H -> dialogView.findViewById<Chip>(R.id.dialogChipUsvH).isChecked = true
            Prefs.DoseUnit.NSV_H -> dialogView.findViewById<Chip>(R.id.dialogChipNsvH).isChecked = true
        }
        
        // Count Unit chips
        val countUnitChips = dialogView.findViewById<ChipGroup>(R.id.dialogCountUnitChips)
        val currentCountUnit = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
        when (currentCountUnit) {
            Prefs.CountUnit.CPS -> dialogView.findViewById<Chip>(R.id.dialogChipCps).isChecked = true
            Prefs.CountUnit.CPM -> dialogView.findViewById<Chip>(R.id.dialogChipCpm).isChecked = true
        }
        
        // Build and show dialog
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Chart Settings")
            .setView(dialogView)
            .setPositiveButton("Done", null)
            .create()
        
        // Set up listeners
        val chipToSeconds = mapOf(
            R.id.dialogChip30s to 30,
            R.id.dialogChip1m to 60,
            R.id.dialogChip5m to 300,
            R.id.dialogChip15m to 900,
            R.id.dialogChip1h to 3600
        )
        
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
        
        doseUnitChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chipId = checkedIds.first()
            val unit = when (chipId) {
                R.id.dialogChipNsvH -> Prefs.DoseUnit.NSV_H
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
                R.id.dialogChipCpm -> Prefs.CountUnit.CPM
                else -> Prefs.CountUnit.CPS
            }
            Prefs.setCountUnit(this, unit)
            refreshSettingsRows()
            updateChartTitles()
            lastReadingTimestampMs = 0L
        }
        
        dialog.show()
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
                
                // Refresh isotope charts for the new device
                refreshIsotopeCharts()
                updateIsotopePanelState()
                
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
            if (devices.size > 1 && currentPanel == Panel.Dashboard) {
                allDevicesOverlay.visibility = View.VISIBLE
            }
            return
        }
        
        // Single device mode - hide overlay
        if (currentPanel == Panel.Dashboard) {
            allDevicesOverlay.visibility = View.GONE
        }
        
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
                
                // Update overlay visibility (only when on Dashboard panel)
                if (currentPanel == Panel.Dashboard) {
                    if (deviceId == null) {
                        // All devices mode - show overlay
                        allDevicesOverlay.visibility = View.VISIBLE
                    } else {
                        // Single device mode - hide overlay and load data
                        allDevicesOverlay.visibility = View.GONE
                    }
                }
                if (deviceId != null) {
                    
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
        
        // Setup settings button callback
        deviceSelector.setOnSettingsClickListener(object : com.radiacode.ble.ui.DeviceSelectorView.OnSettingsClickListener {
            override fun onSettingsClick() {
                showChartSettingsDialog()
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
                
                // Update device selector with current connection states
                val statesMap = buildDeviceStatesMap(devices)
                deviceSelector.setDevices(devices, statesMap)
                
                // Check if in all-devices mode
                val isAllDevicesMode = selectedDeviceId == null && devices.size > 1
                
                // Update overlay visibility based on selection (only when on Dashboard panel)
                if (currentPanel == Panel.Dashboard) {
                    if (isAllDevicesMode) {
                        allDevicesOverlay.visibility = View.VISIBLE
                    } else {
                        allDevicesOverlay.visibility = View.GONE
                    }
                }

                // Dashboard metadata row (signal / temp / battery) - now integrated into deviceSelector
                val effectiveDeviceId = when {
                    selectedDeviceId != null -> selectedDeviceId
                    devices.size == 1 -> devices.first().id
                    else -> null
                }

                val meta = if (effectiveDeviceId != null) {
                    Prefs.getDeviceMetadata(this@MainActivity, effectiveDeviceId)
                } else {
                    null
                }

                // Update the integrated device metadata display in the selector
                deviceSelector.updateDeviceMetadata(
                    signalStrength = meta?.signalStrength,
                    temperature = meta?.temperature,
                    batteryLevel = meta?.batteryLevel
                )
                
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
                updateIntelligenceCard()

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

            // Update alert markers from stored events, filtered by metric
            val allAlertEvents = Prefs.getAlertEvents(this)
            android.util.Log.d("MainActivity", "updateCharts: loaded ${allAlertEvents.size} alert events total")
            allAlertEvents.forEach { e ->
                android.util.Log.d("MainActivity", "  Event: metric=${e.metric}, name=${e.alertName}, trigger=${e.triggerTimestampMs}")
            }

            // Active alerts (condition currently true) so we can visualize threshold exceedance immediately
            val activeAlerts = Prefs.getActiveAlerts(this)
            android.util.Log.d("MainActivity", "updateCharts: loaded ${activeAlerts.size} active alerts")
            
            // Filter and map dose alerts
            val doseAlertMarkers = (
                allAlertEvents
                    .filter { it.metric == "dose" }
                    .map { event ->
                        ProChartView.AlertMarker(
                            triggerTimestampMs = event.triggerTimestampMs,
                            durationWindowStartMs = event.durationWindowStartMs,
                            cooldownEndMs = event.cooldownEndMs,
                            color = "#${event.getColorEnum().hexColor}",
                            icon = event.getSeverityEnum().icon,
                            name = event.alertName
                        )
                    } +
                activeAlerts
                    .filter { it.metric == "dose" }
                    .map { state ->
                        // Active marker: shade from start -> lastSeen, draw line at lastSeen
                        ProChartView.AlertMarker(
                            triggerTimestampMs = state.lastSeenMs,
                            durationWindowStartMs = state.windowStartMs,
                            cooldownEndMs = state.lastSeenMs,
                            color = "#${Prefs.AlertColor.fromString(state.color).hexColor}",
                            icon = Prefs.AlertSeverity.fromString(state.severity).icon,
                            name = state.alertName
                        )
                    }
            )
            android.util.Log.d("MainActivity", "Dose chart: ${doseAlertMarkers.size} markers")
            doseChart.setAlertMarkers(doseAlertMarkers)
            
            // Filter and map count alerts
            val countAlertMarkers = (
                allAlertEvents
                    .filter { it.metric == "count" }
                    .map { event ->
                        ProChartView.AlertMarker(
                            triggerTimestampMs = event.triggerTimestampMs,
                            durationWindowStartMs = event.durationWindowStartMs,
                            cooldownEndMs = event.cooldownEndMs,
                            color = "#${event.getColorEnum().hexColor}",
                            icon = event.getSeverityEnum().icon,
                            name = event.alertName
                        )
                    } +
                activeAlerts
                    .filter { it.metric == "count" }
                    .map { state ->
                        ProChartView.AlertMarker(
                            triggerTimestampMs = state.lastSeenMs,
                            durationWindowStartMs = state.windowStartMs,
                            cooldownEndMs = state.lastSeenMs,
                            color = "#${Prefs.AlertColor.fromString(state.color).hexColor}",
                            icon = Prefs.AlertSeverity.fromString(state.severity).icon,
                            name = state.alertName
                        )
                    }
            )
            android.util.Log.d("MainActivity", "Count chart: ${countAlertMarkers.size} markers")
            cpsChart.setAlertMarkers(countAlertMarkers)

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
    
    private fun updateIntelligenceCard() {
        if (sampleCount < 10) {
            // Not enough data yet
            mainHandler.post {
                intelligenceCard.visibility = View.GONE
            }
            return
        }
        
        // Get intelligence report (use selected device if applicable)
        val currentDeviceId = Prefs.getSelectedDeviceId(this)
        val report = IntelligenceEngine.analyzeReadings(this, currentDeviceId)
        
        mainHandler.post {
            if (!report.hasEnoughData) {
                intelligenceCard.visibility = View.GONE
                return@post
            }
            
            intelligenceCard.visibility = View.VISIBLE
            intelligenceSummary.text = report.summary
            
            // Update stability indicator
            val (stabilityText, stabilityColor) = when (report.stability) {
                StabilityLevel.STABLE -> "● STABLE" to getColor(R.color.pro_green)
                StabilityLevel.VARIABLE -> "◐ VARIABLE" to getColor(R.color.pro_amber)
                StabilityLevel.ERRATIC -> "○ ERRATIC" to getColor(R.color.pro_red)
            }
            stabilityIndicator.text = stabilityText
            stabilityIndicator.setTextColor(stabilityColor)
            
            // Update data quality label
            val qualityText = when (report.dataQuality) {
                DataQuality.EXCELLENT -> "Excellent (${report.sampleCount})"
                DataQuality.GOOD -> "Good (${report.sampleCount})"
                DataQuality.FAIR -> "Fair (${report.sampleCount})"
                DataQuality.LIMITED -> "Limited (${report.sampleCount})"
                DataQuality.INSUFFICIENT -> "Collecting..."
            }
            dataQualityLabel.text = qualityText
            
            // Update alert badge
            val alertCount = report.alerts.size
            if (alertCount > 0) {
                intelligenceAlertBadge.visibility = View.VISIBLE
                intelligenceAlertBadge.text = alertCount.toString()
                // Color by severity
                val hasHighAlert = report.alerts.any { it.severity == AlertSeverity.HIGH }
                val badgeColor = if (hasHighAlert) getColor(R.color.pro_red) else getColor(R.color.pro_amber)
                intelligenceAlertBadge.background.setTint(badgeColor)
            } else {
                intelligenceAlertBadge.visibility = View.GONE
            }
            
            // Update trend label using trend description
            val trendDesc = report.doseTrendDescription
            if (trendDesc != null) {
                val trendDirection = when (trendDesc.direction) {
                    TrendDirection.INCREASING -> "↑ Rising"
                    TrendDirection.DECREASING -> "↓ Falling"
                    TrendDirection.STABLE -> "→ Stable"
                }
                val trendColor = when (trendDesc.direction) {
                    TrendDirection.INCREASING -> getColor(R.color.pro_amber)
                    TrendDirection.DECREASING -> getColor(R.color.pro_cyan)
                    TrendDirection.STABLE -> getColor(R.color.pro_green)
                }
                doseTrendLabel.text = trendDirection
                doseTrendLabel.setTextColor(trendColor)
            }
            
            // Update prediction with confidence
            val dosePrediction = report.predictions.firstOrNull { it.type == PredictionType.NEXT_DOSE }
            if (dosePrediction != null) {
                val doseUnit = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
                val predictedStr = when (doseUnit) {
                    Prefs.DoseUnit.USV_H -> String.format("%.3f μSv/h", dosePrediction.predictedValue)
                    Prefs.DoseUnit.NSV_H -> String.format("%.0f nSv/h", dosePrediction.predictedValue * 1000)
                }
                predictedDoseLabel.text = predictedStr
                
                // Confidence indicator
                val confText = String.format("%.0f%% conf", dosePrediction.confidence)
                val confColor = when (dosePrediction.confidenceLevel) {
                    ConfidenceLevel.HIGH -> getColor(R.color.pro_green)
                    ConfidenceLevel.MEDIUM -> getColor(R.color.pro_amber)
                    ConfidenceLevel.LOW -> getColor(R.color.pro_text_muted)
                }
                predictionConfidenceLabel.text = confText
                predictionConfidenceLabel.setTextColor(confColor)
            }
            
            // Update anomaly count with active/recent distinction
            val activeCount = report.activeAnomalyCount
            val recentCount = report.recentAnomalyCount
            
            if (activeCount > 0) {
                anomalyCountLabel.text = activeCount.toString()
                anomalyCountLabel.setTextColor(getColor(R.color.pro_red))
                anomalyDetailLabel.text = "$activeCount active"
                anomalyDetailLabel.setTextColor(getColor(R.color.pro_red))
            } else if (recentCount > 0) {
                anomalyCountLabel.text = recentCount.toString()
                anomalyCountLabel.setTextColor(getColor(R.color.pro_amber))
                anomalyDetailLabel.text = "$recentCount recent"
                anomalyDetailLabel.setTextColor(getColor(R.color.pro_text_muted))
            } else {
                anomalyCountLabel.text = "0"
                anomalyCountLabel.setTextColor(getColor(R.color.pro_green))
                anomalyDetailLabel.text = "none"
                anomalyDetailLabel.setTextColor(getColor(R.color.pro_text_muted))
            }
            
            // Update expanded stats section (if expanded)
            if (intelligenceExpanded) {
                updateIntelligenceExpandedStats(report)
            }
        }
    }
    
    private fun updateIntelligenceExpandedStats(report: IntelligenceReport) {
        val doseStats = report.doseStatistics ?: return
        val doseUnit = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
        
        // Range (min - max)
        val rangeStr = when (doseUnit) {
            Prefs.DoseUnit.USV_H -> String.format("%.3f - %.3f", doseStats.min, doseStats.max)
            Prefs.DoseUnit.NSV_H -> String.format("%.0f - %.0f", doseStats.min * 1000, doseStats.max * 1000)
        }
        statsRangeLabel.text = rangeStr
        
        // Std Dev
        val stdDevStr = when (doseUnit) {
            Prefs.DoseUnit.USV_H -> String.format("±%.4f", doseStats.stdDev)
            Prefs.DoseUnit.NSV_H -> String.format("±%.1f", doseStats.stdDev * 1000)
        }
        statsStdDevLabel.text = stdDevStr
        
        // CV%
        statsCvLabel.text = String.format("%.1f%%", doseStats.coefficientOfVariation)
        val cvColor = when {
            doseStats.coefficientOfVariation < 15 -> getColor(R.color.pro_green)
            doseStats.coefficientOfVariation < 35 -> getColor(R.color.pro_amber)
            else -> getColor(R.color.pro_red)
        }
        statsCvLabel.setTextColor(cvColor)
        
        // Estimated background
        val bgStr = when (doseUnit) {
            Prefs.DoseUnit.USV_H -> String.format("%.3f μSv/h", report.estimatedBackground)
            Prefs.DoseUnit.NSV_H -> String.format("%.0f nSv/h", report.estimatedBackground * 1000)
        }
        statsBackgroundLabel.text = bgStr
        
        // Current vs background
        val vsStr = String.format("%.0f%%", report.currentVsBackground)
        statsVsBackgroundLabel.text = vsStr
        val vsColor = when {
            report.currentVsBackground > 150 -> getColor(R.color.pro_red)
            report.currentVsBackground > 120 -> getColor(R.color.pro_amber)
            else -> getColor(R.color.pro_green)
        }
        statsVsBackgroundLabel.setTextColor(vsColor)
        
        // Current Z-score
        val zStr = String.format("%.2f σ", report.currentZScore)
        statsZScoreLabel.text = zStr
        val zColor = when {
            kotlin.math.abs(report.currentZScore) > 3 -> getColor(R.color.pro_red)
            kotlin.math.abs(report.currentZScore) > 2 -> getColor(R.color.pro_amber)
            else -> getColor(R.color.pro_green)
        }
        statsZScoreLabel.setTextColor(zColor)
    }
    
    private fun toggleIntelligenceExpanded() {
        intelligenceExpanded = !intelligenceExpanded
        
        if (intelligenceExpanded) {
            intelligenceExpandedSection.visibility = View.VISIBLE
            intelligenceExpandText.text = "Hide Details"
            intelligenceExpandArrow.rotation = 180f
            
            // Update stats now
            val currentDeviceId = Prefs.getSelectedDeviceId(this)
            val report = IntelligenceEngine.analyzeReadings(this, currentDeviceId)
            updateIntelligenceExpandedStats(report)
        } else {
            intelligenceExpandedSection.visibility = View.GONE
            intelligenceExpandText.text = "Show Details"
            intelligenceExpandArrow.rotation = 0f
        }
    }
    
    private fun showIntelligenceHelpDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_intelligence_help, null)
        
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setView(dialogView)
            .setPositiveButton("Got it", null)
            .show()
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
        
        val showTrendArrows = Prefs.isShowTrendArrowsEnabled(this)
        valueTrendArrows.text = if (showTrendArrows) "On" else "Off"
        valueTrendArrows.setTextColor(ContextCompat.getColor(this, 
            if (showTrendArrows) R.color.pro_green else R.color.pro_text_muted))

        val alerts = Prefs.getSmartAlerts(this)
        val enabledCount = alerts.count { it.enabled }
        valueSmartAlerts.text = if (enabledCount == 0) "None" else "$enabledCount active"
        valueSmartAlerts.setTextColor(ContextCompat.getColor(this,
            if (enabledCount > 0) R.color.pro_amber else R.color.pro_text_muted))
        
        // Notification style
        val notifStyle = Prefs.getNotificationStyle(this)
        valueNotificationStyle.text = when (notifStyle) {
            Prefs.NotificationStyle.NONE -> "None"
            Prefs.NotificationStyle.OFF -> "Minimal"
            Prefs.NotificationStyle.STATUS_ONLY -> "Status"
            Prefs.NotificationStyle.READINGS -> "Readings"
            Prefs.NotificationStyle.DETAILED -> "Detailed"
        }
        
        // Isotope settings
        val enabledIsotopes = Prefs.getEnabledIsotopes(this)
        val totalIsotopes = IsotopeLibrary.ALL_ISOTOPES.size
        valueIsotopeSettings.text = "${enabledIsotopes.size}/$totalIsotopes enabled"
    }

    private enum class Panel { Dashboard, Device, Settings, Logs }

    private fun setPanel(panel: Panel) {
        currentPanel = panel
        panelDashboard.visibility = if (panel == Panel.Dashboard) View.VISIBLE else View.GONE
        panelDevice.visibility = if (panel == Panel.Device) View.VISIBLE else View.GONE
        panelSettings.visibility = if (panel == Panel.Settings) View.VISIBLE else View.GONE
        panelLogs.visibility = if (panel == Panel.Logs) View.VISIBLE else View.GONE
        
        // Only show edit dashboard FAB on dashboard panel
        if (panel == Panel.Dashboard) {
            if (!isDashboardEditMode) {
                fabEditDashboard.visibility = View.VISIBLE
            }
        } else {
            fabEditDashboard.visibility = View.GONE
            editModeToolbar.visibility = View.GONE
            // Exit edit mode if switching away from dashboard
            if (isDashboardEditMode) {
                isDashboardEditMode = false
            }
        }
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
        val readingFilter = android.content.IntentFilter(RadiaCodeForegroundService.ACTION_READING)
        val stateFilter = android.content.IntentFilter(RadiaCodeForegroundService.ACTION_DEVICE_STATE_CHANGED)
        val spectrumFilter = android.content.IntentFilter(RadiaCodeForegroundService.ACTION_SPECTRUM_DATA)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(readingReceiver, readingFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(deviceStateReceiver, stateFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(spectrumReceiver, spectrumFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(readingReceiver, readingFilter)
                @Suppress("DEPRECATION")
                registerReceiver(deviceStateReceiver, stateFilter)
                @Suppress("DEPRECATION")
                registerReceiver(spectrumReceiver, spectrumFilter)
            }
        } catch (_: Throwable) {}
    }

    private fun unregisterReadingReceiver() {
        try {
            unregisterReceiver(readingReceiver)
        } catch (_: Throwable) {}
        try {
            unregisterReceiver(deviceStateReceiver)
        } catch (_: Throwable) {}
        try {
            unregisterReceiver(spectrumReceiver)
        } catch (_: Throwable) {}
    }
    
    /**
     * Build a states map for the device selector from tracked connection states.
     */
    private fun buildDeviceStatesMap(devices: List<DeviceConfig>): Map<String, DeviceState> {
        return devices.associate { device ->
            val connectionState = deviceConnectionStates[device.id] ?: DeviceConnectionState.DISCONNECTED
            device.id to DeviceState(
                config = device,
                connectionState = connectionState
            )
        }
    }
    
    /**
     * Update the device selector's states without rebuilding the whole list.
     */
    private fun updateDeviceSelectorStates() {
        val devices = Prefs.getDevices(this)
        val statesMap = buildDeviceStatesMap(devices)
        deviceSelector.updateStates(statesMap)
    }
    
    // ==================== DASHBOARD EDIT MODE ====================
    
    private fun setupDashboardEditMode() {
        // FAB to enter edit mode
        fabEditDashboard.setOnClickListener {
            enterDashboardEditMode()
        }
        
        // Done button exits edit mode
        btnDoneEditing.setOnClickListener {
            exitDashboardEditMode()
        }
        
        // Reset button in toolbar
        btnResetDashboard.setOnClickListener {
            showResetDashboardConfirmation()
        }
        
        // Setup the reorder helper
        dashboardReorderHelper = DashboardReorderHelper(dashboardContainer) { newOrder ->
            Prefs.setDashboardOrder(this, newOrder)
        }
        
        // Register sections for reordering
        dashboardReorderHelper?.registerSection(DashboardReorderHelper.SECTION_METRICS, metricsCardsRow)
        dashboardReorderHelper?.registerSection(DashboardReorderHelper.SECTION_INTELLIGENCE, intelligenceCard)
        dashboardReorderHelper?.registerSection(DashboardReorderHelper.SECTION_DOSE_CHART, doseChartPanel)
        dashboardReorderHelper?.registerSection(DashboardReorderHelper.SECTION_COUNT_CHART, cpsChartPanel)
        dashboardReorderHelper?.registerSection(DashboardReorderHelper.SECTION_ISOTOPE, isotopePanel)
        
        // Apply saved order
        val savedOrder = Prefs.getDashboardOrder(this)
        dashboardReorderHelper?.applyOrder(savedOrder)
    }
    
    private fun enterDashboardEditMode() {
        isDashboardEditMode = true
        
        // Hide FAB, show toolbar
        fabEditDashboard.visibility = View.GONE
        editModeToolbar.visibility = View.VISIBLE
        
        // Enable edit mode on reorder helper
        dashboardReorderHelper?.isEditMode = true
        
        // Show toast with instructions
        android.widget.Toast.makeText(
            this, 
            "Long-press and drag panels to reorder them", 
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    
    private fun exitDashboardEditMode() {
        isDashboardEditMode = false
        
        // Show FAB, hide toolbar
        fabEditDashboard.visibility = View.VISIBLE
        editModeToolbar.visibility = View.GONE
        
        // Disable edit mode on reorder helper
        dashboardReorderHelper?.isEditMode = false
    }
    
    private fun showResetDashboardConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Reset Dashboard Layout")
            .setMessage("This will restore the default panel arrangement. Your data will not be affected.")
            .setPositiveButton("Reset") { _, _ ->
                resetDashboardLayout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun resetDashboardLayout() {
        Prefs.resetDashboardLayout(this)
        
        // Reset reorder helper to default
        dashboardReorderHelper?.resetToDefault()
        
        android.widget.Toast.makeText(
            this,
            "Dashboard layout reset to default",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        
        // Exit edit mode if active
        if (isDashboardEditMode) {
            exitDashboardEditMode()
        }
    }
}
