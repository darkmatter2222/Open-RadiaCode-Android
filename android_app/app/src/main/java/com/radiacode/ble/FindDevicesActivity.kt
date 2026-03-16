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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private lateinit var deviceRecyclerView: RecyclerView
    private lateinit var rescanButton: MaterialButton

    private val mainHandler = Handler(Looper.getMainLooper())

    private data class SeenDevice(
        val device: android.bluetooth.BluetoothDevice,
        var name: String,
        var rssi: Int,
        var lastSeenMs: Long,
    )

    private val seenByAddress = LinkedHashMap<String, SeenDevice>()
    private lateinit var deviceAdapter: DeviceScanAdapter

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

    // ── RecyclerView Adapter ────────────────────────────────────────────

    private inner class DeviceScanAdapter(
        private val items: MutableList<SeenDevice> = mutableListOf(),
        private val onDeviceClick: (SeenDevice) -> Unit,
    ) : RecyclerView.Adapter<DeviceScanAdapter.DeviceViewHolder>() {

        inner class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.deviceName)
            val addressText: TextView = view.findViewById(R.id.deviceAddress)
            val rssiText: TextView = view.findViewById(R.id.rssiText)
            val signalBar: View = view.findViewById(R.id.signalBar)
        }

        fun submitList(newItems: List<SeenDevice>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_find_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = items[position]
            holder.nameText.text = device.name
            holder.addressText.text = device.device.address
            holder.rssiText.text = "${device.rssi}\ndBm"

            // Color signal bar based on RSSI strength
            val barColor = when {
                device.rssi >= -60 -> ContextCompat.getColor(holder.itemView.context, R.color.pro_green)
                device.rssi >= -80 -> ContextCompat.getColor(holder.itemView.context, R.color.pro_amber)
                else -> ContextCompat.getColor(holder.itemView.context, R.color.pro_red)
            }
            holder.signalBar.setBackgroundColor(barColor)

            holder.itemView.setOnClickListener { onDeviceClick(device) }
        }

        override fun getItemCount(): Int = items.size
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_find_devices)

        toolbar = findViewById(R.id.toolbar)
        statusText = findViewById(R.id.statusText)
        deviceRecyclerView = findViewById(R.id.deviceRecyclerView)
        rescanButton = findViewById(R.id.rescanButton)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        deviceAdapter = DeviceScanAdapter { selected ->
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_DEVICE_ADDRESS, selected.device.address),
            )
            finish()
        }
        deviceRecyclerView.layoutManager = LinearLayoutManager(this)
        deviceRecyclerView.adapter = deviceAdapter

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
        deviceAdapter.submitList(emptyList())

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

        // adapter already shows current state; status text handles empty messaging
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
            val sorted = seenByAddress.values
                .sortedWith(
                    compareByDescending<SeenDevice> { it.name.startsWith("RadiaCode", ignoreCase = true) }
                        .thenByDescending { it.rssi }
                )
            deviceAdapter.submitList(sorted)
        }
    }
}
