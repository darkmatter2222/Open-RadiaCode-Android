package com.radiacode.ble.ui

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.core.content.ContextCompat
import com.radiacode.ble.Prefs
import com.radiacode.ble.R

/**
 * Interactive Tutorial System
 * Shows overlay tutorials with spotlight effects
 */
class TutorialOverlay(
    context: Context,
    private val onComplete: () -> Unit
) : Dialog(context, R.style.Theme_RadiaCode_Dialog_FullScreen) {

    private val density = context.resources.displayMetrics.density

    data class TutorialStep(
        val title: String,
        val description: String,
        val targetViewId: Int?,     // View to spotlight (null for full-screen message)
        val position: Position = Position.BOTTOM,
        val emoji: String? = null
    )

    enum class Position { TOP, BOTTOM, LEFT, RIGHT, CENTER }

    private var steps = mutableListOf<TutorialStep>()
    private var currentStep = 0
    private var targetView: View? = null

    private lateinit var overlayView: TutorialOverlayView
    private lateinit var contentContainer: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var descText: TextView
    private lateinit var stepIndicator: TextView
    private lateinit var nextButton: Button
    private lateinit var skipButton: TextView

    class TutorialOverlayView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val overlayPaint = Paint().apply {
            color = Color.BLACK
            alpha = 200
        }
        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.pro_cyan)
            style = Paint.Style.STROKE
            strokeWidth = density * 3f
        }

        var spotlightRect: RectF? = null
        var spotlightRadius = density * 16f

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            // Dark overlay
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)

            // Clear spotlight area
            spotlightRect?.let { rect ->
                canvas.drawRoundRect(rect, spotlightRadius, spotlightRadius, clearPaint)
                
                // Glow border
                canvas.drawRoundRect(rect, spotlightRadius, spotlightRadius, glowPaint)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootLayout = FrameLayout(context)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Overlay view
        overlayView = TutorialOverlayView(context)
        rootLayout.addView(overlayView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Content container
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (density * 24).toInt(),
                (density * 16).toInt(),
                (density * 24).toInt(),
                (density * 16).toInt()
            )
            background = createRoundedDrawable(
                ContextCompat.getColor(context, R.color.pro_surface),
                density * 16f
            )
            elevation = density * 8f
        }

        titleText = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.pro_cyan))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        }
        contentContainer.addView(titleText)

        descText = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.pro_text_secondary))
            textSize = 15f
            setPadding(0, (density * 12).toInt(), 0, (density * 16).toInt())
        }
        contentContainer.addView(descText)

        // Button row
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        skipButton = TextView(context).apply {
            text = "Skip Tutorial"
            setTextColor(ContextCompat.getColor(context, R.color.pro_text_muted))
            textSize = 14f
            setPadding((density * 8).toInt(), (density * 8).toInt(), (density * 8).toInt(), (density * 8).toInt())
            setOnClickListener { dismiss() }
        }
        buttonRow.addView(skipButton)

        val spacer = View(context)
        buttonRow.addView(spacer, LinearLayout.LayoutParams(0, 0, 1f))

        stepIndicator = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.pro_text_muted))
            textSize = 12f
            setPadding((density * 16).toInt(), 0, (density * 16).toInt(), 0)
        }
        buttonRow.addView(stepIndicator)

        nextButton = Button(context).apply {
            text = "Next"
            setBackgroundColor(ContextCompat.getColor(context, R.color.pro_cyan))
            setTextColor(ContextCompat.getColor(context, R.color.pro_background))
            textSize = 14f
            setPadding((density * 24).toInt(), (density * 12).toInt(), (density * 24).toInt(), (density * 12).toInt())
            setOnClickListener { nextStep() }
        }
        buttonRow.addView(nextButton)

        contentContainer.addView(buttonRow)

        val contentParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(
                (density * 16).toInt(),
                (density * 16).toInt(),
                (density * 16).toInt(),
                (density * 32).toInt()
            )
        }
        rootLayout.addView(contentContainer, contentParams)

        setContentView(rootLayout)

        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }

        setCanceledOnTouchOutside(false)
    }

    fun setSteps(tutorialSteps: List<TutorialStep>) {
        steps.clear()
        steps.addAll(tutorialSteps)
        currentStep = 0
    }

    fun setTargetView(view: View?) {
        targetView = view
    }

    override fun show() {
        super.show()
        showStep(0)
    }

    private fun showStep(index: Int) {
        if (index >= steps.size) {
            onComplete()
            dismiss()
            return
        }

        currentStep = index
        val step = steps[index]

        // Update text
        val title = if (step.emoji != null) "${step.emoji} ${step.title}" else step.title
        titleText.text = title
        descText.text = step.description
        stepIndicator.text = "${index + 1}/${steps.size}"
        nextButton.text = if (index == steps.size - 1) "Got it!" else "Next"

        // Update spotlight
        if (step.targetViewId != null && targetView != null) {
            val target = (targetView?.rootView as? ViewGroup)?.findViewById<View>(step.targetViewId)
            if (target != null && target.isShown) {
                val location = IntArray(2)
                target.getLocationOnScreen(location)
                val padding = density * 8f
                overlayView.spotlightRect = RectF(
                    location[0].toFloat() - padding,
                    location[1].toFloat() - padding,
                    location[0].toFloat() + target.width + padding,
                    location[1].toFloat() + target.height + padding
                )
            } else {
                overlayView.spotlightRect = null
            }
        } else {
            overlayView.spotlightRect = null
        }

        overlayView.invalidate()

        // Position content based on spotlight
        positionContent(step.position)
    }

    private fun positionContent(position: Position) {
        val params = contentContainer.layoutParams as FrameLayout.LayoutParams
        val spotRect = overlayView.spotlightRect

        if (spotRect != null) {
            when (position) {
                Position.TOP -> {
                    params.gravity = Gravity.TOP
                    params.topMargin = (density * 16).toInt()
                }
                Position.BOTTOM -> {
                    params.gravity = Gravity.BOTTOM
                    params.bottomMargin = (density * 32).toInt()
                }
                else -> {
                    params.gravity = Gravity.BOTTOM
                    params.bottomMargin = (density * 32).toInt()
                }
            }
        } else {
            params.gravity = Gravity.CENTER
        }

        contentContainer.layoutParams = params
    }

    private fun nextStep() {
        showStep(currentStep + 1)
    }

    private fun createRoundedDrawable(color: Int, radius: Float): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    companion object {
        /**
         * Create default dashboard tutorial steps
         */
        fun createDashboardTutorial(): List<TutorialStep> {
            return listOf(
                TutorialStep(
                    title = "Welcome to Open RadiaCode!",
                    description = "Let's take a quick tour of the dashboard. This will help you understand what all the numbers mean and how to use the app effectively.",
                    targetViewId = null,
                    emoji = "👋"
                ),
                TutorialStep(
                    title = "Dose Rate",
                    description = "This shows your current radiation dose rate in μSv/h (microsieverts per hour). Normal background is typically 0.05-0.2 μSv/h. The sparkline shows recent history and the trend arrow indicates direction.",
                    targetViewId = R.id.doseCard,
                    position = Position.BOTTOM,
                    emoji = "☢️"
                ),
                TutorialStep(
                    title = "Count Rate",
                    description = "Counts per second (CPS) shows how many gamma rays your detector is seeing. Higher counts = more activity. This is useful for finding sources.",
                    targetViewId = R.id.cpsCard,
                    position = Position.BOTTOM,
                    emoji = "📊"
                ),
                TutorialStep(
                    title = "Safety Status",
                    description = "The safety card gives you instant context: Is this reading normal? The traffic-light indicator (🟢 Safe, 🟡 Elevated, 🔴 High) and comparisons to everyday sources help you understand your readings.",
                    targetViewId = R.id.safetyCard,
                    position = Position.TOP,
                    emoji = "🛡️"
                ),
                TutorialStep(
                    title = "Charts",
                    description = "Tap any chart to expand it. Pinch to zoom, drag to pan. The stats row shows min/avg/max values. Spike markers (▲) highlight unusual readings.",
                    targetViewId = R.id.doseChartPanel,
                    position = Position.TOP,
                    emoji = "📈"
                ),
                TutorialStep(
                    title = "Quick Actions",
                    description = "Tap the ⊕ button in the corner for quick access to recording, screenshots, bookmarks, and sound toggles. These are your shortcuts for fieldwork.",
                    targetViewId = null,
                    position = Position.CENTER,
                    emoji = "⚡"
                ),
                TutorialStep(
                    title = "Need Help?",
                    description = "Tap the ❓ icon anywhere to get contextual help. The Help Center in the menu has FAQs, troubleshooting guides, and explanations of all features.",
                    targetViewId = null,
                    position = Position.CENTER,
                    emoji = "❓"
                ),
                TutorialStep(
                    title = "You're Ready!",
                    description = "Start exploring! The app auto-connects to your RadiaCode device and updates in real-time. Add widgets to your home screen for at-a-glance readings.",
                    targetViewId = null,
                    position = Position.CENTER,
                    emoji = "🚀"
                )
            )
        }

        /**
         * Check if tutorial should be shown
         */
        fun shouldShowTutorial(context: Context): Boolean {
            return Prefs.shouldShowDashboardTutorial(context)
        }

        /**
         * Mark tutorial as completed
         */
        fun markTutorialComplete(context: Context) {
            Prefs.setDashboardTutorialSeen(context, true)
        }
    }
}
