package com.radiacode.ble

import android.Manifest
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

    // Dashboard - Metric cards
    private lateinit var doseCard: MetricCardView
    private lateinit var cpsCard: MetricCardView

    // Dashboard - Charts
    private lateinit var doseChartTitle: TextView
    private lateinit var doseChart: ProChartView
    private lateinit var doseStats: StatRowView

    private lateinit var cpsChartTitle: TextView
    private lateinit var cpsChart: ProChartView
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

    // Settings panel
    private lateinit var rowWindow: View
    private lateinit var rowSmoothing: View
    private lateinit var rowUnits: View
    private lateinit var rowThreshold: View
    private lateinit var rowPause: View

    private lateinit var valueWindow: TextView
    private lateinit var valueSmoothing: TextView
    private lateinit var valueUnits: TextView
    private lateinit var valueThreshold: TextView
    private lateinit var valuePause: TextView

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
        Prefs.setPreferredAddress(this, address)
        Prefs.setAutoConnectEnabled(this, true)
        RadiaCodeForegroundService.start(this, address)

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

        bindViews()
        setupNavigation()
        setupDevicePanel()
        setupSettingsPanel()
        setupLogsPanel()
        setupMetricCards()
        setupCharts()
        setupTimeWindowChips()
        setupUnitChips()

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

        doseCard = findViewById(R.id.doseCard)
        cpsCard = findViewById(R.id.cpsCard)

        doseChartTitle = findViewById(R.id.doseChartTitle)
        doseChart = findViewById(R.id.doseChart)
        doseStats = findViewById(R.id.doseStats)

        cpsChartTitle = findViewById(R.id.cpsChartTitle)
        cpsChart = findViewById(R.id.cpsChart)
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

        rowWindow = findViewById(R.id.rowWindow)
        rowSmoothing = findViewById(R.id.rowSmoothing)
        rowUnits = findViewById(R.id.rowUnits)
        rowThreshold = findViewById(R.id.rowThreshold)
        rowPause = findViewById(R.id.rowPause)

        valueWindow = findViewById(R.id.valueWindow)
        valueSmoothing = findViewById(R.id.valueSmoothing)
        valueUnits = findViewById(R.id.valueUnits)
        valueThreshold = findViewById(R.id.valueThreshold)
        valuePause = findViewById(R.id.valuePause)

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
    }

    private fun setupSettingsPanel() {
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

        rowThreshold.setOnClickListener {
            val presets = floatArrayOf(0.0f, 0.05f, 0.10f, 0.50f, 1.00f)
            val cur = Prefs.getDoseThresholdUsvH(this, 0f)
            val idx = presets.indexOfFirst { kotlin.math.abs(it - cur) < 1e-6f }.let { if (it < 0) 0 else it }
            Prefs.setDoseThresholdUsvH(this, presets[(idx + 1) % presets.size])
            refreshSettingsRows()
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
    }

    private fun setupLogsPanel() {
        shareCsvButton.setOnClickListener { shareCsv() }
    }

    private fun setupMetricCards() {
        val cyanColor = ContextCompat.getColor(this, R.color.pro_cyan)
        val magentaColor = ContextCompat.getColor(this, R.color.pro_magenta)

        doseCard.setLabel("DOSE RATE")
        doseCard.setAccentColor(cyanColor)
        doseCard.setValueText("—")
        doseCard.setTrend(0f)

        cpsCard.setLabel("COUNT RATE")
        cpsCard.setAccentColor(magentaColor)
        cpsCard.setValueText("—")
        cpsCard.setTrend(0f)

        doseCard.setOnClickListener { openFocus("dose") }
        cpsCard.setOnClickListener { openFocus("cps") }
    }

    private fun setupCharts() {
        val cyanColor = ContextCompat.getColor(this, R.color.pro_cyan)
        val magentaColor = ContextCompat.getColor(this, R.color.pro_magenta)

        doseChart.setAccentColor(cyanColor)
        cpsChart.setAccentColor(magentaColor)

        // Enable rolling average line on charts
        doseChart.setRollingAverageWindow(10)
        cpsChart.setRollingAverageWindow(10)

        doseChart.setOnClickListener { openFocus("dose") }
        cpsChart.setOnClickListener { openFocus("cps") }
    }

    private fun setupTimeWindowChips() {
        // Map chip IDs to window seconds
        val chipToSeconds = mapOf(
            R.id.chip30s to 30,
            R.id.chip1m to 60,
            R.id.chip5m to 300,
            R.id.chip15m to 900,
            R.id.chip1h to 3600,
            R.id.chipAll to Int.MAX_VALUE
        )

        // Set initial chip selection based on saved preference
        val currentWindow = Prefs.getWindowSeconds(this, 60)
        when (currentWindow) {
            30 -> findViewById<Chip>(R.id.chip30s).isChecked = true
            60 -> findViewById<Chip>(R.id.chip1m).isChecked = true
            300 -> findViewById<Chip>(R.id.chip5m).isChecked = true
            900 -> findViewById<Chip>(R.id.chip15m).isChecked = true
            3600 -> findViewById<Chip>(R.id.chip1h).isChecked = true
            else -> findViewById<Chip>(R.id.chipAll).isChecked = true
        }

        timeWindowChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chipId = checkedIds.first()
            val seconds = chipToSeconds[chipId] ?: 60
            
            Prefs.setWindowSeconds(this, if (seconds == Int.MAX_VALUE) 86400 else seconds)
            refreshSettingsRows()
            updateChartTitles()
            lastReadingTimestampMs = 0L
        }
    }

    private fun setupUnitChips() {
        // Set initial chip selection based on saved preference
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

    override fun onResume() {
        super.onResume()
        registerReadingReceiver()
        startUiLoop()
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

    @Suppress("UNUSED")
    private fun isLocationEnabledForLegacyBle(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) return true
        val lm = getSystemService(LocationManager::class.java) ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Throwable) {
            false
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
        if (!file.exists() || file.length() == 0L) {
            return
        }

        val uri = try {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        } catch (_: Throwable) {
            return
        }

        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(share, "Share readings"))
    }

    private fun startServiceIfConfigured() {
        val preferred = Prefs.getPreferredAddress(this)
        if (Prefs.isAutoConnectEnabled(this) && !preferred.isNullOrBlank()) {
            RadiaCodeForegroundService.start(this)
        }
        refreshSettingsRows()
        updateChartTitles()

        ensureHistoryCapacity()
        Prefs.getLastReading(this)?.let {
            doseHistory.add(it.timestampMs, it.uSvPerHour)
            cpsHistory.add(it.timestampMs, it.cps)
        }
        startUiLoop()
    }

    private fun startUiLoop() {
        if (uiRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                val preferred = Prefs.getPreferredAddress(this@MainActivity)
                val auto = Prefs.isAutoConnectEnabled(this@MainActivity)
                val last = Prefs.getLastReading(this@MainActivity)
                val svc = Prefs.getServiceStatus(this@MainActivity)
                val paused = Prefs.isPauseLiveEnabled(this@MainActivity)

                // Update device panel
                preferredDeviceText.text = if (preferred.isNullOrBlank()) "Not set" else preferred
                if (autoConnectSwitch.isChecked != auto) {
                    autoConnectSwitch.isChecked = auto
                }

                // Connection status - check both status message AND if we're receiving fresh data
                val hasRecentData = last != null && (System.currentTimeMillis() - last.timestampMs) < 10000
                val statusIndicatesConnected = svc?.message?.contains("connected", ignoreCase = true) == true
                val statusIndicatesLiveData = svc?.message?.contains("μSv/h", ignoreCase = true) == true
                val isConnected = hasRecentData || statusIndicatesConnected || statusIndicatesLiveData
                updateConnectionStatus(isConnected, svc?.message ?: "Disconnected")

                // Toolbar status
                val statusText = when {
                    !auto -> "OFF"
                    preferred.isNullOrBlank() -> "NO DEVICE"
                    paused -> "PAUSED"
                    isConnected -> "LIVE"
                    else -> "CONNECTING"
                }
                updateStatus(isConnected && !paused, statusText)

                val shouldUpdateReading = !paused
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

                if (last != null && !paused && last.timestampMs != lastReadingTimestampMs) {
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

        // Calculate trends
        val doseTrend = if (previousDose > 0f) ((dose - previousDose) / previousDose) * 100f else 0f
        val cpsTrend = if (previousCps > 0f) ((cpsOrCpm - previousCps) / previousCps) * 100f else 0f
        previousDose = dose
        previousCps = cpsOrCpm

        mainHandler.post {
            doseCard.setLabel("DOSE RATE · $doseUnit")
            doseCard.setValue(dose, doseUnit)
            doseCard.setTrend(doseTrend)

            cpsCard.setLabel("COUNT RATE · $cpsUnit")
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

        val doseThresholdUsvH = Prefs.getDoseThresholdUsvH(this, 0f)
        val doseThresholdConverted = if (doseThresholdUsvH > 0f) {
            when (du) {
                Prefs.DoseUnit.USV_H -> doseThresholdUsvH
                Prefs.DoseUnit.NSV_H -> doseThresholdUsvH * 1000.0f
            }
        } else {
            Float.NaN
        }

        mainHandler.post {
            doseChart.setSeries(doseDec.first, doseDec.second)
            cpsChart.setSeries(cpsDec.first, cpsDec.second)

            doseChart.setThreshold(if (doseThresholdConverted.isFinite()) doseThresholdConverted else null)
            cpsChart.setThreshold(null)

            // Mini sparklines for cards (last ~20 points)
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
            86400 -> "All"
            else -> "${windowSeconds}s"
        }
        
        val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
        val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
        val doseUnit = doseUnitLabel(du)
        val countUnit = countUnitLabel(cu)
        
        doseChartTitle.text = "DOSE RATE ($doseUnit) — Last $label"
        cpsChartTitle.text = "COUNT RATE ($countUnit) — Last $label"
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

        val tUsvH = Prefs.getDoseThresholdUsvH(this, 0f)
        valueThreshold.text = if (tUsvH <= 0f) {
            "Off"
        } else {
            val (value, unit) = when (du) {
                Prefs.DoseUnit.USV_H -> tUsvH to "μSv/h"
                Prefs.DoseUnit.NSV_H -> (tUsvH * 1000.0f) to "nSv/h"
            }
            val fmt = if (unit == "nSv/h") "%.0f" else "%.2f"
            String.format(Locale.US, "$fmt %s", value, unit)
        }

        valuePause.text = if (Prefs.isPauseLiveEnabled(this)) "On" else "Off"
    }

    private enum class Panel {
        Dashboard,
        Device,
        Settings,
        Logs,
    }

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

    private fun openFocus(kind: String) {
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

        val i = android.content.Intent(this, ChartFocusActivity::class.java)
            .putExtra(ChartFocusActivity.EXTRA_KIND, kind)
            .putExtra("ts", ts.toLongArray())
            .putExtra("v", v.toFloatArray())
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
        } catch (_: Throwable) {
        }
    }

    private fun unregisterReadingReceiver() {
        try {
            unregisterReceiver(readingReceiver)
        } catch (_: Throwable) {
        }
    }
}
