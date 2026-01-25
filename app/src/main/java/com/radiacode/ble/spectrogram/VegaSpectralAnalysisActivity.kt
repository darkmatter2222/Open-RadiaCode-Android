package com.radiacode.ble.spectrogram

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.radiacode.ble.Prefs
import com.radiacode.ble.R
import com.radiacode.ble.RadiaCodeForegroundService
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * Vega Spectral Analysis Activity
 * 
 * Professional spectrum analysis UI with maximum chart real estate.
 * Features:
 * - Full-bleed chart display (waterfall for differential, histogram for accumulated)
 * - Minimal floating controls that don't obscure the chart
 * - Settings bottom sheet for all configuration options
 * - Optimized for both portrait and landscape orientations
 */
class VegaSpectralAnalysisActivity : AppCompatActivity() {

    // ═══════════════════════════════════════════════════════════════════════════
    // COLORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val colorBackground = Color.parseColor("#0D0D0F")
    private val colorSurface = Color.parseColor("#1A1A1E")
    private val colorSurfaceElevated = Color.parseColor("#222228")
    private val colorBorder = Color.parseColor("#2A2A2E")
    private val colorCyan = Color.parseColor("#00E5FF")
    private val colorMagenta = Color.parseColor("#E040FB")
    private val colorGreen = Color.parseColor("#69F0AE")
    private val colorRed = Color.parseColor("#FF5252")
    private val colorYellow = Color.parseColor("#FFD600")
    private val colorText = Color.parseColor("#FFFFFF")
    private val colorTextSecondary = Color.parseColor("#9E9EA8")
    private val colorMuted = Color.parseColor("#6E6E78")

    // ═══════════════════════════════════════════════════════════════════════════
    // VIEWS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private lateinit var rootLayout: FrameLayout
    private lateinit var chartContainer: FrameLayout
    private lateinit var waterfallView: SpectrogramWaterfallView
    private lateinit var histogramView: SpectrumHistogramView
    
    // Floating toolbar
    private lateinit var toolbarOverlay: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var sourceSelector: LinearLayout
    private lateinit var sourceLabel: TextView
    private lateinit var scaleToggle: TextView
    private lateinit var snapToggle: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var recordButton: FrameLayout
    private lateinit var recordDot: View
    private lateinit var recordRing: View
    
    // Bottom controls
    private lateinit var bottomBar: LinearLayout
    private lateinit var smoothingContainer: LinearLayout
    private lateinit var smoothingSlider: SeekBar
    private lateinit var smoothingLabel: TextView
    
    // AI Isotope Analysis button
    private lateinit var aiAnalysisButton: FrameLayout
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var deviceId: String? = null
    private var isRecording = false
    private var currentSource = SpectrumSourceType.DIFFERENTIAL
    private var isLogScale = true
    private var isSnapEnabled = true  // Snap-to-peak feature
    private var showIsotopes = false
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var durationUpdateRunnable: Runnable? = null
    private var pulseAnimator: ValueAnimator? = null
    
    // Background thread executor for IO/data operations (separate from API executor)
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VegaSpectral-IO").apply { isDaemon = true }
    }
    
    // Debounce refresh - prevents rapid-fire refreshes from blocking UI
    private var refreshPending = false
    private var lastRefreshTime = 0L
    private val REFRESH_DEBOUNCE_MS = 100L  // Don't refresh more than 10 times/sec
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BROADCAST RECEIVER
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val spectrumReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SPECTRUM_SNAPSHOT -> scheduleRefresh()  // Use debounced refresh
                RadiaCodeForegroundService.ACTION_CONNECTED -> updateStatus("Connected")
                RadiaCodeForegroundService.ACTION_DISCONNECTED -> updateStatus("Disconnected")
            }
        }
    }
    
    companion object {
        private const val TAG = "VegaSpectral"
        const val ACTION_SPECTRUM_SNAPSHOT = "com.radiacode.ble.SPECTRUM_SNAPSHOT"
        const val EXTRA_DEVICE_ID = "device_id"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Allow both orientations
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        
        // Edge-to-edge display
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        
        // Build UI
        setContentView(buildLayout())
        
        // Get device ID
        val macAddress = Prefs.getPreferredAddress(this)
        deviceId = macAddress?.let { Prefs.getDeviceByMac(this, it)?.id }
        
        // Setup interactions
        setupControls()
        loadPreferences()
        refreshData()
        startDurationUpdates()
    }
    
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ACTION_SPECTRUM_SNAPSHOT)
            addAction(RadiaCodeForegroundService.ACTION_CONNECTED)
            addAction(RadiaCodeForegroundService.ACTION_DISCONNECTED)
        }
        registerReceiver(spectrumReceiver, filter, RECEIVER_NOT_EXPORTED)
        refreshData()
    }
    
    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(spectrumReceiver) } catch (e: Exception) { }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        durationUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        pulseAnimator?.cancel()
        // Shutdown IO executor to free resources
        (ioExecutor as? java.util.concurrent.ExecutorService)?.shutdown()
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adjustLayoutForOrientation(newConfig.orientation)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UI BUILDING - MAIN LAYOUT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildLayout(): View {
        val density = resources.displayMetrics.density
        
        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(colorBackground)
            
            // Chart container - below title bar, above bottom controls
            chartContainer = FrameLayout(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    // Leave room for title bar and smoothing slider only (no status bar)
                    topMargin = getStatusBarHeight() + (56 * density).toInt()
                    bottomMargin = getNavigationBarHeight() + (52 * density).toInt()  // Reduced from 80
                }
                
                // Waterfall view (for differential mode)
                waterfallView = SpectrogramWaterfallView(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    visibility = View.VISIBLE
                }
                addView(waterfallView)
                
                // Histogram view (for accumulated mode)
                histogramView = SpectrumHistogramView(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    visibility = View.GONE
                }
                addView(histogramView)
                
                // AI Isotope Analysis button (floating, bottom-right)
                aiAnalysisButton = FrameLayout(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (52 * density).toInt(),
                        (52 * density).toInt()
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                        rightMargin = (16 * density).toInt()
                        bottomMargin = (16 * density).toInt()
                    }
                    background = createAiButtonBackground(density)
                    elevation = 8 * density
                    
                    addView(ImageView(this@VegaSpectralAnalysisActivity).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            (28 * density).toInt(),
                            (28 * density).toInt()
                        ).apply {
                            gravity = Gravity.CENTER
                        }
                        setImageResource(R.drawable.ic_vega_ai)
                        setColorFilter(colorCyan)
                    })
                    
                    setOnClickListener { performIsotopeAnalysis() }
                }
                addView(aiAnalysisButton)
            }
            addView(chartContainer)
            
            // Floating toolbar overlay (top layer)
            addView(buildToolbarOverlay(density))
            
            // Bottom control bar (top layer)
            addView(buildBottomBar(density))
        }
        
        return rootLayout
    }
    
    private fun buildToolbarOverlay(density: Float): LinearLayout {
        toolbarOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            ).apply {
                gravity = Gravity.TOP
                topMargin = getStatusBarHeight()
            }
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            
            // Solid background (not gradient - chart is below)
            setBackgroundColor(colorBackground)
            
            // Back button
            backButton = ImageButton(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(),
                    (40 * density).toInt()
                )
                setImageResource(R.drawable.ic_arrow_back)
                setColorFilter(colorText)
                background = createCircleRipple(density)
                scaleType = ImageView.ScaleType.CENTER
            }
            addView(backButton)
            
            // Title
            titleText = TextView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (8 * density).toInt()
                }
                text = "Spectral Analysis"
                setTextColor(colorText)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(titleText)
            
            // Flexible spacer
            addView(View(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
            
            // Source selector (dropdown style) - in toolbar now
            sourceSelector = LinearLayout(this@VegaSpectralAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (32 * density).toInt()
                ).apply {
                    rightMargin = (8 * density).toInt()
                }
                setPadding((10 * density).toInt(), 0, (6 * density).toInt(), 0)
                background = createPillBackground(density, colorSurface, colorBorder)
                
                sourceLabel = TextView(this@VegaSpectralAnalysisActivity).apply {
                    text = "Diff"
                    setTextColor(colorText)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(sourceLabel)
                
                // Dropdown arrow
                addView(ImageView(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (16 * density).toInt(),
                        (16 * density).toInt()
                    ).apply {
                        leftMargin = (2 * density).toInt()
                    }
                    setImageResource(R.drawable.ic_arrow_down)
                    setColorFilter(colorMuted)
                })
            }
            addView(sourceSelector)
            
            // Scale toggle button (LOG/LIN) - in toolbar now
            scaleToggle = TextView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (32 * density).toInt()
                ).apply {
                    rightMargin = (8 * density).toInt()
                }
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                gravity = Gravity.CENTER
                text = "LOG"
                setTextColor(colorCyan)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = createPillBackground(density, colorSurface, colorCyan)
            }
            addView(scaleToggle)
            
            // Snap toggle button (SNAP) - enables peak snapping while dragging cursor
            snapToggle = TextView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (32 * density).toInt()
                ).apply {
                    rightMargin = (8 * density).toInt()
                }
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                gravity = Gravity.CENTER
                text = "SNAP"
                setTextColor(colorGreen)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = createPillBackground(density, colorSurface, colorGreen)
                visibility = View.GONE  // Only visible in Accumulated mode
            }
            addView(snapToggle)
            
            // Settings button
            settingsButton = ImageButton(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(),
                    (40 * density).toInt()
                )
                setImageResource(R.drawable.ic_settings)
                setColorFilter(colorText)
                background = createCircleRipple(density)
                scaleType = ImageView.ScaleType.CENTER
            }
            addView(settingsButton)
            
            // Record button (small indicator)
            recordButton = FrameLayout(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(),
                    (40 * density).toInt()
                )
                background = createCircleRipple(density)
                
                // Outer ring (shows when recording)
                recordRing = View(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (24 * density).toInt(),
                        (24 * density).toInt()
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke((2 * density).toInt(), colorRed)
                        setColor(Color.TRANSPARENT)
                    }
                    alpha = 0f
                }
                addView(recordRing)
                
                // Inner dot
                recordDot = View(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (12 * density).toInt(),
                        (12 * density).toInt()
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colorMuted)
                    }
                }
                addView(recordDot)
            }
            addView(recordButton)
        }
        return toolbarOverlay
    }
    
    private fun buildBottomBar(density: Float): LinearLayout {
        // Container for smoothing slider + status bar
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = getNavigationBarHeight()
            }
            setBackgroundColor(colorBackground)
            
            // Smoothing slider row (only visible in accumulated mode)
            smoothingContainer = LinearLayout(this@VegaSpectralAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (44 * density).toInt()
                )
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                visibility = View.GONE  // Hidden by default
                
                addView(TextView(this@VegaSpectralAnalysisActivity).apply {
                    text = "Smooth"
                    setTextColor(colorMuted)
                    textSize = 12f
                })
                
                smoothingSlider = SeekBar(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = (12 * density).toInt()
                        rightMargin = (8 * density).toInt()
                    }
                    max = 20
                    progress = 0
                    progressTintList = android.content.res.ColorStateList.valueOf(colorCyan)
                    thumbTintList = android.content.res.ColorStateList.valueOf(colorCyan)
                }
                addView(smoothingSlider)
                
                smoothingLabel = TextView(this@VegaSpectralAnalysisActivity).apply {
                    text = "0"
                    setTextColor(colorCyan)
                    textSize = 12f
                    minWidth = (24 * density).toInt()
                    gravity = Gravity.END
                }
                addView(smoothingLabel)
            }
            addView(smoothingContainer)
            
            // Status bar row - minimal, just for potential future use
            bottomBar = LinearLayout(this@VegaSpectralAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (4 * density).toInt()  // Minimal height - just a spacer now
                )
                // No status text - removed recording timer to save real estate
            }
            addView(bottomBar)
        }
        return container
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER UI METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun createCircleRipple(density: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
    }
    
    private fun createAiButtonBackground(density: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorSurface)
            setStroke((2 * density).toInt(), colorCyan)
        }
    }
    
    private fun createPillBackground(density: Float, fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18 * density
            setColor(fillColor)
            setStroke((1 * density).toInt(), strokeColor)
        }
    }
    
    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }
    
    private fun getNavigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }
    
    private fun adjustLayoutForOrientation(orientation: Int) {
        val density = resources.displayMetrics.density
        
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            toolbarOverlay.setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            bottomBar.setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        } else {
            toolbarOverlay.setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            bottomBar.setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONTROL SETUP
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun setupControls() {
        backButton.setOnClickListener { finish() }
        settingsButton.setOnClickListener { showSettingsSheet() }
        
        recordButton.setOnClickListener {
            if (isRecording) {
                showStopRecordingConfirmation()
            } else {
                startRecording()
            }
        }
        
        sourceSelector.setOnClickListener { showSourcePicker() }
        scaleToggle.setOnClickListener { toggleScale() }
        snapToggle.setOnClickListener { toggleSnap() }
        
        // Smoothing slider listener
        smoothingSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                smoothingLabel.text = progress.toString()
                histogramView.setSmoothing(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        waterfallView.onSnapshotTapListener = { snapshot, energy ->
            val channel = snapshot.spectrumData.energyToChannel(energy)
            val count = if (channel in snapshot.spectrumData.counts.indices) {
                snapshot.spectrumData.counts[channel]
            } else 0
            
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
            val timeStr = sdf.format(Date(snapshot.timestampMs))
            
            Toast.makeText(
                this,
                "Time: $timeStr | ${energy.toInt()} keV | $count counts",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE SELECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun showSourcePicker() {
        val density = resources.displayMetrics.density
        
        val popup = PopupWindow(this).apply {
            val content = LinearLayout(this@VegaSpectralAnalysisActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(colorSurfaceElevated)  // Slightly lighter for visibility
                    setStroke((1 * density).toInt(), colorBorder)
                }
                setPadding((4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
                elevation = 16 * density
                
                addView(createSourceOption("Differential", currentSource == SpectrumSourceType.DIFFERENTIAL) {
                    setSource(SpectrumSourceType.DIFFERENTIAL)
                    dismiss()
                })
                
                addView(createSourceOption("Accumulated", currentSource == SpectrumSourceType.ACCUMULATED) {
                    setSource(SpectrumSourceType.ACCUMULATED)
                    dismiss()
                })
            }
            
            contentView = content
            width = (140 * density).toInt()
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isFocusable = true
            elevation = 16 * density
            setBackgroundDrawable(null)  // Let content handle background
        }
        
        popup.showAsDropDown(sourceSelector, 0, (4 * density).toInt())
    }
    
    private fun createSourceOption(label: String, isSelected: Boolean, onClick: () -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (40 * density).toInt()
            )
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            
            // Selection indicator dot
            addView(View(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (8 * density).toInt(),
                    (8 * density).toInt()
                ).apply {
                    rightMargin = (10 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isSelected) colorCyan else colorMuted)
                }
            })
            
            // Label text - ensure white color for visibility
            addView(TextView(this@VegaSpectralAnalysisActivity).apply {
                text = label
                setTextColor(if (isSelected) colorCyan else Color.WHITE)  // White for visibility
                textSize = 14f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            })
            
            setOnClickListener { onClick() }
        }
    }
    
    private fun setSource(source: SpectrumSourceType) {
        currentSource = source
        SpectrogramPrefs.setSpectrumSource(this, source)
        
        // Short labels for compact toolbar display
        sourceLabel.text = when (source) {
            SpectrumSourceType.DIFFERENTIAL -> "Diff"
            SpectrumSourceType.ACCUMULATED -> "Accum"
        }
        
        when (source) {
            SpectrumSourceType.DIFFERENTIAL -> {
                waterfallView.visibility = View.VISIBLE
                histogramView.visibility = View.GONE
                smoothingContainer.visibility = View.GONE  // Hide slider
                snapToggle.visibility = View.GONE  // Hide snap toggle
            }
            SpectrumSourceType.ACCUMULATED -> {
                waterfallView.visibility = View.GONE
                histogramView.visibility = View.VISIBLE
                smoothingContainer.visibility = View.VISIBLE  // Show slider
                snapToggle.visibility = View.VISIBLE  // Show snap toggle
            }
        }
        
        refreshData()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCALE TOGGLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun toggleScale() {
        val density = resources.displayMetrics.density
        isLogScale = !isLogScale
        
        scaleToggle.text = if (isLogScale) "LOG" else "LIN"
        scaleToggle.setTextColor(if (isLogScale) colorCyan else colorMagenta)
        scaleToggle.background = createPillBackground(
            density,
            colorSurface,
            if (isLogScale) colorCyan else colorMagenta
        )
        
        histogramView.setLogScale(isLogScale)
    }
    
    private fun toggleSnap() {
        val density = resources.displayMetrics.density
        isSnapEnabled = !isSnapEnabled
        
        // Update button appearance
        val activeColor = if (isSnapEnabled) colorGreen else colorMuted
        snapToggle.text = "SNAP"
        snapToggle.setTextColor(activeColor)
        snapToggle.background = createPillBackground(
            density,
            colorSurface,
            activeColor
        )
        
        // Update histogram view
        histogramView.setSnapToPeak(isSnapEnabled)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECORDING CONTROL
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun startRecording() {
        isRecording = true
        SpectrogramPrefs.setRecordingEnabled(this, true)
        
        val intent = Intent(this, RadiaCodeForegroundService::class.java).apply {
            action = "START_SPECTRUM_RECORDING"
        }
        startService(intent)
        
        updateRecordingUI()
    }
    
    private fun showStopRecordingConfirmation() {
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Stop Recording?")
            .setMessage("Are you sure you want to stop spectrum recording?")
            .setPositiveButton("Stop") { _, _ -> stopRecording() }
            .setNegativeButton("Continue", null)
            .show()
    }
    
    private fun stopRecording() {
        isRecording = false
        SpectrogramPrefs.setRecordingEnabled(this, false)
        
        val intent = Intent(this, RadiaCodeForegroundService::class.java).apply {
            action = "STOP_SPECTRUM_RECORDING"
        }
        startService(intent)
        
        updateRecordingUI()
    }
    
    private fun updateRecordingUI() {
        if (isRecording) {
            (recordDot.background as GradientDrawable).setColor(colorRed)
            recordRing.alpha = 1f
            startRecordingPulse()
        } else {
            (recordDot.background as GradientDrawable).setColor(colorMuted)
            recordRing.alpha = 0f
            pulseAnimator?.cancel()
        }
    }
    
    private fun startRecordingPulse() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.3f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                recordRing.scaleX = scale
                recordRing.scaleY = scale
                recordRing.alpha = 2f - scale
            }
            start()
        }
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
        
        // Recording duration shown in title or as toast instead of status bar
        // (status bar removed for more chart real estate)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SETTINGS BOTTOM SHEET
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun showSettingsSheet() {
        val density = resources.displayMetrics.density
        
        val dialog = BottomSheetDialog(this, R.style.Theme_RadiaCodeBLE_BottomSheet)
        
        val content = ScrollView(this).apply {
            setBackgroundColor(colorSurface)
            
            addView(LinearLayout(this@VegaSpectralAnalysisActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (16 * density).toInt(), 0, (24 * density).toInt())
                
                // Handle bar
                addView(View(this@VegaSpectralAnalysisActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (40 * density).toInt(),
                        (4 * density).toInt()
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = (16 * density).toInt()
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 2 * density
                        setColor(colorBorder)
                    }
                })
                
                // Title
                addView(TextView(this@VegaSpectralAnalysisActivity).apply {
                    text = "Settings"
                    setTextColor(colorText)
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding((20 * density).toInt(), 0, (20 * density).toInt(), (16 * density).toInt())
                })
                
                addView(createDivider(density))
                
                // DISPLAY section
                addView(createSectionHeader("Display", density))
                
                addView(createToggleRow("Isotope Markers", showIsotopes, density) { enabled ->
                    showIsotopes = enabled
                    SpectrogramPrefs.setShowIsotopeMarkers(this@VegaSpectralAnalysisActivity, enabled)
                    waterfallView.setShowIsotopeMarkers(enabled)
                    histogramView.setShowIsotopeMarkers(enabled)
                })
                
                addView(createPickerRow("Color Scheme", SpectrogramPrefs.getColorScheme(this@VegaSpectralAnalysisActivity).label, density) {
                    dialog.dismiss()
                    showColorSchemePicker()
                })
                
                addView(createDivider(density))
                
                // RECORDING section
                addView(createSectionHeader("Recording", density))
                
                addView(createPickerRow("Update Interval", 
                    SpectrogramPrefs.UpdateInterval.fromMs(SpectrogramPrefs.getUpdateIntervalMs(this@VegaSpectralAnalysisActivity)).label, 
                    density) {
                    dialog.dismiss()
                    showUpdateIntervalPicker()
                })
                
                addView(createPickerRow("History Depth",
                    SpectrogramPrefs.HistoryDepth.fromMs(SpectrogramPrefs.getHistoryDepthMs(this@VegaSpectralAnalysisActivity)).label,
                    density) {
                    dialog.dismiss()
                    showHistoryDepthPicker()
                })
                
                addView(createDivider(density))
                
                // BACKGROUND section
                addView(createSectionHeader("Background Subtraction", density))
                
                val bgEnabled = SpectrogramPrefs.isBackgroundSubtractionEnabled(this@VegaSpectralAnalysisActivity)
                addView(createToggleRow("Enable Subtraction", bgEnabled, density) { enabled ->
                    SpectrogramPrefs.setBackgroundSubtractionEnabled(this@VegaSpectralAnalysisActivity, enabled)
                    waterfallView.setShowBackgroundSubtraction(enabled)
                    if (enabled && SpectrogramPrefs.getSelectedBackgroundId(this@VegaSpectralAnalysisActivity) == null) {
                        dialog.dismiss()
                        showBackgroundPicker()
                    }
                })
                
                addView(createActionRow("Select Background", density) {
                    dialog.dismiss()
                    showBackgroundPicker()
                })
                
                addView(createActionRow("Save Current as Background", density) {
                    dialog.dismiss()
                    saveCurrentAsBackground()
                })
                
                addView(createDivider(density))
                
                // DATA section
                addView(createSectionHeader("Data", density))
                
                addView(createActionRow("Export Data", density) {
                    dialog.dismiss()
                    showExportDialog()
                })
                
                addView(createDivider(density))
                
                // DEBUG section
                addView(createSectionHeader("Debug / Training", density))
                
                addView(createActionRow("Export API Payload (CSV)", density) {
                    dialog.dismiss()
                    exportIsotopeApiPayload()
                })
                
                addView(createDivider(density))
                
                addView(createDangerRow("Clear All Data", density) {
                    dialog.dismiss()
                    showClearConfirmation()
                })
            })
        }
        
        dialog.setContentView(content)
        dialog.show()
    }
    
    private fun createSectionHeader(text: String, density: Float): TextView {
        return TextView(this).apply {
            this.text = text.uppercase()
            setTextColor(colorMuted)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
        }
    }
    
    private fun createToggleRow(label: String, initialState: Boolean, density: Float, onToggle: (Boolean) -> Unit): LinearLayout {
        var isOn = initialState
        
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (52 * density).toInt()
            )
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            
            addView(TextView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                this.text = label
                setTextColor(colorText)
                textSize = 15f
            })
            
            val toggleBg = View(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (24 * density).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(if (isOn) colorCyan else colorBorder)
                }
            }
            
            val toggleDot = View(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (20 * density).toInt(),
                    (20 * density).toInt()
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = if (isOn) (22 * density).toInt() else (2 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorText)
                }
            }
            
            val toggleContainer = FrameLayout(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (24 * density).toInt())
                addView(toggleBg)
                addView(toggleDot)
            }
            addView(toggleContainer)
            
            setOnClickListener {
                isOn = !isOn
                (toggleBg.background as GradientDrawable).setColor(if (isOn) colorCyan else colorBorder)
                (toggleDot.layoutParams as FrameLayout.LayoutParams).leftMargin = 
                    if (isOn) (22 * density).toInt() else (2 * density).toInt()
                toggleDot.requestLayout()
                onToggle(isOn)
            }
        }
    }
    
    private fun createPickerRow(label: String, value: String, density: Float, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (52 * density).toInt()
            )
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            
            addView(TextView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                this.text = label
                setTextColor(colorText)
                textSize = 15f
            })
            
            addView(TextView(this@VegaSpectralAnalysisActivity).apply {
                this.text = value
                setTextColor(colorMuted)
                textSize = 14f
            })
            
            addView(ImageView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (20 * density).toInt(),
                    (20 * density).toInt()
                ).apply {
                    leftMargin = (8 * density).toInt()
                }
                setImageResource(R.drawable.ic_arrow_right)
                setColorFilter(colorMuted)
            })
            
            setOnClickListener { onClick() }
        }
    }
    
    private fun createActionRow(label: String, density: Float, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (52 * density).toInt()
            )
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            
            addView(TextView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                this.text = label
                setTextColor(colorCyan)
                textSize = 15f
            })
            
            addView(ImageView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (20 * density).toInt(),
                    (20 * density).toInt()
                )
                setImageResource(R.drawable.ic_arrow_right)
                setColorFilter(colorMuted)
            })
            
            setOnClickListener { onClick() }
        }
    }
    
    private fun createDangerRow(label: String, density: Float, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (52 * density).toInt()
            )
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            
            addView(TextView(this@VegaSpectralAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                this.text = label
                setTextColor(colorRed)
                textSize = 15f
            })
            
            setOnClickListener { onClick() }
        }
    }
    
    private fun createDivider(density: Float): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                topMargin = (8 * density).toInt()
            }
            setBackgroundColor(colorBorder)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PICKER DIALOGS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun showUpdateIntervalPicker() {
        val intervals = SpectrogramPrefs.UpdateInterval.values()
        val names = intervals.map { it.label }.toTypedArray()
        val current = SpectrogramPrefs.UpdateInterval.fromMs(SpectrogramPrefs.getUpdateIntervalMs(this))
        
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Update Interval")
            .setSingleChoiceItems(names, intervals.indexOf(current)) { dialog, which ->
                SpectrogramPrefs.setUpdateIntervalMs(this, intervals[which].ms)
                dialog.dismiss()
            }
            .show()
    }
    
    private fun showHistoryDepthPicker() {
        val depths = SpectrogramPrefs.HistoryDepth.values()
        val names = depths.map { it.label }.toTypedArray()
        val current = SpectrogramPrefs.HistoryDepth.fromMs(SpectrogramPrefs.getHistoryDepthMs(this))
        
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("History Depth")
            .setSingleChoiceItems(names, depths.indexOf(current)) { dialog, which ->
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
        
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Color Scheme")
            .setSingleChoiceItems(names, schemes.indexOf(current)) { dialog, which ->
                SpectrogramPrefs.setColorScheme(this, schemes[which])
                waterfallView.setColorScheme(schemes[which])
                dialog.dismiss()
            }
            .show()
    }
    
    private fun showBackgroundPicker() {
        val backgrounds = SpectrogramRepository.getInstance(this).getBackgrounds()
        
        if (backgrounds.isEmpty()) {
            AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
                .setTitle("No Backgrounds")
                .setMessage("Save a spectrum as background first.")
                .setPositiveButton("OK", null)
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
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveCurrentAsBackground() {
        val deviceId = this.deviceId ?: return
        val snapshots = SpectrogramRepository.getInstance(this)
            .getSnapshots(deviceId, 0, System.currentTimeMillis())
            .filter { !it.isDifferential }
        
        if (snapshots.isEmpty()) {
            Toast.makeText(this, "No accumulated spectrum available", Toast.LENGTH_SHORT).show()
            return
        }
        
        val latest = snapshots.maxByOrNull { it.timestampMs } ?: return
        
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
    
    private fun showExportDialog() {
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Export Data")
            .setItems(arrayOf("Export as CSV", "Export as RadiaCode (.rctrk)")) { _, which ->
                when (which) {
                    0 -> exportAsCsv()
                    1 -> Toast.makeText(this, "RadiaCode format coming soon", Toast.LENGTH_SHORT).show()
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
        
        val sb = StringBuilder()
        sb.append("timestamp_ms,source_type,integration_s,channel_count")
        val channelCount = snapshots.first().spectrumData.counts.size
        for (i in 0 until channelCount) {
            sb.append(",ch_$i")
        }
        sb.append("\n")
        
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
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(cacheDir, "spectrogram_$timestamp.csv")
        file.writeText(sb.toString())
        
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export Spectrogram"))
    }
    
    /**
     * Export the exact payload that would be sent to the Vega Isotope Analysis API (v2.0).
     * This is useful for collecting training data for the isotope classification model.
     * 
     * The API v2.0 expects a 2D matrix:
     *   - Shape: (60, 1023)
     *   - Axis 0: Time intervals (60 one-second intervals)
     *   - Axis 1: Energy channels (1023 channels, 20 keV to 3000 keV)
     * 
     * Exports the most recent 60 differential snapshots in chronological order.
     */
    private fun exportIsotopeApiPayload() {
        val deviceId = this.deviceId ?: run {
            Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val source = SpectrogramPrefs.getSpectrumSource(this)
            val historyMs = SpectrogramPrefs.getHistoryDepthMs(this)
            val startTime = System.currentTimeMillis() - historyMs
            
            // Model was trained on differential data only
            if (source != SpectrumSourceType.DIFFERENTIAL) {
                Toast.makeText(this, "Switch to Differential mode first (model trained on differential data)", Toast.LENGTH_LONG).show()
                return
            }
            
            val requiredSamples = VegaIsotopeApiClient.REQUIRED_TIME_INTERVALS
            
            val allSnapshots = SpectrogramRepository.getInstance(this)
                .getSnapshots(deviceId, startTime, System.currentTimeMillis())
                .filter { it.isDifferential }
                .sortedByDescending { it.timestampMs }  // Most recent first
            
            if (allSnapshots.isEmpty()) {
                Toast.makeText(this, "No differential spectrum data available", Toast.LENGTH_SHORT).show()
                return
            }
            
            if (allSnapshots.size < requiredSamples) {
                val remaining = requiredSamples - allSnapshots.size
                Toast.makeText(
                    this,
                    "Need $requiredSamples samples for export.\n" +
                    "Currently have ${allSnapshots.size} samples.\n" +
                    "$remaining more needed - please wait.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            
            // Take most recent 60 and reverse to chronological order
            val snapshots = allSnapshots.take(requiredSamples).reversed()
            
            // Step 1: Find global max for normalization (matches training data format)
            var globalMax = 0
            var totalCounts = 0L
            for (snapshot in snapshots) {
                val counts = snapshot.spectrumData.counts
                for (i in 0 until minOf(VegaIsotopeApiClient.EXPECTED_CHANNELS, counts.size)) {
                    val count = counts[i]
                    if (count > globalMax) globalMax = count
                    totalCounts += count
                }
            }
            
            // Build CSV - each row is one snapshot, normalized to [0,1] range
            // This matches the training data format: float64, max-normalized
            val sb = StringBuilder()
            
            // Header row: channel_0, channel_1, ..., channel_1022
            for (i in 0 until VegaIsotopeApiClient.EXPECTED_CHANNELS) {
                if (i > 0) sb.append(",")
                sb.append("channel_$i")
            }
            sb.append("\n")
            
            // Each snapshot becomes one row with normalized float values
            for (snapshot in snapshots) {
                val counts = snapshot.spectrumData.counts
                for (i in 0 until VegaIsotopeApiClient.EXPECTED_CHANNELS) {
                    if (i > 0) sb.append(",")
                    val count = if (i < counts.size) counts[i] else 0
                    // Normalize: divide by global max
                    val normalized = if (globalMax > 0) count.toDouble() / globalMax else 0.0
                    sb.append(String.format("%.8f", normalized))
                }
                sb.append("\n")
            }
            
            // Write to file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(cacheDir, "vega_training_matrix_$timestamp.csv")
            file.writeText(sb.toString())
            
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Export Training Matrix"))
            
            Toast.makeText(this, "Exported ${snapshots.size}x${VegaIsotopeApiClient.EXPECTED_CHANNELS} normalized matrix (max=$globalMax)", Toast.LENGTH_LONG).show()
            
        } catch (e: Exception) {
            android.util.Log.e("VegaSpectral", "Export API payload failed", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showClearConfirmation() {
        AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle("Clear Data")
            .setMessage("Delete all recorded spectrum data? This cannot be undone.\n\nFor accumulated spectrum, this will set a new zero point from the current device reading.")
            .setPositiveButton("Clear") { _, _ ->
                deviceId?.let { id ->
                    // For accumulated mode, save current spectrum as baseline to create a "zero point"
                    // This way clearing the data shows counts from NOW, not device lifetime
                    val source = SpectrogramPrefs.getSpectrumSource(this)
                    if (source == SpectrumSourceType.ACCUMULATED) {
                        // Get the last accumulated snapshot to use as baseline
                        val historyMs = SpectrogramPrefs.getHistoryDepthMs(this)
                        val startTime = System.currentTimeMillis() - historyMs
                        val lastAccumSnapshot = SpectrogramRepository.getInstance(this)
                            .getSnapshots(id, startTime, System.currentTimeMillis())
                            .filter { !it.isDifferential }
                            .lastOrNull()
                        
                        if (lastAccumSnapshot != null) {
                            SpectrogramPrefs.setBaselineSpectrum(this, id, lastAccumSnapshot.spectrumData.counts)
                            android.util.Log.d("VegaSpectral", "Set baseline spectrum with ${lastAccumSnapshot.spectrumData.counts.sumOf { it.toLong() }} total counts")
                        }
                    }
                    
                    // Clear all data from the repository
                    SpectrogramRepository.getInstance(this).clearDeviceData(id)
                    
                    // Explicitly clear chart views to show empty state
                    histogramView.clearAll()
                    waterfallView.setSnapshots(emptyList())
                    waterfallView.invalidate()
                    histogramView.invalidate()
                    
                    // DON'T call refreshData() here - it would immediately show new incoming data
                    // The views should stay empty until new data accumulates
                    
                    Toast.makeText(this, "Data cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AI ISOTOPE ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun performIsotopeAnalysis() {
        try {
            android.util.Log.d("VegaIsotope", "performIsotopeAnalysis called")
            
            val deviceId = this.deviceId
            if (deviceId == null) {
                Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show()
                return
            }
            
            android.util.Log.d("VegaIsotope", "Device ID: $deviceId")
            
            // Get the current spectrum data based on mode
            val source = SpectrogramPrefs.getSpectrumSource(this)
            val historyMs = SpectrogramPrefs.getHistoryDepthMs(this)
            val startTime = System.currentTimeMillis() - historyMs
            
            android.util.Log.d("VegaIsotope", "Source: $source, historyMs: $historyMs")
            
            // Model requires differential mode (trained on 2D spectrogram images)
            val wantDifferential = source == SpectrumSourceType.DIFFERENTIAL
            if (!wantDifferential) {
                Toast.makeText(this, "Switch to Differential mode for isotope analysis", Toast.LENGTH_LONG).show()
                return
            }
            
            val allSnapshots = SpectrogramRepository.getInstance(this)
                .getSnapshots(deviceId, startTime, System.currentTimeMillis())
                .filter { it.isDifferential }
                .sortedByDescending { it.timestampMs }  // Most recent first
            
            android.util.Log.d("VegaIsotope", "Got ${allSnapshots.size} differential snapshots")
            
            // API v2.0 requires exactly 60 time intervals (1-second snapshots)
            val requiredSamples = VegaIsotopeApiClient.REQUIRED_TIME_INTERVALS
            
            if (allSnapshots.size < requiredSamples) {
                val remaining = requiredSamples - allSnapshots.size
                Toast.makeText(
                    this,
                    "Need $requiredSamples samples for analysis.\n" +
                    "Currently have ${allSnapshots.size} samples.\n" +
                    "$remaining more needed - please wait.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            
            // Take the most recent 60 snapshots and reverse to chronological order
            val snapshots = allSnapshots.take(requiredSamples).reversed()
            
            val totalCounts = snapshots.sumOf { it.spectrumData.counts.sum().toLong() }
            android.util.Log.d("VegaIsotope", "Matrix size: ${snapshots.size} rows x 1023 channels, totalCounts: $totalCounts")
            
            if (totalCounts < 100) {
                Toast.makeText(this, "Insufficient data (need more counts)", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Show the results dialog
            android.util.Log.d("VegaIsotope", "Creating dialog")
            val dialog = VegaIsotopeResultDialog(this)
            android.util.Log.d("VegaIsotope", "Showing dialog")
            dialog.show()
            android.util.Log.d("VegaIsotope", "Dialog shown, calling API with ${snapshots.size} snapshots (60x1023 matrix)")
            
            // Call the API with 2D matrix (60 time intervals x 1023 channels)
            VegaIsotopeApiClient.identifyIsotopes(
                snapshots = snapshots,
                threshold = 0.3f,  // Lower threshold for sensitivity
                returnAll = false
            ) { response ->
                android.util.Log.d("VegaIsotope", "API response received: success=${response.success}")
                // Update UI on main thread
                mainHandler.post {
                    if (dialog.isShowing) {
                        dialog.showResults(response)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VegaIsotope", "performIsotopeAnalysis failed", e)
            Toast.makeText(this, "Analysis error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA LOADING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun loadPreferences() {
        currentSource = SpectrogramPrefs.getSpectrumSource(this)
        sourceLabel.text = when (currentSource) {
            SpectrumSourceType.DIFFERENTIAL -> "Diff"
            SpectrumSourceType.ACCUMULATED -> "Accum"
        }
        
        when (currentSource) {
            SpectrumSourceType.DIFFERENTIAL -> {
                waterfallView.visibility = View.VISIBLE
                histogramView.visibility = View.GONE
                smoothingContainer.visibility = View.GONE
                snapToggle.visibility = View.GONE
            }
            SpectrumSourceType.ACCUMULATED -> {
                waterfallView.visibility = View.GONE
                histogramView.visibility = View.VISIBLE
                smoothingContainer.visibility = View.VISIBLE
                snapToggle.visibility = View.VISIBLE
            }
        }
        
        showIsotopes = SpectrogramPrefs.isShowIsotopeMarkers(this)
        waterfallView.setShowIsotopeMarkers(showIsotopes)
        histogramView.setShowIsotopeMarkers(showIsotopes)
        
        isLogScale = true
        histogramView.setLogScale(true)
        
        val bgEnabled = SpectrogramPrefs.isBackgroundSubtractionEnabled(this)
        waterfallView.setShowBackgroundSubtraction(bgEnabled)
        
        waterfallView.setColorScheme(SpectrogramPrefs.getColorScheme(this))
        
        isRecording = SpectrogramPrefs.isRecordingEnabled(this)
        updateRecordingUI()
    }
    
    /**
     * Schedules a debounced refresh to prevent rapid-fire updates from blocking the UI.
     * This is called from the broadcast receiver when new spectrum data arrives.
     * Max refresh rate is ~10 Hz (100ms debounce).
     */
    private fun scheduleRefresh() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefreshTime
        
        if (elapsed >= REFRESH_DEBOUNCE_MS) {
            // Enough time has passed, refresh immediately
            lastRefreshTime = now
            refreshDataAsync()
        } else if (!refreshPending) {
            // Schedule a refresh after debounce period
            refreshPending = true
            mainHandler.postDelayed({
                refreshPending = false
                lastRefreshTime = System.currentTimeMillis()
                refreshDataAsync()
            }, REFRESH_DEBOUNCE_MS - elapsed)
        }
        // else: refresh already pending, do nothing
    }
    
    /**
     * Async version of refreshData() - performs repository access on IO thread,
     * then posts UI updates to main thread. This prevents lock contention from
     * blocking the UI thread when BLE polling is writing to the repository.
     */
    private fun refreshDataAsync() {
        val deviceId = this.deviceId ?: return
        val context = this
        
        // Capture current settings on UI thread (these are fast prefs reads)
        val source = SpectrogramPrefs.getSpectrumSource(this)
        val historyMs = SpectrogramPrefs.getHistoryDepthMs(this)
        val wantDifferential = source == SpectrumSourceType.DIFFERENTIAL
        val bgSubEnabled = SpectrogramPrefs.isBackgroundSubtractionEnabled(this)
        val bgId = if (bgSubEnabled) SpectrogramPrefs.getSelectedBackgroundId(this) else null
        val baseline = SpectrogramPrefs.getBaselineSpectrum(this, deviceId)
        
        // Do heavy work (repository access with locks) on IO thread
        ioExecutor.execute {
            try {
                val startTime = System.currentTimeMillis() - historyMs
                val snapshots = SpectrogramRepository.getInstance(context)
                    .getSnapshots(deviceId, startTime, System.currentTimeMillis())
                    .filter { it.isDifferential == wantDifferential }
                
                val bg = if (bgId != null) {
                    SpectrogramRepository.getInstance(context).getBackgrounds()
                        .find { it.id == bgId }
                } else null
                
                // Post UI updates back to main thread
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    
                    if (wantDifferential) {
                        waterfallView.setSnapshots(snapshots)
                        if (snapshots.isNotEmpty()) {
                            val first = snapshots.first()
                            waterfallView.setCalibration(first.spectrumData.a0, first.spectrumData.a1, first.spectrumData.a2)
                        }
                    } else {
                        // ACCUMULATED mode: Use the LATEST snapshot only, don't sum!
                        if (snapshots.isNotEmpty()) {
                            val latest = snapshots.last()
                            var displayCounts = latest.spectrumData.counts
                            
                            // Subtract baseline if set
                            if (baseline != null && baseline.size == displayCounts.size) {
                                displayCounts = IntArray(displayCounts.size) { i ->
                                    maxOf(0, displayCounts[i] - baseline[i])
                                }
                            }
                            
                            histogramView.setSpectrum(
                                counts = displayCounts,
                                a0 = latest.spectrumData.a0,
                                a1 = latest.spectrumData.a1,
                                a2 = latest.spectrumData.a2,
                                sampleCount = snapshots.size
                            )
                        } else {
                            histogramView.setSpectrum(intArrayOf(), 0f, 3f, 0f, sampleCount = 0)
                        }
                    }
                    
                    if (!isRecording) {
                        updateStatus("${snapshots.size} snapshots")
                    }
                    
                    if (bgSubEnabled && bg != null) {
                        waterfallView.setBackgroundSpectrum(bg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing data", e)
            }
        }
    }
    
    /**
     * Synchronous version - only used when we need immediate data (e.g., for export).
     * For normal UI updates, use refreshDataAsync().
     */
    private fun refreshData() {
        val deviceId = this.deviceId ?: return

        val source = SpectrogramPrefs.getSpectrumSource(this)
        val historyMs = SpectrogramPrefs.getHistoryDepthMs(this)
        val startTime = System.currentTimeMillis() - historyMs

        val wantDifferential = source == SpectrumSourceType.DIFFERENTIAL
        val snapshots = SpectrogramRepository.getInstance(this)
            .getSnapshots(deviceId, startTime, System.currentTimeMillis())
            .filter { it.isDifferential == wantDifferential }

        if (wantDifferential) {
            waterfallView.setSnapshots(snapshots)
            if (snapshots.isNotEmpty()) {
                val first = snapshots.first()
                waterfallView.setCalibration(first.spectrumData.a0, first.spectrumData.a1, first.spectrumData.a2)
            }
        } else {
            // ACCUMULATED mode: Use the LATEST snapshot only, don't sum!
            // Each accumulated spectrum already contains the device's total lifetime counts.
            // Summing multiple accumulated snapshots would multiply the counts incorrectly.
            if (snapshots.isNotEmpty()) {
                val latest = snapshots.last()
                var displayCounts = latest.spectrumData.counts
                
                // Subtract baseline if set (to show counts since clear, not device lifetime)
                val baseline = SpectrogramPrefs.getBaselineSpectrum(this, deviceId)
                if (baseline != null && baseline.size == displayCounts.size) {
                    displayCounts = IntArray(displayCounts.size) { i ->
                        // Clamp to zero in case baseline is higher (shouldn't happen but be safe)
                        maxOf(0, displayCounts[i] - baseline[i])
                    }
                }
                
                histogramView.setSpectrum(
                    counts = displayCounts,
                    a0 = latest.spectrumData.a0,
                    a1 = latest.spectrumData.a1,
                    a2 = latest.spectrumData.a2,
                    sampleCount = snapshots.size
                )
            } else {
                histogramView.setSpectrum(intArrayOf(), 0f, 3f, 0f, sampleCount = 0)
            }
        }

        if (!isRecording) {
            updateStatus("${snapshots.size} snapshots")
        }

        if (SpectrogramPrefs.isBackgroundSubtractionEnabled(this)) {
            val bgId = SpectrogramPrefs.getSelectedBackgroundId(this)
            if (bgId != null) {
                val bg = SpectrogramRepository.getInstance(this).getBackgrounds()
                    .find { it.id == bgId }
                waterfallView.setBackgroundSpectrum(bg)
            }
        }
    }
    
    private fun updateStatus(text: String) {
        // Status bar removed for more chart real estate
        // Could show toast or update title if needed
    }
}
