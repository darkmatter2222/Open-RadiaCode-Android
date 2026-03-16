package com.radiacode.ble

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import android.view.HapticFeedbackConstants
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.radiacode.ble.spectrogram.SpectrogramPrefs
import com.radiacode.ble.ui.ProChartView
import com.radiacode.ble.ui.VegaIntroDialog
import com.radiacode.ble.ui.VegaGpsWarningDialog
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val AUDIO_PERMISSION_REQUEST_CODE = 101
        private const val TAG = "RadiaCode"
        private const val MAX_CHART_POINTS = 800
    }

    // Navigation - Tab layout (Point 1)
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnSettingsGear: ImageView

    // Toolbar status
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var statusContainer: View
    private lateinit var readingPulseDot: View
    private lateinit var spectrogramRecordingDot: View

    // Geiger tick
    private lateinit var btnGeigerToggle: ImageView
    private var geigerTickEngine: GeigerTickEngine? = null
    private var deltaBaseline: Float = Float.NaN  // EMA baseline for delta modes
    


    private val mainHandler = Handler(Looper.getMainLooper())

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var chartLoadFuture: Future<*>? = null
    private var chartLoadToken: Int = 0

    private var uiRunnable: Runnable? = null
    private var lastReadingTimestampMs: Long = 0L
    private var lastMapReadingTimestampMs: Long = 0L
    var sessionStartMs: Long = System.currentTimeMillis()
        private set
    var sampleCount: Int = 0
        private set

    // Cache selection state to avoid frequent Prefs reads on the UI thread.
    private var selectedDeviceIdCache: String? = null
    private var isAllDevicesModeCache: Boolean = false

    // Coalesce expensive redraws (charts/intelligence) to at most once per second.
    @Volatile private var uiDirty: Boolean = true
    @Volatile private var lastChartUpdateMs: Long = 0L
    private val chartUpdateThrottleMs: Long = 250L  // Max 4 chart updates per second

    private var lastDeviceRefreshMs: Long = 0L
    private var lastMetadataRefreshMs: Long = 0L
    private var lastDeviceListSignature: String? = null

    private val doseHistory = SampleHistory(4000)
    private val cpsHistory = SampleHistory(4000)

    private var pausedSnapshotDose: SampleHistory.Series? = null
    private var pausedSnapshotCps: SampleHistory.Series? = null

    private var lastShownReading: Prefs.LastReading? = null

    // Trend tracking
    private var previousDose: Float = 0f
    private var previousCps: Float = 0f
    
    // Track device connection states from service broadcasts
    private val deviceConnectionStates = mutableMapOf<String, DeviceConnectionState>()
    private var shownConnectionSnackbarForDevice: String? = null

    private val readingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != RadiaCodeForegroundService.ACTION_READING) return
            val paused = Prefs.isPauseLiveEnabled(this@MainActivity)
            if (paused) return

            pulseReadingDot()

            val ts = intent.getLongExtra(RadiaCodeForegroundService.EXTRA_TS_MS, 0L)
            val uSvH = intent.getFloatExtra(RadiaCodeForegroundService.EXTRA_USV_H, 0f)
            val cps = intent.getFloatExtra(RadiaCodeForegroundService.EXTRA_CPS, 0f)
            val deviceId = intent.getStringExtra(RadiaCodeForegroundService.EXTRA_DEVICE_ID)

            // Feed Geiger tick engine based on selected mode
            val geigerMode = Prefs.getGeigerTickMode(this@MainActivity)
            if (geigerMode != Prefs.GeigerTickMode.OFF) {
                val rate = when (geigerMode) {
                    Prefs.GeigerTickMode.CPS -> {
                        geigerTickEngine?.deltaDirection = 0
                        cps
                    }
                    Prefs.GeigerTickMode.NSV -> {
                        geigerTickEngine?.deltaDirection = 0
                        uSvH * 1000f
                    }
                    Prefs.GeigerTickMode.DELTA_CPS -> computeDeltaRate(cps)
                    Prefs.GeigerTickMode.DELTA_NSV -> computeDeltaRate(uSvH * 1000f)
                    else -> cps
                }
                geigerTickEngine?.onDataReceived(rate)
            }

            // Forward to map fragment
            if (ts > 0L && ts != lastMapReadingTimestampMs) {
                lastMapReadingTimestampMs = ts
                getMapFragment()?.addReading(uSvH, cps)
            }

            // In all-devices mode, keep charts/metrics quiet (overlay explains why).
            if (isAllDevicesModeCache) {
                uiDirty = true
                triggerThrottledChartUpdate()
                return
            }

            // If a device is selected, ignore readings from other devices for charts/metrics.
            val selectedId = selectedDeviceIdCache
            if (selectedId != null && deviceId != selectedId) return

            // Update metric cards and histories immediately without hitting SharedPreferences.
            if (ts > 0L && ts != lastReadingTimestampMs) {
                lastReadingTimestampMs = ts
                val last = Prefs.LastReading(uSvPerHour = uSvH, cps = cps, timestampMs = ts)
                lastShownReading = last
                
                // Calculate trends
                val trendDose = uSvH - previousDose
                val trendCps = cps - previousCps
                previousDose = uSvH
                previousCps = cps
                getDashboardFragment()?.updateMetricCards(uSvH, cps, trendDose, trendCps)

                ensureHistoryCapacity()
                doseHistory.add(ts, uSvH)
                cpsHistory.add(ts, cps)
                sampleCount++

                // Auto-start a session if none is active.
                // Session data recording is handled by the foreground service
                // so it works regardless of which activity is in the foreground.
                if (!SessionManager.isRecording(this@MainActivity)) {
                    SessionManager.startSession(this@MainActivity)
                }

                uiDirty = true
                
                // Trigger immediate chart update (throttled to avoid overwhelming the UI)
                triggerThrottledChartUpdate()
            }
        }
    }
    
    /**
     * Receiver for VEGA Statistical Intelligence updates.
     * Updates the chart forecast visualization when forecast data is available.
     */
    private val statisticalReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != RadiaCodeForegroundService.ACTION_STATISTICAL_UPDATE) return
            getDashboardFragment()?.onStatisticalUpdate(intent)
        }
    }
    
    /**
     * Trigger a chart update immediately if enough time has passed since the last one.
     * This makes charts feel responsive without overwhelming the UI.
     */
    private fun triggerThrottledChartUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastChartUpdateMs < chartUpdateThrottleMs) return
        lastChartUpdateMs = now
        getDashboardFragment()?.refreshCharts()
        uiDirty = false
    }

    private fun pulseReadingDot() {
        if (!::readingPulseDot.isInitialized) return
        readingPulseDot.animate().cancel()
        readingPulseDot.scaleX = 1f
        readingPulseDot.scaleY = 1f
        readingPulseDot.alpha = 0.9f
        readingPulseDot.animate()
            .scaleX(1.8f)
            .scaleY(1.8f)
            .alpha(0.25f)
            .setDuration(180)
            .withEndAction {
                readingPulseDot.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(0.6f)
                    .setDuration(220)
                    .start()
            }
            .start()
    }
    
    private val deviceStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != RadiaCodeForegroundService.ACTION_DEVICE_STATE_CHANGED) return
            val deviceId = intent.getStringExtra(RadiaCodeForegroundService.EXTRA_DEVICE_ID) ?: return
            val stateName = intent.getStringExtra(RadiaCodeForegroundService.EXTRA_CONNECTION_STATE) ?: return
            val state = try {
                DeviceConnectionState.valueOf(stateName)
            } catch (_: Exception) {
                DeviceConnectionState.DISCONNECTED
            }
            deviceConnectionStates[deviceId] = state
            // Show connection Snackbar on first connect
            if (state == DeviceConnectionState.CONNECTED && deviceId != shownConnectionSnackbarForDevice) {
                shownConnectionSnackbarForDevice = deviceId
                getDashboardFragment()?.showConnectedSnackbar(deviceId)
            }
            // Forward to device fragment
            getDeviceFragment()?.updateConnectionStatus()
        }
    }
    
    private val findDevicesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val address = result.data?.getStringExtra(FindDevicesActivity.EXTRA_DEVICE_ADDRESS) ?: return@registerForActivityResult
        
        // Multi-device: Add to device list instead of just setting preferred
        val existingDevice = Prefs.getDeviceByMac(this, address)
        if (existingDevice == null) {
            val newDevice = DeviceConfig(
                macAddress = address,
                enabled = true
            )
            Prefs.addDevice(this, newDevice)
        } else if (!existingDevice.enabled) {
            // Re-enable if it was disabled
            Prefs.updateDevice(this, existingDevice.copy(enabled = true))
        }
        
        // Also set as preferred for backward compatibility
        Prefs.setPreferredAddress(this, address)
        Prefs.setAutoConnectEnabled(this, true)
        
        // Tell the service to reload device list (picks up the new device)
        RadiaCodeForegroundService.reloadDevices(this)

        doseHistory.clear()
        cpsHistory.clear()
        lastReadingTimestampMs = 0L
        pausedSnapshotDose = null
        pausedSnapshotCps = null
        sessionStartMs = System.currentTimeMillis()
        sampleCount = 0
        updateStatus(true, "Connecting")
    }

    private val requiredPermissions: Array<String>
        get() {
            val perms = ArrayList<String>(4)
            if (Build.VERSION.SDK_INT >= 31) {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_CONNECT
            } else {
                perms += Manifest.permission.ACCESS_FINE_LOCATION
            }
            // Always request location for map feature
            if (Build.VERSION.SDK_INT >= 31) {
                perms += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= 33) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen (must be before super.onCreate)
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Edge-to-edge: let content draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main_tabs)

        // Migrate single device to multi-device if needed
        Prefs.migrateToMultiDevice(this)

        bindViews()
        setupTabs()
        setupSettingsGear()
        updateStatus(false, "Starting")

        // Apply system bar insets so content doesn't hide behind navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.viewPager)) { v, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBar.bottom)
            insets
        }

        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
        } else {
            startServiceIfConfigured()
        }
        
        // Handle intent from widget (e.g., open settings for GPS tracking)
        handleWidgetIntent(intent)
        
        // Check for first-run intro
        checkAndShowIntro()

        // Register activity ref for debug test receiver
        if (BuildConfig.DEBUG) {
            com.radiacode.ble.testing.TestReceiver.activityRef = this
            val filter = android.content.IntentFilter(com.radiacode.ble.testing.TestReceiver.ACTION)
            registerReceiver(
                com.radiacode.ble.testing.TestReceiver(),
                filter,
                android.content.Context.RECEIVER_EXPORTED
            )
            android.util.Log.d("RadiaCode", "TestReceiver registered (debug build)")
        }
    }
    
    /**
     * Handle intents from widgets (e.g., to open settings for GPS tracking).
     */
    private fun handleWidgetIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_settings", false) == true) {
            if (intent.getBooleanExtra("scroll_to_map", false)) {
                // Show the GPS tracking warning dialog
                mainHandler.postDelayed({
                    showGpsTrackingWarningDialog()
                }, 500)
            }
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // ViewPager2 bug: when the container width changes (rotation) the
        // internal RecyclerView's scroll offset becomes stale and the pager
        // lands between two pages.  ViewPager2.setCurrentItem() is useless
        // here because it short-circuits when currentItem hasn't changed.
        //
        // Fix: wait for the layout pass that applies the new dimensions,
        // then tell the internal RecyclerView to snap directly to the
        // correct adapter position.
        val currentItem = viewPager.currentItem
        viewPager.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                viewPager.removeOnLayoutChangeListener(this)
                // Post so we run after the layout pass is fully complete
                viewPager.post {
                    (viewPager.getChildAt(0) as? RecyclerView)
                        ?.scrollToPosition(currentItem)
                }
            }
        })
    }
    
    /**
     * Check if this is first launch or app update, and show the Vega intro if needed.
     */
    private fun checkAndShowIntro() {
        if (Prefs.shouldShowIntro(this)) {
            // Delay slightly to let the UI settle
            mainHandler.postDelayed({
                showVegaIntro(isFirstRun = true)
            }, 500)
        }
    }
    
    /**
     * Show the Vega introduction dialog.
     * Requests RECORD_AUDIO permission for audio visualization.
     * @param isFirstRun If true, marks the intro as seen after dismissal.
     */
    private fun showVegaIntro(isFirstRun: Boolean = false) {
        // Request RECORD_AUDIO permission for real-time audio visualization
        // This allows the waveform to react to the actual audio being played
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            // Request permission, then show intro regardless of result
            pendingIntroFirstRun = isFirstRun
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_REQUEST_CODE
            )
        } else {
            // Permission already granted, show intro
            showVegaIntroDialog(isFirstRun)
        }
    }
    
    private var pendingIntroFirstRun = false
    
    private fun showVegaIntroDialog(isFirstRun: Boolean) {
        val dialog = VegaIntroDialog(this) {
            // Callback when dismissed
            if (isFirstRun) {
                Prefs.markIntroSeen(this)
            }
        }
        dialog.show()
    }
    
    /**
     * Show the GPS tracking warning dialog with Vega voice.
     * Called when user tries to enable GPS tracking.
     */
    private fun showGpsTrackingWarningDialog() {
        val dialog = VegaGpsWarningDialog(
            this,
            onConfirm = {
                Prefs.setGpsTrackingEnabled(this, true)
                RadiaCodeForegroundService.reloadDevices(this)
            },
            onCancel = { }
        )
        dialog.show()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        btnSettingsGear = findViewById(R.id.btnSettingsGear)

        statusDot = findViewById(R.id.statusDot)
        statusLabel = findViewById(R.id.statusLabel)
        statusContainer = statusLabel.parent as View
        readingPulseDot = findViewById(R.id.readingPulseDot)
        spectrogramRecordingDot = findViewById(R.id.spectrogramRecordingDot)

        // Geiger tick toggle (toolbar icon) - shows mode picker modal
        btnGeigerToggle = findViewById(R.id.btnGeigerToggle)
        updateGeigerIcon()
        btnGeigerToggle.setOnClickListener { showGeigerModeDialog() }
    }

    private fun setupTabs() {
        val adapter = MainPagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 2 // keep all tabs alive

        // Subtle crossfade transition when switching tabs
        viewPager.setPageTransformer { page, position ->
            page.alpha = when {
                position <= -1f || position >= 1f -> 0f      // off-screen
                position == 0f -> 1f                          // fully visible
                else -> 1f - kotlin.math.abs(position) * 0.5f // fading
            }
        }

        // Disable ViewPager2 swiping on the Map tab to prevent
        // horizontal swipe conflicts with the map's pan gesture.
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewPager.isUserInputEnabled = position != 1  // disable swipe on Map tab
            }
        })

        val tabIcons = intArrayOf(
            R.drawable.ic_tab_dashboard,
            R.drawable.ic_tab_map,
            R.drawable.ic_tab_device
        )
        val tabLabels = arrayOf("Dashboard", "Map", "Device")

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabLabels[position]
            tab.setIcon(tabIcons[position])
        }.attach()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tab.view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupSettingsGear() {
        btnSettingsGear.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // --- Fragment accessors ---
    fun getDashboardFragment(): DashboardFragment? =
        supportFragmentManager.findFragmentByTag("f0") as? DashboardFragment

    fun getMapFragment(): MapFragment? =
        supportFragmentManager.findFragmentByTag("f1") as? MapFragment

    fun getDeviceFragment(): DeviceFragment? =
        supportFragmentManager.findFragmentByTag("f2") as? DeviceFragment

    fun onDeviceDiscovered(address: String) {
        val existingDevice = Prefs.getDeviceByMac(this, address)
        if (existingDevice == null) {
            val newDevice = DeviceConfig(
                macAddress = address,
                enabled = true
            )
            Prefs.addDevice(this, newDevice)
        } else if (!existingDevice.enabled) {
            Prefs.updateDevice(this, existingDevice.copy(enabled = true))
        }
        Prefs.setPreferredAddress(this, address)
        Prefs.setAutoConnectEnabled(this, true)
        RadiaCodeForegroundService.reloadDevices(this)
        doseHistory.clear()
        cpsHistory.clear()
        lastReadingTimestampMs = 0L
        pausedSnapshotDose = null
        pausedSnapshotCps = null
        sessionStartMs = System.currentTimeMillis()
        sampleCount = 0
        updateStatus(true, "Connecting")
    }

    fun getDoseHistory(): SampleHistory = doseHistory
    fun getCpsHistory(): SampleHistory = cpsHistory
    fun getSelectedDeviceId(): String? = selectedDeviceIdCache
    fun getDeviceConnectionStates(): Map<String, DeviceConnectionState> = deviceConnectionStates

    /**
     * Called by DashboardFragment when time window chips change.
     * Reloads chart history for the new window.
     */
    fun onTimeWindowChanged() {
        uiDirty = true
        getDashboardFragment()?.refreshCharts()
    }

    /**
     * Build export data points from current dose/cps history.
     * Used by MapFragment for data export.
     */
    fun buildExportDataPoints(): List<DataExportManager.ExportDataPoint> {
        val doseSeries = doseHistory.lastN(doseHistory.size())
        val cpsSeries = cpsHistory.lastN(cpsHistory.size())
        val points = mutableListOf<DataExportManager.ExportDataPoint>()
        for (i in doseSeries.timestampsMs.indices) {
            points.add(
                DataExportManager.ExportDataPoint(
                    timestampMs = doseSeries.timestampsMs[i],
                    uSvPerHour = doseSeries.values[i],
                    cps = if (i < cpsSeries.values.size) cpsSeries.values[i] else 0f,
                    deviceId = selectedDeviceIdCache
                )
            )
        }
        return points
    }

    /**
     * Show the chart settings dialog (time window + units).
     */

    override fun onResume() {
        super.onResume()
        Prefs.setAppInForeground(this, true)
        registerReadingReceiver()
        reloadChartHistoryForSelectedDeviceAsync()
        startUiLoop()
        
        // Geiger tick engine: start if enabled
        if (Prefs.isGeigerTickEnabled(this)) {
            geigerTickEngine = GeigerTickEngine.getInstance(this)
            geigerTickEngine?.start()
        }
        updateGeigerIcon()

        // Update spectrogram recording indicator
        updateSpectrogramRecordingIndicator()
    }
    
    private fun showGeigerModeDialog() {
        val modes = arrayOf(
            "CPS (Counts Per Second)",
            "nSv/h (Nano Sieverts)",
            "\u0394 Count Rate (delta CPS)",
            "\u0394 Dose Rate (delta nSv/h)",
            "Off"
        )
        val currentMode = Prefs.getGeigerTickMode(this)
        val checkedIndex = when (currentMode) {
            Prefs.GeigerTickMode.CPS -> 0
            Prefs.GeigerTickMode.NSV -> 1
            Prefs.GeigerTickMode.DELTA_CPS -> 2
            Prefs.GeigerTickMode.DELTA_NSV -> 3
            Prefs.GeigerTickMode.OFF -> 4
        }
        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle("Geiger Tick Audio Source")
            .setSingleChoiceItems(modes, checkedIndex) { dialog, which ->
                val selected = when (which) {
                    0 -> Prefs.GeigerTickMode.CPS
                    1 -> Prefs.GeigerTickMode.NSV
                    2 -> Prefs.GeigerTickMode.DELTA_CPS
                    3 -> Prefs.GeigerTickMode.DELTA_NSV
                    else -> Prefs.GeigerTickMode.OFF
                }
                Prefs.setGeigerTickMode(this, selected)
                updateGeigerIcon()
                // Reset delta baselines when switching modes
                deltaBaseline = Float.NaN
                if (selected != Prefs.GeigerTickMode.OFF) {
                    geigerTickEngine = GeigerTickEngine.getInstance(this)
                    geigerTickEngine?.start()
                } else {
                    geigerTickEngine?.stop()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun updateGeigerIcon() {
        val mode = Prefs.getGeigerTickMode(this)
        val active = mode != Prefs.GeigerTickMode.OFF
        btnGeigerToggle.alpha = if (active) 1f else 0.3f
        // Tint color: amber when active, muted when off
        val color = if (active) {
            android.graphics.Color.parseColor("#FFB300")  // pro_amber
        } else {
            android.graphics.Color.parseColor("#80FFFFFF")
        }
        btnGeigerToggle.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    /**
     * Compute tick rate from delta (change) in a value.
     * Uses an EMA baseline; returns a tick rate proportional to % change.
     * Sets deltaDirection on the engine: +1 for increase (high pitch), -1 for decrease (low pitch).
     * Dead zone: changes below 2% are silent.
     */
    private fun computeDeltaRate(currentValue: Float): Float {
        val ema_alpha = 0.15f  // smoothing factor: ~7 readings to converge
        val deadZone = 0.02f   // 2% dead zone

        if (deltaBaseline.isNaN()) {
            deltaBaseline = currentValue
            geigerTickEngine?.deltaDirection = 0
            return 0.1f  // near-silent while baseline initializes
        }

        // Update EMA baseline
        val oldBaseline = deltaBaseline
        deltaBaseline = ema_alpha * currentValue + (1f - ema_alpha) * oldBaseline

        // Percent change from baseline
        val pctChange = if (oldBaseline > 0.001f) {
            (currentValue - oldBaseline) / oldBaseline
        } else {
            0f
        }

        // Dead zone: small fluctuations are silent
        if (kotlin.math.abs(pctChange) < deadZone) {
            geigerTickEngine?.deltaDirection = 0
            return 0.1f  // near-silent
        }

        // Direction: +1 = increase (high pitch), -1 = decrease (low pitch)
        geigerTickEngine?.deltaDirection = if (pctChange > 0) 1 else -1

        // Map |change| to tick rate: 2% -> ~1 tick/s, 100% -> ~50 ticks/s
        val magnitude = kotlin.math.abs(pctChange)
        return (magnitude * 50f).coerceIn(1f, 500f)
    }
    
    /**
     * Update the spectrogram recording indicator in the toolbar.
     */
    private fun updateSpectrogramRecordingIndicator() {
        val isRecording = SpectrogramPrefs.isRecordingEnabled(this)
        spectrogramRecordingDot.visibility = if (isRecording) View.VISIBLE else View.GONE
        
        if (isRecording) {
            // Set red color and start pulsing animation
            (spectrogramRecordingDot.background as? GradientDrawable)?.setColor(
                ContextCompat.getColor(this, R.color.pro_red)
            )
            startSpectrogramRecordingPulse()
        } else {
            spectrogramRecordingDot.animate().cancel()
            spectrogramRecordingDot.alpha = 1f
        }
    }
    
    private fun startSpectrogramRecordingPulse() {
        spectrogramRecordingDot.animate()
            .alpha(0.3f)
            .setDuration(600)
            .withEndAction {
                if (SpectrogramPrefs.isRecordingEnabled(this)) {
                    spectrogramRecordingDot.animate()
                        .alpha(1f)
                        .setDuration(600)
                        .withEndAction { startSpectrogramRecordingPulse() }
                        .start()
                }
            }
            .start()
    }

    private fun reloadChartHistoryForSelectedDeviceAsync() {
        val token = ++chartLoadToken
        chartLoadFuture?.cancel(true)

        // Clear immediately so we render fast.
        doseHistory.clear()
        cpsHistory.clear()
        sampleCount = 0
        lastReadingTimestampMs = 0L
        uiDirty = true

        chartLoadFuture = ioExecutor.submit {
            val devices = Prefs.getDevices(this)
            var selectedDeviceId = Prefs.getSelectedDeviceId(this)

            // If only one device exists, auto-select it.
            if (selectedDeviceId == null && devices.size == 1) {
                selectedDeviceId = devices.first().id
            }

            val isAllDevicesMode = (selectedDeviceId == null && devices.size > 1)
            val history = if (selectedDeviceId != null) {
                Prefs.getDeviceChartHistory(this, selectedDeviceId)
            } else {
                emptyList()
            }

            mainHandler.post {
                if (token != chartLoadToken) return@post

                selectedDeviceIdCache = selectedDeviceId
                isAllDevicesModeCache = isAllDevicesMode

                doseHistory.clear()
                cpsHistory.clear()
                sampleCount = 0

                if (selectedDeviceId != null) {
                    android.util.Log.d("RadiaCode", "Loading ${history.size} readings for device $selectedDeviceId")
                    for (reading in history) {
                        doseHistory.add(reading.timestampMs, reading.uSvPerHour)
                        cpsHistory.add(reading.timestampMs, reading.cps)
                        sampleCount++
                    }
                } else {
                    android.util.Log.d("RadiaCode", "All devices mode - no chart data to load")
                }

                uiDirty = true
            }
        }
    }


    override fun onPause() {
        super.onPause()
        unregisterReadingReceiver()
        stopUiLoop()
        Prefs.setAppInForeground(this, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Finalize any active recording session
        if (SessionManager.isRecording(this)) {
            SessionManager.stopSession(this)
        }
        if (BuildConfig.DEBUG) {
            com.radiacode.ble.testing.TestReceiver.activityRef = null
        }
        stopUiLoop()
        chartLoadFuture?.cancel(true)
        ioExecutor.shutdownNow()
        geigerTickEngine = null
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (hasAllPermissions()) {
                    startServiceIfConfigured()
                } else {
                    updateStatus(false, "Permissions")
                }
            }
            AUDIO_PERMISSION_REQUEST_CODE -> {
                // Show intro regardless of permission result
                // The visualization will fall back to simulated if permission denied
                showVegaIntroDialog(pendingIntroFirstRun)
            }
        }
    }

    // Back navigation handled by system OnBackPressedDispatcher (no override needed)

    private fun shareCsv() {
        val file = File(filesDir, "readings.csv")
        if (!file.exists() || file.length() == 0L) return

        val uri = try {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        } catch (_: Throwable) { return }

        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(share, "Share readings"))
    }
    
    private var pendingCalibrationReceiver: BroadcastReceiver? = null
    private var isCalibrationDownloadInProgress: Boolean = false

    private fun downloadKevCalibration() {
        if (isCalibrationDownloadInProgress) {
            android.widget.Toast.makeText(this, "Calibration download already in progressâ€¦", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        isCalibrationDownloadInProgress = true

        val selectedDeviceId = selectedDeviceIdCache
        
        // Show progress
        android.widget.Toast.makeText(this, "Reading calibration from device...", android.widget.Toast.LENGTH_SHORT).show()
        
        // Register a one-shot receiver for calibration data
        pendingCalibrationReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        
        pendingCalibrationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == RadiaCodeForegroundService.ACTION_CALIBRATION_DATA) {
                    // Unregister immediately
                    try { unregisterReceiver(this) } catch (_: Exception) {}
                    pendingCalibrationReceiver = null

                    isCalibrationDownloadInProgress = false
                    
                    // Check for error
                    val success = intent.getBooleanExtra("success", true)
                    if (!success) {
                        val error = intent.getStringExtra("error") ?: "Unknown error"
                        android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
                        return
                    }
                    
                    val deviceId = intent.getStringExtra(RadiaCodeForegroundService.EXTRA_DEVICE_ID) ?: "unknown"
                    val a0 = intent.getFloatExtra(RadiaCodeForegroundService.EXTRA_CALIB_A0, 0f)
                    val a1 = intent.getFloatExtra(RadiaCodeForegroundService.EXTRA_CALIB_A1, 1f)
                    val a2 = intent.getFloatExtra(RadiaCodeForegroundService.EXTRA_CALIB_A2, 0f)
                    
                    // Generate and share CSV
                    exportCalibrationAsCsv(deviceId, a0, a1, a2)
                }
            }
        }
        
        val filter = IntentFilter(RadiaCodeForegroundService.ACTION_CALIBRATION_DATA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pendingCalibrationReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pendingCalibrationReceiver, filter)
        }
        
        // Request calibration from service
        RadiaCodeForegroundService.requestCalibration(this, selectedDeviceId)
        
        // Timeout after 10 seconds
        mainHandler.postDelayed({
            pendingCalibrationReceiver?.let {
                try { unregisterReceiver(it) } catch (_: Exception) {}
                pendingCalibrationReceiver = null
                isCalibrationDownloadInProgress = false
                android.widget.Toast.makeText(this, "Calibration request timed out. Is device connected?", android.widget.Toast.LENGTH_LONG).show()
            }
        }, 10_000)
    }
    
    private fun exportCalibrationAsCsv(deviceId: String, a0: Float, a1: Float, a2: Float) {
        try {
            // Generate CSV content with channel-to-keV mapping
            val sb = StringBuilder()
            sb.appendLine("# RadiaCode Energy Calibration Export")
            sb.appendLine("# Device: $deviceId")
            sb.appendLine("# Export Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            sb.appendLine("#")
            sb.appendLine("# Calibration Coefficients (Energy = a0 + a1*channel + a2*channel^2):")
            sb.appendLine("# a0 (offset) = $a0 keV")
            sb.appendLine("# a1 (linear) = $a1 keV/channel")
            sb.appendLine("# a2 (quadratic) = $a2 keV/channel^2")
            sb.appendLine("#")
            sb.appendLine("Channel,Energy_keV")
            
            // Generate channel-to-energy mapping for all 1024 channels
            for (channel in 0..1023) {
                val energy = a0 + a1 * channel + a2 * channel * channel
                sb.appendLine("$channel,${String.format(java.util.Locale.US, "%.4f", energy)}")
            }
            
            // Save to file
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val filename = "radiacode_keV_calibration_$timestamp.csv"
            val file = File(filesDir, filename)
            file.writeText(sb.toString())
            
            // Share via Intent
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "RadiaCode Energy Calibration")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(android.content.Intent.createChooser(shareIntent, "Share keV Calibration"))
            
        } catch (e: Exception) {
            android.util.Log.e("RadiaCode", "Failed to export calibration", e)
            android.widget.Toast.makeText(
                this, 
                "Failed to export calibration: ${e.message}", 
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startServiceIfConfigured() {
        // Multi-device: Check if we have any enabled devices
        val devices = Prefs.getDevices(this)
        val hasEnabledDevices = devices.any { it.enabled }
        
        // Fall back to legacy preferred address check
        val preferred = Prefs.getPreferredAddress(this)
        
        if (Prefs.isAutoConnectEnabled(this) && (hasEnabledDevices || !preferred.isNullOrBlank())) {
            RadiaCodeForegroundService.start(this)
        }

        ensureHistoryCapacity()
        
        val chartHistory = Prefs.getChartHistory(this)
        if (chartHistory.isNotEmpty()) {
            for (reading in chartHistory) {
                doseHistory.add(reading.timestampMs, reading.uSvPerHour)
                cpsHistory.add(reading.timestampMs, reading.cps)
            }
            sampleCount = chartHistory.size
            sessionStartMs = chartHistory.first().timestampMs
        } else {
            Prefs.getLastReading(this)?.let {
                doseHistory.add(it.timestampMs, it.uSvPerHour)
                cpsHistory.add(it.timestampMs, it.cps)
            }
        }
        startUiLoop()
    }


    private fun startUiLoop() {
        if (uiRunnable != null) return

        // Initialize caches
        refreshDevicesAndSelection(force = true)
        
        val r = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val auto = Prefs.isAutoConnectEnabled(this@MainActivity)
                val paused = Prefs.isPauseLiveEnabled(this@MainActivity)
                val svc = Prefs.getServiceStatus(this@MainActivity)

                // Low-frequency device refresh
                if (now - lastDeviceRefreshMs >= 10_000L) {
                    refreshDevicesAndSelection(force = false)
                }

                // Status text
                val statusIndicatesConnected = svc?.message?.contains("connected", ignoreCase = true) == true
                val hasRecent = lastShownReading != null && (now - (lastShownReading?.timestampMs ?: 0L)) < 10_000
                val isConnected = hasRecent || statusIndicatesConnected

                val statusText = when {
                    !auto -> "OFF"
                    paused -> "PAUSED"
                    isAllDevicesModeCache -> if (isConnected) "LIVE" else "CONNECTING"
                    isConnected -> "LIVE"
                    else -> "CONNECTING"
                }
                updateStatus(isConnected && !paused, statusText)
                getDeviceFragment()?.updateConnectionStatus()

                // Only redraw expensive parts when new data arrived.
                if (uiDirty) {
                    getDashboardFragment()?.refreshCharts()
                    getDashboardFragment()?.updateSessionInfo()
                    uiDirty = false
                }

                mainHandler.postDelayed(this, 1000)
            }
        }
        uiRunnable = r
        mainHandler.post(r)
    }

    private fun refreshDevicesAndSelection(force: Boolean) {
        val devices = Prefs.getDevices(this)
        val selectedId = Prefs.getSelectedDeviceId(this)
        selectedDeviceIdCache = selectedId
        isAllDevicesModeCache = (selectedId == null && devices.size > 1)
        lastDeviceRefreshMs = System.currentTimeMillis()
    }

    // refreshDeviceMetadataIfNeeded removed - metadata handled in DeviceFragment

    private fun stopUiLoop() {
        uiRunnable?.let { mainHandler.removeCallbacks(it) }
        uiRunnable = null
    }

    private fun updateStatus(live: Boolean, text: String) {
        mainHandler.post {
            statusLabel.text = text
            val color = if (live) {
                ContextCompat.getColor(this, R.color.pro_status_live)
            } else {
                ContextCompat.getColor(this, R.color.pro_text_muted)
            }
            statusLabel.setTextColor(color)
            (statusDot.background as? GradientDrawable)?.setColor(color)
        }
    }


    private fun doseUnitLabel(du: Prefs.DoseUnit): String = when (du) {
        Prefs.DoseUnit.USV_H -> "Î¼Sv/h"
        Prefs.DoseUnit.NSV_H -> "nSv/h"
    }

    private fun countUnitLabel(cu: Prefs.CountUnit): String = when (cu) {
        Prefs.CountUnit.CPS -> "cps"
        Prefs.CountUnit.CPM -> "cpm"
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

    fun applySmoothing(values: List<Float>, windowSamples: Int): List<Float> {
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

    fun convertDose(values: List<Float>, unit: Prefs.DoseUnit): List<Float> {
        if (unit == Prefs.DoseUnit.USV_H) return values
        return values.map { it * 1000.0f }
    }

    fun convertCount(values: List<Float>, unit: Prefs.CountUnit): List<Float> {
        if (unit == Prefs.CountUnit.CPS) return values
        return values.map { it * 60.0f }
    }

    fun decimate(timestamps: List<Long>, values: List<Float>): Pair<List<Long>, List<Float>> {
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
        // Always include the last point so the visible time range is stable (prevents jitter).
        val lastIdx = values.lastIndex
        if (outT.isEmpty() || outT.last() != timestamps[lastIdx]) {
            outT.add(timestamps[lastIdx])
            outV.add(values[lastIdx])
        }
        return outT to outV
    }

    fun openDetailedChart(kind: String) {
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

        val secondaryV = if (kind == "cps") {
            val du = Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)
            val smooth = Prefs.getSmoothSeconds(this, 0)
            val poll = Prefs.getPollIntervalMs(this, 1000L)
            val smoothSamples = if (smooth <= 0) 0 else max(1, ((smooth * 1000L) / max(1L, poll)).toInt())
            convertDose(applySmoothing(doseSeries?.values.orEmpty(), smoothSamples), du)
        } else {
            val cu = Prefs.getCountUnit(this, Prefs.CountUnit.CPS)
            val smooth = Prefs.getSmoothSeconds(this, 0)
            val poll = Prefs.getPollIntervalMs(this, 1000L)
            val smoothSamples = if (smooth <= 0) 0 else max(1, ((smooth * 1000L) / max(1L, poll)).toInt())
            convertCount(applySmoothing(cpsSeries?.values.orEmpty(), smoothSamples), cu)
        }
        
        val (_, secondaryDecimated) = decimate(ts.indices.map { doseSeries?.timestampsMs?.getOrNull(it) ?: 0L }, secondaryV)

        val i = Intent(this, DetailedChartActivity::class.java)
            .putExtra(DetailedChartActivity.EXTRA_KIND, kind)
            .putExtra("ts", ts.toLongArray())
            .putExtra("v", v.toFloatArray())
            .putExtra("secondary_v", secondaryDecimated.toFloatArray())
        startActivity(i)
    }

    private fun registerReadingReceiver() {
        val readingFilter = android.content.IntentFilter(RadiaCodeForegroundService.ACTION_READING)
        val stateFilter = android.content.IntentFilter(RadiaCodeForegroundService.ACTION_DEVICE_STATE_CHANGED)
        val statisticalFilter = android.content.IntentFilter(RadiaCodeForegroundService.ACTION_STATISTICAL_UPDATE)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(readingReceiver, readingFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(deviceStateReceiver, stateFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(statisticalReceiver, statisticalFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(readingReceiver, readingFilter)
                @Suppress("DEPRECATION")
                registerReceiver(deviceStateReceiver, stateFilter)
                @Suppress("DEPRECATION")
                registerReceiver(statisticalReceiver, statisticalFilter)
            }
        } catch (_: Throwable) {}
    }

    private fun unregisterReadingReceiver() {
        try {
            unregisterReceiver(readingReceiver)
        } catch (_: Throwable) {}
        try {
            unregisterReceiver(deviceStateReceiver)
        } catch (_: Throwable) {}
        try {
            unregisterReceiver(statisticalReceiver)
        } catch (_: Throwable) {}
    }
    
    /**
     * Build a states map for the device selector from tracked connection states.
     */
    fun buildDeviceStatesMap(devices: List<DeviceConfig>): Map<String, DeviceState> {
        return devices.associate { device ->
            val connectionState = deviceConnectionStates[device.id] ?: DeviceConnectionState.DISCONNECTED
            device.id to DeviceState(
                config = device,
                connectionState = connectionState
            )
        }
    }
}