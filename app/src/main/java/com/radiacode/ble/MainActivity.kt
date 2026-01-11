package com.radiacode.ble

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "RadiaCode"

        private const val MAX_CHART_POINTS = 800
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: MaterialToolbar

    private lateinit var panelDashboard: android.view.View
    private lateinit var panelCharts: android.view.View
    private lateinit var panelDevice: android.view.View
    private lateinit var panelDisplay: android.view.View
    private lateinit var panelLogs: android.view.View

    private lateinit var statusLine: TextView
    private lateinit var serviceLine: TextView
    private lateinit var primaryDoseText: TextView
    private lateinit var primaryCountText: TextView
    private lateinit var ageText: TextView

    private lateinit var dashboardDoseChart: SparklineView

    private lateinit var doseChart: SparklineView
    private lateinit var chartStatsText: TextView
    private lateinit var cpsChart: SparklineView
    private lateinit var cpsChartStatsText: TextView
    private lateinit var doseChartTitle: TextView
    private lateinit var cpsChartTitle: TextView

    private lateinit var preferredDeviceText: TextView
    private lateinit var autoConnectSwitch: SwitchMaterial
    private lateinit var findDevicesButton: MaterialButton
    private lateinit var reconnectButton: MaterialButton
    private lateinit var stopServiceButton: MaterialButton

    private lateinit var rowWindow: android.view.View
    private lateinit var rowSmoothing: android.view.View
    private lateinit var rowUnits: android.view.View
    private lateinit var rowThreshold: android.view.View
    private lateinit var rowPause: android.view.View

    private lateinit var valueWindow: TextView
    private lateinit var valueSmoothing: TextView
    private lateinit var valueUnits: TextView
    private lateinit var valueThreshold: TextView
    private lateinit var valuePause: TextView

    private lateinit var shareCsvButton: MaterialButton

    private val mainHandler = Handler(Looper.getMainLooper())

    private var uiRunnable: Runnable? = null
    private var lastReadingTimestampMs: Long = 0L

    private val doseHistory = SampleHistory(4000)
    private val cpsHistory = SampleHistory(4000)

    private var pausedSnapshotDose: SampleHistory.Series? = null
    private var pausedSnapshotCps: SampleHistory.Series? = null

    private var lastShownReading: Prefs.LastReading? = null

    private val readingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != RadiaCodeForegroundService.ACTION_READING) return

            val paused = Prefs.isPauseLiveEnabled(this@MainActivity)
            if (paused) return

            // Trigger UI loop fast-path.
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
        updateCharts(SampleHistory.Series(emptyList(), emptyList()), SampleHistory.Series(emptyList(), emptyList()))
        updateStatus("Preferred device set: $address")
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

        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        panelDashboard = findViewById(R.id.panelDashboard)
        panelCharts = findViewById(R.id.panelCharts)
        panelDevice = findViewById(R.id.panelDevice)
        panelDisplay = findViewById(R.id.panelDisplay)
        panelLogs = findViewById(R.id.panelLogs)

        statusLine = findViewById(R.id.statusLine)
        serviceLine = findViewById(R.id.serviceLine)
        primaryDoseText = findViewById(R.id.primaryDoseText)
        primaryCountText = findViewById(R.id.primaryCountText)
        ageText = findViewById(R.id.ageText)

        dashboardDoseChart = findViewById(R.id.dashboardDoseChart)

        doseChart = findViewById(R.id.doseChart)
        chartStatsText = findViewById(R.id.chartStatsText)
        cpsChart = findViewById(R.id.cpsChart)
        cpsChartStatsText = findViewById(R.id.cpsChartStatsText)
        doseChartTitle = findViewById(R.id.doseChartTitle)
        cpsChartTitle = findViewById(R.id.cpsChartTitle)

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
                R.id.nav_charts -> setPanel(Panel.Charts)
                R.id.nav_device -> setPanel(Panel.Device)
                R.id.nav_display -> setPanel(Panel.Display)
                R.id.nav_logs -> setPanel(Panel.Logs)
            }
            item.isChecked = true
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        navView.setCheckedItem(R.id.nav_dashboard)
        setPanel(Panel.Dashboard)

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

        shareCsvButton.setOnClickListener { shareCsv() }

        rowWindow.setOnClickListener {
            val next = when (Prefs.getWindowSeconds(this, 60)) {
                10 -> 60
                60 -> 600
                600 -> 3600
                else -> 10
            }
            Prefs.setWindowSeconds(this, next)
            refreshDisplayRows()
            updateChartsTitles()
            lastReadingTimestampMs = 0L
        }

        rowSmoothing.setOnClickListener {
            val next = when (Prefs.getSmoothSeconds(this, 0)) {
                0 -> 5
                5 -> 30
                else -> 0
            }
            Prefs.setSmoothSeconds(this, next)
            refreshDisplayRows()
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
            refreshDisplayRows()
            updateChartsTitles()
            lastReadingTimestampMs = 0L
        }

        rowThreshold.setOnClickListener {
            val presets = floatArrayOf(0.0f, 0.05f, 0.10f, 0.50f, 1.00f)
            val cur = Prefs.getDoseThresholdUsvH(this, 0f)
            val idx = presets.indexOfFirst { kotlin.math.abs(it - cur) < 1e-6f }.let { if (it < 0) 0 else it }
            Prefs.setDoseThresholdUsvH(this, presets[(idx + 1) % presets.size])
            refreshDisplayRows()
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
            refreshDisplayRows()
            lastReadingTimestampMs = 0L
        }

        dashboardDoseChart.setOnClickListener { openFocus("dose") }
        doseChart.setOnClickListener { openFocus("dose") }
        cpsChart.setOnClickListener { openFocus("cps") }

        refreshDisplayRows()
        updateChartsTitles()

        updateStatus("Starting")

        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
        } else {
            startServiceIfConfigured()
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
            updateStatus("Missing Bluetooth permissions")
        }
    }

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
            updateStatus("No CSV yet (waiting for readings)")
            return
        }

        val uri = try {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        } catch (t: Throwable) {
            updateStatus("Share failed: FileProvider")
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
        refreshDisplayRows()
        updateChartsTitles()

        ensureHistoryCapacity()
        Prefs.getLastReading(this)?.let {
            // Seed history if empty.
            doseHistory.add(it.timestampMs, it.uSvPerHour)
            cpsHistory.add(it.timestampMs, it.cps)
        }
        startUiLoop()
    }

    private fun startUiLoop() {
        if (uiRunnable != null) return
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.US)
        val r = object : Runnable {
            override fun run() {
                val preferred = Prefs.getPreferredAddress(this@MainActivity)
                val auto = Prefs.isAutoConnectEnabled(this@MainActivity)
                val last = Prefs.getLastReading(this@MainActivity)
                val svc = Prefs.getServiceStatus(this@MainActivity)
                val paused = Prefs.isPauseLiveEnabled(this@MainActivity)

                preferredDeviceText.text = if (preferred.isNullOrBlank()) {
                    "Preferred device: —"
                } else {
                    "Preferred device: $preferred"
                }

                updateStatus(
                    when {
                        !auto -> "Auto-connect: off"
                        preferred.isNullOrBlank() -> "Pick a device in Device"
                        paused -> "Paused"
                        else -> "Live"
                    }
                )

                updateServiceStatus(svc)

                if (autoConnectSwitch.isChecked != auto) {
                    autoConnectSwitch.isChecked = auto
                }

                val shouldUpdateReading = !paused
                if (!shouldUpdateReading) {
                    // Freeze cards at last shown.
                    val frozen = lastShownReading
                    if (frozen != null) {
                        updateReadingCards(frozen, timeFmt)
                    }
                } else if (last == null) {
                    showEmptyPrimary()
                    updateChartStats(emptyList())
                    updateCpsChartStats(emptyList())
                } else {
                    lastShownReading = last
                    updateReadingCards(last, timeFmt)
                }

                if (last != null && !paused && last.timestampMs != lastReadingTimestampMs) {
                    lastReadingTimestampMs = last.timestampMs
                    ensureHistoryCapacity()
                    doseHistory.add(last.timestampMs, last.uSvPerHour)
                    cpsHistory.add(last.timestampMs, last.cps)
                }

                if (paused && (pausedSnapshotDose == null || pausedSnapshotCps == null)) {
                    pausedSnapshotDose = currentWindowSeriesDose()
                    pausedSnapshotCps = currentWindowSeriesCps()
                }
                val doseSeries = if (paused) pausedSnapshotDose else currentWindowSeriesDose()
                val cpsSeries = if (paused) pausedSnapshotCps else currentWindowSeriesCps()
                updateCharts(doseSeries, cpsSeries)

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

    private fun updateStatus(msg: String) {
        mainHandler.post {
            statusLine.text = msg
        }
    }

    private fun showEmptyPrimary() {
        mainHandler.post {
            primaryDoseText.text = "—"
            primaryCountText.text = "—"
            ageText.text = "Waiting for readings…"
        }
    }

    private fun updateReadingCards(last: Prefs.LastReading, timeFmt: DateTimeFormatter) {
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

        val localTime = Instant.ofEpochMilli(last.timestampMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        val ageSec = max(0L, (System.currentTimeMillis() - last.timestampMs) / 1000L)

        mainHandler.post {
            primaryDoseText.text = when (du) {
                Prefs.DoseUnit.USV_H -> String.format(Locale.US, "%.3f μSv/h", dose)
                Prefs.DoseUnit.NSV_H -> String.format(Locale.US, "%.0f nSv/h", dose)
            }

            primaryCountText.text = when (cu) {
                Prefs.CountUnit.CPS -> String.format(Locale.US, "%.1f cps", cpsOrCpm)
                Prefs.CountUnit.CPM -> String.format(Locale.US, "%.0f cpm", cpsOrCpm)
            }

            ageText.text = "${timeFmt.format(localTime)} • ${ageSec}s ago"
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

            // A compact trend on the dashboard.
            dashboardDoseChart.setSeries(doseDec.first, doseDec.second)

            doseChart.setThreshold(if (doseThresholdConverted.isFinite()) doseThresholdConverted else null)
            cpsChart.setThreshold(null)

            dashboardDoseChart.setThreshold(if (doseThresholdConverted.isFinite()) doseThresholdConverted else null)
        }

        updateChartStats(doseDec.second)
        updateCpsChartStats(cpsDec.second)

    }

    private fun updateCpsChartStats(samples: List<Float>) {
        mainHandler.post {
            if (samples.isEmpty()) {
                cpsChartStatsText.text = ""
                return@post
            }
            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY
            var sum = 0.0f
            for (v in samples) {
                minV = min(minV, v)
                maxV = max(maxV, v)
                sum += v
            }
            val avg = sum / max(1, samples.size)
            val unit = when (Prefs.getCountUnit(this, Prefs.CountUnit.CPS)) {
                Prefs.CountUnit.CPS -> "cps"
                Prefs.CountUnit.CPM -> "cpm"
            }
            cpsChartStatsText.text = String.format(Locale.US, "Min %.1f • Avg %.1f • Max %.1f %s", minV, avg, maxV, unit)
        }
    }

    private fun updateChartStats(samples: List<Float>) {
        mainHandler.post {
            if (samples.isEmpty()) {
                chartStatsText.text = ""
                return@post
            }
            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY
            var sum = 0.0f
            for (v in samples) {
                minV = min(minV, v)
                maxV = max(maxV, v)
                sum += v
            }
            val avg = sum / max(1, samples.size)
            val unit = when (Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)) {
                Prefs.DoseUnit.USV_H -> "μSv/h"
                Prefs.DoseUnit.NSV_H -> "nSv/h"
            }
            chartStatsText.text = String.format(Locale.US, "Min %.3f • Avg %.3f • Max %.3f %s", minV, avg, maxV, unit)
        }
    }

    private fun updateServiceStatus(status: Prefs.ServiceStatus?) {
        mainHandler.post {
            if (status == null) {
                serviceLine.text = "Service: —"
            } else {
                val ageSec = max(0L, (System.currentTimeMillis() - status.timestampMs) / 1000L)
                serviceLine.text = "Service: ${status.message} • ${ageSec}s ago"
            }
        }
    }

    private fun updateChartsTitles() {
        val windowSeconds = Prefs.getWindowSeconds(this, 60)
        val label = when (windowSeconds) {
            10 -> "10s"
            60 -> "1m"
            600 -> "10m"
            3600 -> "1h"
            else -> "${windowSeconds}s"
        }
        val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
        val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
        doseChartTitle.text = "Last $label (${doseUnitLabel(du)})"
        cpsChartTitle.text = "Last $label (${countUnitLabel(cu)})"
    }

    private fun refreshDisplayRows() {
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
        Charts,
        Device,
        Display,
        Logs,
    }

    private fun setPanel(panel: Panel) {
        panelDashboard.visibility = if (panel == Panel.Dashboard) android.view.View.VISIBLE else android.view.View.GONE
        panelCharts.visibility = if (panel == Panel.Charts) android.view.View.VISIBLE else android.view.View.GONE
        panelDevice.visibility = if (panel == Panel.Device) android.view.View.VISIBLE else android.view.View.GONE
        panelDisplay.visibility = if (panel == Panel.Display) android.view.View.VISIBLE else android.view.View.GONE
        panelLogs.visibility = if (panel == Panel.Logs) android.view.View.VISIBLE else android.view.View.GONE
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
