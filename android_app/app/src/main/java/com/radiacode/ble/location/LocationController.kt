package com.radiacode.ble.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.radiacode.ble.Prefs
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Single owner of all location updates in the app.
 *
 * Design goals (from MAPPING_GPS_BATTERY_UPGRADE.md):
 * - Only ONE component may request location updates (this).
 * - Two modes: foreground (high accuracy) and background (battery-friendly).
 * - Clients subscribe to updates; they never call LocationManager/FLP directly.
 */
class LocationController private constructor(appContext: Context) {

    interface Listener {
        fun onLocation(location: Location)
    }

    enum class Mode {
        FOREGROUND,
        BACKGROUND,
        STOPPED,
    }

    private val context: Context = appContext.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(this.context)

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    private var lastLocation: Location? = null

    @Volatile
    private var currentMode: Mode = Mode.STOPPED

    /** Current GPS precision tier set by the UI (Point 12). */
    @Volatile
    private var gpsTier: Prefs.GpsTier = Prefs.GpsTier.PASSIVE

    // Tokens to avoid boolean state races across multiple clients.
    private val interactiveTokens = mutableSetOf<UUID>()
    private val backgroundTokens = mutableSetOf<UUID>()

    @Volatile
    private var isScreenOn: Boolean = true

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLocation = loc
            for (listener in listeners) {
                try {
                    listener.onLocation(loc)
                } catch (_: Throwable) {
                    // Client errors must not break location delivery.
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    reevaluateMode()
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOn = true
                    reevaluateMode()
                }
            }
        }
    }

    init {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            @Suppress("DEPRECATION")
            context.registerReceiver(screenReceiver, filter)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register screen receiver", t)
        }

        // Warm initial location snapshot.
        refreshLastKnownLocation()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        lastLocation?.let { listener.onLocation(it) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun getLastLocation(): Location? = lastLocation

    fun acquireInteractive(): UUID {
        val token = UUID.randomUUID()
        synchronized(lock) {
            interactiveTokens.add(token)
        }
        reevaluateMode()
        return token
    }

    fun releaseInteractive(token: UUID?) {
        if (token == null) return
        synchronized(lock) {
            interactiveTokens.remove(token)
        }
        reevaluateMode()
    }

    fun acquireBackground(): UUID {
        val token = UUID.randomUUID()
        synchronized(lock) {
            backgroundTokens.add(token)
        }
        reevaluateMode()
        return token
    }

    fun releaseBackground(token: UUID?) {
        if (token == null) return
        synchronized(lock) {
            backgroundTokens.remove(token)
        }
        reevaluateMode()
    }

    /**
     * Set the GPS precision tier from the UI (Point 12).
     * Forces a mode re-evaluation so the correct priority is applied.
     * When set to OFF, all location updates are stopped.
     */
    fun setGpsTier(tier: Prefs.GpsTier) {
        gpsTier = tier
        if (tier == Prefs.GpsTier.OFF) {
            // Immediately stop all location updates
            currentMode = Mode.STOPPED
            stopUpdatesInternal()
            Log.d(TAG, "GPS tier set to OFF - all location updates stopped")
            return
        }
        // Force restart with new priority
        val prev = currentMode
        currentMode = Mode.STOPPED
        reevaluateMode()
    }

    private fun desiredModeLocked(): Mode {
        // If GPS is OFF, never request location
        if (gpsTier == Prefs.GpsTier.OFF) return Mode.STOPPED

        val wantsAnything = interactiveTokens.isNotEmpty() || backgroundTokens.isNotEmpty()
        if (!wantsAnything) return Mode.STOPPED

        // If a user is actively using the map (and the screen is on), go high accuracy.
        if (interactiveTokens.isNotEmpty() && isScreenOn) return Mode.FOREGROUND

        // Otherwise (service running in background and/or screen off), use battery-friendly mode.
        return Mode.BACKGROUND
    }

    private fun reevaluateMode() {
        val desired: Mode
        synchronized(lock) {
            desired = desiredModeLocked()
        }

        if (desired == currentMode) return

        when (desired) {
            Mode.STOPPED -> stopUpdatesInternal()
            Mode.FOREGROUND -> startForegroundUpdatesInternal()
            Mode.BACKGROUND -> startBackgroundUpdatesInternal()
        }

        currentMode = desired
        Log.d(TAG, "Location mode -> $currentMode (interactive=${interactiveTokens.size} background=${backgroundTokens.size} screenOn=$isScreenOn)")
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun refreshLastKnownLocation() {
        if (!hasLocationPermission()) return
        try {
            fusedClient.lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        lastLocation = loc
                    }
                }
                .addOnFailureListener { /* ignore */ }
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startForegroundUpdatesInternal() {
        if (!hasLocationPermission()) {
            stopUpdatesInternal()
            return
        }

        stopUpdatesInternal()

        // Apply GPS tier (Point 12): OFF/PASSIVE/BALANCED/HIGH
        val (priority, interval, minInterval, minDist) = when (gpsTier) {
            Prefs.GpsTier.OFF -> {
                // Should not reach here, but guard anyway
                stopUpdatesInternal()
                return
            }
            Prefs.GpsTier.PASSIVE -> Quadruple(
                Priority.PRIORITY_PASSIVE,
                PASSIVE_INTERVAL_MS,
                PASSIVE_MIN_INTERVAL_MS,
                0f
            )
            Prefs.GpsTier.BALANCED -> Quadruple(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                BALANCED_INTERVAL_MS,
                BALANCED_MIN_INTERVAL_MS,
                BALANCED_MIN_DISTANCE_M
            )
            Prefs.GpsTier.HIGH -> Quadruple(
                Priority.PRIORITY_HIGH_ACCURACY,
                FOREGROUND_INTERVAL_MS,
                FOREGROUND_MIN_INTERVAL_MS,
                FOREGROUND_MIN_DISTANCE_M
            )
        }

        val request = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .setMinUpdateDistanceMeters(minDist)
            .setMaxUpdateDelayMillis(0L)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            refreshLastKnownLocation()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start foreground location", t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBackgroundUpdatesInternal() {
        if (!hasLocationPermission()) {
            stopUpdatesInternal()
            return
        }

        stopUpdatesInternal()

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, BACKGROUND_INTERVAL_MS)
            .setMinUpdateIntervalMillis(BACKGROUND_MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(BACKGROUND_MIN_DISTANCE_M)
            .setMaxUpdateDelayMillis(BACKGROUND_MAX_DELAY_MS)
            .build()

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            refreshLastKnownLocation()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start background location", t)
        }
    }

    private fun stopUpdatesInternal() {
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Throwable) {
        }
    }

    /** Simple 4-tuple for destructuring. */
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        private const val TAG = "RadiaCode"

        // Foreground HIGH: responsive for map UI.
        private const val FOREGROUND_INTERVAL_MS = 1_500L
        private const val FOREGROUND_MIN_INTERVAL_MS = 1_000L
        private const val FOREGROUND_MIN_DISTANCE_M = 1f

        // BALANCED: cell/Wi-Fi positioning, moderate battery.
        private const val BALANCED_INTERVAL_MS = 5_000L
        private const val BALANCED_MIN_INTERVAL_MS = 3_000L
        private const val BALANCED_MIN_DISTANCE_M = 5f

        // PASSIVE: zero additional battery, piggyback on other apps.
        private const val PASSIVE_INTERVAL_MS = 30_000L
        private const val PASSIVE_MIN_INTERVAL_MS = 10_000L

        // Background: battery-friendly. Displacement-driven, batched.
        private const val BACKGROUND_INTERVAL_MS = 10_000L
        private const val BACKGROUND_MIN_INTERVAL_MS = 5_000L
        private const val BACKGROUND_MIN_DISTANCE_M = 10f
        private const val BACKGROUND_MAX_DELAY_MS = 60_000L

        private val lock = Any()

        @Volatile
        private var instance: LocationController? = null

        fun getInstance(context: Context): LocationController {
            return instance ?: synchronized(lock) {
                instance ?: LocationController(context.applicationContext).also { instance = it }
            }
        }
    }
}
