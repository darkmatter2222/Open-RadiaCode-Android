package com.radiacode.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

class RadiaCodeForegroundService : Service() {

    companion object {
        private const val TAG = "RadiaCode"

        private const val CHANNEL_ID = "radiacode_live"
        private const val NOTIF_ID = 1

        private const val ACTION_START = "com.radiacode.ble.action.START"
        private const val ACTION_STOP = "com.radiacode.ble.action.STOP"
        private const val ACTION_RECONNECT = "com.radiacode.ble.action.RECONNECT"
        private const val ACTION_RELOAD_DEVICES = "com.radiacode.ble.action.RELOAD_DEVICES"

        const val ACTION_READING = "com.radiacode.ble.action.READING"
        const val ACTION_DEVICE_STATE_CHANGED = "com.radiacode.ble.action.DEVICE_STATE"
        const val EXTRA_TS_MS = "ts_ms"
        const val EXTRA_USV_H = "usv_h"
        const val EXTRA_CPS = "cps"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_CONNECTION_STATE = "connection_state"

        private const val EXTRA_ADDRESS = "address"

        fun start(context: Context, addressOverride: String? = null) {
            val intent = Intent(context, RadiaCodeForegroundService::class.java).setAction(ACTION_START)
            if (!addressOverride.isNullOrBlank()) intent.putExtra(EXTRA_ADDRESS, addressOverride)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RadiaCodeForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun reconnect(context: Context) {
            val intent = Intent(context, RadiaCodeForegroundService::class.java).setAction(ACTION_RECONNECT)
            context.startService(intent)
        }
        
        fun reloadDevices(context: Context) {
            val intent = Intent(context, RadiaCodeForegroundService::class.java).setAction(ACTION_RELOAD_DEVICES)
            context.startService(intent)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    // Multi-device manager
    private var deviceManager: MultiDeviceBleManager? = null

    // Alert evaluation
    private lateinit var alertEvaluator: AlertEvaluator
    
    // Track last CSV timestamp per device
    private val lastCsvTimestamps = mutableMapOf<String, Long>()

    private val btStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON) {
                Log.d(TAG, "btStateReceiver: STATE_ON")
                // Reconnect all enabled devices
                if (Prefs.isAutoConnectEnabled(this@RadiaCodeForegroundService)) {
                    deviceManager?.forceReconnectAll()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        alertEvaluator = AlertEvaluator(this)
        
        // Initialize multi-device manager
        deviceManager = MultiDeviceBleManager(
            context = applicationContext,
            onDeviceStateChanged = { state -> handleDeviceStateChanged(state) },
            onDeviceReading = { deviceId, uSvH, cps, ts -> handleDeviceReading(deviceId, uSvH, cps, ts) }
        )
        
        try {
            registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopInternal("Stopped")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                deviceManager?.forceReconnectAll()
                return START_STICKY
            }
            ACTION_RELOAD_DEVICES -> {
                deviceManager?.reloadDevices()
                updateNotificationFromState()
                return START_STICKY
            }
            ACTION_START, null -> {
                // fallthrough
            }
            else -> {
                // Unknown action; treat as start.
            }
        }

        Log.d(TAG, "service onStartCommand: action=${intent?.action}")

        if (!Prefs.isAutoConnectEnabled(this)) {
            Log.d(TAG, "service: auto-connect disabled, stopping")
            stopInternal("Auto-connect disabled")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasNotificationPermission()) {
            Log.w(TAG, "service: missing POST_NOTIFICATIONS permission; stopping")
            stopInternal("Notification permission missing; open app")
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (!hasBlePermission()) {
            Log.w(TAG, "service: missing BLUETOOTH_CONNECT permission; stopping")
            stopInternal("Bluetooth permission missing; open app")
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Handle legacy single-device start (for backward compatibility)
        val addressFromIntent = intent?.getStringExtra(EXTRA_ADDRESS)?.trim()?.takeIf { it.isNotEmpty() }
        if (!addressFromIntent.isNullOrBlank()) {
            // Add this device if not already configured
            val existingDevice = Prefs.getDeviceByMac(this, addressFromIntent)
            if (existingDevice == null) {
                val newDevice = DeviceConfig(
                    macAddress = addressFromIntent,
                    enabled = true
                )
                Prefs.addDevice(this, newDevice)
            }
        }

        // Check if we have any devices configured
        val devices = Prefs.getEnabledDevices(this)
        if (devices.isEmpty()) {
            Log.d(TAG, "service: no devices configured")
            updateForeground("No devices", "Open app and add a device")
            return START_STICKY
        }

        // Start the multi-device manager
        updateForeground("Connecting", "${devices.size} device(s)")
        deviceManager?.start()
        
        return START_STICKY
    }

    override fun onDestroy() {
        stopInternal("Service destroyed")
        try { unregisterReceiver(btStateReceiver) } catch (_: Throwable) {}
        try { scheduler.shutdownNow() } catch (_: Throwable) {}
        try { executor.shutdownNow() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun handleDeviceStateChanged(state: DeviceState) {
        // Broadcast state change for UI updates
        try {
            val i = Intent(ACTION_DEVICE_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_DEVICE_ID, state.config.id)
                .putExtra(EXTRA_CONNECTION_STATE, state.connectionState.name)
            sendBroadcast(i)
        } catch (_: Throwable) {}
        
        updateNotificationFromState()
    }
    
    private fun handleDeviceReading(deviceId: String, uSvPerHour: Float, cps: Float, timestampMs: Long) {
        val device = Prefs.getDeviceById(this, deviceId) ?: return
        
        // Append to device-specific CSV
        appendReadingCsvIfNew(deviceId, device.displayName, timestampMs, uSvPerHour, cps)
        
        // Evaluate smart alerts (using combined recent readings)
        try {
            val recentReadings = Prefs.getRecentReadings(this)
            alertEvaluator.evaluate(uSvPerHour, cps, recentReadings)
        } catch (t: Throwable) {
            Log.e(TAG, "Alert evaluation failed", t)
        }
        
        // Live update broadcast for in-app charts
        try {
            val i = Intent(ACTION_READING)
                .setPackage(packageName)
                .putExtra(EXTRA_TS_MS, timestampMs)
                .putExtra(EXTRA_USV_H, uSvPerHour)
                .putExtra(EXTRA_CPS, cps)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
            sendBroadcast(i)
        } catch (_: Throwable) {}
        
        // Update all widget types
        RadiaCodeWidgetProvider.updateAll(this)
        SimpleWidgetProvider.updateAll(this)
        ChartWidgetProvider.updateAll(this)
        
        updateNotificationFromState()
    }
    
    private fun updateNotificationFromState() {
        val manager = deviceManager ?: return
        val connectedCount = manager.getConnectedCount()
        val totalCount = manager.getTotalCount()
        
        if (totalCount == 0) {
            notifyUpdate("Open RadiaCode", "No devices configured")
            return
        }
        
        // Build notification text from all connected devices
        val states = manager.getAllDeviceStates()
        val connectedStates = states.filter { it.connectionState == DeviceConnectionState.CONNECTED }
        
        if (connectedStates.isEmpty()) {
            val connectingCount = states.count { 
                it.connectionState == DeviceConnectionState.CONNECTING || 
                it.connectionState == DeviceConnectionState.RECONNECTING 
            }
            if (connectingCount > 0) {
                notifyUpdate("Open RadiaCode", "Connecting to $connectingCount device(s)…")
            } else {
                notifyUpdate("Open RadiaCode", "All devices disconnected")
            }
            return
        }
        
        // Show readings from connected devices
        val readingTexts = connectedStates.mapNotNull { state ->
            state.lastReading?.let { reading ->
                val name = state.config.shortDisplayName
                "$name: ${"%.3f".format(reading.uSvPerHour)} μSv/h"
            }
        }
        
        val title = if (connectedCount == 1) "Open RadiaCode" else "Open RadiaCode ($connectedCount connected)"
        val text = if (readingTexts.size <= 2) {
            readingTexts.joinToString(" • ")
        } else {
            "${readingTexts.take(2).joinToString(" • ")} +${readingTexts.size - 2} more"
        }
        
        notifyUpdate(title, text.ifEmpty { "$connectedCount device(s) connected" })
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Open RadiaCode live", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Open RadiaCode background connection and live reading"
            }
        )
    }

    private fun updateForeground(title: String, text: String) {
        try {
            val notif = buildNotification(title, text)
            startForeground(NOTIF_ID, notif)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            stopInternal("Foreground notification failed")
            stopSelf()
        }
    }

    private fun notifyUpdate(title: String, text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            Prefs.setServiceStatus(this, text)
            nm.notify(NOTIF_ID, buildNotification(title, text))
        } catch (t: Throwable) {
            Log.e(TAG, "notify failed", t)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val contentPi = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)

        val reconnectIntent = Intent(this, RadiaCodeForegroundService::class.java).setAction(ACTION_RECONNECT)
        val reconnectPi = PendingIntent.getService(this, 2, reconnectIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)

        val stopIntent = Intent(this, RadiaCodeForegroundService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(0, "Reconnect", reconnectPi)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun stopInternal(reason: String) {
        Log.d(TAG, "service stopInternal: $reason")
        
        deviceManager?.stop()
        deviceManager = null

        notifyUpdate("Open RadiaCode", reason)
    }

    private fun appendReadingCsvIfNew(deviceId: String, deviceName: String, timestampMs: Long, uSvPerHour: Float, cps: Float) {
        val lastLogged = lastCsvTimestamps[deviceId] ?: 0L
        if (timestampMs <= lastLogged) return
        lastCsvTimestamps[deviceId] = timestampMs

        try {
            // Use device-specific CSV file
            val safeDeviceName = deviceName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(20)
            val file = File(filesDir, "readings_$safeDeviceName.csv")
            
            // Rotate if it gets too big
            if (file.exists() && file.length() > 2_000_000L) {
                val old = File(filesDir, "readings_$safeDeviceName.old.csv")
                try { if (old.exists()) old.delete() } catch (_: Throwable) {}
                try { file.renameTo(old) } catch (_: Throwable) {}
            }

            val isNew = !file.exists()
            file.parentFile?.mkdirs()
            file.appendText(buildString {
                if (isNew) {
                    appendLine("timestamp_ms,device_id,device_name,usv_per_h,cps")
                }
                append(timestampMs.toString())
                append(',')
                append(deviceId)
                append(',')
                append(deviceName)
                append(',')
                append(String.format(java.util.Locale.US, "%.6f", uSvPerHour))
                append(',')
                append(String.format(java.util.Locale.US, "%.3f", cps))
                appendLine()
            })
            
            // Also append to global CSV for backward compatibility
            val globalFile = File(filesDir, "readings.csv")
            if (globalFile.exists() && globalFile.length() > 2_000_000L) {
                val old = File(filesDir, "readings.old.csv")
                try { if (old.exists()) old.delete() } catch (_: Throwable) {}
                try { globalFile.renameTo(old) } catch (_: Throwable) {}
            }
            val globalIsNew = !globalFile.exists()
            globalFile.appendText(buildString {
                if (globalIsNew) {
                    appendLine("timestamp_ms,usv_per_h,cps")
                }
                append(timestampMs.toString())
                append(',')
                append(String.format(java.util.Locale.US, "%.6f", uSvPerHour))
                append(',')
                append(String.format(java.util.Locale.US, "%.3f", cps))
                appendLine()
            })
        } catch (t: Throwable) {
            Log.w(TAG, "appendReadingCsvIfNew failed", t)
        }
    }

    private fun hasBlePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
