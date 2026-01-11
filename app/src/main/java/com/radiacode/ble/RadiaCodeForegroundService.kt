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

        private const val EXTRA_ADDRESS = "address"

        private const val DEFAULT_POLL_MS = 1000L

        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L

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
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var client: RadiacodeBleClient? = null
    private var pollTask: ScheduledFuture<*>? = null

    private var consecutivePollFailures: Int = 0

    private var targetAddress: String? = null
    private var reconnectAttempts: Int = 0
    private var reconnectTask: ScheduledFuture<*>? = null

    private val btStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON) {
                Log.d(TAG, "btStateReceiver: STATE_ON")
                // If we have a preferred device and auto-connect enabled, try to restore session.
                if (Prefs.isAutoConnectEnabled(this@RadiaCodeForegroundService)) {
                    val addr = Prefs.getPreferredAddress(this@RadiaCodeForegroundService)
                    if (!addr.isNullOrBlank()) {
                        targetAddress = addr
                        forceReconnect("Bluetooth on")
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
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
                forceReconnect("Manual reconnect")
                return START_STICKY
            }
            ACTION_START, null -> {
                // fallthrough
            }
            else -> {
                // Unknown action; treat as start.
            }
        }

        val addressFromIntent = intent?.getStringExtra(EXTRA_ADDRESS)?.trim()?.takeIf { it.isNotEmpty() }
        val preferred = Prefs.getPreferredAddress(this)
        val address = addressFromIntent ?: preferred

        if (!Prefs.isAutoConnectEnabled(this)) {
            stopInternal("Auto-connect disabled")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasNotificationPermission()) {
            // Foreground service requires a visible notification; if we can't post notifications,
            // stop and require the user to open the app and grant POST_NOTIFICATIONS.
            Log.w(TAG, "service: missing POST_NOTIFICATIONS; stopping")
            stopInternal("Notification permission missing; open app")
            stopSelf()
            return START_NOT_STICKY
        }

        if (address.isNullOrBlank()) {
            updateForeground("No preferred device", "Open app and pick a preferred device")
            return START_STICKY
        }

        // If we're already connected and polling the same device, ignore duplicate starts.
        val alreadyTargeting = (targetAddress == address)
        val pollingActive = pollTask?.let { !it.isCancelled && !it.isDone } == true
        if (alreadyTargeting && client != null && pollingActive) {
            updateForeground("Running", address)
            return START_STICKY
        }

        targetAddress = address
        updateForeground("Connecting", address)

        startOrRestartSession("startCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        stopInternal("Service destroyed")
        try { unregisterReceiver(btStateReceiver) } catch (_: Throwable) {}
        try { scheduler.shutdownNow() } catch (_: Throwable) {}
        try { executor.shutdownNow() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RadiaCode live", NotificationManager.IMPORTANCE_LOW).apply {
                description = "RadiaCode background connection and live reading"
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
        reconnectTask?.cancel(true)
        reconnectTask = null

        pollTask?.cancel(true)
        pollTask = null

        client?.close()
        client = null

        notifyUpdate("RadiaCode", reason)
    }

    private fun startOrRestartSession(why: String) {
        executor.execute {
            reconnectTask?.cancel(true)
            reconnectTask = null

            pollTask?.cancel(true)
            pollTask = null

            client?.close()
            client = null

            val address = targetAddress
            if (address.isNullOrBlank()) {
                notifyUpdate("RadiaCode", "No preferred device")
                return@execute
            }

            if (!hasBlePermission()) {
                notifyUpdate("RadiaCode", "Bluetooth permission missing; open app")
                return@execute
            }

            val btManager = getSystemService(BluetoothManager::class.java)
            val adapter = btManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                scheduleReconnect("Bluetooth off")
                return@execute
            }

            val device = try {
                adapter.getRemoteDevice(address)
            } catch (t: Throwable) {
                notifyUpdate("RadiaCode", "Bad preferred address")
                return@execute
            }

            val c = RadiacodeBleClient(applicationContext) { msg ->
                // Detect disconnects via status callback.
                if (msg.startsWith("Disconnected") || msg.startsWith("GATT error") || msg.startsWith("Service discovery failed")) {
                    scheduleReconnect(msg)
                }
                notifyUpdate("RadiaCode", msg)
            }

            client = c

            Log.d(TAG, "service connect: $address ($why)")
            c.connect(device)

            c.ready()
                .thenCompose { c.initializeSession() }
                .thenRun {
                    reconnectAttempts = 0
                    notifyUpdate("RadiaCode", "Connected")
                    startPolling()
                }
                .exceptionally { t ->
                    Log.e(TAG, "service init failed", t)
                    scheduleReconnect("Init failed")
                    null
                }
        }
    }

    private fun startPolling() {
        pollTask?.cancel(true)
        pollTask = null

        consecutivePollFailures = 0
        scheduleNextPoll(0)
    }

    private fun scheduleNextPoll(delayMs: Long) {
        pollTask?.cancel(true)
        pollTask = scheduler.schedule(
            {
                val c = client ?: return@schedule
                c.readDataBuf()
                    .thenAccept { buf ->
                        val rt = RadiacodeDataBuf.decodeLatestRealTime(buf) ?: return@thenAccept
                        consecutivePollFailures = 0

                        val uSvPerHour = rt.doseRate * 10000.0f
                        val timestampMs = System.currentTimeMillis()
                        Prefs.setLastReading(this, uSvPerHour, rt.countRate, timestampMs)
                        appendReadingCsvIfNew(timestampMs, uSvPerHour, rt.countRate)
                        RadiaCodeWidgetProvider.updateAll(this)
                        notifyUpdate(
                            "RadiaCode",
                            "${"%.3f".format(uSvPerHour)} μSv/h • ${"%.1f".format(rt.countRate)} cps"
                        )
                    }
                    .exceptionally { t ->
                        consecutivePollFailures += 1
                        Log.e(TAG, "service poll failed (count=$consecutivePollFailures)", t)
                        if (consecutivePollFailures >= 3) {
                            scheduleReconnect("Read failed")
                        }
                        null
                    }
                    .whenComplete { _, _ ->
                        // Schedule the next poll only after the current one completes.
                        if (client != null && (reconnectTask?.isDone != false)) {
                            scheduleNextPoll(Prefs.getPollIntervalMs(this, DEFAULT_POLL_MS))
                        }
                    }
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun forceReconnect(reason: String) {
        Log.d(TAG, "service forceReconnect: $reason")
        reconnectTask?.cancel(true)
        reconnectTask = null
        reconnectAttempts = 0

        pollTask?.cancel(true)
        pollTask = null

        client?.close()
        client = null

        val addr = targetAddress ?: Prefs.getPreferredAddress(this)
        if (!addr.isNullOrBlank()) {
            targetAddress = addr
            notifyUpdate("RadiaCode", "Reconnecting… ($reason)")
            startOrRestartSession("forceReconnect")
        } else {
            updateForeground("No preferred device", "Open app and pick a preferred device")
        }
    }

    private fun appendReadingCsvIfNew(timestampMs: Long, uSvPerHour: Float, cps: Float) {
        val lastLogged = Prefs.getLastCsvTimestampMs(this)
        if (timestampMs <= lastLogged) return
        Prefs.setLastCsvTimestampMs(this, timestampMs)

        try {
            val file = File(filesDir, "readings.csv")
            // Rotate if it gets too big (simple safety guard).
            if (file.exists() && file.length() > 2_000_000L) {
                val old = File(filesDir, "readings.old.csv")
                try { if (old.exists()) old.delete() } catch (_: Throwable) {}
                try { file.renameTo(old) } catch (_: Throwable) {}
            }

            val isNew = !file.exists()
            file.parentFile?.mkdirs()
            file.outputStream().writer(Charsets.UTF_8).buffered(8 * 1024).use { w ->
                if (isNew) {
                    w.appendLine("timestamp_ms,usv_per_h,cps")
                }
                w.append(timestampMs.toString())
                w.append(',')
                w.append(String.format(java.util.Locale.US, "%.6f", uSvPerHour))
                w.append(',')
                w.append(String.format(java.util.Locale.US, "%.3f", cps))
                w.appendLine()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "appendReadingCsvIfNew failed", t)
        }
    }

    private fun scheduleReconnect(reason: String) {
        // Avoid spamming; schedule once.
        if (reconnectTask?.isDone == false) return

        pollTask?.cancel(true)
        pollTask = null

        client?.close()
        client = null

        reconnectAttempts += 1
        val backoff = min(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS * reconnectAttempts.toLong())
        Log.d(TAG, "service scheduleReconnect in ${backoff}ms: $reason")
        notifyUpdate("RadiaCode", "Reconnecting… ($reason)")

        reconnectTask = scheduler.schedule(
            { startOrRestartSession("reconnect") },
            backoff,
            TimeUnit.MILLISECONDS
        )
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
