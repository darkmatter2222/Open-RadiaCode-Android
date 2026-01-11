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
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "RadiaCode"

        private const val HISTORY_SECONDS = 60
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: TextView
    private lateinit var doseText: TextView
    private lateinit var cpsText: TextView
    private lateinit var readingText: TextView
    private lateinit var doseChart: SparklineView

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
        doseText = findViewById(R.id.doseText)
        cpsText = findViewById(R.id.cpsText)
        readingText = findViewById(R.id.readingText)
        doseChart = findViewById(R.id.doseChart)

        setSupportActionBar(toolbar)

        updateStatus("Starting")

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

                updateStatus(
                    when {
                        !auto -> "Auto-connect disabled"
                        preferred.isNullOrBlank() -> "No preferred device (menu → Find devices)"
                        else -> "Preferred: $preferred"
                    }
                )

                if (last == null) {
                    updateDose(null)
                    updateCps(null)
                    updateReading("Waiting for readings…")
                } else {
                    updateDose(last.uSvPerHour)
                    updateCps(last.cps)
                    val localTime = Instant.ofEpochMilli(last.timestampMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalTime()
                    updateReading("Updated: ${timeFmt.format(localTime)}")

                    if (last.timestampMs != lastReadingTimestampMs) {
                        lastReadingTimestampMs = last.timestampMs
                        addDoseSample(last.uSvPerHour)
                        updateDoseChart(doseHistory.toList())
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

    private fun updateReading(msg: String) {
        mainHandler.post {
            readingText.text = msg
        }
    }
}
