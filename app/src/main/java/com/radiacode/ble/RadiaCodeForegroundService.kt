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
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.radiacode.ble.location.LocationController
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import java.util.UUID

class RadiaCodeForegroundService : Service() {

    companion object {
        private const val TAG = "RadiaCode"

        private const val CHANNEL_ID = "radiacode_live"
        private const val NOTIF_ID = 1

        private const val ACTION_START = "com.radiacode.ble.action.START"
        private const val ACTION_STOP = "com.radiacode.ble.action.STOP"
        private const val ACTION_RECONNECT = "com.radiacode.ble.action.RECONNECT"
        private const val ACTION_RELOAD_DEVICES = "com.radiacode.ble.action.RELOAD_DEVICES"
        private const val ACTION_REFRESH_NOTIFICATION = "com.radiacode.ble.action.REFRESH_NOTIFICATION"
        private const val ACTION_REQUEST_SPECTRUM = "com.radiacode.ble.action.REQUEST_SPECTRUM"

        const val ACTION_READING = "com.radiacode.ble.action.READING"
        const val ACTION_DEVICE_STATE_CHANGED = "com.radiacode.ble.action.DEVICE_STATE"
        const val ACTION_SPECTRUM_DATA = "com.radiacode.ble.action.SPECTRUM_DATA"
        const val ACTION_STATISTICAL_UPDATE = "com.radiacode.ble.action.STATISTICAL_UPDATE"
        const val EXTRA_TS_MS = "ts_ms"
        const val EXTRA_USV_H = "usv_h"
        const val EXTRA_CPS = "cps"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_CONNECTION_STATE = "connection_state"
        const val EXTRA_SPECTRUM_COUNTS = "spectrum_counts"
        const val EXTRA_CALIB_A0 = "calib_a0"
        const val EXTRA_CALIB_A1 = "calib_a1"
        const val EXTRA_CALIB_A2 = "calib_a2"
        const val EXTRA_IS_REALTIME = "is_realtime"  // true for differential spectrum, false for scan
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        
        // Statistical intelligence (VEGA) extras
        const val EXTRA_DOSE_ZSCORE = "dose_zscore"
        const val EXTRA_DOSE_SIGMA_LEVEL = "dose_sigma_level"
        const val EXTRA_DOSE_ROC = "dose_roc"
        const val EXTRA_DOSE_ROC_PERCENT = "dose_roc_percent"
        const val EXTRA_DOSE_TREND = "dose_trend"
        const val EXTRA_DOSE_FORECAST_30S = "dose_forecast_30s"
        const val EXTRA_DOSE_FORECAST_60S = "dose_forecast_60s"
        const val EXTRA_DOSE_BASELINE_MEAN = "dose_baseline_mean"
        const val EXTRA_DOSE_BASELINE_STDDEV = "dose_baseline_stddev"
        const val EXTRA_CUSUM_CHANGE = "cusum_change"
        const val EXTRA_CPS_ZSCORE = "cps_zscore"
        const val EXTRA_CPS_SIGMA_LEVEL = "cps_sigma_level"

        private const val NOTIF_MIN_INTERVAL_CONNECTED_MS = 10_000L
        private const val NOTIF_MIN_INTERVAL_DISCONNECTED_MS = 2_000L

        private const val WIDGET_MIN_INTERVAL_FOREGROUND_MS = 1_000L
        private const val WIDGET_MIN_INTERVAL_BACKGROUND_MS = 10_000L
        private const val WIDGET_MIN_INTERVAL_SCREEN_OFF_MS = 30_000L

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
        
        fun refreshNotification(context: Context) {
            val intent = Intent(context, RadiaCodeForegroundService::class.java).setAction(ACTION_REFRESH_NOTIFICATION)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // Service might not be running
            }
        }
        
        fun requestSpectrum(context: Context, deviceId: String? = null) {
            val intent = Intent(context, RadiaCodeForegroundService::class.java)
                .setAction(ACTION_REQUEST_SPECTRUM)
            if (deviceId != null) {
                intent.putExtra(EXTRA_DEVICE_ID, deviceId)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // Service might not be running
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var lastWidgetUpdateMs: Long = 0L
    private var widgetUpdateFuture: ScheduledFuture<*>? = null

    private var lastNotifTitle: String? = null
    private var lastNotifText: String? = null
    private var lastNotifPostMs: Long = 0L

    // Multi-device manager
    private var deviceManager: MultiDeviceBleManager? = null

    // Alert evaluation
    private lateinit var alertEvaluator: AlertEvaluator
    
    // Statistical intelligence engine (VEGA)
    private lateinit var statisticalEngine: StatisticalEngine
    
    // Track last CSV timestamp per device
    private val lastCsvTimestamps = mutableMapOf<String, Long>()
    
    // Track previous connection states to detect actual changes
    private val previousConnectionStates = mutableMapOf<String, DeviceConnectionState>()
    
    // Background location tracking for map data
    private var locationController: LocationController? = null
    private var backgroundLocationToken: UUID? = null

    private var lastMapSaveLogMs: Long = 0L
    private var lastNoLocationLogMs: Long = 0L
    private var lastAlertEvalMs: Long = 0L

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
        
        // Initialize statistical intelligence engine
        statisticalEngine = StatisticalEngine.getInstance(this)
        
        // Initialize sound manager
        SoundManager.init(this)
        
        // Initialize VEGA TTS
        VegaTTS.init(this)
        
        // Initialize multi-device manager
        deviceManager = MultiDeviceBleManager(
            context = applicationContext,
            onDeviceStateChanged = { state -> handleDeviceStateChanged(state) },
            onDeviceReading = { deviceId, uSvH, cps, ts -> handleDeviceReading(deviceId, uSvH, cps, ts) },
            onSpectrumReading = { deviceId, spectrum -> handleSpectrumReading(deviceId, spectrum) }
        )
        
        try {
            registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver failed", t)
        }

        // Single location owner for the entire app
        // Only acquire if GPS tracking is enabled
        locationController = LocationController.getInstance(this)
        if (Prefs.isGpsTrackingEnabled(this)) {
            backgroundLocationToken = locationController?.acquireBackground()
            Log.d(TAG, "GPS tracking enabled - acquired background location token")
        } else {
            Log.d(TAG, "GPS tracking disabled - skipping location acquisition")
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
                updateNotificationFromState(force = true)
                // Re-evaluate GPS tracking state
                updateGpsTrackingState()
                return START_STICKY
            }
            ACTION_REFRESH_NOTIFICATION -> {
                updateNotificationFromState(force = true)
                return START_STICKY
            }
            ACTION_REQUEST_SPECTRUM -> {
                val requestedDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                executor.submit { handleSpectrumRequest(requestedDeviceId) }
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
        
        // Prefetch map tiles in background for widget (only if GPS tracking enabled)
        if (Prefs.isGpsTrackingEnabled(this)) {
            MapTileLoader.prefetchTilesForWidget(this)
        }
        
        return START_STICKY
    }
    
    /**
     * Update GPS tracking state based on preference.
     * Called when the user toggles GPS tracking in settings.
     */
    private fun updateGpsTrackingState() {
        val gpsEnabled = Prefs.isGpsTrackingEnabled(this)
        
        if (gpsEnabled && backgroundLocationToken == null) {
            // GPS was just enabled - acquire location
            backgroundLocationToken = locationController?.acquireBackground()
            Log.d(TAG, "GPS tracking enabled - acquired background location token")
            // Prefetch map tiles
            MapTileLoader.prefetchTilesForWidget(this)
        } else if (!gpsEnabled && backgroundLocationToken != null) {
            // GPS was just disabled - release location
            locationController?.releaseBackground(backgroundLocationToken)
            backgroundLocationToken = null
            Log.d(TAG, "GPS tracking disabled - released background location token")
        }
    }

    override fun onDestroy() {
        stopInternal("Service destroyed")
        locationController?.releaseBackground(backgroundLocationToken)
        backgroundLocationToken = null
        SoundManager.release()  // Release sound resources
        VegaTTS.release()  // Release VEGA TTS resources
        try { unregisterReceiver(btStateReceiver) } catch (_: Throwable) {}
        try { scheduler.shutdownNow() } catch (_: Throwable) {}
        try { executor.shutdownNow() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun handleDeviceStateChanged(state: DeviceState) {
        val deviceId = state.config.id
        val previousState = previousConnectionStates[deviceId]
        val currentState = state.connectionState
        
        // Only play connection sounds when state actually changes
        if (previousState != currentState) {
            previousConnectionStates[deviceId] = currentState
            when (currentState) {
                DeviceConnectionState.CONNECTED -> SoundManager.play(this, Prefs.SoundType.CONNECTED)
                DeviceConnectionState.DISCONNECTED -> SoundManager.play(this, Prefs.SoundType.DISCONNECTED)
                else -> { /* No sound for other states */ }
            }
        }
        
        // Broadcast state change for UI updates
        try {
            val i = Intent(ACTION_DEVICE_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_DEVICE_ID, state.config.id)
                .putExtra(EXTRA_CONNECTION_STATE, state.connectionState.name)
            sendBroadcast(i)
        } catch (_: Throwable) {}
        
        updateNotificationFromState(force = true)
    }
    
    private fun handleSpectrumRequest(requestedDeviceId: String?) {
        val manager = deviceManager ?: return
        val client = manager.getClient(requestedDeviceId)
        
        // Determine actual device ID
        val deviceId = requestedDeviceId ?: manager.getAllDeviceStates()
            .firstOrNull { it.connectionState == DeviceConnectionState.CONNECTED }?.config?.id
        
        if (client == null) {
            Log.w(TAG, "handleSpectrumRequest: No connected device")
            return
        }
        
        try {
            // Read calibration first
            val calibration = client.readEnergyCalibration().get(5, TimeUnit.SECONDS)
            
            // Read spectrum
            val spectrumData = client.readSpectrum().get(5, TimeUnit.SECONDS)
            
            if (spectrumData != null) {
                // Combine calibration with spectrum
                val fullData = spectrumData.copy(
                    a0 = calibration?.a0 ?: spectrumData.a0,
                    a1 = calibration?.a1 ?: spectrumData.a1,
                    a2 = calibration?.a2 ?: spectrumData.a2
                )
                
                Log.d(TAG, "Spectrum read: ${fullData.numChannels} channels, duration=${fullData.durationSeconds}s")
                
                // Broadcast spectrum data with device ID - this is a SCAN (accumulated), not realtime
                val i = Intent(ACTION_SPECTRUM_DATA)
                    .setPackage(packageName)
                    .putExtra(EXTRA_TS_MS, System.currentTimeMillis())
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
                    .putExtra(EXTRA_SPECTRUM_COUNTS, fullData.counts)
                    .putExtra(EXTRA_CALIB_A0, fullData.a0)
                    .putExtra(EXTRA_CALIB_A1, fullData.a1)
                    .putExtra(EXTRA_CALIB_A2, fullData.a2)
                    .putExtra(EXTRA_IS_REALTIME, false)  // This is a scan, not realtime
                sendBroadcast(i)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleSpectrumRequest failed", t)
        }
    }
    
    /**
     * Handle spectrum reading from the multi-device manager (real-time mode).
     * This uses DIFFERENTIAL spectrum - recent counts only.
     */
    private fun handleSpectrumReading(deviceId: String, spectrum: SpectrumData) {
        Log.d(TAG, "Realtime spectrum from $deviceId: ${spectrum.numChannels} channels, totalCounts=${spectrum.totalCounts}")
        
        // Broadcast spectrum data with device ID - this is REALTIME (differential)
        try {
            val i = Intent(ACTION_SPECTRUM_DATA)
                .setPackage(packageName)
                .putExtra(EXTRA_TS_MS, System.currentTimeMillis())
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_SPECTRUM_COUNTS, spectrum.counts)
                .putExtra(EXTRA_CALIB_A0, spectrum.a0)
                .putExtra(EXTRA_CALIB_A1, spectrum.a1)
                .putExtra(EXTRA_CALIB_A2, spectrum.a2)
                .putExtra(EXTRA_IS_REALTIME, true)  // This is realtime differential, not a scan
            sendBroadcast(i)
        } catch (t: Throwable) {
            Log.e(TAG, "handleSpectrumReading broadcast failed", t)
        }
    }
    
    private fun handleDeviceReading(deviceId: String, uSvPerHour: Float, cps: Float, timestampMs: Long) {
        // Audio gating: don't keep audio pipeline hot while background/locked.
        if (shouldPlayDataTick()) {
            SoundManager.play(this, Prefs.SoundType.DATA_TICK)
        }

        // Hold-last-fix strategy: attach most recent location fix to each reading.
        // Only get location if GPS tracking is enabled
        val gpsEnabled = Prefs.isGpsTrackingEnabled(this)
        val locationSnap = if (gpsEnabled) locationController?.getLastLocation() else null
        
        // PRIORITY 1: Broadcast to UI immediately for responsive updates
        // This is the ONLY synchronous thing that happens on the BLE thread
        // Include location so map views can update hexagons immediately without polling Prefs
        try {
            val i = Intent(ACTION_READING)
                .setPackage(packageName)
                .putExtra(EXTRA_TS_MS, timestampMs)
                .putExtra(EXTRA_USV_H, uSvPerHour)
                .putExtra(EXTRA_CPS, cps)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
            // Add location if available
            if (locationSnap != null) {
                i.putExtra(EXTRA_LATITUDE, locationSnap.latitude)
                i.putExtra(EXTRA_LONGITUDE, locationSnap.longitude)
            }
            sendBroadcast(i)
        } catch (_: Throwable) {}
        
        // PRIORITY 2: Statistical analysis - feed data to VEGA engine
        // This is fast (just math) and runs on the calling thread
        val (doseSnapshot, cpsSnapshot) = try {
            statisticalEngine.addReading(uSvPerHour, cps, timestampMs)
        } catch (t: Throwable) {
            Log.e(TAG, "Statistical analysis failed", t)
            Pair(null, null)
        }
        
        // Broadcast statistical snapshot for UI (z-score, forecast, etc.)
        if (doseSnapshot != null && cpsSnapshot != null) {
            try {
                val statsIntent = Intent(ACTION_STATISTICAL_UPDATE)
                    .setPackage(packageName)
                    .putExtra(EXTRA_DOSE_ZSCORE, doseSnapshot.zScore.zScore)
                    .putExtra(EXTRA_DOSE_SIGMA_LEVEL, doseSnapshot.zScore.sigmaLevel)
                    .putExtra(EXTRA_DOSE_ROC, doseSnapshot.rateOfChange.ratePerSecond)
                    .putExtra(EXTRA_DOSE_ROC_PERCENT, doseSnapshot.rateOfChange.ratePercentPerSecond)
                    .putExtra(EXTRA_DOSE_TREND, doseSnapshot.rateOfChange.trend.name)
                    .putExtra(EXTRA_DOSE_FORECAST_30S, doseSnapshot.forecast30s.predictedValue)
                    .putExtra(EXTRA_DOSE_FORECAST_60S, doseSnapshot.forecast60s.predictedValue)
                    .putExtra(EXTRA_DOSE_BASELINE_MEAN, doseSnapshot.baseline.mean)
                    .putExtra(EXTRA_DOSE_BASELINE_STDDEV, doseSnapshot.baseline.standardDeviation)
                    .putExtra(EXTRA_CUSUM_CHANGE, doseSnapshot.cusum.changeDetected)
                    .putExtra(EXTRA_CPS_ZSCORE, cpsSnapshot.zScore.zScore)
                    .putExtra(EXTRA_CPS_SIGMA_LEVEL, cpsSnapshot.zScore.sigmaLevel)
                sendBroadcast(statsIntent)
            } catch (_: Throwable) {}
        }
        
        // PRIORITY 3: Evaluate alerts - both classic and statistical
        // Don't queue behind slow operations. Runs on calling thread but is fast (just math).
        val now = System.currentTimeMillis()
        if (now - lastAlertEvalMs > 1_000L) {  // Check every second for faster response
            lastAlertEvalMs = now
            try {
                val recentReadings = Prefs.getRecentReadings(this)
                // Classic threshold alerts
                alertEvaluator.evaluate(uSvPerHour, cps, recentReadings)
                
                // Statistical alerts (VEGA intelligence)
                if (doseSnapshot != null && cpsSnapshot != null) {
                    alertEvaluator.evaluateStatistical(doseSnapshot, cpsSnapshot, statisticalEngine)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Alert evaluation failed", t)
            }
        }
        
        // PRIORITY 3: Move heavy work to background thread
        // This includes: widget updates, notification, CSV, map points
        executor.execute {
            try {
                // Widget and notification updates (can involve bitmap rendering)
                requestWidgetUpdate()
                updateNotificationFromState(force = false)
                
                val device = Prefs.getDeviceById(this, deviceId) ?: return@execute
                
                // Append to device-specific CSV (file I/O)
                appendReadingCsvIfNew(deviceId, device.displayName, timestampMs, uSvPerHour, cps)
                
                // Save map data point if we have location
                // This is SLOW: parses and rebuilds 86400-entry string
                if (locationSnap != null) {
                    Prefs.addMapDataPoint(this, locationSnap.latitude, locationSnap.longitude, uSvPerHour, cps)

                    // Rate-limited logging
                    val logNow = System.currentTimeMillis()
                    if (logNow - lastMapSaveLogMs > 5_000L) {
                        lastMapSaveLogMs = logNow
                        Log.d(TAG, "MapPoint saved: dev=$deviceId dose=$uSvPerHour cps=$cps loc=${locationSnap.latitude},${locationSnap.longitude}")
                    }
                } else {
                    val logNow = System.currentTimeMillis()
                    if (logNow - lastNoLocationLogMs > 5_000L) {
                        lastNoLocationLogMs = logNow
                        Log.w(TAG, "MapPoint skipped: no location (dev=$deviceId dose=$uSvPerHour cps=$cps)")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Background handleDeviceReading failed", t)
            }
        }
    }

    private fun requestWidgetUpdate() {
        val now = System.currentTimeMillis()
        val minIntervalMs = getWidgetMinIntervalMs()
        val elapsed = now - lastWidgetUpdateMs

        if (elapsed >= minIntervalMs) {
            lastWidgetUpdateMs = now
            widgetUpdateFuture?.cancel(false)
            widgetUpdateFuture = null
            updateAllWidgetsNow()
            return
        }

        // Already scheduled? Let it run.
        if (widgetUpdateFuture?.isDone == false) return

        val delayMs = (minIntervalMs - elapsed).coerceAtLeast(0L)
        widgetUpdateFuture = scheduler.schedule({
            try {
                lastWidgetUpdateMs = System.currentTimeMillis()
                updateAllWidgetsNow()
            } catch (t: Throwable) {
                Log.w(TAG, "Widget update failed", t)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun updateAllWidgetsNow() {
        // V2 unified + map + legacy for any remaining
        UnifiedWidgetProvider.updateAll(this)
        MapWidgetProvider.updateAll(this)
        RadiaCodeWidgetProvider.updateAll(this)
        SimpleWidgetProvider.updateAll(this)
        ChartWidgetProvider.updateAll(this)
    }
    
    private fun updateNotificationFromState(force: Boolean) {
        val manager = deviceManager ?: return
        val connectedCount = manager.getConnectedCount()
        val totalCount = manager.getTotalCount()
        
        // Get notification style preference
        val style = Prefs.getNotificationStyle(this)
        
        if (totalCount == 0) {
            notifyUpdate("Open RadiaCode", "No devices configured", force = force, minIntervalMs = 0L)
            return
        }
        
        // Build notification text from all connected devices
        val states = manager.getAllDeviceStates()
        val connectedStates = states.filter { it.connectionState == DeviceConnectionState.CONNECTED }
        
        // Handle disconnected/connecting states based on preference
        if (connectedStates.isEmpty()) {
            val connectingCount = states.count { 
                it.connectionState == DeviceConnectionState.CONNECTING || 
                it.connectionState == DeviceConnectionState.RECONNECTING 
            }
            
            when (style) {
                Prefs.NotificationStyle.NONE,
                Prefs.NotificationStyle.OFF -> {
                    notifyUpdate("Open RadiaCode", "Service running", force = force, minIntervalMs = NOTIF_MIN_INTERVAL_DISCONNECTED_MS)
                }
                Prefs.NotificationStyle.STATUS_ONLY,
                Prefs.NotificationStyle.READINGS,
                Prefs.NotificationStyle.DETAILED -> {
                    if (connectingCount > 0) {
                        notifyUpdate("Open RadiaCode", "Connecting to $connectingCount device(s)…", force = force, minIntervalMs = NOTIF_MIN_INTERVAL_DISCONNECTED_MS)
                    } else {
                        notifyUpdate("Open RadiaCode", "All devices disconnected", force = force, minIntervalMs = NOTIF_MIN_INTERVAL_DISCONNECTED_MS)
                    }
                }
            }
            return
        }
        
        // Build notification based on style
        when (style) {
            Prefs.NotificationStyle.NONE,
            Prefs.NotificationStyle.OFF -> {
                notifyUpdate("Open RadiaCode", "Service running", force = force, minIntervalMs = NOTIF_MIN_INTERVAL_CONNECTED_MS)
            }
            
            Prefs.NotificationStyle.STATUS_ONLY -> {
                val text = if (connectedCount == 1) {
                    "${connectedStates.first().config.shortDisplayName} connected"
                } else {
                    "$connectedCount devices connected"
                }
                notifyUpdate("Open RadiaCode", text, force = force, minIntervalMs = NOTIF_MIN_INTERVAL_CONNECTED_MS)
            }
            
            Prefs.NotificationStyle.READINGS -> {
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
                
                notifyUpdate(title, text.ifEmpty { "$connectedCount device(s) connected" }, force = force, minIntervalMs = NOTIF_MIN_INTERVAL_CONNECTED_MS)
            }
            
            Prefs.NotificationStyle.DETAILED -> {
                buildDetailedNotification(connectedCount, connectedStates, force = force)
            }
        }
    }
    
    private fun buildDetailedNotification(
        connectedCount: Int,
        connectedStates: List<DeviceState>,
        force: Boolean,
    ) {
        val showReadings = Prefs.isNotificationShowReadings(this)
        val showDeviceCount = Prefs.isNotificationShowDeviceCount(this)
        val showConnectionStatus = Prefs.isNotificationShowConnectionStatus(this)
        val showAlerts = Prefs.isNotificationShowAlerts(this)
        val showAnomalies = Prefs.isNotificationShowAnomalies(this)
        
        // Build title
        val title = if (showDeviceCount && connectedCount > 1) {
            "Open RadiaCode ($connectedCount connected)"
        } else {
            "Open RadiaCode"
        }
        
        // Build content parts
        val parts = mutableListOf<String>()
        
        // Connection status
        if (showConnectionStatus) {
            val statusText = if (connectedCount == 1) {
                "${connectedStates.first().config.shortDisplayName} connected"
            } else {
                "$connectedCount devices connected"
            }
            // Only add if we're not already showing it in readings
            if (!showReadings) {
                parts.add(statusText)
            }
        }
        
        // Readings
        if (showReadings) {
            val readingTexts = connectedStates.mapNotNull { state ->
                state.lastReading?.let { reading ->
                    val name = state.config.shortDisplayName
                    "$name: ${"%.3f".format(reading.uSvPerHour)} μSv/h"
                }
            }
            if (readingTexts.isNotEmpty()) {
                val readingText = if (readingTexts.size <= 2) {
                    readingTexts.joinToString(" • ")
                } else {
                    "${readingTexts.take(2).joinToString(" • ")} +${readingTexts.size - 2} more"
                }
                parts.add(readingText)
            }
        }
        
        // Alert status
        if (showAlerts) {
            val alerts = Prefs.getSmartAlerts(this)
            val enabledAlerts = alerts.count { it.enabled }
            if (enabledAlerts > 0) {
                // Check if any alerts are currently triggered
                val triggeredCount = alertEvaluator.getActiveAlertCount()
                if (triggeredCount > 0) {
                    parts.add("⚠️ $triggeredCount alert(s) active")
                }
            }
        }
        
        // Anomaly detection
        if (showAnomalies) {
            // Check if any recent anomalies detected
            val hasAnomaly = connectedStates.any { state ->
                state.lastReading?.let { reading ->
                    // Simple anomaly check: reading significantly above normal background
                    reading.uSvPerHour > 0.5  // Threshold for "anomaly" - could be made configurable
                } ?: false
            }
            if (hasAnomaly) {
                parts.add("🔬 Elevated readings detected")
            }
        }
        
        val text = if (parts.isEmpty()) {
            "All systems normal"
        } else {
            parts.joinToString(" | ")
        }
        
        notifyUpdate(title, text, force = force, minIntervalMs = NOTIF_MIN_INTERVAL_CONNECTED_MS)
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

    private fun notifyUpdate(title: String, text: String, force: Boolean, minIntervalMs: Long) {
        try {
            val now = System.currentTimeMillis()

            // De-dupe: if nothing meaningful changed, don't repost.
            if (!force && title == lastNotifTitle && text == lastNotifText) return

            // Rate-limit: reduce NotificationManagerService:post spam.
            if (!force && now - lastNotifPostMs < minIntervalMs) return

            val nm = getSystemService(NotificationManager::class.java) ?: return
            Prefs.setServiceStatus(this, text)
            nm.notify(NOTIF_ID, buildNotification(title, text))

            lastNotifTitle = title
            lastNotifText = text
            lastNotifPostMs = now
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

        notifyUpdate("Open RadiaCode", reason, force = true, minIntervalMs = 0L)
    }

    private fun shouldPlayDataTick(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = pm?.isInteractive ?: true
        val inForeground = Prefs.isAppInForeground(this)
        return screenOn && inForeground
    }

    private fun getWidgetMinIntervalMs(): Long {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = pm?.isInteractive ?: true

        // Widgets should remain "real-time" whenever the screen is on (home screen included).
        // Only slow down when the screen is off.
        return if (screenOn) {
            WIDGET_MIN_INTERVAL_FOREGROUND_MS
        } else {
            WIDGET_MIN_INTERVAL_SCREEN_OFF_MS
        }
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
