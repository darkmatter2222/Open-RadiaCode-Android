package com.radiacode.ble

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
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

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: TextView
    private lateinit var serviceStatusText: TextView
    private lateinit var doseText: TextView
    private lateinit var cpsText: TextView
    private lateinit var readingText: TextView
    private lateinit var doseChart: SparklineView
    private lateinit var chartStatsText: TextView

    private lateinit var cpsChart: SparklineView
    private lateinit var cpsChartStatsText: TextView
    private lateinit var doseChartTitle: TextView
    private lateinit var cpsChartTitle: TextView

    private lateinit var autoConnectSwitch: SwitchMaterial
    private lateinit var pauseSwitch: SwitchMaterial
    private lateinit var compactSwitch: SwitchMaterial
    private lateinit var pollIntervalButton: MaterialButton
    private lateinit var windowButton: MaterialButton
    private lateinit var smoothingButton: MaterialButton
    private lateinit var unitButton: MaterialButton
    private lateinit var findDevicesButton: MaterialButton
    private lateinit var reconnectButton: MaterialButton
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

            val ts = intent.getLongExtra(RadiaCodeForegroundService.EXTRA_TS_MS, 0L)
            val uSvH = intent.getFloatExtra(RadiaCodeForegroundService.EXTRA_USV_H, Float.NaN)
            val cps = intent.getFloatExtra(RadiaCodeForegroundService.EXTRA_CPS, Float.NaN)
            if (ts <= 0L || !uSvH.isFinite() || !cps.isFinite()) return

            ensureHistoryCapacity()
            doseHistory.add(ts, uSvH)
            cpsHistory.add(ts, cps)

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

        toolbar = findViewById(R.id.toolbar)
        statusText = findViewById(R.id.statusText)
        serviceStatusText = findViewById(R.id.serviceStatusText)
        doseText = findViewById(R.id.doseText)
        cpsText = findViewById(R.id.cpsText)
        readingText = findViewById(R.id.readingText)
        doseChart = findViewById(R.id.doseChart)
        chartStatsText = findViewById(R.id.chartStatsText)

        cpsChart = findViewById(R.id.cpsChart)
        cpsChartStatsText = findViewById(R.id.cpsChartStatsText)
        doseChartTitle = findViewById(R.id.doseChartTitle)
        cpsChartTitle = findViewById(R.id.cpsChartTitle)

        autoConnectSwitch = findViewById(R.id.autoConnectSwitch)
        pauseSwitch = findViewById(R.id.pauseSwitch)
        compactSwitch = findViewById(R.id.compactSwitch)
        pollIntervalButton = findViewById(R.id.pollIntervalButton)
        windowButton = findViewById(R.id.windowButton)
        smoothingButton = findViewById(R.id.smoothingButton)
        unitButton = findViewById(R.id.unitButton)
        findDevicesButton = findViewById(R.id.findDevicesButton)
        reconnectButton = findViewById(R.id.reconnectButton)
        shareCsvButton = findViewById(R.id.shareCsvButton)

        setSupportActionBar(toolbar)

        updateStatus("Starting")

        autoConnectSwitch.isChecked = Prefs.isAutoConnectEnabled(this)
        autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAutoConnectEnabled(this, isChecked)
            if (isChecked) {
                RadiaCodeForegroundService.start(this)
                updateStatus("Auto-connect enabled")
            } else {
                RadiaCodeForegroundService.stop(this)
                updateStatus("Auto-connect disabled")
            }
        }

        pollIntervalButton.setOnClickListener {
            val next = when (Prefs.getPollIntervalMs(this, 1000L)) {
                1000L -> 2000L
                2000L -> 5000L
                else -> 1000L
            }
            Prefs.setPollIntervalMs(this, next)
            RadiaCodeForegroundService.reconnect(this)
            updatePollIntervalUi(next)
        }

        pauseSwitch.isChecked = Prefs.isPauseLiveEnabled(this)
        pauseSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setPauseLiveEnabled(this, isChecked)
            if (isChecked) {
                pausedSnapshotDose = currentWindowSeriesDose()
                pausedSnapshotCps = currentWindowSeriesCps()
                updateStatus("Paused")
            } else {
                pausedSnapshotDose = null
                pausedSnapshotCps = null
                updateStatus("Live")
            }
            lastReadingTimestampMs = 0L
        }

        compactSwitch.isChecked = Prefs.isCompactLayoutEnabled(this)
        compactSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setCompactLayoutEnabled(this, isChecked)
            applyCompactUi(isChecked)
            lastReadingTimestampMs = 0L
        }

        windowButton.setOnClickListener {
            val next = when (Prefs.getWindowSeconds(this, 60)) {
                10 -> 60
                60 -> 600
                600 -> 3600
                else -> 10
            }
            Prefs.setWindowSeconds(this, next)
            updateWindowUi(next)
            lastReadingTimestampMs = 0L
        }

        smoothingButton.setOnClickListener {
            val next = when (Prefs.getSmoothSeconds(this, 0)) {
                0 -> 5
                5 -> 30
                else -> 0
            }
            Prefs.setSmoothSeconds(this, next)
            updateSmoothingUi(next)
            lastReadingTimestampMs = 0L
        }

        unitButton.setOnClickListener {
            // Cycle through 4 combos.
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
            updateUnitsUi(next.first, next.second)
            lastReadingTimestampMs = 0L
        }

        findDevicesButton.setOnClickListener {
            findDevicesLauncher.launch(android.content.Intent(this, FindDevicesActivity::class.java))
        }

        reconnectButton.setOnClickListener {
            RadiaCodeForegroundService.reconnect(this)
            updateStatus("Reconnect requested")
        }

        shareCsvButton.setOnClickListener {
            shareCsv()
        }

        doseChart.setOnClickListener {
            openFocus("dose")
        }
        cpsChart.setOnClickListener {
            openFocus("cps")
        }

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

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        // Reflect poll interval selection.
        val poll = Prefs.getPollIntervalMs(this, 1000L)
        when (poll) {
            1000L -> menu.findItem(R.id.action_poll_1s)?.isChecked = true
            2000L -> menu.findItem(R.id.action_poll_2s)?.isChecked = true
            5000L -> menu.findItem(R.id.action_poll_5s)?.isChecked = true
        }
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_find_devices -> {
                findDevicesLauncher.launch(android.content.Intent(this, FindDevicesActivity::class.java))
                true
            }

            R.id.action_toggle_autoconnect -> {
                val enabled = !Prefs.isAutoConnectEnabled(this)
                Prefs.setAutoConnectEnabled(this, enabled)
                if (enabled) {
                    RadiaCodeForegroundService.start(this)
                    updateStatus("Auto-connect enabled")
                } else {
                    RadiaCodeForegroundService.stop(this)
                    updateStatus("Auto-connect disabled")
                }
                true
            }

            R.id.action_reconnect -> {
                RadiaCodeForegroundService.reconnect(this)
                updateStatus("Reconnect requested")
                true
            }

            R.id.action_stop_service -> {
                RadiaCodeForegroundService.stop(this)
                updateStatus("Stopping service")
                true
            }

            R.id.action_poll_1s, R.id.action_poll_2s, R.id.action_poll_5s -> {
                val newMs = when (item.itemId) {
                    R.id.action_poll_1s -> 1000L
                    R.id.action_poll_2s -> 2000L
                    else -> 5000L
                }
                Prefs.setPollIntervalMs(this, newMs)
                item.isChecked = true
                RadiaCodeForegroundService.reconnect(this)
                updateStatus("Poll interval set: ${newMs / 1000}s")
                true
            }

            R.id.action_share_csv -> {
                shareCsv()
                true
            }

            else -> super.onOptionsItemSelected(item)
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
        updatePollIntervalUi(Prefs.getPollIntervalMs(this, 1000L))
        updateWindowUi(Prefs.getWindowSeconds(this, 60))
        updateSmoothingUi(Prefs.getSmoothSeconds(this, 0))
        updateUnitsUi(Prefs.getDoseUnit(this), Prefs.getCountUnit(this))
        pauseSwitch.isChecked = Prefs.isPauseLiveEnabled(this)
        applyCompactUi(Prefs.isCompactLayoutEnabled(this))

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

                updateStatus(
                    when {
                        !auto -> "Auto-connect disabled"
                        preferred.isNullOrBlank() -> "No preferred device (menu → Find devices)"
                        paused -> "Paused"
                        else -> "Preferred: $preferred"
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
                    updateDose(null)
                    updateCps(null)
                    updateReading("Waiting for readings…")
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
            statusText.text = "Status: $msg"
        }
    }

    private fun updateDose(uSvPerHour: Float?) {
        mainHandler.post {
            val unit = when (Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)) {
                Prefs.DoseUnit.USV_H -> "μSv/h"
                Prefs.DoseUnit.NSV_H -> "nSv/h"
            }
            doseText.text = if (uSvPerHour == null) {
                "— $unit"
            } else {
                String.format(Locale.US, "%.3f %s", uSvPerHour, unit)
            }
        }
    }

    private fun updateCps(cps: Float?) {
        mainHandler.post {
            val unit = when (Prefs.getCountUnit(this, Prefs.CountUnit.CPS)) {
                Prefs.CountUnit.CPS -> "cps"
                Prefs.CountUnit.CPM -> "cpm"
            }
            cpsText.text = if (cps == null) {
                "— $unit"
            } else {
                val fmt = if (unit == "cpm") "%.0f" else "%.1f"
                String.format(Locale.US, "$fmt %s", cps, unit)
            }
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

        updateDose(dose)
        updateCps(cpsOrCpm)

        val localTime = Instant.ofEpochMilli(last.timestampMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        val ageSec = max(0L, (System.currentTimeMillis() - last.timestampMs) / 1000L)
        updateReading("Updated: ${timeFmt.format(localTime)} • ${ageSec}s ago")
    }

    private fun updateCharts(dose: SampleHistory.Series?, cps: SampleHistory.Series?) {
        val compact = Prefs.isCompactLayoutEnabled(this)
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
        }

        updateChartStats(doseDec.second)
        updateCpsChartStats(cpsDec.second)

        if (!compact) {
            // Titles already updated by updateWindowUi/updateUnitsUi; keep as-is.
        }
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

    private fun updateReading(msg: String) {
        mainHandler.post {
            readingText.text = msg
        }
    }

    private fun updateServiceStatus(status: Prefs.ServiceStatus?) {
        mainHandler.post {
            if (status == null) {
                serviceStatusText.text = "Service: —"
            } else {
                val ageSec = max(0L, (System.currentTimeMillis() - status.timestampMs) / 1000L)
                serviceStatusText.text = "Service: ${status.message} • ${ageSec}s ago"
            }
        }
    }

    private fun updatePollIntervalUi(intervalMs: Long) {
        val s = when (intervalMs) {
            1000L -> "1s"
            2000L -> "2s"
            5000L -> "5s"
            else -> "${intervalMs}ms"
        }
        pollIntervalButton.text = "Poll interval: $s"
    }

    private fun updateWindowUi(windowSeconds: Int) {
        val label = when (windowSeconds) {
            10 -> "10s"
            60 -> "1m"
            600 -> "10m"
            3600 -> "1h"
            else -> "${windowSeconds}s"
        }
        windowButton.text = "Window: $label"

        val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
        val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
        doseChartTitle.text = "Last $label (${doseUnitLabel(du)})"
        cpsChartTitle.text = "Last $label (${countUnitLabel(cu)})"
    }

    private fun updateSmoothingUi(seconds: Int) {
        smoothingButton.text = when (seconds) {
            0 -> "Smoothing: off"
            else -> "Smoothing: ${seconds}s"
        }
    }

    private fun updateUnitsUi(du: Prefs.DoseUnit, cu: Prefs.CountUnit) {
        unitButton.text = "Units: ${doseUnitLabel(du)} & ${countUnitLabel(cu)}"

        val label = when (Prefs.getWindowSeconds(this, 60)) {
            10 -> "10s"
            60 -> "1m"
            600 -> "10m"
            3600 -> "1h"
            else -> "${Prefs.getWindowSeconds(this, 60)}s"
        }
        doseChartTitle.text = "Last $label (${doseUnitLabel(du)})"
        cpsChartTitle.text = "Last $label (${countUnitLabel(cu)})"
    }

    private fun doseUnitLabel(du: Prefs.DoseUnit): String = when (du) {
        Prefs.DoseUnit.USV_H -> "μSv/h"
        Prefs.DoseUnit.NSV_H -> "nSv/h"
    }

    private fun countUnitLabel(cu: Prefs.CountUnit): String = when (cu) {
        Prefs.CountUnit.CPS -> "cps"
        Prefs.CountUnit.CPM -> "cpm"
    }

    private fun applyCompactUi(compact: Boolean) {
        mainHandler.post {
            chartStatsText.visibility = if (compact) android.view.View.GONE else android.view.View.VISIBLE
            cpsChartStatsText.visibility = if (compact) android.view.View.GONE else android.view.View.VISIBLE
        }
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
