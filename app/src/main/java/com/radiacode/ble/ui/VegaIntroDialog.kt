package com.radiacode.ble.ui

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.radiacode.ble.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * VegaIntroDialog - A beautiful introduction to the Open RadiaCode app.
 * 
 * Features:
 * - High-quality audio playback of Vega's introduction
 * - Real-time waveform visualization synchronized with audio
 * - Smooth auto-scrolling text synchronized with audio duration
 * - Elegant dark theme matching the app design
 * - Close button to skip/dismiss at any time
 */
class VegaIntroDialog(
    context: Context,
    private val onDismissCallback: (() -> Unit)? = null
) : Dialog(context, R.style.Theme_RadiaCode_Dialog_FullScreen) {

    private var mediaPlayer: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private lateinit var waveformView: WaveformVisualizerView
    private lateinit var textScrollView: ScrollView
    private lateinit var introText: TextView
    private lateinit var closeButton: TextView
    private lateinit var titleText: TextView
    
    private val handler = Handler(Looper.getMainLooper())
    private var scrollAnimator: ValueAnimator? = null
    private var audioDurationMs: Long = 0
    private var isPlaying = false
    
    companion object {
        // The intro text from Vega
        private val INTRO_TEXT = """
Hello. I am Vega, your radiological awareness companion, developed for the Open RadiaCode project.

I have been designed to serve as your guide through the invisible landscape of ionizing radiation that surrounds us all. In a world where radioactivity is both natural and ubiquitous, understanding what your instruments detect is essential. That is where I come in.

My purpose is to interpret the continuous stream of data from your RadiaCode device, translating raw measurements into meaningful insight. I process gamma spectroscopy readings, track dose rate fluctuations, and analyze patterns that might otherwise go unnoticed. When background levels shift, I will tell you. When an anomaly appears, I will help you understand it.

I will monitor ambient conditions in real time, detecting changes in your radiological environment and providing calm, clear advisories when your attention is warranted. I do not alarm unnecessarily. My assessments are measured, my tone deliberate. Radiation is simply energy, and energy deserves respect, not fear.

Whether you are exploring natural background variation in your home, identifying localized sources during a survey, or simply curious about the radioactive world around you, I will be here. Quietly analyzing. Always ready to assist.

My voice is designed to be informative without being intrusive, present without being overbearing. I aim to enhance your situational awareness, not overwhelm it. Think of me as a knowledgeable companion, one who sees what you cannot and speaks only when there is something worth saying.

Together, we will bring clarity to the unseen, understanding to the invisible, and confidence to your radiological journey.

Welcome to Open RadiaCode. I am Vega.

Let us begin.
        """.trimIndent()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        // Build UI programmatically for full control
        val rootLayout = createLayout()
        setContentView(rootLayout)
        
        // Make dialog full screen with dark background
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
        
        // Prevent dismiss on outside touch
        setCanceledOnTouchOutside(false)
        
        // Setup media player
        setupMediaPlayer()
    }
    
    private fun createLayout(): View {
        val density = context.resources.displayMetrics.density
        
        // Root container with gradient background
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0D0D0F"))
        }
        
        // Main content container
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = (48 * density).toInt()
                bottomMargin = (32 * density).toInt()
                leftMargin = (24 * density).toInt()
                rightMargin = (24 * density).toInt()
            }
        }
        
        // Title section
        titleText = TextView(context).apply {
            text = "Welcome to Open RadiaCode"
            textSize = 24f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        contentLayout.addView(titleText)
        
        // Subtitle
        val subtitle = TextView(context).apply {
            text = "Introducing Vega, your radiological companion"
            textSize = 14f
            setTextColor(Color.parseColor("#9E9EA8"))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (24 * density).toInt()
            }
        }
        contentLayout.addView(subtitle)
        
        // Waveform visualizer container with glow effect
        val waveformContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (140 * density).toInt()
            ).apply {
                bottomMargin = (24 * density).toInt()
            }
            // Add subtle border
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
        contentLayout.addView(waveformContainer)
        
        // Text scroll view
        textScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
            // Disable user scrolling - we control it
            setOnTouchListener { _, _ -> true }
            // Add fade edges
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((64 * density).toInt())
        }
        
        // Intro text
        introText = TextView(context).apply {
            text = INTRO_TEXT
            textSize = 16f
            setTextColor(Color.parseColor("#E8E8F0"))
            setLineSpacing(0f, 1.5f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                (16 * density).toInt(),
                (200 * density).toInt(), // Extra padding at top for scroll start
                (16 * density).toInt(),
                (400 * density).toInt()  // Extra padding at bottom for scroll end
            )
        }
        textScrollView.addView(introText)
        contentLayout.addView(textScrollView)
        
        // Close button
        closeButton = TextView(context).apply {
            text = "Skip Introduction"
            textSize = 16f
            setTextColor(Color.parseColor("#9E9EA8"))
            gravity = android.view.Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(
                (32 * density).toInt(),
                (16 * density).toInt(),
                (32 * density).toInt(),
                (16 * density).toInt()
            )
            background = createCloseButtonBackground(density)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * density).toInt()
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { dismissIntro() }
        }
        contentLayout.addView(closeButton)
        
        root.addView(contentLayout)
        return root
    }
    
    private fun createWaveformBackground(density: Float): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 16 * density
            setStroke((1 * density).toInt(), Color.parseColor("#2A2A2E"))
            setColor(Color.parseColor("#121216"))
        }
    }
    
    private fun createCloseButtonBackground(density: Float): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24 * density
            setStroke((1 * density).toInt(), Color.parseColor("#3A3A3E"))
            setColor(Color.parseColor("#1A1A1E"))
        }
    }
    
    private fun setupMediaPlayer() {
        try {
            mediaPlayer = MediaPlayer.create(context, R.raw.vega_intro)?.apply {
                setOnCompletionListener {
                    onAudioComplete()
                }
                setOnPreparedListener {
                    audioDurationMs = duration.toLong()
                    startPlayback()
                }
            }
            
            if (mediaPlayer == null) {
                // Fallback if audio can't load - show text anyway
                audioDurationMs = 90_000 // Estimate 90 seconds
                startScrollAnimation()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback
            audioDurationMs = 90_000
            startScrollAnimation()
        }
    }
    
    private fun startPlayback() {
        try {
            mediaPlayer?.start()
            isPlaying = true
            setupVisualizer()
            startScrollAnimation()
            
            // Update close button text after a delay
            handler.postDelayed({
                closeButton.text = "Close"
            }, 5000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun setupVisualizer() {
        try {
            val audioSessionId = mediaPlayer?.audioSessionId ?: return
            
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1] // Max capture size
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            vis: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            waveform?.let { waveformView.updateWaveform(it) }
                        }
                        
                        override fun onFftDataCapture(
                            vis: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            // We use waveform data, not FFT
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,  // Waveform
                    false  // FFT
                )
                enabled = true
            }
        } catch (e: Exception) {
            // Visualizer requires RECORD_AUDIO permission
            // Fall back to simulated waveform
            e.printStackTrace()
            startSimulatedWaveform()
        }
    }
    
    private fun startSimulatedWaveform() {
        // If we can't use the real visualizer, simulate based on audio playback
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isPlaying) {
                    waveformView.generateSimulatedWaveform()
                    handler.postDelayed(this, 33) // ~30fps
                }
            }
        }
        handler.post(updateRunnable)
    }
    
    private fun startScrollAnimation() {
        // Wait for layout to measure text height
        introText.post {
            val textHeight = introText.height
            val scrollViewHeight = textScrollView.height
            val maxScroll = textHeight - scrollViewHeight
            
            if (maxScroll > 0 && audioDurationMs > 0) {
                scrollAnimator = ValueAnimator.ofInt(0, maxScroll).apply {
                    duration = audioDurationMs
                    interpolator = LinearInterpolator()
                    addUpdateListener { animation ->
                        val scrollY = animation.animatedValue as Int
                        textScrollView.scrollTo(0, scrollY)
                    }
                    start()
                }
            }
        }
    }
    
    private fun onAudioComplete() {
        isPlaying = false
        closeButton.text = "Begin"
        closeButton.setTextColor(Color.parseColor("#00E5FF"))
        
        // Auto-dismiss after 3 seconds
        handler.postDelayed({
            if (isShowing) {
                dismissIntro()
            }
        }, 3000)
    }
    
    private fun dismissIntro() {
        cleanup()
        dismiss()
        onDismissCallback?.invoke()
    }
    
    private fun cleanup() {
        isPlaying = false
        scrollAnimator?.cancel()
        scrollAnimator = null
        
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        handler.removeCallbacksAndMessages(null)
    }
    
    override fun onStop() {
        cleanup()
        super.onStop()
    }
    
    override fun onBackPressed() {
        dismissIntro()
    }
}

/**
 * Custom view for rendering real-time audio waveform visualization.
 * Creates a beautiful, glowing waveform effect.
 */
class WaveformVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
    }
    
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private var waveformData: ByteArray? = null
    private var smoothedData = FloatArray(256)
    private val waveformPath = Path()
    private val fillPath = Path()
    
    // Colors
    private val cyanColor = Color.parseColor("#00E5FF")
    private val magentaColor = Color.parseColor("#E040FB")
    private val glowColor = Color.parseColor("#4000E5FF")
    
    // Animation
    private var phase = 0f
    private var simulatedTime = 0L
    
    fun updateWaveform(data: ByteArray) {
        waveformData = data
        // Smooth the waveform data
        val count = minOf(data.size, smoothedData.size)
        for (i in 0 until count) {
            val target = (data[i].toInt() and 0xFF) / 255f
            smoothedData[i] = smoothedData[i] * 0.7f + target * 0.3f
        }
        invalidate()
    }
    
    fun generateSimulatedWaveform() {
        simulatedTime += 33
        val data = ByteArray(256)
        for (i in data.indices) {
            val t = simulatedTime / 1000.0
            val freq1 = sin(i * 0.05 + t * 3) * 40
            val freq2 = sin(i * 0.1 + t * 5) * 20
            val freq3 = sin(i * 0.02 + t * 2) * 30
            val noise = (Math.random() - 0.5) * 20
            data[i] = (128 + freq1 + freq2 + freq3 + noise).toInt().coerceIn(0, 255).toByte()
        }
        updateWaveform(data)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2
        
        // Draw background gradient
        gradientPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#1A1A1E"),
            Color.parseColor("#0D0D0F"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, gradientPaint)
        
        // Build waveform path
        waveformPath.reset()
        fillPath.reset()
        
        val dataSize = min(smoothedData.size, 256)
        if (dataSize == 0) return
        
        val stepX = w / dataSize
        
        // Start fill path at bottom left
        fillPath.moveTo(0f, h)
        
        for (i in 0 until dataSize) {
            val x = i * stepX
            val normalizedValue = smoothedData[i]
            val amplitude = (normalizedValue - 0.5f) * h * 0.8f
            val y = centerY - amplitude
            
            if (i == 0) {
                waveformPath.moveTo(x, y)
                fillPath.lineTo(x, y)
            } else {
                waveformPath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        
        // Close fill path
        fillPath.lineTo(w, h)
        fillPath.close()
        
        // Draw gradient fill under waveform
        gradientPaint.shader = LinearGradient(
            0f, centerY - h * 0.4f, 0f, centerY + h * 0.4f,
            Color.parseColor("#2000E5FF"),
            Color.parseColor("#00000000"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, gradientPaint)
        
        // Draw glow
        glowPaint.color = glowColor
        canvas.drawPath(waveformPath, glowPaint)
        
        // Draw waveform line with gradient
        waveformPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            cyanColor,
            magentaColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(waveformPath, waveformPaint)
        
        // Draw center line (subtle)
        val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.parseColor("#2A2A2E")
        }
        canvas.drawLine(0f, centerY, w, centerY, centerLinePaint)
    }
}
