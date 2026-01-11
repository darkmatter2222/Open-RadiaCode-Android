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

        private const val HISTORY_SECONDS = 60
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: TextView
    private lateinit var serviceStatusText: TextView
    private lateinit var doseText: TextView
    private lateinit var cpsText: TextView
    private lateinit var readingText: TextView
    private lateinit var doseChart: SparklineView
    private lateinit var chartStatsText: TextView

    private lateinit var autoConnectSwitch: SwitchMaterial
    private lateinit var pollIntervalButton: MaterialButton
    private lateinit var findDevicesButton: MaterialButton
    private lateinit var reconnectButton: MaterialButton
    private lateinit var shareCsvButton: MaterialButton

    private val mainHandler = Handler(Looper.getMainLooper())

    private var uiRunnable: Runnable? = null
    private var lastReadingTimestampMs: Long = 0L

    private val doseHistory = ArrayDeque<Float>(HISTORY_SECONDS)

    private val findDevicesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val address = result.data?.getStringExtra(FindDevicesActivity.EXTRA_DEVICE_ADDRESS) ?: return@registerForActivityResult
        Prefs.setPreferredAddress(this, address)
        Prefs.setAutoConnectEnabled(this, true)
        RadiaCodeForegroundService.start(this, address)

        doseHistory.clear()
        lastReadingTimestampMs = 0L
        updateDoseChart(emptyList())
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

        autoConnectSwitch = findViewById(R.id.autoConnectSwitch)
        pollIntervalButton = findViewById(R.id.pollIntervalButton)
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

        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
        } else {
            startServiceIfConfigured()
        }
    }

    override fun onResume() {
        super.onResume()
        startUiLoop()
    }

    override fun onPause() {
        super.onPause()
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

                updateStatus(
                    when {
                        !auto -> "Auto-connect disabled"
                        preferred.isNullOrBlank() -> "No preferred device (menu → Find devices)"
                        else -> "Preferred: $preferred"
                    }
                )

                updateServiceStatus(svc)

                if (autoConnectSwitch.isChecked != auto) {
                    autoConnectSwitch.isChecked = auto
                }

                if (last == null) {
                    updateDose(null)
                    updateCps(null)
                    updateReading("Waiting for readings…")
                    updateChartStats(emptyList())
                } else {
                    updateDose(last.uSvPerHour)
                    updateCps(last.cps)
                    val localTime = Instant.ofEpochMilli(last.timestampMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalTime()
                    val ageSec = max(0L, (System.currentTimeMillis() - last.timestampMs) / 1000L)
                    updateReading("Updated: ${timeFmt.format(localTime)} • ${ageSec}s ago")

                    if (last.timestampMs != lastReadingTimestampMs) {
                        lastReadingTimestampMs = last.timestampMs
                        addDoseSample(last.uSvPerHour)
                        updateDoseChart(doseHistory.toList())
                        updateChartStats(doseHistory.toList())
                    }
                }

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
            doseText.text = if (uSvPerHour == null) {
                "— μSv/h"
            } else {
                String.format(Locale.US, "%.3f μSv/h", uSvPerHour)
            }
        }
    }

    private fun updateCps(cps: Float?) {
        mainHandler.post {
            cpsText.text = if (cps == null) {
                "— cps"
            } else {
                String.format(Locale.US, "%.1f cps", cps)
            }
        }
    }

    private fun addDoseSample(uSvPerHour: Float) {
        while (doseHistory.size >= HISTORY_SECONDS) {
            doseHistory.removeFirst()
        }
        doseHistory.addLast(uSvPerHour)
    }

    private fun updateDoseChart(samples: List<Float>) {
        mainHandler.post {
            doseChart.setSamples(samples)
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
            chartStatsText.text = String.format(Locale.US, "Min %.3f • Avg %.3f • Max %.3f μSv/h", minV, avg, maxV)
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
}
