package com.radiacode.ble.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.core.content.ContextCompat
import com.radiacode.ble.R

/**
 * Contextual Help System - "What's This?" popup
 * Shows explanations for any tapped element
 */
class ContextualHelpPopup(
    context: Context
) : Dialog(context, R.style.DarkDialogTheme) {

    private val density = context.resources.displayMetrics.density

    data class HelpContent(
        val title: String,
        val description: String,
        val details: List<String>? = null,
        val learnMoreLink: String? = null,
        val emoji: String? = null
    )

    private lateinit var container: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var descText: TextView
    private lateinit var detailsContainer: LinearLayout
    private lateinit var learnMoreButton: TextView
    private lateinit var closeButton: TextView

    private var content: HelpContent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (density * 20).toInt(),
                (density * 16).toInt(),
                (density * 20).toInt(),
                (density * 16).toInt()
            )
            background = createRoundedDrawable(
                ContextCompat.getColor(context, R.color.pro_surface),
                density * 16f
            ).apply {
                setStroke(
                    (density * 1).toInt(),
                    ContextCompat.getColor(context, R.color.pro_border)
                )
            }
        }

        // Header row
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleText = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.pro_cyan))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
        headerRow.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(ContextCompat.getColor(context, R.color.pro_text_muted))
            textSize = 20f
            setPadding((density * 8).toInt(), 0, 0, 0)
            setOnClickListener { dismiss() }
        }
        headerRow.addView(closeButton)

        container.addView(headerRow)

        // Description
        descText = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.pro_text_secondary))
            textSize = 14f
            setPadding(0, (density * 12).toInt(), 0, 0)
            setLineSpacing(0f, 1.3f)
        }
        container.addView(descText)

        // Details list
        detailsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (density * 12).toInt(), 0, 0)
        }
        container.addView(detailsContainer)

        // Learn more link
        learnMoreButton = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.pro_cyan))
            textSize = 14f
            setPadding(0, (density * 12).toInt(), 0, 0)
            visibility = View.GONE
            setOnClickListener {
                // Could open help center to specific topic
                dismiss()
            }
        }
        container.addView(learnMoreButton)

        setContentView(container)

        window?.apply {
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.9f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
        }
    }

    fun setContent(helpContent: HelpContent) {
        content = helpContent
    }

    override fun show() {
        super.show()
        
        content?.let { c ->
            // Title with optional emoji
            titleText.text = if (c.emoji != null) "${c.emoji} ${c.title}" else c.title
            
            // Description
            descText.text = c.description

            // Details list
            detailsContainer.removeAllViews()
            c.details?.forEach { detail ->
                val bulletText = TextView(context).apply {
                    text = "• $detail"
                    setTextColor(ContextCompat.getColor(context, R.color.pro_text_secondary))
                    textSize = 13f
                    setPadding(0, (density * 4).toInt(), 0, 0)
                }
                detailsContainer.addView(bulletText)
            }

            // Learn more
            if (c.learnMoreLink != null) {
                learnMoreButton.text = "Learn more →"
                learnMoreButton.visibility = View.VISIBLE
            } else {
                learnMoreButton.visibility = View.GONE
            }
        }
    }

    private fun createRoundedDrawable(color: Int, radius: Float): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    companion object {
        /**
         * Library of help content for different UI elements
         */
        val HELP_LIBRARY = mapOf(
            "dose_rate" to HelpContent(
                title = "Dose Rate",
                description = "The radiation dose rate measures how much ionizing radiation energy you would absorb per unit of time at the current location.",
                details = listOf(
                    "Measured in μSv/h (microsieverts per hour)",
                    "Normal background: 0.05-0.2 μSv/h",
                    "Higher values indicate more radiation",
                    "Fluctuations are normal due to statistical nature of radioactive decay"
                ),
                emoji = "☢️",
                learnMoreLink = "dose_rate"
            ),
            "count_rate" to HelpContent(
                title = "Count Rate",
                description = "Count rate shows how many gamma ray interactions your detector registers each second. This is the raw detection data before conversion to dose.",
                details = listOf(
                    "Measured in CPS (counts per second) or CPM (counts per minute)",
                    "Higher counts = more radioactivity detected",
                    "Useful for finding sources (goes up as you approach)",
                    "Depends on detector sensitivity and distance from source"
                ),
                emoji = "📊",
                learnMoreLink = "count_rate"
            ),
            "z_score" to HelpContent(
                title = "Z-Score (Standard Deviations)",
                description = "Z-score measures how far a value is from the average, expressed in standard deviations (σ). It helps identify unusual readings statistically.",
                details = listOf(
                    "Z = 0: Exactly average",
                    "Z = ±1σ: Within normal variation (68% of readings)",
                    "Z = ±2σ: Somewhat unusual (95% of readings)",
                    "Z > ±3σ: Statistically significant anomaly",
                    "Green = near average, Yellow = elevated, Red = anomaly"
                ),
                emoji = "📐",
                learnMoreLink = "statistics"
            ),
            "trend" to HelpContent(
                title = "Trend Indicator",
                description = "The trend arrow shows whether readings are increasing, decreasing, or stable compared to recent history.",
                details = listOf(
                    "▲▲ Strong increase (>2σ above mean)",
                    "▲ Increasing (>1σ above mean)",
                    "— Stable (within 1σ of mean)",
                    "▼ Decreasing (>1σ below mean)",
                    "▼▼ Strong decrease (>2σ below mean)"
                ),
                emoji = "📈",
                learnMoreLink = "trends"
            ),
            "sparkline" to HelpContent(
                title = "Sparkline Chart",
                description = "The mini chart shows recent reading history at a glance. Colors indicate how each reading compares to the statistical average.",
                details = listOf(
                    "Green segments: Near average",
                    "Yellow segments: Elevated (1-2σ)",
                    "Red segments: Anomalous (>2σ)",
                    "Dotted line: Running mean",
                    "Tap to expand for more detail"
                ),
                emoji = "〰️"
            ),

            "safety_status" to HelpContent(
                title = "Safety Status",
                description = "The safety indicator provides quick context about whether current radiation levels are normal or require attention.",
                details = listOf(
                    "🟢 SAFE: Normal background, no concerns",
                    "🟡 ELEVATED: Above typical, but safe for extended periods",
                    "🟠 HIGH: Limit exposure time, find the source",
                    "🔴 VERY HIGH: Minimize time in area",
                    "Comparisons help put numbers in perspective"
                ),
                emoji = "🛡️",
                learnMoreLink = "safety"
            ),
            "background" to HelpContent(
                title = "Background Radiation",
                description = "Background radiation is the natural radiation present everywhere from cosmic rays, radon, and radioactive materials in the earth.",
                details = listOf(
                    "Average: ~0.1 μSv/h (varies by location)",
                    "Higher at altitude (more cosmic rays)",
                    "Higher near granite, concrete",
                    "Indoors can be higher (radon accumulation)",
                    "Normal fluctuations of ±50% are typical"
                ),
                emoji = "🌍"
            ),
            "map" to HelpContent(
                title = "Radiation Map",
                description = "The map shows radiation readings at different locations, helping you visualize spatial patterns and find hotspots.",
                details = listOf(
                    "Hexagons show average readings in each area",
                    "Color gradient: Green (low) → Yellow → Red (high)",
                    "Your position is shown with a blue dot",
                    "Tap a hexagon to see detailed readings",
                    "Data persists between sessions"
                ),
                emoji = "🗺️"
            ),
            "intelligence" to HelpContent(
                title = "Intelligence Report",
                description = "The AI-powered intelligence engine analyzes your readings to detect anomalies, predict trends, and provide insights.",
                details = listOf(
                    "Stability: How consistent are readings",
                    "Trend: Direction of change over time",
                    "Prediction: Expected next reading",
                    "Anomalies: Unusual readings detected",
                    "Tap 'Show Details' for full statistics"
                ),
                emoji = "🤖"
            ),
            "session" to HelpContent(
                title = "Session",
                description = "A session represents continuous data collection from when you opened the app or started recording.",
                details = listOf(
                    "Duration: How long you've been collecting",
                    "Samples: Number of readings taken",
                    "Sessions can be named and saved",
                    "Compare sessions to see differences",
                    "Export session data as CSV/JSON"
                ),
                emoji = "⏱️"
            ),
            "widget" to HelpContent(
                title = "Home Screen Widget",
                description = "Widgets let you see radiation readings without opening the app. Add them from your phone's widget picker.",
                details = listOf(
                    "Simple: Just dose and count rate",
                    "Chart: Includes sparkline history",
                    "Customize colors, transparency, fields",
                    "Tap widget to open app",
                    "Updates in real-time while service runs"
                ),
                emoji = "📱"
            )
        )

        /**
         * Get help content for a given topic
         */
        fun getHelp(topic: String): HelpContent? = HELP_LIBRARY[topic]
    }
}
