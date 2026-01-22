package com.radiacode.ble.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.radiacode.ble.Prefs
import com.radiacode.ble.R

/**
 * VegaGpsWarningDialog - Warning dialog when enabling GPS tracking.
 * 
 * Features:
 * - Real-time waveform visualization when Vega speaks (if voice enabled)
 * - Elegant dark theme matching the app design
 * - "I understand, enable GPS" and "Cancel" buttons
 * - Only speaks if Vega voice is enabled in sound settings
 */
class VegaGpsWarningDialog(
    context: Context,
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit
) : Dialog(context, R.style.Theme_RadiaCode_Dialog_FullScreen) {

    private lateinit var waveformView: WaveformVisualizerView
    private lateinit var warningText: TextView
    private lateinit var confirmButton: TextView
    private lateinit var cancelButton: TextView
    
    private val handler = Handler(Looper.getMainLooper())
    private var visualizer: Visualizer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var hasSpeechStarted = false
    private var waveformAnimator: ValueAnimator? = null
    
    companion object {
        // Warning text for GPS tracking (displayed on screen - no hyphens for clarity)
        private const val WARNING_TEXT = """Enabling high accuracy GPS tracking will significantly impact battery life.

This feature records your radiological journey with precise location data, creating detailed maps of radiation levels as you move.

Consider using a battery bank or enabling this feature only when actively mapping your environment.

When GPS tracking is enabled, the app will continuously collect location data and store radiation readings for each position you visit."""
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        // Build UI programmatically for full control
        val rootLayout = createLayout()
        setContentView(rootLayout)
        
        // Make dialog properly sized with blur behind it
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            // Add dim and blur effect
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.7f)
            // Enable blur if supported (Android 12+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setBackgroundBlurRadius(25)
            }
        }
        
        // Prevent dismiss on outside touch
        setCanceledOnTouchOutside(false)
        
        // Start Vega's warning if voice is enabled
        if (Prefs.isVegaTtsEnabled(context)) {
            playPrebakedWarning()
        } else {
            // Just show simulated waveform animation if voice disabled
            waveformView.setUsingRealAudio(false)
            startSimulatedWaveform()
        }
    }
    
    private fun createLayout(): View {
        val density = context.resources.displayMetrics.density
        
        // Root container
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Card container with dark background
        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (16 * density).toInt()
                rightMargin = (16 * density).toInt()
                topMargin = (48 * density).toInt()
                bottomMargin = (48 * density).toInt()
            }
            background = createCardBackground(density)
            setPadding(
                (20 * density).toInt(),
                (24 * density).toInt(),
                (20 * density).toInt(),
                (20 * density).toInt()
            )
        }
        
        // Warning icon and title row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }
        
        // Warning icon
        val warningIcon = TextView(context).apply {
            text = "⚠️"
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (12 * density).toInt()
            }
        }
        titleRow.addView(warningIcon)
        
        // Title
        val titleText = TextView(context).apply {
            text = "GPS Tracking Warning"
            textSize = 20f
            setTextColor(Color.parseColor("#FFD600"))  // pro_yellow/warning color
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        titleRow.addView(titleText)
        cardLayout.addView(titleRow)
        
        // Waveform visualizer container
        val waveformContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (80 * density).toInt()
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
            background = createWaveformBackground(density)
        }
        
        // Waveform view
        waveformView = WaveformVisualizerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = (2 * density).toInt()
                rightMargin = (2 * density).toInt()
                topMargin = (2 * density).toInt()
                bottomMargin = (2 * density).toInt()
            }
        }
        waveformContainer.addView(waveformView)
        cardLayout.addView(waveformContainer)
        
        // Warning text
        warningText = TextView(context).apply {
            text = WARNING_TEXT
            textSize = 14f
            setTextColor(Color.parseColor("#E8E8F0"))
            setLineSpacing(0f, 1.4f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (24 * density).toInt()
            }
        }
        cardLayout.addView(warningText)
        
        // Button container
        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Cancel button
        cancelButton = TextView(context).apply {
            text = "Cancel"
            textSize = 15f
            setTextColor(Color.parseColor("#9E9EA8"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(
                (24 * density).toInt(),
                (12 * density).toInt(),
                (24 * density).toInt(),
                (12 * density).toInt()
            )
            background = createButtonBackground(density, false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (12 * density).toInt()
            }
            setOnClickListener { 
                cleanupAndDismiss()
                onCancel()
            }
        }
        buttonContainer.addView(cancelButton)
        
        // Confirm button
        confirmButton = TextView(context).apply {
            text = "I understand, enable GPS"
            textSize = 15f
            setTextColor(Color.parseColor("#00E5FF"))  // pro_cyan
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(
                (24 * density).toInt(),
                (12 * density).toInt(),
                (24 * density).toInt(),
                (12 * density).toInt()
            )
            background = createButtonBackground(density, true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { 
                cleanupAndDismiss()
                onConfirm()
            }
        }
        buttonContainer.addView(confirmButton)
        
        cardLayout.addView(buttonContainer)
        root.addView(cardLayout)
        return root
    }
    
    private fun createCardBackground(density: Float): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 16 * density
            setColor(Color.parseColor("#1A1A1E"))  // pro_surface
            setStroke((1 * density).toInt(), Color.parseColor("#2A2A2E"))  // pro_border
        }
    }
    
    private fun createWaveformBackground(density: Float): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 12 * density
            setStroke((1 * density).toInt(), Color.parseColor("#2A2A2E"))
            setColor(Color.parseColor("#121216"))
        }
    }
    
    private fun createButtonBackground(density: Float, isPrimary: Boolean): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20 * density
            if (isPrimary) {
                setStroke((1 * density).toInt(), Color.parseColor("#00E5FF"))
                setColor(Color.parseColor("#1A2A30"))
            } else {
                setStroke((1 * density).toInt(), Color.parseColor("#3A3A3E"))
                setColor(Color.parseColor("#1A1A1E"))
            }
        }
    }
    
    /**
     * Play the pre-baked GPS warning audio from raw resources.
     * This avoids real-time API calls and ensures consistent playback.
     */
    private fun playPrebakedWarning() {
        hasSpeechStarted = true
        waveformView.setUsingRealAudio(false)
        
        try {
            // Release any existing player
            mediaPlayer?.release()
            
            // Create MediaPlayer from raw resource
            mediaPlayer = MediaPlayer.create(context, R.raw.vega_gps_warning)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .build()
                )
                
                setOnCompletionListener {
                    // Speech finished - stop waveform animation
                    handler.post {
                        waveformAnimator?.cancel()
                        waveformAnimator = null
                    }
                }
                
                setOnErrorListener { _, what, extra ->
                    android.util.Log.e("VegaGpsWarning", "MediaPlayer error: what=$what, extra=$extra")
                    true
                }
                
                start()
            }
            
            // Start waveform animation synced to audio duration
            if (mediaPlayer != null) {
                startSimulatedWaveform()
            } else {
                // Fallback - audio file not found, just show animation
                android.util.Log.w("VegaGpsWarning", "Pre-baked audio not found, using animation only")
                startSimulatedWaveform()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("VegaGpsWarning", "Failed to play pre-baked audio", e)
            // Fallback to just the animation
            startSimulatedWaveform()
        }
    }
    
    private fun startSimulatedWaveform() {
        waveformAnimator?.cancel()
        
        waveformAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                // Generate random waveform data
                val data = ByteArray(128) { 
                    ((Math.random() * 80 + 88).toInt() and 0xFF).toByte() 
                }
                waveformView.updateWaveform(data)
            }
            start()
        }
        
        // Stop after typical speech duration (about 12 seconds) if no MediaPlayer
        if (mediaPlayer == null) {
            handler.postDelayed({
                waveformAnimator?.cancel()
                waveformAnimator = null
            }, 12000)
        }
    }
    
    private fun cleanupAndDismiss() {
        try {
            // Stop waveform animation
            waveformAnimator?.cancel()
            waveformAnimator = null
            
            // Stop and release media player
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            
            // Release visualizer
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        handler.removeCallbacksAndMessages(null)
        dismiss()
    }
    
    override fun onStop() {
        cleanupAndDismiss()
        super.onStop()
    }
}
