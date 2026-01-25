package com.radiacode.ble.spectrogram

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.*
import com.radiacode.ble.R

/**
 * VegaIsotopeResultDialog - Modal displaying isotope identification results.
 * 
 * Features:
 * - Loading state with animated spinner
 * - Results list with probabilities and activities
 * - Professional dark theme matching the app
 * - Blur background effect (Android 12+)
 */
class VegaIsotopeResultDialog(
    context: Context,
    private val onDismiss: (() -> Unit)? = null
) : Dialog(context, R.style.Theme_RadiaCode_Dialog_FullScreen) {

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
    private val colorTextSecondary = Color.parseColor("#9E9EA8")
    private val colorMuted = Color.parseColor("#6E6E78")
    
    // ═══════════════════════════════════════════════════════════════════════════
    // VIEWS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private lateinit var loadingContainer: LinearLayout
    private lateinit var resultsContainer: LinearLayout
    private lateinit var resultsScrollView: ScrollView
    private lateinit var errorContainer: LinearLayout
    private lateinit var spinnerView: View
    private lateinit var statusText: TextView
    private lateinit var confidenceText: TextView
    private lateinit var processingTimeText: TextView
    private lateinit var closeButton: TextView
    
    private val handler = Handler(Looper.getMainLooper())
    private var spinnerAnimator: ValueAnimator? = null
    private var spinnerRotation = 0f
    
    private val density: Float by lazy { context.resources.displayMetrics.density }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set content first - window decorView needs this before blur can be applied
        setContentView(buildLayout())
        setCanceledOnTouchOutside(false)
        
        // Setup window effects AFTER setContentView (decorView must exist for blur)
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.7f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setBackgroundBlurRadius(25)
            }
        }
        
        // Start in loading state
        showLoading()
    }
    
    override fun onStop() {
        super.onStop()
        spinnerAnimator?.cancel()
        onDismiss?.invoke()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Show loading state with animated spinner
     */
    fun showLoading() {
        loadingContainer.visibility = View.VISIBLE
        resultsContainer.visibility = View.GONE
        errorContainer.visibility = View.GONE
        closeButton.visibility = View.GONE
        statusText.text = "Analyzing spectrum..."
        startSpinnerAnimation()
    }
    
    /**
     * Show the identification results
     */
    fun showResults(response: VegaIsotopeApiClient.IdentificationResponse) {
        stopSpinnerAnimation()
        
        if (!response.success) {
            showError(response.errorMessage ?: "Unknown error")
            return
        }
        
        loadingContainer.visibility = View.GONE
        errorContainer.visibility = View.GONE
        resultsContainer.visibility = View.VISIBLE
        closeButton.visibility = View.VISIBLE
        
        // Update metadata
        confidenceText.text = "Confidence: ${(response.confidence * 100).toInt()}%"
        processingTimeText.text = "Processing: ${String.format("%.1f", response.processingTimeMs)} ms"
        
        // Build results list
        buildResultsList(response.isotopes)
    }
    
    /**
     * Show error state
     */
    fun showError(message: String) {
        stopSpinnerAnimation()
        
        loadingContainer.visibility = View.GONE
        resultsContainer.visibility = View.GONE
        errorContainer.visibility = View.VISIBLE
        closeButton.visibility = View.VISIBLE
        
        errorContainer.removeAllViews()
        errorContainer.addView(TextView(context).apply {
            text = "Analysis Failed"
            setTextColor(colorRed)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        errorContainer.addView(TextView(context).apply {
            text = message
            setTextColor(colorTextSecondary)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
            }
        })
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UI BUILDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildLayout(): View {
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            // Center card
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    (320 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
                background = createCardBackground()
                setPadding(
                    (20 * density).toInt(),
                    (20 * density).toInt(),
                    (20 * density).toInt(),
                    (20 * density).toInt()
                )
                
                // Title row
                addView(buildTitleRow())
                
                // Divider
                addView(createDivider())
                
                // Loading container
                loadingContainer = buildLoadingContainer()
                addView(loadingContainer)
                
                // Results container
                resultsContainer = buildResultsContainer()
                addView(resultsContainer)
                
                // Error container
                errorContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    setPadding(0, (24 * density).toInt(), 0, (24 * density).toInt())
                }
                addView(errorContainer)
                
                // Close button
                closeButton = TextView(context).apply {
                    text = "Close"
                    setTextColor(colorCyan)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (44 * density).toInt()
                    ).apply {
                        topMargin = (16 * density).toInt()
                    }
                    background = createButtonBackground()
                    setOnClickListener { dismiss() }
                }
                addView(closeButton)
            })
        }
    }
    
    private fun buildTitleRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            
            // Icon
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (28 * density).toInt(),
                    (28 * density).toInt()
                ).apply {
                    rightMargin = (12 * density).toInt()
                }
                setImageResource(R.drawable.ic_vega_ai)
                setColorFilter(colorCyan)
            })
            
            // Title
            addView(TextView(context).apply {
                text = "VEGA Isotope Analysis"
                setTextColor(colorCyan)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            })
        }
    }
    
    private fun buildLoadingContainer(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, (32 * density).toInt(), 0, (32 * density).toInt())
            
            // Spinner
            spinnerView = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt()
                )
                background = createSpinnerDrawable()
            }
            addView(spinnerView)
            
            // Status text
            statusText = TextView(context).apply {
                text = "Analyzing spectrum..."
                setTextColor(colorTextSecondary)
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (16 * density).toInt()
                }
            }
            addView(statusText)
        }
    }
    
    private fun buildResultsContainer(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
            
            // Metadata row
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
                
                confidenceText = TextView(context).apply {
                    text = "Confidence: --%"
                    setTextColor(colorGreen)
                    textSize = 12f
                }
                addView(confidenceText)
                
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
                
                processingTimeText = TextView(context).apply {
                    text = "Processing: -- ms"
                    setTextColor(colorMuted)
                    textSize = 12f
                }
                addView(processingTimeText)
            })
            
            // Results scroll view
            resultsScrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (300 * density).toInt()  // Max height
                )
            }
            addView(resultsScrollView)
        }
    }
    
    private fun buildResultsList(isotopes: List<VegaIsotopeApiClient.IsotopeResult>) {
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        if (isotopes.isEmpty()) {
            listContainer.addView(TextView(context).apply {
                text = "No isotopes detected above threshold"
                setTextColor(colorTextSecondary)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, (24 * density).toInt(), 0, (24 * density).toInt())
            })
        } else {
            // Header row
            listContainer.addView(buildHeaderRow())
            listContainer.addView(createDivider())
            
            // Sort by probability descending
            val sortedIsotopes = isotopes.sortedByDescending { it.probability }
            
            for (isotope in sortedIsotopes) {
                listContainer.addView(buildIsotopeRow(isotope))
                listContainer.addView(createDivider())
            }
        }
        
        resultsScrollView.removeAllViews()
        resultsScrollView.addView(listContainer)
    }
    
    private fun buildHeaderRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (36 * density).toInt()
            )
            
            // Isotope name
            addView(TextView(context).apply {
                text = "Isotope"
                setTextColor(colorMuted)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            })
            
            // Probability
            addView(TextView(context).apply {
                text = "Prob"
                setTextColor(colorMuted)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
            })
            
            // Activity
            addView(TextView(context).apply {
                text = "Activity"
                setTextColor(colorMuted)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }
    
    private fun buildIsotopeRow(isotope: VegaIsotopeApiClient.IsotopeResult): LinearLayout {
        val probPercent = (isotope.probability * 100)
        val probColor = when {
            probPercent >= 80 -> colorGreen
            probPercent >= 50 -> colorYellow
            probPercent >= 25 -> colorMagenta
            else -> colorTextSecondary
        }
        
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            )
            
            // Isotope name with energy
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                
                addView(TextView(context).apply {
                    text = isotope.name
                    setTextColor(colorText)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                })
                
                if (isotope.primaryEnergy > 0) {
                    addView(TextView(context).apply {
                        text = "${isotope.primaryEnergy.toInt()} keV"
                        setTextColor(colorMuted)
                        textSize = 10f
                    })
                }
            })
            
            // Probability
            addView(TextView(context).apply {
                text = "${String.format("%.1f", probPercent)}%"
                setTextColor(probColor)
                textSize = 14f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
            })
            
            // Activity
            addView(TextView(context).apply {
                text = formatActivity(isotope.activityBq)
                setTextColor(colorCyan)
                textSize = 14f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }
    
    private fun formatActivity(bq: Float): String {
        return when {
            bq < 0.001f -> "< 1 mBq"
            bq < 1f -> "${String.format("%.1f", bq * 1000)} mBq"
            bq < 1000f -> "${String.format("%.1f", bq)} Bq"
            bq < 1000000f -> "${String.format("%.2f", bq / 1000)} kBq"
            else -> "${String.format("%.2f", bq / 1000000)} MBq"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DRAWING HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * density
            setColor(colorSurface)
            setStroke((1 * density).toInt(), colorBorder)
        }
    }
    
    private fun createButtonBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * density
            setColor(Color.parseColor("#1A2A30"))
            setStroke((1 * density).toInt(), colorCyan)
        }
    }
    
    private fun createDivider(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                topMargin = (4 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
            setBackgroundColor(colorBorder)
        }
    }
    
    private fun createSpinnerDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RING
            setColor(Color.TRANSPARENT)
            setStroke((3 * density).toInt(), colorCyan)
            useLevel = false
            innerRadiusRatio = 3f
            thicknessRatio = 10f
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ANIMATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun startSpinnerAnimation() {
        spinnerAnimator?.cancel()
        spinnerAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                spinnerRotation = animation.animatedValue as Float
                spinnerView.rotation = spinnerRotation
            }
            start()
        }
    }
    
    private fun stopSpinnerAnimation() {
        spinnerAnimator?.cancel()
        spinnerAnimator = null
    }
}
