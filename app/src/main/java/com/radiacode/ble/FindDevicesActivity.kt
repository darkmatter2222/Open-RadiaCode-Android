package com.radiacode.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class FindDevicesActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 200
        private const val SCAN_TIMEOUT_MS = 12_000L

        const val EXTRA_DEVICE_ADDRESS = "device_address"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: TextView
    private lateinit var deviceList: ListView
    private lateinit var rescanButton: MaterialButton

    private val mainHandler = Handler(Looper.getMainLooper())

    private data class SeenDevice(
        val device: android.bluetooth.BluetoothDevice,
        var name: String,
        var rssi: Int,
        var lastSeenMs: Long,
    )

    private val seenByAddress = LinkedHashMap<String, SeenDevice>()
    private val deviceLines = ArrayList<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var isScanning: Boolean = false
    private var scanTimeoutRunnable: Runnable? = null

    private val requiredPermissions: Array<String>
        get() {
            return if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_find_devices)

        toolbar = findViewById(R.id.toolbar)
        statusText = findViewById(R.id.statusText)
        deviceList = findViewById(R.id.deviceList)
        rescanButton = findViewById(R.id.rescanButton)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceLines)
        deviceList.adapter = deviceAdapter

        deviceList.setOnItemClickListener { _, _, position, _ ->
            val line = deviceLines.getOrNull(position) ?: return@setOnItemClickListener
            val address = line.substringAfterLast("(").substringBefore(")").trim()
            val seen = seenByAddress[address] ?: return@setOnItemClickListener

            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_DEVICE_ADDRESS, seen.device.address)
            )
            finish()
        }

        rescanButton.setOnClickListener { startScan() }

        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
        } else {
            startScan()
        }
    }

    override fun onPause() {
        super.onPause()
        stopScan()
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
            startScan()
        } else {
            statusText.text = "Missing Bluetooth permissions"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
            return
        }

        if (!isLocationEnabledForLegacyBle()) {
            statusText.text = "Turn on Location (required for BLE scan on Android 11 and below)"
            return
        }

        val btManager = getSystemService(BluetoothManager::class.java)
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Bluetooth is off"
            return
        }

        if (isScanning) {
            statusText.text = "Already scanning…"
            return
        }

        seenByAddress.clear()
        deviceLines.clear()
        deviceLines.add("Scanning…")
        deviceAdapter.notifyDataSetChanged()

        statusText.text = "Scanning…"

        val scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (se: SecurityException) {
            statusText.text = "Scan blocked by permissions"
            return
        } catch (t: Throwable) {
            statusText.text = "Scan failed: ${t.javaClass.simpleName}"
            return
        }

        isScanning = true

        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = Runnable { stopScan() }.also { mainHandler.postDelayed(it, SCAN_TIMEOUT_MS) }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return

        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null

        val btManager = getSystemService(BluetoothManager::class.java)
        val adapter = btManager.adapter
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Throwable) {
        }

        isScanning = false

        statusText.text = if (seenByAddress.isEmpty()) {
            "No devices found"
        } else {
            "Tap a device to connect"
        }

        if (seenByAddress.isEmpty()) {
            deviceLines.clear()
            deviceLines.add("No devices found. Make sure RadiaCode is on and nearby.")
            deviceAdapter.notifyDataSetChanged()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            statusText.text = "Scan failed: $errorCode"
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val name = device.name ?: result.scanRecord?.deviceName ?: "(unknown)"
        val rssi = result.rssi

        val now = System.currentTimeMillis()
        val entry = seenByAddress[address]
        if (entry == null) {
            seenByAddress[address] = SeenDevice(device = device, name = name, rssi = rssi, lastSeenMs = now)
        } else {
            entry.name = name
            entry.rssi = rssi
            entry.lastSeenMs = now
        }

        renderDeviceList()
    }

    private fun renderDeviceList() {
        mainHandler.post {
            val items = seenByAddress.values
                .sortedWith(
                    compareByDescending<SeenDevice> { it.name.startsWith("RadiaCode", ignoreCase = true) }
                        .thenByDescending { it.rssi }
                )

            deviceLines.clear()
            items.forEach { d ->
                val label = d.name
                deviceLines.add("$label  RSSI ${d.rssi}  (${d.device.address})")
            }
            deviceAdapter.notifyDataSetChanged()
        }
    }
}
