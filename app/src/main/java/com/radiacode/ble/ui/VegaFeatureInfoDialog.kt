package com.radiacode.ble.ui

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.radiacode.ble.Prefs
import com.radiacode.ble.R

/**
 * VegaFeatureInfoDialog - Educational modal explaining VEGA Statistical Intelligence features.
 * 
 * Features:
 * - Real-time waveform visualization when Vega speaks
 * - Auto-scrolling text synchronized with audio
 * - Elegant dark theme matching the app design
 * - Pre-synthesized Vega voice explanations
 */
class VegaFeatureInfoDialog(
    context: Context,
    private val feature: VegaFeature,
    private val onDismiss: (() -> Unit)? = null
) : Dialog(context, R.style.Theme_RadiaCode_Dialog_FullScreen) {

    private lateinit var waveformView: WaveformVisualizerView
    private lateinit var textScrollView: ScrollView
    private lateinit var featureText: TextView
    private lateinit var closeButton: TextView
    
    private val handler = Handler(Looper.getMainLooper())
    private var visualizer: Visualizer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var scrollAnimator: ValueAnimator? = null
    private var waveformAnimator: ValueAnimator? = null
    private var audioDurationMs: Long = 0
    
    /**
     * Enum defining each VEGA Statistical Intelligence feature with its explanation.
     */
    enum class VegaFeature(
        val title: String,
        val icon: String,
        val audioResName: String,  // Resource name for pre-baked audio
        val explanation: String
    ) {
        ZSCORE(
            title = "Z-Score Anomaly Detection",
            icon = "📊",
            audioResName = "vega_info_zscore",
            explanation = """
Z-Score anomaly detection is your mathematical guardian against unexpected radiation changes.

HOW IT WORKS
I continuously calculate the statistical baseline of your radiation readings. The Z-score measures how many standard deviations the current reading is from this baseline. When the Z-score exceeds your configured sigma threshold, I alert you.

SIGMA THRESHOLDS EXPLAINED
• 1σ (68% confidence) — Very sensitive. Alerts on minor deviations. Use when you need to catch subtle changes, but expect more frequent notifications.
• 2σ (95% confidence) — Balanced. The statistical gold standard. Alerts when readings are genuinely unusual. Recommended for most users.
• 3σ (99.7% confidence) — Conservative. Only extreme anomalies trigger alerts. Use when you want minimal interruptions.
• 4σ (99.99% confidence) — Very conservative. Practically eliminates false positives. Use in stable environments where any alert demands immediate attention.

WHEN TO USE Z-SCORE
Z-Score excels at detecting sudden spikes or unexpected changes in your environment. It's perfect for: background monitoring in your home or workspace, detecting when you've unknowingly entered an area with elevated radiation, and general anomaly detection during daily activities.

COMPARED TO OTHER VEGA FEATURES
Unlike Rate of Change, which focuses on how fast readings change, Z-Score cares about deviation from normal. Unlike CUSUM, which detects gradual drift, Z-Score catches sudden jumps. They complement each other beautifully.

PROS
• Statistically rigorous
• Adjustable sensitivity
• Works well for spike detection
• Self-calibrating baseline

CONS  
• May miss very gradual changes
• Requires sufficient history for accurate baseline
• Can be noisy at 1σ in variable environments
            """.trimIndent()
        ),
        
        ROC(
            title = "Rate of Change Detection",
            icon = "📈",
            audioResName = "vega_info_roc",
            explanation = """
Rate of Change detection monitors how rapidly your radiation levels are shifting.

HOW IT WORKS
I calculate the percentage change in dose rate per second. When this rate exceeds your configured threshold, I alert you immediately. This catches scenarios where you're approaching a radiation source, even if absolute levels haven't yet reached concerning thresholds.

THRESHOLD SETTINGS
• 1-3% per second — Very sensitive. Detects gradual approaches to sources. Good for methodical surveys.
• 5% per second — Balanced. The default. Catches moderate changes without excessive alerts.
• 7-15% per second — Conservative. Only rapid changes trigger alerts. Good for environments with known fluctuations.
• 15%+ per second — Very conservative. Essentially only catches dramatic exposure events.

WHEN TO USE RATE OF CHANGE
ROC is invaluable when: conducting radiation surveys and approaching sources, exploring unknown environments where sources may be present, and wanting early warning before absolute thresholds are crossed.

COMPARED TO OTHER VEGA FEATURES
While Z-Score asks "is this reading unusual?", Rate of Change asks "is the situation changing quickly?". Z-Score might not alert if you're steadily approaching a source that keeps readings within normal variance. ROC catches that approach. CUSUM detects persistent shifts over time, while ROC catches rapid changes in the moment.

PROS
• Early warning system
• Catches approaching sources
• Works regardless of absolute level
• Intuitive threshold setting

CONS
• Can alert on natural fluctuations in noisy environments  
• Doesn't distinguish between rising and falling rates by default
• Brief spikes may trigger alerts
            """.trimIndent()
        ),
        
        CUSUM(
            title = "CUSUM Change Detection",
            icon = "📉",
            audioResName = "vega_info_cusum",
            explanation = """
CUSUM, or Cumulative Sum, detects subtle persistent shifts that other methods miss.

HOW IT WORKS
I accumulate small deviations from your baseline over time. While individual readings might seem normal, CUSUM notices when they consistently trend in one direction. It's like noticing you've been slowly gaining weight even though each daily measurement looks fine.

THE MATHEMATICS
CUSUM maintains a running sum of positive and negative deviations. When either sum exceeds a statistical threshold, I know the underlying level has genuinely shifted, not just fluctuated.

WHEN TO USE CUSUM
CUSUM excels at detecting: slow contamination buildup over hours or days, gradual environmental changes your other alerts would miss, subtle but persistent increases that demand attention, and drift in readings that might indicate calibration issues.

COMPARED TO OTHER VEGA FEATURES
Z-Score catches sudden spikes. Rate of Change catches rapid movement. CUSUM catches slow, persistent drift. Consider a room slowly filling with radon: Z-Score won't alert because no single reading is unusual. ROC won't alert because the change per second is tiny. CUSUM will eventually notice that readings keep being slightly above baseline, hour after hour.

PROS
• Catches what other methods miss
• Statistically robust against noise
• Low false positive rate for drift detection
• Works even with highly variable data

CONS
• Slower to respond than Z-Score or ROC
• Requires longer observation periods
• May miss brief excursions that quickly return to normal
• More complex to understand intuitively
            """.trimIndent()
        ),
        
        FORECAST(
            title = "Holt-Winters Forecasting",
            icon = "🔮",
            audioResName = "vega_info_forecast",
            explanation = """
The Holt-Winters forecasting system predicts where your radiation levels are heading.

HOW IT WORKS
I analyze trends and patterns in your recent readings to project future values. The algorithm accounts for both the current level and its momentum. When my forecast shows you're likely to exceed a threshold within the next 30 to 60 seconds, I can warn you before it happens.

THE VISUALIZATION
When enabled, you'll see an amber forecast line extending from your current reading on the dose rate chart. The shaded confidence band shows the uncertainty range. This gives you visual awareness of predicted trends.

WHEN TO USE FORECASTING
Forecasting is powerful when: you want advance warning of threshold crossings, conducting surveys where anticipating changes helps planning, learning to recognize patterns in your environment, and understanding the trajectory of changing conditions.

COMPARED TO OTHER VEGA FEATURES  
Z-Score, ROC, and CUSUM are reactive, alerting you to what has already happened. Forecasting is predictive, warning you about what's likely to happen next. Used together, they provide complete temporal coverage: past anomalies, current changes, and future projections.

THRESHOLD SETTING
Set your forecast threshold to the level where you'd want advance warning. For most users, this should match or be slightly below your Smart Alert thresholds. This way, you're warned before your regular alerts fire.

PROS
• Proactive rather than reactive
• Visual trend indication on chart
• Configurable warning threshold
• Helps anticipate changing conditions

CONS
• Predictions become less accurate further into the future
• Sudden reversals may not be predicted
• Requires stable reading history for accuracy
• Adds visual complexity to chart
            """.trimIndent()
        ),
        
        PREDICTIVE_CROSSING(
            title = "Predictive Threshold Crossing",
            icon = "⏰",
            audioResName = "vega_info_predictive",
            explanation = """
Predictive Threshold Crossing warns you before you hit your configured Smart Alert thresholds.

HOW IT WORKS
I combine the Holt-Winters forecast with your active Smart Alerts. When my predictions show you're likely to cross an alert threshold within your configured warning time, I notify you. This gives you time to prepare, retreat, or take protective action.

WARNING TIME SETTINGS
• 10-30 seconds — Minimal advance warning. Use when you want alerts close to real-time.
• 60 seconds — The default. One minute warning is usually enough to make decisions.
• 2-3 minutes — Extended warning. Good when evacuation or significant action might be needed.
• 5 minutes — Maximum advance warning. Use for critical thresholds where early notice is essential.

WHEN TO USE PREDICTIVE CROSSING
This feature shines when: you have Smart Alerts configured for important thresholds, working in environments where crossing thresholds has consequences, wanting time to prepare before alerts fire, and conducting surveys with defined safety limits.

COMPARED TO OTHER VEGA FEATURES
Predictive Crossing is the bridge between VEGA's statistical intelligence and your Smart Alerts. While the forecast shows you trends visually, Predictive Crossing actively monitors for threshold interactions. Think of it as your Smart Alerts getting the benefit of statistical foresight.

REQUIREMENTS
This feature requires at least one active Smart Alert. Without configured thresholds to watch, there's nothing for the prediction to cross.

PROS
• Advance warning before threshold events
• Integrates with your existing Smart Alerts  
• Configurable warning time
• Actionable intelligence

CONS
• Requires Smart Alerts to be configured
• Only as accurate as the underlying forecast
• False positives possible if readings are volatile
• May create alert fatigue if set too sensitively
            """.trimIndent()
        ),
        
        VOICE(
            title = "VEGA Voice Announcements",
            icon = "🔊",
            audioResName = "vega_info_voice",
            explanation = """
VEGA Voice brings statistical intelligence to life through spoken announcements.

HOW IT WORKS
When this setting is enabled and VEGA TTS is active in your main sound settings, I will verbally announce statistical alerts. These announcements are contextual, explaining what was detected and why it matters.

WHAT YOU'LL HEAR
When Z-Score triggers: I'll tell you a reading was statistically unusual.
When ROC triggers: I'll note that radiation levels are changing rapidly.
When CUSUM triggers: I'll mention a persistent shift has been detected.
When Forecast predicts crossing: I'll warn you about approaching thresholds.

WHEN TO ENABLE VOICE
Voice announcements are valuable when: you want hands-free awareness while surveying, visual attention is focused elsewhere, you prefer auditory feedback over checking your screen, and conducting fieldwork where spoken alerts help maintain situational awareness.

COMPARED TO STANDARD VEGA TTS
Standard VEGA TTS in your main sound settings controls Vega's voice for general app events like Smart Alert triggers and connection status. This statistical voice setting specifically controls whether VEGA's statistical intelligence features speak.

CUSTOMIZATION TIP  
Enable individual statistical features selectively. You might want voice for Z-Score anomalies but not for every ROC fluctuation. Mix and match based on your operational needs.

PROS
• Hands-free awareness
• Contextual explanations
• Works while screen is off
• Complements visual alerts

CONS
• May be intrusive in quiet environments
• Requires VEGA TTS to be enabled globally
• Audio can't convey numerical precision like display
• Social situations may require muting
            """.trimIndent()
        );
        
        companion object {
            fun fromOrdinal(ordinal: Int): VegaFeature = values()[ordinal]
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        val rootLayout = createLayout()
        setContentView(rootLayout)
        
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.85f)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setBackgroundBlurRadius(30)
            }
        }
        
        setCanceledOnTouchOutside(false)
        
        // Start playback if voice is enabled
        if (Prefs.isVegaTtsEnabled(context)) {
            playFeatureExplanation()
        } else {
            waveformView.setUsingRealAudio(false)
            startSimulatedWaveform()
            startScrollAnimation(30000)  // Default 30 second scroll
        }
    }
    
    private fun createLayout(): View {
        val density = context.resources.displayMetrics.density
        
        // Root container
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
                topMargin = (32 * density).toInt()
                bottomMargin = (24 * density).toInt()
                leftMargin = (20 * density).toInt()
                rightMargin = (20 * density).toInt()
            }
        }
        
        // Title row with icon
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        
        // Feature icon
        val iconText = TextView(context).apply {
            text = feature.icon
            textSize = 28f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (12 * density).toInt()
            }
        }
        titleRow.addView(iconText)
        
        // Title
        val titleText = TextView(context).apply {
            text = feature.title
            textSize = 22f
            setTextColor(Color.parseColor("#00E5FF"))  // Cyan accent
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        titleRow.addView(titleText)
        contentLayout.addView(titleRow)
        
        // Subtitle
        val subtitle = TextView(context).apply {
            text = "VEGA Statistical Intelligence"
            textSize = 12f
            setTextColor(Color.parseColor("#6E6E78"))
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (20 * density).toInt()
            }
        }
        contentLayout.addView(subtitle)
        
        // Waveform visualizer container
        val waveformContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (100 * density).toInt()
            ).apply {
                bottomMargin = (20 * density).toInt()
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
        contentLayout.addView(waveformContainer)
        
        // Scrollable text area
        textScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
            setOnTouchListener { _, _ -> true }  // Disable manual scroll
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((48 * density).toInt())
        }
        
        // Feature explanation text
        featureText = TextView(context).apply {
            text = feature.explanation
            textSize = 15f
            setTextColor(Color.parseColor("#E8E8F0"))
            setLineSpacing(0f, 1.4f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                (8 * density).toInt(),
                (120 * density).toInt(),  // Top padding for scroll start
                (8 * density).toInt(),
                (300 * density).toInt()   // Bottom padding for scroll end
            )
        }
        textScrollView.addView(featureText)
        contentLayout.addView(textScrollView)
        
        // Close button
        closeButton = TextView(context).apply {
            text = "Close"
            textSize = 16f
            setTextColor(Color.parseColor("#00E5FF"))
            gravity = android.view.Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(
                (48 * density).toInt(),
                (14 * density).toInt(),
                (48 * density).toInt(),
                (14 * density).toInt()
            )
            background = createButtonBackground(density)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * density).toInt()
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { dismissDialog() }
        }
        contentLayout.addView(closeButton)
        
        root.addView(contentLayout)
        return root
    }
    
    private fun createWaveformBackground(density: Float): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 12 * density
            setStroke((1 * density).toInt(), Color.parseColor("#2A2A2E"))
            setColor(Color.parseColor("#121216"))
        }
    }
    
    private fun createButtonBackground(density: Float): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24 * density
            setStroke((1.5f * density).toInt(), Color.parseColor("#00E5FF"))
            setColor(Color.parseColor("#0D1A1F"))
        }
    }
    
    private fun playFeatureExplanation() {
        try {
            // Try to load the pre-baked audio file
            val resId = context.resources.getIdentifier(
                feature.audioResName, "raw", context.packageName
            )
            
            if (resId == 0) {
                // Audio file doesn't exist yet - fall back to simulated
                waveformView.setUsingRealAudio(false)
                startSimulatedWaveform()
                startScrollAnimation(45000)  // 45 second fallback
                return
            }
            
            mediaPlayer = MediaPlayer.create(context, resId)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setOnCompletionListener {
                    onAudioComplete()
                }
                setOnPreparedListener {
                    audioDurationMs = duration.toLong()
                    start()
                    setupVisualizer()
                    startScrollAnimation(audioDurationMs)
                }
            }
            
            if (mediaPlayer == null) {
                // Fallback
                waveformView.setUsingRealAudio(false)
                startSimulatedWaveform()
                startScrollAnimation(45000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            waveformView.setUsingRealAudio(false)
            startSimulatedWaveform()
            startScrollAnimation(45000)
        }
    }
    
    private fun setupVisualizer() {
        try {
            val audioSessionId = mediaPlayer?.audioSessionId ?: return
            
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
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
                    true,
                    true
                )
                enabled = true
            }
            
            waveformView.setUsingRealAudio(true)
        } catch (e: Exception) {
            e.printStackTrace()
            waveformView.setUsingRealAudio(false)
            startSimulatedWaveform()
        }
    }
    
    private fun startScrollAnimation(durationMs: Long) {
        handler.postDelayed({
            featureText.post {
                val scrollDistance = featureText.height - textScrollView.height
                if (scrollDistance > 0) {
                    scrollAnimator = ValueAnimator.ofInt(0, scrollDistance).apply {
                        duration = durationMs
                        interpolator = android.view.animation.LinearInterpolator()
                        addUpdateListener { animation ->
                            textScrollView.scrollTo(0, animation.animatedValue as Int)
                        }
                        start()
                    }
                }
            }
        }, 500)  // Small delay before starting scroll
    }
    
    private fun startSimulatedWaveform() {
        var phase = 0f
        waveformAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase += 0.15f
                val simulatedWaveform = ByteArray(256) { i ->
                    val base = 128
                    val wave1 = (20 * kotlin.math.sin(i * 0.1 + phase)).toInt()
                    val wave2 = (15 * kotlin.math.sin(i * 0.05 + phase * 0.7)).toInt()
                    val noise = (-5..5).random()
                    (base + wave1 + wave2 + noise).coerceIn(0, 255).toByte()
                }
                waveformView.updateWaveform(simulatedWaveform)
            }
            start()
        }
    }
    
    private fun onAudioComplete() {
        handler.postDelayed({
            // Keep dialog open, user must close manually
            closeButton.text = "Got it!"
        }, 500)
    }
    
    private fun dismissDialog() {
        cleanup()
        dismiss()
        onDismiss?.invoke()
    }
    
    private fun cleanup() {
        scrollAnimator?.cancel()
        waveformAnimator?.cancel()
        
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) { }
        visualizer = null
        
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) { }
        mediaPlayer = null
        
        handler.removeCallbacksAndMessages(null)
    }
    
    override fun onStop() {
        super.onStop()
        cleanup()
    }
}
