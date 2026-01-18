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
                            fft?.let { waveformView.updateFft(it) }
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    true,  // Waveform
                    true   // FFT - now enabled for frequency data!
                )
                enabled = true
            }
            
            // Mark that we have real audio data
            waveformView.setUsingRealAudio(true)
        } catch (e: Exception) {
            // Visualizer requires RECORD_AUDIO permission
            // Fall back to simulated waveform
            e.printStackTrace()
            waveformView.setUsingRealAudio(false)
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
 * Custom view for rendering real-time audio visualization.
 * Creates a stunning, audio-reactive display with:
 * - Frequency spectrum bars (FFT data)
 * - Smooth waveform overlay
 * - Glow effects and gradients
 * - Particle effects that react to audio energy
 */
class WaveformVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Paints
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }
    
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    // Audio data
    private var waveformData: ByteArray? = null
    private var fftData: ByteArray? = null
    private var usingRealAudio = false
    
    // Smoothed frequency bands (32 bands for visualization)
    private val numBands = 32
    private val bandValues = FloatArray(numBands)
    private val targetBandValues = FloatArray(numBands)
    private val bandPeaks = FloatArray(numBands)
    private val peakDecay = FloatArray(numBands)
    
    // Smoothed waveform
    private val smoothedWaveform = FloatArray(128)
    
    // Energy tracking for effects
    private var currentEnergy = 0f
    private var targetEnergy = 0f
    private var peakEnergy = 0f
    
    // Particles
    private val particles = mutableListOf<Particle>()
    private val maxParticles = 50
    
    // Paths
    private val waveformPath = Path()
    
    // Colors
    private val cyanColor = Color.parseColor("#00E5FF")
    private val magentaColor = Color.parseColor("#E040FB")
    private val greenColor = Color.parseColor("#69F0AE")
    
    // Animation
    private var animationTime = 0L
    private var lastFrameTime = System.currentTimeMillis()
    
    data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        var maxLife: Float,
        var size: Float,
        var color: Int
    )
    
    fun setUsingRealAudio(using: Boolean) {
        usingRealAudio = using
    }
    
    fun updateWaveform(data: ByteArray) {
        waveformData = data
        
        // Calculate overall energy from waveform
        var energy = 0f
        for (byte in data) {
            val value = (byte.toInt() and 0xFF) - 128
            energy += abs(value)
        }
        targetEnergy = (energy / data.size / 128f).coerceIn(0f, 1f)
        
        // Update smoothed waveform
        val step = data.size / smoothedWaveform.size
        for (i in smoothedWaveform.indices) {
            val idx = (i * step).coerceIn(0, data.size - 1)
            val target = ((data[idx].toInt() and 0xFF) - 128) / 128f
            smoothedWaveform[i] = smoothedWaveform[i] * 0.6f + target * 0.4f
        }
        
        postInvalidateOnAnimation()
    }
    
    fun updateFft(data: ByteArray) {
        fftData = data
        
        // Convert FFT data to frequency bands
        // FFT data is organized as [real0, imag0, real1, imag1, ...]
        val fftSize = data.size / 2
        val bandsPerGroup = fftSize / numBands
        
        for (band in 0 until numBands) {
            var magnitude = 0f
            val startBin = band * bandsPerGroup
            val endBin = minOf(startBin + bandsPerGroup, fftSize)
            
            for (bin in startBin until endBin) {
                val realIdx = bin * 2
                val imagIdx = bin * 2 + 1
                if (realIdx < data.size && imagIdx < data.size) {
                    val real = data[realIdx].toInt()
                    val imag = data[imagIdx].toInt()
                    // Calculate magnitude
                    magnitude += kotlin.math.sqrt((real * real + imag * imag).toFloat())
                }
            }
            
            // Normalize and apply logarithmic scaling for better visualization
            magnitude = magnitude / (endBin - startBin).coerceAtLeast(1)
            magnitude = (kotlin.math.log10(magnitude.coerceAtLeast(1f) + 1) / 3f).coerceIn(0f, 1f)
            
            // Boost lower frequencies slightly (they tend to be more prominent in speech)
            val boost = if (band < numBands / 3) 1.3f else if (band < numBands * 2 / 3) 1.1f else 1f
            targetBandValues[band] = (magnitude * boost).coerceIn(0f, 1f)
        }
        
        postInvalidateOnAnimation()
    }
    
    fun generateSimulatedWaveform() {
        animationTime += 33
        
        // Generate fake but responsive-looking data
        val t = animationTime / 1000.0
        
        // Simulate waveform
        val fakeWaveform = ByteArray(256)
        for (i in fakeWaveform.indices) {
            val wave = sin(i * 0.1 + t * 4) * 50 +
                       sin(i * 0.05 + t * 2.5) * 30 +
                       sin(i * 0.02 + t * 1.5) * 40
            fakeWaveform[i] = (128 + wave + (Math.random() - 0.5) * 20).toInt().coerceIn(0, 255).toByte()
        }
        updateWaveform(fakeWaveform)
        
        // Simulate FFT bands
        for (band in 0 until numBands) {
            val baseFreq = 0.5 + band * 0.1
            val pulse = ((sin(t * baseFreq) + 1) / 2).toFloat()
            val variation = ((sin(t * 2 + band * 0.3) + 1) / 4).toFloat()
            targetBandValues[band] = ((pulse * 0.6f + variation + Math.random().toFloat() * 0.2f) * 0.8f).coerceIn(0f, 1f)
        }
        
        postInvalidateOnAnimation()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val currentTime = System.currentTimeMillis()
        val deltaTime = (currentTime - lastFrameTime) / 1000f
        lastFrameTime = currentTime
        
        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2
        
        // Update smoothed values
        updateAnimations(deltaTime)
        
        // Draw background with subtle gradient
        drawBackground(canvas, w, h)
        
        // Draw frequency bars
        drawFrequencyBars(canvas, w, h)
        
        // Draw waveform overlay
        drawWaveform(canvas, w, h, centerY)
        
        // Draw particles
        drawParticles(canvas, deltaTime)
        
        // Draw energy glow at edges
        drawEnergyGlow(canvas, w, h)
    }
    
    private fun updateAnimations(deltaTime: Float) {
        // Smooth energy
        currentEnergy += (targetEnergy - currentEnergy) * 0.15f
        peakEnergy = maxOf(peakEnergy * 0.98f, currentEnergy)
        
        // Smooth band values and update peaks
        for (i in 0 until numBands) {
            bandValues[i] += (targetBandValues[i] - bandValues[i]) * 0.25f
            
            // Update peak with decay
            if (bandValues[i] > bandPeaks[i]) {
                bandPeaks[i] = bandValues[i]
                peakDecay[i] = 0f
            } else {
                peakDecay[i] += deltaTime
                if (peakDecay[i] > 0.5f) {
                    bandPeaks[i] -= deltaTime * 0.8f
                    bandPeaks[i] = maxOf(bandPeaks[i], 0f)
                }
            }
        }
        
        // Spawn particles based on energy
        if (currentEnergy > 0.3f && particles.size < maxParticles && Math.random() < currentEnergy * 0.3) {
            spawnParticle()
        }
        
        // Update existing particles
        particles.removeAll { particle ->
            particle.life -= deltaTime
            particle.x += particle.vx * deltaTime
            particle.y += particle.vy * deltaTime
            particle.vy += 50f * deltaTime // gravity
            particle.life <= 0
        }
    }
    
    private fun spawnParticle() {
        val x = (Math.random() * width).toFloat()
        val y = height * 0.6f + (Math.random() * height * 0.3f).toFloat()
        
        particles.add(Particle(
            x = x,
            y = y,
            vx = ((Math.random() - 0.5) * 100).toFloat(),
            vy = (-50 - Math.random() * 100).toFloat(),
            life = (0.5f + Math.random() * 1.5f).toFloat(),
            maxLife = (0.5f + Math.random() * 1.5f).toFloat(),
            size = (2f + Math.random() * 4f).toFloat(),
            color = if (Math.random() > 0.5) cyanColor else magentaColor
        ))
    }
    
    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        gradientPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#14141A"),
            Color.parseColor("#0A0A0D"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, gradientPaint)
        
        // Draw subtle grid lines
        val gridPaint = Paint().apply {
            color = Color.parseColor("#1A1A20")
            strokeWidth = 1f
        }
        
        // Horizontal lines
        for (i in 1..4) {
            val y = h * i / 5
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
    }
    
    private fun drawFrequencyBars(canvas: Canvas, w: Float, h: Float) {
        val barWidth = w / numBands * 0.7f
        val gap = w / numBands * 0.3f
        val maxBarHeight = h * 0.85f
        
        for (i in 0 until numBands) {
            val x = i * (barWidth + gap) + gap / 2
            val barHeight = bandValues[i] * maxBarHeight
            val peakY = h - bandPeaks[i] * maxBarHeight
            
            if (barHeight > 0) {
                // Calculate color based on position (cyan -> magenta gradient)
                val ratio = i.toFloat() / numBands
                val color = interpolateColor(cyanColor, magentaColor, ratio)
                
                // Draw glow behind bar
                glowPaint.color = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawRoundRect(
                    x - 4, h - barHeight - 4,
                    x + barWidth + 4, h + 4,
                    8f, 8f, glowPaint
                )
                
                // Draw bar with gradient
                barPaint.shader = LinearGradient(
                    0f, h - barHeight, 0f, h,
                    color,
                    Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(
                    x, h - barHeight,
                    x + barWidth, h,
                    4f, 4f, barPaint
                )
                barPaint.shader = null
                
                // Draw peak indicator
                if (bandPeaks[i] > 0.05f) {
                    barPaint.color = Color.WHITE
                    canvas.drawRoundRect(
                        x, peakY - 3,
                        x + barWidth, peakY,
                        2f, 2f, barPaint
                    )
                }
            }
        }
    }
    
    private fun drawWaveform(canvas: Canvas, w: Float, h: Float, centerY: Float) {
        waveformPath.reset()
        
        val amplitude = h * 0.25f * (0.5f + currentEnergy * 0.5f)
        val stepX = w / smoothedWaveform.size
        
        for (i in smoothedWaveform.indices) {
            val x = i * stepX
            val y = centerY - smoothedWaveform[i] * amplitude
            
            if (i == 0) {
                waveformPath.moveTo(x, y)
            } else {
                waveformPath.lineTo(x, y)
            }
        }
        
        // Draw waveform glow
        glowPaint.color = Color.argb(60, 0, 229, 255)
        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeWidth = 8f
        canvas.drawPath(waveformPath, glowPaint)
        glowPaint.style = Paint.Style.FILL
        
        // Draw waveform line
        waveformPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            cyanColor, magentaColor,
            Shader.TileMode.CLAMP
        )
        waveformPaint.alpha = (180 + currentEnergy * 75).toInt().coerceIn(0, 255)
        canvas.drawPath(waveformPath, waveformPaint)
    }
    
    private fun drawParticles(canvas: Canvas, deltaTime: Float) {
        for (particle in particles) {
            val alpha = (particle.life / particle.maxLife * 255).toInt().coerceIn(0, 255)
            particlePaint.color = Color.argb(alpha, 
                Color.red(particle.color),
                Color.green(particle.color),
                Color.blue(particle.color)
            )
            canvas.drawCircle(particle.x, particle.y, particle.size, particlePaint)
        }
    }
    
    private fun drawEnergyGlow(canvas: Canvas, w: Float, h: Float) {
        // Left edge glow
        val glowWidth = 40f * currentEnergy
        gradientPaint.shader = LinearGradient(
            0f, 0f, glowWidth, 0f,
            Color.argb((currentEnergy * 80).toInt(), 0, 229, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, glowWidth, h, gradientPaint)
        
        // Right edge glow
        gradientPaint.shader = LinearGradient(
            w - glowWidth, 0f, w, 0f,
            Color.TRANSPARENT,
            Color.argb((currentEnergy * 80).toInt(), 224, 64, 251),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(w - glowWidth, 0f, w, h, gradientPaint)
    }
    
    private fun interpolateColor(color1: Int, color2: Int, ratio: Float): Int {
        val r = (Color.red(color1) * (1 - ratio) + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * (1 - ratio) + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * (1 - ratio) + Color.blue(color2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }
}
