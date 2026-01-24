package com.radiacode.ble.spectrogram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.radiacode.ble.Prefs
import com.radiacode.ble.R
import com.radiacode.ble.RadiaCodeForegroundService
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Vega Spectral Analysis Activity
 * 
 * Main screen for the spectrogram waterfall feature.
 * Shows spectrum data over time as a heatmap with:
 * - Toggle between accumulated/differential spectrum
 * - Recording start/stop control
 * - Timeline showing connected/disconnected periods
 * - Export functionality
 * - Background subtraction
 * - Isotope markers
 */
class VegaSpectralAnalysisActivity : AppCompatActivity() {

    // ═══════════════════════════════════════════════════════════════════════════
    // COLORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val colorBackground = Color.parseColor("#0D0D0F")
    private val colorSurface = Color.parseColor("#1A1A1E")
    private val colorBorder = Color.parseColor("#2A2A2E")
    private val colorCyan = Color.parseColor("#00E5FF")
    private val colorMagenta = Color.parseColor("#E040FB")
    private val colorGreen = Color.parseColor("#69F0AE")
    private val colorRed = Color.parseColor("#FF5252")
    private val colorYellow = Color.parseColor("#FFD600")
    private val colorText = Color.parseColor("#FFFFFF")
    private val colorMuted = Color.parseColor("#6E6E78")

    // ═══════════════════════════════════════════════════════════════════════════
    // VIEWS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private lateinit var toolbar: MaterialToolbar
    private lateinit var waterfallView: SpectrogramWaterfallView
    private lateinit var histogramView: SpectrumHistogramView
    private lateinit var chartContainer: FrameLayout
    private lateinit var timelineView: SpectrogramTimelineView
    
    // Recording controls
    private lateinit var recordToggle: MaterialButton
    private lateinit var recordingDuration: TextView
    private lateinit var recordingIndicator: View
    
    // Source toggle
    private lateinit var sourceChipGroup: ChipGroup
    private lateinit var chipAccumulated: Chip
    private lateinit var chipDifferential: Chip
    
    // Scale toggle (for histogram)
    private lateinit var chipLogScale: Chip
    
    // Settings chips
    private lateinit var chipIsotopes: Chip
    private lateinit var chipBackground: Chip
    private lateinit var chipSettings: Chip
    private lateinit var chipExport: Chip
    private lateinit var chipClear: Chip
    
    // Status display
    private lateinit var statusText: TextView
    private lateinit var snapshotCount: TextView
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var deviceId: String? = null
    private var isRecording = false
    private var currentSource = SpectrumSourceType.DIFFERENTIAL
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var durationUpdateRunnable: Runnable? = null
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BROADCAST RECEIVER
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val spectrumReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SPECTRUM_SNAPSHOT -> {
                    // New snapshot available - refresh view
                    refreshData()
                }
                RadiaCodeForegroundService.ACTION_CONNECTED -> {
                    updateConnectionStatus(true)
                }
                RadiaCodeForegroundService.ACTION_DISCONNECTED -> {
                    updateConnectionStatus(false)
                }
            }
        }
    }
    
    companion object {
        const val ACTION_SPECTRUM_SNAPSHOT = "com.radiacode.ble.SPECTRUM_SNAPSHOT"
        const val EXTRA_DEVICE_ID = "device_id"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Support both portrait and landscape
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        
        // Build UI programmatically
        setContentView(buildLayout())
        
        // Get device ID (use the device's UUID, not MAC address)
        val macAddress = Prefs.getPreferredAddress(this)
        deviceId = macAddress?.let { Prefs.getDeviceByMac(this, it)?.id }
        
        // Setup components
        setupToolbar()
        setupRecordingControls()
        setupSourceToggle()
        setupSettingsChips()
        setupWaterfallView()
        setupTimelineView()
        
        // Load initial data
        loadPreferences()
        refreshData()
        
        // Start duration updates
        startDurationUpdates()
    }
    
    override fun onResume() {
        super.onResume()
        
        // Register receivers
        val filter = IntentFilter().apply {
            addAction(ACTION_SPECTRUM_SNAPSHOT)
            addAction(RadiaCodeForegroundService.ACTION_CONNECTED)
            addAction(RadiaCodeForegroundService.ACTION_DISCONNECTED)
        }
        registerReceiver(spectrumReceiver, filter, RECEIVER_NOT_EXPORTED)
        
        // Refresh data
        refreshData()
    }
    
    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(spectrumReceiver)
        } catch (e: Exception) { }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        updateRunnable?.let { mainHandler.removeCallbacks(it) }
        durationUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UI BUILDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildLayout(): View {
        val density = resources.displayMetrics.density
        
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(colorBackground)
            
            // Toolbar
            toolbar = MaterialToolbar(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (56 * density).toInt()
                )
                setBackgroundColor(colorSurface)
                title = "Vega Spectral Analysis"
                setTitleTextColor(colorText)
                setNavigationIcon(R.drawable.ic_arrow_back)
            }
            addView(toolbar)
            
            // Recording controls card
            addView(buildRecordingCard(density))
            
            // Source toggle and settings row
            addView(buildControlsRow(density))
            
            // Chart container (holds either waterfall or histogram)
            chartContainer = FrameLayout(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f  // Take remaining space
                ).apply {
                    setMargins(
                        (12 * density).toInt(),
                        (8 * density).toInt(),
                        (12 * density).toInt(),
                        (8 * density).toInt()
                    )
                }
                
                // Waterfall view (for differential mode - shows time evolution)
                waterfallView = SpectrogramWaterfallView(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    visibility = View.VISIBLE  // Default visible
                }
                addView(waterfallView)
                
                // Histogram view (for accumulated mode - classic spectrum display)
                histogramView = SpectrumHistogramView(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    visibility = View.GONE  // Hidden by default
                }
                addView(histogramView)
            }
            addView(chartContainer)
            
            // Timeline view (bottom)
            timelineView = SpectrogramTimelineView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (80 * density).toInt()
                ).apply {
                    setMargins(
                        (12 * density).toInt(),
                        0,
                        (12 * density).toInt(),
                        (12 * density).toInt()
                    )
                }
            }
            addView(timelineView)
            
            // Status bar
            addView(buildStatusBar(density))
        }
    }
    
    private fun buildRecordingCard(density: Float): MaterialCardView {
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    (12 * density).toInt(),
                    (8 * density).toInt(),
                    (12 * density).toInt(),
                    (4 * density).toInt()
                )
            }
            setCardBackgroundColor(colorSurface)
            strokeColor = colorBorder
            strokeWidth = (1 * density).toInt()
            radius = 12 * density
            
            addView(LinearLayout(this@VegaSpectralAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * density).toInt(),
                    (12 * density).toInt(),
                    (16 * density).toInt(),
                    (12 * density).toInt()
                )
                
                // Recording indicator dot
                recordingIndicator = View(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (12 * density).toInt(),
                        (12 * density).toInt()
                    ).apply {
                        rightMargin = (12 * density).toInt()
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colorMuted)
                    }
                }
                addView(recordingIndicator)
                
                // Recording status and duration
                addView(LinearLayout(this@VegaSpectralAnalysisActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    
                    addView(TextView(this@VegaSpectralAnalysisActivity).apply {
                        text = "Spectrum Recording"
                        setTextColor(colorText)
                        textSize = 16f
                    })
                    
                    recordingDuration = TextView(this@VegaSpectralAnalysisActivity).apply {
                        text = "Not recording"
                        setTextColor(colorMuted)
                        textSize = 14f
                    }
                    addView(recordingDuration)
                })
                
                // Record toggle button
                recordToggle = MaterialButton(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        (48 * density).toInt()
                    )
                    text = "START"
                    setTextColor(colorText)
                    setBackgroundColor(colorGreen)
                    cornerRadius = (24 * density).toInt()
                }
                addView(recordToggle)
            })
        }
    }
    
    private fun buildControlsRow(density: Float): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
            
            addView(LinearLayout(this@VegaSpectralAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(
                    (12 * density).toInt(),
                    (4 * density).toInt(),
                    (12 * density).toInt(),
                    (4 * density).toInt()
                )
                
                // Source toggle chip group
                sourceChipGroup = ChipGroup(this@VegaSpectralAnalysisActivity).apply {
                    isSingleSelection = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = (16 * density).toInt()
                    }
                    
                    chipAccumulated = Chip(this@VegaSpectralAnalysisActivity).apply {
                        text = "Accumulated"
                        isCheckable = true
                        setChipBackgroundColorResource(R.color.pro_surface)
                        setTextColor(colorText)
                    }
                    addView(chipAccumulated)
                    
                    chipDifferential = Chip(this@VegaSpectralAnalysisActivity).apply {
                        text = "Differential"
                        isCheckable = true
                        isChecked = true
                        setChipBackgroundColorResource(R.color.pro_surface)
                        setTextColor(colorText)
                    }
                    addView(chipDifferential)
                }
                addView(sourceChipGroup)
                
                // Separator
                addView(View(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (1 * density).toInt(),
                        (32 * density).toInt()
                    ).apply {
                        rightMargin = (16 * density).toInt()
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    setBackgroundColor(colorBorder)
                })
                
                // Settings chips
                chipIsotopes = createChip("Isotopes", false)
                addView(chipIsotopes)
                
                chipLogScale = createChip("Log Scale", true)  // Default to log scale
                addView(chipLogScale)
                
                chipBackground = createChip("Background", false)
                addView(chipBackground)
                
                chipSettings = createChip("Settings", false)
                addView(chipSettings)
                
                chipExport = createChip("Export", false)
                addView(chipExport)
                
                chipClear = createChip("Clear", false)
                addView(chipClear)
            })
        }
    }
    
    private fun createChip(text: String, isChecked: Boolean): Chip {
        val density = resources.displayMetrics.density
        return Chip(this).apply {
            this.text = text
            isCheckable = true
            this.isChecked = isChecked
            setChipBackgroundColorResource(R.color.pro_surface)
            setTextColor(colorText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (8 * density).toInt()
            }
        }
    }
    
    private fun buildStatusBar(density: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (16 * density).toInt(),
                (8 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            setBackgroundColor(colorSurface)
            
            statusText = TextView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = "Ready"
                setTextColor(colorMuted)
                textSize = 12f
            }
            addView(statusText)
            
            snapshotCount = TextView(this@VegaSpectralAnalysisActivity).apply {
                text = "0 snapshots"
                setTextColor(colorMuted)
                textSize = 12f
            }
            addView(snapshotCount)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SETUP
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { finish() }
    }
    
    private fun setupRecordingControls() {
        recordToggle.setOnClickListener {
            toggleRecording()
        }
        
        updateRecordingState()
    }
    
    private fun setupSourceToggle() {
        chipAccumulated.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentSource = SpectrumSourceType.ACCUMULATED
                SpectrogramPrefs.setSpectrumSource(this, SpectrumSourceType.ACCUMULATED)
                // Show histogram, hide waterfall
                waterfallView.visibility = View.GONE
                histogramView.visibility = View.VISIBLE
                refreshData()
            }
        }
        
        chipDifferential.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentSource = SpectrumSourceType.DIFFERENTIAL
                SpectrogramPrefs.setSpectrumSource(this, SpectrumSourceType.DIFFERENTIAL)
                // Show waterfall, hide histogram
                waterfallView.visibility = View.VISIBLE
                histogramView.visibility = View.GONE
                refreshData()
            }
        }
    }
    
    private fun setupSettingsChips() {
        chipIsotopes.setOnCheckedChangeListener { _, isChecked ->
            SpectrogramPrefs.setShowIsotopeMarkers(this, isChecked)
            waterfallView.setShowIsotopeMarkers(isChecked)
            histogramView.setShowIsotopeMarkers(isChecked)
        }
        
        chipLogScale.setOnCheckedChangeListener { _, isChecked ->
            histogramView.setLogScale(isChecked)
        }
        
        chipBackground.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showBackgroundMenu()
            } else {
                SpectrogramPrefs.setBackgroundSubtractionEnabled(this, false)
                waterfallView.setShowBackgroundSubtraction(false)
            }
        }
        
        chipSettings.setOnClickListener {
            chipSettings.isChecked = false
            showSettingsDialog()
        }
        
        chipExport.setOnClickListener {
            chipExport.isChecked = false
            showExportDialog()
        }
        
        chipClear.setOnClickListener {
            chipClear.isChecked = false
            showClearConfirmation()
        }
    }
    
    private fun setupWaterfallView() {
        waterfallView.onSnapshotTapListener = { snapshot, energy ->
            // Show info about tapped point - use spectrumData for channel conversion
            val channel = snapshot.spectrumData.energyToChannel(energy)
            val count = if (channel in snapshot.spectrumData.counts.indices) {
                snapshot.spectrumData.counts[channel]
            } else 0
            
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
            val timeStr = sdf.format(Date(snapshot.timestampMs))
            
            Toast.makeText(
                this,
                "Time: $timeStr\nEnergy: ${energy.toInt()} keV\nCounts: $count",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun setupTimelineView() {
        timelineView.onSegmentSelectedListener = { segment ->
            // Scroll waterfall to show this segment
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
            val startStr = sdf.format(Date(segment.startMs))
            val endStr = segment.endMs?.let { sdf.format(Date(it)) } ?: "now"
            
            val isConnected = segment.type == ConnectionSegmentType.CONNECTED
            statusText.text = if (isConnected) {
                "Segment: $startStr - $endStr (connected)"
            } else {
                "Gap: $startStr - $endStr"
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECORDING CONTROL
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun toggleRecording() {
        isRecording = !isRecording
        SpectrogramPrefs.setRecordingEnabled(this, isRecording)
        
        // Notify service
        val action = if (isRecording) "START_SPECTRUM_RECORDING" else "STOP_SPECTRUM_RECORDING"
        val intent = Intent(this, RadiaCodeForegroundService::class.java).apply {
            this.action = action
        }
        startService(intent)
        
        updateRecordingState()
    }
    
    private fun updateRecordingState() {
        isRecording = SpectrogramPrefs.isRecordingEnabled(this)
        
        if (isRecording) {
            recordToggle.text = "STOP"
            recordToggle.setBackgroundColor(colorRed)
            recordingIndicator.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorRed)
            }
            // Start pulsing animation
            startRecordingPulse()
        } else {
            recordToggle.text = "START"
            recordToggle.setBackgroundColor(colorGreen)
            recordingIndicator.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorMuted)
            }
            recordingDuration.text = "Not recording"
            stopRecordingPulse()
        }
    }
    
    private fun startRecordingPulse() {
        // Simple pulse animation using alpha
        recordingIndicator.animate()
            .alpha(0.3f)
            .setDuration(500)
            .withEndAction {
                if (isRecording) {
                    recordingIndicator.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .withEndAction { startRecordingPulse() }
                        .start()
                }
            }
            .start()
    }
    
    private fun stopRecordingPulse() {
        recordingIndicator.animate().cancel()
        recordingIndicator.alpha = 1f
    }
    
    private fun startDurationUpdates() {
        durationUpdateRunnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    updateDurationDisplay()
                }
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.post(durationUpdateRunnable!!)
    }
    
    private fun updateDurationDisplay() {
        val startMs = SpectrogramPrefs.getRecordingStartMs(this)
        val durationMs = System.currentTimeMillis() - startMs
        
        val hours = (durationMs / 3600000).toInt()
        val minutes = ((durationMs % 3600000) / 60000).toInt()
        val seconds = ((durationMs % 60000) / 1000).toInt()
        
        val durationStr = if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
        
        recordingDuration.text = "Recording: $durationStr"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA LOADING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun loadPreferences() {
        // Load source type and set correct view visibility
        val source = SpectrogramPrefs.getSpectrumSource(this)
        currentSource = source
        when (source) {
            SpectrumSourceType.ACCUMULATED -> {
                chipAccumulated.isChecked = true
                waterfallView.visibility = View.GONE
                histogramView.visibility = View.VISIBLE
            }
            SpectrumSourceType.DIFFERENTIAL -> {
                chipDifferential.isChecked = true
                waterfallView.visibility = View.VISIBLE
                histogramView.visibility = View.GONE
            }
        }
        
        // Load settings
        chipIsotopes.isChecked = SpectrogramPrefs.isShowIsotopeMarkers(this)
        waterfallView.setShowIsotopeMarkers(chipIsotopes.isChecked)
        histogramView.setShowIsotopeMarkers(chipIsotopes.isChecked)
        
        // Log scale is on by default
        chipLogScale.isChecked = true
        histogramView.setLogScale(true)
        
        val bgEnabled = SpectrogramPrefs.isBackgroundSubtractionEnabled(this)
        chipBackground.isChecked = bgEnabled
        waterfallView.setShowBackgroundSubtraction(bgEnabled)
        
        // Load color scheme
        waterfallView.setColorScheme(SpectrogramPrefs.getColorScheme(this))
    }
    
    private fun refreshData() {
        val deviceId = this.deviceId ?: return
        
        // Load snapshots from repository
        val source = SpectrogramPrefs.getSpectrumSource(this)
        val historyMs = SpectrogramPrefs.getHistoryDepthMs(this)
        val startTime = System.currentTimeMillis() - historyMs
        
        // Filter by source type (differential vs accumulated based on source preference)
        val wantDifferential = source == SpectrumSourceType.DIFFERENTIAL
        val snapshots = SpectrogramRepository.getInstance(this)
            .getSnapshots(deviceId, startTime, System.currentTimeMillis())
            .filter { it.isDifferential == wantDifferential }
        
        // Update the appropriate view based on source type
        if (wantDifferential) {
            // Differential mode - use waterfall view
            waterfallView.setSnapshots(snapshots)
            
            // Apply calibration from first snapshot
            if (snapshots.isNotEmpty()) {
                val first = snapshots.first()
                waterfallView.setCalibration(first.spectrumData.a0, first.spectrumData.a1, first.spectrumData.a2)
            }
        } else {
            // Accumulated mode - use histogram view showing the most recent accumulated spectrum
            if (snapshots.isNotEmpty()) {
                val latest = snapshots.last()  // Most recent accumulated spectrum
                histogramView.setSpectrum(
                    counts = latest.spectrumData.counts,
                    a0 = latest.spectrumData.a0,
                    a1 = latest.spectrumData.a1,
                    a2 = latest.spectrumData.a2
                )
            } else {
                // Clear histogram if no data
                histogramView.setSpectrum(intArrayOf(), 0f, 3f, 0f)
            }
        }
        
        // Load connection segments
        val segments = SpectrogramRepository.getInstance(this)
            .getConnectionSegments(deviceId)
        timelineView.setSegments(segments)
        
        // Update status
        snapshotCount.text = "${snapshots.size} snapshots"
        statusText.text = if (snapshots.isEmpty()) {
            "No data - start recording to capture spectrum"
        } else {
            "Showing ${source.name.lowercase()} spectrum"
        }
        
        // Legacy: apply calibration for waterfall if needed (for backwards compat)
        if (wantDifferential && snapshots.isNotEmpty()) {
            val first = snapshots.first()
            waterfallView.setCalibration(first.spectrumData.a0, first.spectrumData.a1, first.spectrumData.a2)
        }
        
        // Load background if enabled
        if (SpectrogramPrefs.isBackgroundSubtractionEnabled(this)) {
            val bgId = SpectrogramPrefs.getSelectedBackgroundId(this)
            if (bgId != null) {
                val bg = SpectrogramRepository.getInstance(this).getBackgrounds()
                    .find { it.id == bgId }
                waterfallView.setBackgroundSpectrum(bg)
            }
        }
    }
    
    private fun updateConnectionStatus(connected: Boolean) {
        statusText.text = if (connected) {
            "Device connected"
        } else {
            "Device disconnected"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun showBackgroundMenu() {
        val backgrounds = SpectrogramRepository.getInstance(this).getBackgrounds()
        
        if (backgrounds.isEmpty()) {
            AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
                .setTitle("No Backgrounds")
                .setMessage("You haven't saved any background spectra yet. Capture a spectrum in a low-radiation environment and use 'Save as Background' to create one.")
                .setPositiveButton("OK") { d: DialogInterface, _: Int -> 
                    d.dismiss()
                    chipBackground.isChecked = false
                }
                .show()
            return
        }
        
        val names = backgrounds.map { bg ->
            "${bg.name} (${SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(bg.capturedMs))})"
        }.toTypedArray()
        
        val selectedId = SpectrogramPrefs.getSelectedBackgroundId(this)
        val selectedIndex = backgrounds.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Select Background")
            .setSingleChoiceItems(names, selectedIndex) { dialog: DialogInterface, which: Int ->
                val bg = backgrounds[which]
                SpectrogramPrefs.setSelectedBackgroundId(this, bg.id)
                SpectrogramPrefs.setBackgroundSubtractionEnabled(this, true)
                waterfallView.setBackgroundSpectrum(bg)
                waterfallView.setShowBackgroundSubtraction(true)
                dialog.dismiss()
            }
            .setNeutralButton("Save Current as Background") { d: DialogInterface, _: Int ->
                d.dismiss()
                saveCurrentAsBackground()
            }
            .setNegativeButton("Cancel") { d: DialogInterface, _: Int ->
                d.dismiss()
                chipBackground.isChecked = false
            }
            .show()
    }
    
    private fun saveCurrentAsBackground() {
        // Get current spectrum and save as background
        val deviceId = this.deviceId ?: return
        val snapshots = SpectrogramRepository.getInstance(this)
            .getSnapshots(deviceId, 0, System.currentTimeMillis())
        
        if (snapshots.isEmpty()) {
            Toast.makeText(this, "No spectrum data to save", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Use most recent accumulated spectrum (isDifferential = false means accumulated)
        val latest = snapshots.filter { !it.isDifferential }
            .maxByOrNull { it.timestampMs }
        
        if (latest == null) {
            Toast.makeText(this, "No accumulated spectrum available", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Prompt for name
        val input = EditText(this).apply {
            hint = "Background name"
            setText("Background ${SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date())}")
        }
        
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Save as Background")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifEmpty { "Background" }
                SpectrogramRepository.getInstance(this).saveAsBackground(
                    name = name,
                    deviceId = deviceId,
                    spectrumData = latest.spectrumData
                )
                Toast.makeText(this, "Background saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showSettingsDialog() {
        val settings = arrayOf(
            "Update Interval",
            "History Depth",
            "Color Scheme"
        )
        
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Settings")
            .setItems(settings) { _, which ->
                when (which) {
                    0 -> showUpdateIntervalPicker()
                    1 -> showHistoryDepthPicker()
                    2 -> showColorSchemePicker()
                }
            }
            .show()
    }
    
    private fun showUpdateIntervalPicker() {
        val intervals = SpectrogramPrefs.UpdateInterval.values()
        val names = intervals.map { it.label }.toTypedArray()
        val current = SpectrogramPrefs.UpdateInterval.fromMs(
            SpectrogramPrefs.getUpdateIntervalMs(this)
        )
        val selectedIndex = intervals.indexOf(current)
        
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Update Interval")
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                SpectrogramPrefs.setUpdateIntervalMs(this, intervals[which].ms)
                dialog.dismiss()
            }
            .show()
    }
    
    private fun showHistoryDepthPicker() {
        val depths = SpectrogramPrefs.HistoryDepth.values()
        val names = depths.map { it.label }.toTypedArray()
        val current = SpectrogramPrefs.HistoryDepth.fromMs(
            SpectrogramPrefs.getHistoryDepthMs(this)
        )
        val selectedIndex = depths.indexOf(current)
        
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("History Depth")
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                SpectrogramPrefs.setHistoryDepthMs(this, depths[which].ms)
                refreshData()
                dialog.dismiss()
            }
            .show()
    }
    
    private fun showColorSchemePicker() {
        val schemes = SpectrogramPrefs.ColorScheme.values()
        val names = schemes.map { it.label }.toTypedArray()
        val current = SpectrogramPrefs.getColorScheme(this)
        val selectedIndex = schemes.indexOf(current)
        
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Color Scheme")
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                SpectrogramPrefs.setColorScheme(this, schemes[which])
                waterfallView.setColorScheme(schemes[which])
                dialog.dismiss()
            }
            .show()
    }
    
    private fun showExportDialog() {
        val options = arrayOf(
            "Export as CSV",
            "Export as RadiaCode (.rctrk)"
        )
        
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Export Data")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportAsCsv()
                    1 -> exportAsRctrk()
                }
            }
            .show()
    }
    
    private fun exportAsCsv() {
        val deviceId = this.deviceId ?: return
        val snapshots = SpectrogramRepository.getInstance(this)
            .getSnapshots(deviceId, 0, System.currentTimeMillis())
        
        if (snapshots.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Build CSV
        val sb = StringBuilder()
        sb.append("timestamp_ms,source_type,integration_s,channel_count")
        
        // Add channel headers
        val channelCount = snapshots.first().spectrumData.counts.size
        for (i in 0 until channelCount) {
            sb.append(",ch_$i")
        }
        sb.append("\n")
        
        // Add rows
        for (snapshot in snapshots) {
            sb.append(snapshot.timestampMs)
            sb.append(",")
            sb.append(if (snapshot.isDifferential) "DIFFERENTIAL" else "ACCUMULATED")
            sb.append(",")
            sb.append(snapshot.spectrumData.durationSeconds)
            sb.append(",")
            sb.append(snapshot.spectrumData.counts.size)
            for (count in snapshot.spectrumData.counts) {
                sb.append(",")
                sb.append(count)
            }
            sb.append("\n")
        }
        
        // Save and share
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(cacheDir, "spectrogram_$timestamp.csv")
        file.writeText(sb.toString())
        
        shareFile(file, "text/csv")
    }
    
    private fun exportAsRctrk() {
        // TODO: Implement RadiaCode .rctrk format export
        Toast.makeText(this, "RadiaCode format export coming soon", Toast.LENGTH_SHORT).show()
    }
    
    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        startActivity(Intent.createChooser(intent, "Export Spectrogram"))
    }
    
    private fun showClearConfirmation() {
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Clear Data")
            .setMessage("This will delete all recorded spectrum data for this device. This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                deviceId?.let { id ->
                    SpectrogramRepository.getInstance(this).clearDeviceData(id)
                    refreshData()
                    Toast.makeText(this, "Data cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
