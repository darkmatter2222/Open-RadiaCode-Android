package com.radiacode.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Manages multiple simultaneous RadiaCode BLE connections.
 * Each device gets its own BleClient instance and polling loop.
 */
class MultiDeviceBleManager(
    private val context: Context,
    private val onDeviceStateChanged: (DeviceState) -> Unit,
    private val onDeviceReading: (String, Float, Float, Long) -> Unit  // deviceId, uSvH, cps, timestampMs
) {
    companion object {
        private const val TAG = "RadiaCode"
        private const val DEFAULT_POLL_MS = 1000L
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
    }
    
    // Per-device state
    private data class ManagedDevice(
        val config: DeviceConfig,
        var client: RadiacodeBleClient? = null,
        var pollTask: ScheduledFuture<*>? = null,
        var reconnectTask: ScheduledFuture<*>? = null,
        var reconnectAttempts: Int = 0,
        var consecutivePollFailures: Int = 0,
        var lastSuccessfulPollMs: Long = 0L,
        @Volatile var intentionalClose: Boolean = false,
        var state: DeviceState = DeviceState(config)
    )
    
    private val devices = ConcurrentHashMap<String, ManagedDevice>()
    private val executor = Executors.newCachedThreadPool()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(4)
    
    @Volatile
    private var isRunning = false
    
    /**
     * Start the manager and connect to all enabled devices.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        
        // Migrate old single-device data if needed
        Prefs.migrateToMultiDevice(context)
        
        // Load enabled devices and start connections
        val enabledDevices = Prefs.getEnabledDevices(context)
        Log.d(TAG, "MultiDeviceBleManager starting with ${enabledDevices.size} enabled devices")
        
        for (device in enabledDevices) {
            addDevice(device)
        }
    }
    
    /**
     * Stop all connections and clean up.
     */
    fun stop() {
        isRunning = false
        
        for ((_, managed) in devices) {
            disconnectDevice(managed, "Manager stopped")
        }
        devices.clear()
        
        try { scheduler.shutdownNow() } catch (_: Throwable) {}
    }
    
    /**
     * Add a device to be managed (and optionally connect).
     */
    fun addDevice(config: DeviceConfig, connect: Boolean = true) {
        if (devices.containsKey(config.id)) {
            Log.d(TAG, "Device ${config.id} already managed")
            return
        }
        
        val managed = ManagedDevice(
            config = config,
            state = DeviceState(config, DeviceConnectionState.DISCONNECTED)
        )
        devices[config.id] = managed
        
        Log.d(TAG, "Added device: ${config.displayName} (${config.macAddress})")
        onDeviceStateChanged(managed.state)
        
        if (connect && config.enabled && isRunning) {
            connectDevice(config.id)
        }
    }
    
    /**
     * Remove a device from management.
     */
    fun removeDevice(deviceId: String) {
        val managed = devices.remove(deviceId) ?: return
        disconnectDevice(managed, "Device removed")
        Log.d(TAG, "Removed device: ${managed.config.displayName}")
    }
    
    /**
     * Update device configuration (e.g., name change).
     */
    fun updateDevice(config: DeviceConfig) {
        val managed = devices[config.id] ?: return
        val wasEnabled = managed.config.enabled
        
        devices[config.id] = managed.copy(
            config = config,
            state = managed.state.copy(config = config)
        )
        
        // Handle enable/disable change
        if (!wasEnabled && config.enabled && isRunning) {
            connectDevice(config.id)
        } else if (wasEnabled && !config.enabled) {
            disconnectDevice(managed, "Device disabled")
        }
        
        onDeviceStateChanged(managed.state)
    }
    
    /**
     * Connect to a specific device.
     */
    @SuppressLint("MissingPermission")
    fun connectDevice(deviceId: String) {
        val managed = devices[deviceId] ?: return
        
        executor.execute {
            managed.intentionalClose = true
            
            // Clean up existing connection
            managed.reconnectTask?.cancel(true)
            managed.reconnectTask = null
            managed.pollTask?.cancel(true)
            managed.pollTask = null
            managed.client?.close()
            managed.client = null
            
            Thread.sleep(200)
            managed.intentionalClose = false
            
            // Update state
            managed.state = managed.state.copy(
                connectionState = DeviceConnectionState.CONNECTING,
                statusMessage = "Connecting…"
            )
            onDeviceStateChanged(managed.state)
            
            val btManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = btManager?.adapter
            
            if (adapter == null || !adapter.isEnabled) {
                scheduleReconnect(deviceId, "Bluetooth off")
                return@execute
            }
            
            val bleDevice: BluetoothDevice = try {
                adapter.getRemoteDevice(managed.config.macAddress)
            } catch (t: Throwable) {
                managed.state = managed.state.copy(
                    connectionState = DeviceConnectionState.ERROR,
                    statusMessage = "Invalid MAC address"
                )
                onDeviceStateChanged(managed.state)
                return@execute
            }
            
            val client = RadiacodeBleClient(context) { msg ->
                // Status callback
                if (!managed.intentionalClose && 
                    (msg.startsWith("Disconnected") || msg.startsWith("GATT error") || msg.startsWith("Service discovery failed"))) {
                    scheduleReconnect(deviceId, msg)
                }
                managed.state = managed.state.copy(statusMessage = msg)
                onDeviceStateChanged(managed.state)
            }
            
            managed.client = client
            
            Log.d(TAG, "Connecting to ${managed.config.displayName} (${managed.config.macAddress})")
            client.connect(bleDevice)
            
            client.ready()
                .thenCompose { client.initializeSession() }
                .thenRun {
                    // Connection established!
                    managed.reconnectAttempts = 0
                    managed.consecutivePollFailures = 0
                    managed.lastSuccessfulPollMs = System.currentTimeMillis()
                    
                    managed.state = managed.state.copy(
                        connectionState = DeviceConnectionState.CONNECTED,
                        statusMessage = "Connected"
                    )
                    onDeviceStateChanged(managed.state)
                    
                    Thread.sleep(300)
                    startPolling(deviceId)
                }
                .exceptionally { t ->
                    Log.e(TAG, "Connection failed for ${managed.config.displayName}", t)
                    scheduleReconnect(deviceId, "Init failed")
                    null
                }
        }
    }
    
    /**
     * Disconnect a specific device.
     */
    fun disconnectDevice(deviceId: String, reason: String = "User requested") {
        val managed = devices[deviceId] ?: return
        disconnectDevice(managed, reason)
    }
    
    private fun disconnectDevice(managed: ManagedDevice, reason: String) {
        Log.d(TAG, "Disconnecting ${managed.config.displayName}: $reason")
        managed.intentionalClose = true
        
        managed.reconnectTask?.cancel(true)
        managed.reconnectTask = null
        managed.pollTask?.cancel(true)
        managed.pollTask = null
        managed.client?.close()
        managed.client = null
        
        managed.state = managed.state.copy(
            connectionState = DeviceConnectionState.DISCONNECTED,
            statusMessage = reason
        )
        onDeviceStateChanged(managed.state)
    }
    
    /**
     * Force reconnect a device.
     */
    fun forceReconnect(deviceId: String) {
        val managed = devices[deviceId] ?: return
        Log.d(TAG, "Force reconnect: ${managed.config.displayName}")
        managed.reconnectAttempts = 0
        connectDevice(deviceId)
    }
    
    /**
     * Force reconnect all devices.
     */
    fun forceReconnectAll() {
        for ((id, _) in devices) {
            forceReconnect(id)
        }
    }
    
    private fun startPolling(deviceId: String) {
        val managed = devices[deviceId] ?: return
        managed.pollTask?.cancel(true)
        managed.consecutivePollFailures = 0
        scheduleNextPoll(deviceId, 0)
    }
    
    private fun scheduleNextPoll(deviceId: String, delayMs: Long) {
        val managed = devices[deviceId] ?: return
        
        managed.pollTask?.cancel(true)
        managed.pollTask = scheduler.schedule(
            {
                val m = devices[deviceId] ?: return@schedule
                val client = m.client ?: return@schedule
                
                client.readDataBuf()
                    .thenAccept { buf ->
                        val rt = RadiacodeDataBuf.decodeLatestRealTime(buf) ?: return@thenAccept
                        m.consecutivePollFailures = 0
                        m.lastSuccessfulPollMs = System.currentTimeMillis()
                        
                        val uSvPerHour = rt.doseRate * 10000.0f
                        val timestampMs = System.currentTimeMillis()
                        
                        // Store reading for this device
                        val reading = Prefs.LastReading(uSvPerHour, rt.countRate, timestampMs)
                        Prefs.setDeviceLastReading(context, deviceId, uSvPerHour, rt.countRate, timestampMs)
                        Prefs.addDeviceReading(context, deviceId, reading)
                        
                        // Also update global readings for compatibility
                        Prefs.setLastReading(context, uSvPerHour, rt.countRate, timestampMs)
                        Prefs.addRecentReading(context, reading)
                        
                        // Update device state
                        m.state = m.state.copy(
                            lastReading = DeviceReading(
                                deviceId = deviceId,
                                macAddress = m.config.macAddress,
                                uSvPerHour = uSvPerHour,
                                cps = rt.countRate,
                                timestampMs = timestampMs,
                                isConnected = true
                            ),
                            statusMessage = "${"%.3f".format(uSvPerHour)} μSv/h"
                        )
                        onDeviceStateChanged(m.state)
                        
                        // Callback
                        onDeviceReading(deviceId, uSvPerHour, rt.countRate, timestampMs)
                    }
                    .exceptionally { t ->
                        m.consecutivePollFailures += 1
                        Log.e(TAG, "Poll failed for ${m.config.displayName} (count=${m.consecutivePollFailures})", t)
                        
                        val timeSinceSuccess = System.currentTimeMillis() - m.lastSuccessfulPollMs
                        val shouldReconnect = m.consecutivePollFailures >= 5 || timeSinceSuccess > 30_000L
                        
                        if (shouldReconnect && m.consecutivePollFailures >= 3) {
                            scheduleReconnect(deviceId, "Read failed")
                        }
                        null
                    }
                    .whenComplete { _, _ ->
                        // Schedule next poll
                        val mm = devices[deviceId]
                        if (mm?.client != null && mm.reconnectTask?.isDone != false) {
                            scheduleNextPoll(deviceId, Prefs.getPollIntervalMs(context, DEFAULT_POLL_MS))
                        }
                    }
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }
    
    private fun scheduleReconnect(deviceId: String, reason: String) {
        val managed = devices[deviceId] ?: return
        
        // Avoid spamming
        if (managed.reconnectTask?.isDone == false) return
        if (managed.intentionalClose) {
            Log.d(TAG, "Reconnect ignored (intentional close) for ${managed.config.displayName}: $reason")
            return
        }
        
        managed.intentionalClose = true
        
        managed.pollTask?.cancel(true)
        managed.pollTask = null
        managed.client?.close()
        managed.client = null
        
        managed.intentionalClose = false
        
        managed.reconnectAttempts += 1
        val backoff = min(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS * managed.reconnectAttempts.toLong())
        
        Log.d(TAG, "Scheduling reconnect for ${managed.config.displayName} in ${backoff}ms: $reason")
        
        managed.state = managed.state.copy(
            connectionState = DeviceConnectionState.RECONNECTING,
            statusMessage = "Reconnecting… ($reason)"
        )
        onDeviceStateChanged(managed.state)
        
        managed.reconnectTask = scheduler.schedule(
            { connectDevice(deviceId) },
            backoff,
            TimeUnit.MILLISECONDS
        )
    }
    
    /**
     * Get current state for all devices.
     */
    fun getAllDeviceStates(): List<DeviceState> {
        return devices.values.map { it.state }
    }
    
    /**
     * Get state for a specific device.
     */
    fun getDeviceState(deviceId: String): DeviceState? {
        return devices[deviceId]?.state
    }
    
    /**
     * Get connected device count.
     */
    fun getConnectedCount(): Int {
        return devices.values.count { it.state.connectionState == DeviceConnectionState.CONNECTED }
    }
    
    /**
     * Get total managed device count.
     */
    fun getTotalCount(): Int {
        return devices.size
    }
    
    /**
     * Check if any device is connected.
     */
    fun hasAnyConnected(): Boolean {
        return devices.values.any { it.state.connectionState == DeviceConnectionState.CONNECTED }
    }
    
    /**
     * Reload devices from preferences and sync state.
     */
    fun reloadDevices() {
        val savedDevices = Prefs.getDevices(context).associateBy { it.id }
        
        // Remove devices that are no longer in prefs
        val toRemove = devices.keys.filter { !savedDevices.containsKey(it) }
        for (id in toRemove) {
            removeDevice(id)
        }
        
        // Add/update devices from prefs
        for ((id, config) in savedDevices) {
            if (devices.containsKey(id)) {
                updateDevice(config)
            } else {
                addDevice(config)
            }
        }
    }
}
