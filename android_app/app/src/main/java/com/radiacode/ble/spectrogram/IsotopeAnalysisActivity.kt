package com.radiacode.ble.spectrogram

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.radiacode.ble.R
import kotlin.math.roundToInt

/**
 * Full-screen Isotope Analysis Results Activity
 * 
 * Comprehensive visualization of Vega AI isotope identification results including:
 * - Detected isotopes with confidence bars
 * - Decay chain visualization and parent inference
 * - Adjustable detection threshold slider
 * - Multi-threshold analysis view
 * - Interpretation guidance and educational content
 * - Isotope categories and gamma line information
 */
class IsotopeAnalysisActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        
        // Result data is passed via companion object to avoid serialization issues
        private var pendingResult: VegaIsotopeApiClient.IdentificationResponse? = null
        private var pendingAllIsotopes: List<VegaIsotopeApiClient.IsotopeResult>? = null
        
        fun start(
            context: Context,
            deviceId: String,
            result: VegaIsotopeApiClient.IdentificationResponse,
            allIsotopes: List<VegaIsotopeApiClient.IsotopeResult>? = null
        ) {
            pendingResult = result
            pendingAllIsotopes = allIsotopes
            context.startActivity(Intent(context, IsotopeAnalysisActivity::class.java).apply {
                putExtra(EXTRA_DEVICE_ID, deviceId)
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COLORS (Pro Dark Theme)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val colorBackground = Color.parseColor("#0D0D0F")
    private val colorSurface = Color.parseColor("#1A1A1E")
    private val colorSurfaceLight = Color.parseColor("#242428")
    private val colorBorder = Color.parseColor("#2A2A2E")
    private val colorCyan = Color.parseColor("#00E5FF")
    private val colorMagenta = Color.parseColor("#E040FB")
    private val colorGreen = Color.parseColor("#69F0AE")
    private val colorRed = Color.parseColor("#FF5252")
    private val colorYellow = Color.parseColor("#FFD600")
    private val colorOrange = Color.parseColor("#FF9100")
    private val colorBlue = Color.parseColor("#448AFF")
    private val colorText = Color.parseColor("#FFFFFF")
    private val colorTextSecondary = Color.parseColor("#9E9EA8")
    private val colorMuted = Color.parseColor("#6E6E78")

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private lateinit var deviceId: String
    private lateinit var result: VegaIsotopeApiClient.IdentificationResponse
    private var allIsotopes: List<VegaIsotopeApiClient.IsotopeResult> = emptyList()
    
    private var currentThreshold = 0.5f
    private val handler = Handler(Looper.getMainLooper())
    
    private val density: Float by lazy { resources.displayMetrics.density }
    
    // Views
    private lateinit var rootScroll: ScrollView
    private lateinit var thresholdSlider: SeekBar
    private lateinit var thresholdLabel: TextView
    private lateinit var isotopesContainer: LinearLayout
    private lateinit var decayChainContainer: LinearLayout
    private lateinit var multiThresholdContainer: LinearLayout
    private lateinit var summaryText: TextView
    private lateinit var confidenceBar: View
    private lateinit var detectedCountText: TextView

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get data
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
        result = pendingResult ?: run {
            finish()
            return
        }
        allIsotopes = pendingAllIsotopes ?: result.isotopes
        
        // Clear pending data
        pendingResult = null
        pendingAllIsotopes = null
        
        // Setup window
        window.statusBarColor = colorBackground
        window.navigationBarColor = colorBackground
        
        // Build UI
        setContentView(buildLayout())
        
        // Initial render
        updateForThreshold(currentThreshold)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN LAYOUT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildLayout(): View {
        return FrameLayout(this).apply {
            setBackgroundColor(colorBackground)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            // Main scrollable content
            rootScroll = ScrollView(this@IsotopeAnalysisActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isVerticalScrollBarEnabled = false
                
                addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(
                        (16 * density).toInt(),
                        (16 * density).toInt(),
                        (16 * density).toInt(),
                        (100 * density).toInt() // Extra padding for bottom
                    )
                    
                    // Header with close button
                    addView(buildHeader())
                    
                    // Overall confidence card
                    addView(buildConfidenceCard())
                    
                    // Threshold control card
                    addView(buildThresholdCard())
                    
                    // Detected isotopes section
                    addView(buildSectionHeader("Detected Isotopes", R.drawable.ic_vega_ai))
                    isotopesContainer = LinearLayout(this@IsotopeAnalysisActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    addView(isotopesContainer)
                    
                    // Decay chain analysis section
                    addView(buildSectionHeader("Decay Chain Analysis", R.drawable.ic_vega_ai))
                    decayChainContainer = LinearLayout(this@IsotopeAnalysisActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    addView(decayChainContainer)
                    
                    // Multi-threshold analysis section  
                    addView(buildSectionHeader("Confidence Analysis", R.drawable.ic_vega_ai))
                    multiThresholdContainer = LinearLayout(this@IsotopeAnalysisActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    addView(multiThresholdContainer)
                    
                    // Interpretation guide
                    addView(buildInterpretationGuide())
                    
                    // Processing info footer
                    addView(buildFooter())
                })
            }
            addView(rootScroll)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HEADER
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
            
            // Back button
            addView(ImageView(this@IsotopeAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(),
                    (40 * density).toInt()
                )
                setImageResource(R.drawable.ic_arrow_back)
                setColorFilter(colorText)
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                setOnClickListener { finish() }
            })
            
            // Title
            addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = (8 * density).toInt()
                }
                
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = "VEGA Isotope Analysis"
                    setTextColor(colorCyan)
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                })
                
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = "AI-Powered Radioisotope Identification"
                    setTextColor(colorTextSecondary)
                    textSize = 12f
                })
            })
            
            // Vega icon
            addView(ImageView(this@IsotopeAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    (36 * density).toInt()
                )
                setImageResource(R.drawable.ic_vega_ai)
                setColorFilter(colorCyan)
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIDENCE CARD
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildConfidenceCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
            background = createCardBackground()
            setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt()
            )
            
            // Summary row
            addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                
                // Left: detected count
                addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    
                    detectedCountText = TextView(this@IsotopeAnalysisActivity).apply {
                        text = "${result.numDetected}"
                        setTextColor(colorCyan)
                        textSize = 36f
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    }
                    addView(detectedCountText)
                    
                    addView(TextView(this@IsotopeAnalysisActivity).apply {
                        text = "Isotopes Detected"
                        setTextColor(colorTextSecondary)
                        textSize = 12f
                    })
                })
                
                // Right: confidence
                addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    
                    addView(TextView(this@IsotopeAnalysisActivity).apply {
                        text = "${(result.confidence * 100).roundToInt()}%"
                        setTextColor(getConfidenceColor(result.confidence))
                        textSize = 36f
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                        gravity = Gravity.END
                    })
                    
                    addView(TextView(this@IsotopeAnalysisActivity).apply {
                        text = "Overall Confidence"
                        setTextColor(colorTextSecondary)
                        textSize = 12f
                        gravity = Gravity.END
                    })
                })
            })
            
            // Confidence bar
            addView(FrameLayout(this@IsotopeAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (8 * density).toInt()
                ).apply {
                    topMargin = (16 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4 * density
                    setColor(colorBorder)
                }
                
                confidenceBar = View(this@IsotopeAnalysisActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 4 * density
                        setColor(getConfidenceColor(result.confidence))
                    }
                }
                addView(confidenceBar)
                
                // Animate the bar
                post {
                    val targetWidth = (width * result.confidence).toInt()
                    ValueAnimator.ofInt(0, targetWidth).apply {
                        duration = 800
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { 
                            confidenceBar.layoutParams.width = it.animatedValue as Int
                            confidenceBar.requestLayout()
                        }
                        start()
                    }
                }
            })
            
            // Summary text
            summaryText = TextView(this@IsotopeAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (12 * density).toInt()
                }
                setTextColor(colorTextSecondary)
                textSize = 13f
                text = generateSummaryText()
            }
            addView(summaryText)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // THRESHOLD CONTROL
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildThresholdCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
            background = createCardBackground()
            setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt()
            )
            
            // Header row
            addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = "Detection Threshold"
                    setTextColor(colorText)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                
                thresholdLabel = TextView(this@IsotopeAnalysisActivity).apply {
                    text = "50%"
                    setTextColor(colorCyan)
                    textSize = 16f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }
                addView(thresholdLabel)
            })
            
            // Description
            addView(TextView(this@IsotopeAnalysisActivity).apply {
                text = "Lower threshold = more sensitive (may include false positives)\nHigher threshold = more confident (may miss weak signals)"
                setTextColor(colorMuted)
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                    bottomMargin = (12 * density).toInt()
                }
            })
            
            // Slider
            thresholdSlider = SeekBar(this@IsotopeAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                max = 90  // 10-90% range (step of 1)
                progress = 40  // Default 50% (40 + 10 = 50)
                
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val threshold = (progress + 10) / 100f
                        currentThreshold = threshold
                        thresholdLabel.text = "${progress + 10}%"
                        updateForThreshold(threshold)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            addView(thresholdSlider)
            
            // Preset buttons
            addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (12 * density).toInt()
                }
                
                val presets = listOf(
                    Triple("Sensitive", 0.3f, "Safety screening"),
                    Triple("Balanced", 0.5f, "General purpose"),
                    Triple("Confident", 0.7f, "Verification")
                )
                
                presets.forEach { (name, threshold, desc) ->
                    addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = (4 * density).toInt()
                            marginEnd = (4 * density).toInt()
                        }
                        background = createPresetButtonBackground(currentThreshold == threshold)
                        setPadding(
                            (8 * density).toInt(),
                            (8 * density).toInt(),
                            (8 * density).toInt(),
                            (8 * density).toInt()
                        )
                        setOnClickListener {
                            currentThreshold = threshold
                            thresholdSlider.progress = ((threshold * 100) - 10).toInt()
                            thresholdLabel.text = "${(threshold * 100).toInt()}%"
                            updateForThreshold(threshold)
                        }
                        
                        addView(TextView(this@IsotopeAnalysisActivity).apply {
                            text = name
                            setTextColor(if (currentThreshold == threshold) colorCyan else colorText)
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER
                        })
                        
                        addView(TextView(this@IsotopeAnalysisActivity).apply {
                            text = desc
                            setTextColor(colorMuted)
                            textSize = 9f
                            gravity = Gravity.CENTER
                        })
                    })
                }
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECTION HEADER
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildSectionHeader(title: String, iconRes: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (20 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
            
            addView(View(this@IsotopeAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (2 * density).toInt())
                setBackgroundColor(colorBorder)
            })
            
            addView(TextView(this@IsotopeAnalysisActivity).apply {
                text = title.uppercase()
                setTextColor(colorTextSecondary)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.1f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (12 * density).toInt()
                    marginEnd = (12 * density).toInt()
                }
            })
            
            addView(View(this@IsotopeAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, (2 * density).toInt(), 1f)
                setBackgroundColor(colorBorder)
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ISOTOPE CARDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildIsotopeCard(isotope: VegaIsotopeApiClient.IsotopeResult, rank: Int): View {
        val isotopeInfo = IsotopeDatabase.getInfo(isotope.name)
        
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            background = createCardBackground()
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            
            // Top row: Name, category, probability
            addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                
                // Rank badge
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = "#$rank"
                    setTextColor(colorMuted)
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = (8 * density).toInt()
                    }
                })
                
                // Isotope name with formatted display
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = formatIsotopeName(isotope.name)
                    setTextColor(colorText)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = (8 * density).toInt()
                    }
                })
                
                // Category badge
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = isotopeInfo.category.displayName
                    setTextColor(colorBackground)
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 4 * density
                        setColor(isotopeInfo.category.color)
                    }
                    setPadding(
                        (6 * density).toInt(),
                        (2 * density).toInt(),
                        (6 * density).toInt(),
                        (2 * density).toInt()
                    )
                })
                
                // Spacer
                addView(View(this@IsotopeAnalysisActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
                
                // Probability
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = "${(isotope.probability * 100).roundToInt()}%"
                    setTextColor(getConfidenceColor(isotope.probability))
                    textSize = 20f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                })
            })
            
            // Full name
            addView(TextView(this@IsotopeAnalysisActivity).apply {
                text = isotopeInfo.fullName
                setTextColor(colorTextSecondary)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                }
            })
            
            // Probability bar
            addView(FrameLayout(this@IsotopeAnalysisActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (6 * density).toInt()
                ).apply {
                    topMargin = (8 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 3 * density
                    setColor(colorBorder)
                }
                
                addView(View(this@IsotopeAnalysisActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 3 * density
                        setColor(getConfidenceColor(isotope.probability))
                    }
                    
                    post {
                        val parent = parent as FrameLayout
                        val targetWidth = (parent.width * isotope.probability).toInt()
                        ValueAnimator.ofInt(0, targetWidth).apply {
                            duration = 600
                            startDelay = (rank * 50).toLong()
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { 
                                layoutParams.width = it.animatedValue as Int
                                requestLayout()
                            }
                            start()
                        }
                    }
                })
            })
            
            // Details row: Energy, Activity, Half-life
            addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
                
                // Primary gamma energy
                addView(buildDetailItem(
                    "Primary Gamma",
                    if (isotope.primaryEnergy > 0) "${isotope.primaryEnergy.toInt()} keV" 
                    else "${isotopeInfo.primaryGammaKeV.toInt()} keV",
                    colorCyan
                ))
                
                // Activity estimate
                addView(buildDetailItem(
                    "Est. Activity",
                    if (isotope.activityBq > 0) "${String.format("%.1f", isotope.activityBq)} Bq" else "N/A",
                    colorMagenta
                ))
                
                // Half-life
                addView(buildDetailItem(
                    "Half-life",
                    isotopeInfo.halfLife,
                    colorYellow
                ))
            })
            
            // Decay chain indicator (if applicable)
            if (isotopeInfo.decayChain != null) {
                addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * density).toInt()
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6 * density
                        setColor(Color.argb(30, 0, 229, 255))
                    }
                    setPadding(
                        (8 * density).toInt(),
                        (6 * density).toInt(),
                        (8 * density).toInt(),
                        (6 * density).toInt()
                    )
                    
                    addView(TextView(this@IsotopeAnalysisActivity).apply {
                        text = "Part of ${isotopeInfo.decayChain} decay chain"
                        setTextColor(colorCyan)
                        textSize = 11f
                    })
                })
            }
        }
    }
    
    private fun buildDetailItem(label: String, value: String, valueColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            
            addView(TextView(this@IsotopeAnalysisActivity).apply {
                text = label
                setTextColor(colorMuted)
                textSize = 10f
            })
            
            addView(TextView(this@IsotopeAnalysisActivity).apply {
                text = value
                setTextColor(valueColor)
                textSize = 12f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DECAY CHAIN VISUALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildDecayChainVisualization(detectedIsotopes: Set<String>): View {
        val chainAnalysis = DecayChainAnalyzer.analyze(detectedIsotopes)
        
        if (chainAnalysis.isEmpty()) {
            return TextView(this).apply {
                text = "No decay chain signatures detected in current results."
                setTextColor(colorMuted)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                background = createCardBackground()
                setPadding(
                    (16 * density).toInt(),
                    (16 * density).toInt(),
                    (16 * density).toInt(),
                    (16 * density).toInt()
                )
            }
        }
        
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            
            chainAnalysis.forEach { chain ->
                addView(buildChainCard(chain, detectedIsotopes))
            }
        }
    }
    
    private fun buildChainCard(chain: DecayChainAnalyzer.ChainResult, detectedIsotopes: Set<String>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            background = createCardBackground()
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            
            // Chain header
            addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = chain.chainName
                    setTextColor(colorCyan)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = "${(chain.confidence * 100).roundToInt()}% match"
                    setTextColor(getConfidenceColor(chain.confidence))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                })
            })
            
            // Inferred parent
            if (chain.inferredParent != null) {
                addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (8 * density).toInt()
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6 * density
                        setColor(Color.argb(40, 105, 240, 174))
                    }
                    setPadding(
                        (10 * density).toInt(),
                        (8 * density).toInt(),
                        (10 * density).toInt(),
                        (8 * density).toInt()
                    )
                    
                    addView(TextView(this@IsotopeAnalysisActivity).apply {
                        text = "Inferred parent: "
                        setTextColor(colorTextSecondary)
                        textSize = 12f
                    })
                    
                    addView(TextView(this@IsotopeAnalysisActivity).apply {
                        text = chain.inferredParent
                        setTextColor(colorGreen)
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                })
            }
            
            // Chain visualization
            addView(DecayChainDiagramView(this@IsotopeAnalysisActivity, chain.chainMembers, detectedIsotopes).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (120 * density).toInt()
                ).apply {
                    topMargin = (12 * density).toInt()
                }
            })
            
            // Explanation
            addView(TextView(this@IsotopeAnalysisActivity).apply {
                text = chain.explanation
                setTextColor(colorTextSecondary)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * density).toInt()
                }
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-THRESHOLD ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildMultiThresholdView(): View {
        val thresholds = listOf(0.3f, 0.5f, 0.7f, 0.9f)
        
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = createCardBackground()
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            
            addView(TextView(this@IsotopeAnalysisActivity).apply {
                text = "How confidence affects detection:"
                setTextColor(colorTextSecondary)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (12 * density).toInt()
                }
            })
            
            thresholds.forEach { threshold ->
                val isotopesAtThreshold = allIsotopes.filter { it.probability >= threshold }
                val isCurrentThreshold = (threshold - currentThreshold).let { kotlin.math.abs(it) < 0.05f }
                
                addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                    if (isCurrentThreshold) {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 6 * density
                            setColor(Color.argb(30, 0, 229, 255))
                        }
                        setPadding(
                            (8 * density).toInt(),
                            (6 * density).toInt(),
                            (8 * density).toInt(),
                            (6 * density).toInt()
                        )
                    }
                    
                    // Threshold label
                    addView(TextView(this@IsotopeAnalysisActivity).apply {
                        text = "${(threshold * 100).toInt()}%"
                        setTextColor(if (isCurrentThreshold) colorCyan else colorTextSecondary)
                        textSize = 14f
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(
                            (48 * density).toInt(),
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    })
                    
                    // Count
                    addView(TextView(this@IsotopeAnalysisActivity).apply {
                        text = "${isotopesAtThreshold.size} detected"
                        setTextColor(if (isCurrentThreshold) colorText else colorMuted)
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(
                            (80 * density).toInt(),
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    })
                    
                    // Isotope names (scrollable)
                    addView(HorizontalScrollView(this@IsotopeAnalysisActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        isHorizontalScrollBarEnabled = false
                        
                        addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            
                            isotopesAtThreshold.take(8).forEach { iso ->
                                addView(TextView(this@IsotopeAnalysisActivity).apply {
                                    text = iso.name
                                    setTextColor(getConfidenceColor(iso.probability))
                                    textSize = 11f
                                    typeface = Typeface.DEFAULT_BOLD
                                    background = GradientDrawable().apply {
                                        shape = GradientDrawable.RECTANGLE
                                        cornerRadius = 4 * density
                                        setStroke((1 * density).toInt(), getConfidenceColor(iso.probability))
                                    }
                                    setPadding(
                                        (6 * density).toInt(),
                                        (2 * density).toInt(),
                                        (6 * density).toInt(),
                                        (2 * density).toInt()
                                    )
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        marginEnd = (4 * density).toInt()
                                    }
                                })
                            }
                            
                            if (isotopesAtThreshold.size > 8) {
                                addView(TextView(this@IsotopeAnalysisActivity).apply {
                                    text = "+${isotopesAtThreshold.size - 8} more"
                                    setTextColor(colorMuted)
                                    textSize = 10f
                                })
                            }
                        })
                    })
                })
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERPRETATION GUIDE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildInterpretationGuide(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (20 * density).toInt()
            }
            
            addView(buildSectionHeader("Understanding Results", R.drawable.ic_vega_ai))
            
            addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = createCardBackground()
                setPadding(
                    (16 * density).toInt(),
                    (12 * density).toInt(),
                    (16 * density).toInt(),
                    (12 * density).toInt()
                )
                
                // Confidence levels
                val levels = listOf(
                    Triple(">95%", "Very High Confidence", "Definitely present"),
                    Triple("80-95%", "High Confidence", "Very likely present"),
                    Triple("50-80%", "Moderate Confidence", "Probably present, verify"),
                    Triple("30-50%", "Low Confidence", "Possibly present, investigate"),
                    Triple("<30%", "Very Low", "Likely absent")
                )
                
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = "Probability Interpretation"
                    setTextColor(colorText)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                })
                
                levels.forEach { (range, level, meaning) ->
                    addView(LinearLayout(this@IsotopeAnalysisActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (4 * density).toInt()
                        }
                        
                        addView(TextView(this@IsotopeAnalysisActivity).apply {
                            text = range
                            setTextColor(colorCyan)
                            textSize = 11f
                            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                            layoutParams = LinearLayout.LayoutParams(
                                (56 * density).toInt(),
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        })
                        
                        addView(TextView(this@IsotopeAnalysisActivity).apply {
                            text = "$level - $meaning"
                            setTextColor(colorTextSecondary)
                            textSize = 11f
                        })
                    })
                }
                
                // Activity disclaimer
                addView(View(this@IsotopeAnalysisActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt()
                    ).apply {
                        topMargin = (12 * density).toInt()
                        bottomMargin = (12 * density).toInt()
                    }
                    setBackgroundColor(colorBorder)
                })
                
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = "Activity Estimates"
                    setTextColor(colorText)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                })
                
                addView(TextView(this@IsotopeAnalysisActivity).apply {
                    text = "Activity values are approximate estimates. Due to unknown source distance, " +
                           "shielding, and detector efficiency variations, use these for relative " +
                           "comparisons only. Do NOT use for regulatory compliance or safety calculations."
                    setTextColor(colorYellow)
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (4 * density).toInt()
                    }
                })
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FOOTER
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun buildFooter(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (24 * density).toInt()
            }
            
            addView(TextView(this@IsotopeAnalysisActivity).apply {
                text = "Processed in ${String.format("%.1f", result.processingTimeMs)} ms"
                setTextColor(colorMuted)
                textSize = 11f
                gravity = Gravity.CENTER
            })
            
            addView(TextView(this@IsotopeAnalysisActivity).apply {
                text = "Vega 2D-CNN Model v2.0 - 82 Isotope Classes"
                setTextColor(colorMuted)
                textSize = 10f
                gravity = Gravity.CENTER
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE LOGIC
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateForThreshold(threshold: Float) {
        val detectedIsotopes = allIsotopes.filter { it.probability >= threshold }
        val detectedNames = detectedIsotopes.map { it.name }.toSet()
        
        // Update detected count
        detectedCountText.text = "${detectedIsotopes.size}"
        
        // Rebuild isotopes container
        isotopesContainer.removeAllViews()
        if (detectedIsotopes.isEmpty()) {
            isotopesContainer.addView(TextView(this).apply {
                text = "No isotopes detected at ${(threshold * 100).toInt()}% threshold.\n\n" +
                       "This likely indicates background radiation only, or the detection " +
                       "threshold is set too high for the current signal strength."
                setTextColor(colorMuted)
                textSize = 13f
                gravity = Gravity.CENTER
                background = createCardBackground()
                setPadding(
                    (16 * density).toInt(),
                    (24 * density).toInt(),
                    (16 * density).toInt(),
                    (24 * density).toInt()
                )
            })
        } else {
            detectedIsotopes.sortedByDescending { it.probability }.forEachIndexed { index, isotope ->
                isotopesContainer.addView(buildIsotopeCard(isotope, index + 1))
            }
        }
        
        // Rebuild decay chain analysis
        decayChainContainer.removeAllViews()
        decayChainContainer.addView(buildDecayChainVisualization(detectedNames))
        
        // Rebuild multi-threshold view
        multiThresholdContainer.removeAllViews()
        multiThresholdContainer.addView(buildMultiThresholdView())
        
        // Update summary
        summaryText.text = generateSummaryText()
    }
    
    private fun generateSummaryText(): String {
        val detected = allIsotopes.filter { it.probability >= currentThreshold }
        
        if (detected.isEmpty()) {
            return "No significant isotope signatures detected above the current threshold. " +
                   "The spectrum shows background radiation patterns only."
        }
        
        val topIsotope = detected.maxByOrNull { it.probability }
        val categories = detected.mapNotNull { IsotopeDatabase.getInfo(it.name).category }
            .distinct()
            .map { it.displayName }
        
        val sb = StringBuilder()
        sb.append("Detected ${detected.size} isotope${if (detected.size > 1) "s" else ""}")
        
        if (topIsotope != null) {
            sb.append(". Primary identification: ${topIsotope.name} (${(topIsotope.probability * 100).roundToInt()}%)")
        }
        
        if (categories.isNotEmpty()) {
            sb.append(". Categories: ${categories.joinToString(", ")}")
        }
        
        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun formatIsotopeName(name: String): SpannableStringBuilder {
        // Convert "Cs-137" to "Cs" with superscript "137"
        val parts = name.split("-")
        if (parts.size != 2) return SpannableStringBuilder(name)
        
        val element = parts[0]
        val massNumber = parts[1]
        
        return SpannableStringBuilder().apply {
            // Mass number as superscript
            append(massNumber)
            setSpan(RelativeSizeSpan(0.6f), 0, massNumber.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(colorTextSecondary), 0, massNumber.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            // Element symbol
            append(element)
        }
    }
    
    private fun getConfidenceColor(confidence: Float): Int {
        return when {
            confidence >= 0.95f -> colorGreen
            confidence >= 0.80f -> colorCyan
            confidence >= 0.50f -> colorYellow
            confidence >= 0.30f -> colorOrange
            else -> colorRed
        }
    }
    
    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * density
            setColor(colorSurface)
            setStroke((1 * density).toInt(), colorBorder)
        }
    }
    
    private fun createPresetButtonBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * density
            if (selected) {
                setColor(Color.argb(30, 0, 229, 255))
                setStroke((1 * density).toInt(), colorCyan)
            } else {
                setColor(colorSurfaceLight)
                setStroke((1 * density).toInt(), colorBorder)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DECAY CHAIN DIAGRAM VIEW
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Custom view that draws a decay chain diagram showing parent → daughter relationships
 */
class DecayChainDiagramView(
    context: Context,
    private val chainMembers: List<String>,
    private val detectedIsotopes: Set<String>
) : View(context) {
    
    private val density = context.resources.displayMetrics.density
    
    private val colorBackground = Color.parseColor("#1A1A1E")
    private val colorBorder = Color.parseColor("#2A2A2E")
    private val colorCyan = Color.parseColor("#00E5FF")
    private val colorGreen = Color.parseColor("#69F0AE")
    private val colorMuted = Color.parseColor("#6E6E78")
    private val colorText = Color.parseColor("#FFFFFF")
    
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = colorBorder
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorBorder
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (chainMembers.isEmpty()) return
        
        val boxWidth = 70 * density
        val boxHeight = 36 * density
        val spacing = 20 * density
        val totalWidth = chainMembers.size * boxWidth + (chainMembers.size - 1) * spacing
        val startX = (width - totalWidth) / 2
        val centerY = height / 2f
        
        chainMembers.forEachIndexed { index, isotope ->
            val x = startX + index * (boxWidth + spacing)
            val isDetected = detectedIsotopes.contains(isotope)
            
            // Draw connecting line/arrow to next
            if (index < chainMembers.size - 1) {
                val lineStartX = x + boxWidth
                val lineEndX = x + boxWidth + spacing
                
                canvas.drawLine(lineStartX, centerY, lineEndX - 8 * density, centerY, linePaint)
                
                // Arrow head
                val arrowPath = android.graphics.Path().apply {
                    moveTo(lineEndX, centerY)
                    lineTo(lineEndX - 8 * density, centerY - 4 * density)
                    lineTo(lineEndX - 8 * density, centerY + 4 * density)
                    close()
                }
                canvas.drawPath(arrowPath, arrowPaint)
            }
            
            // Draw box
            val rect = RectF(x, centerY - boxHeight / 2, x + boxWidth, centerY + boxHeight / 2)
            
            boxPaint.style = Paint.Style.FILL
            boxPaint.color = if (isDetected) Color.argb(40, 105, 240, 174) else colorBackground
            canvas.drawRoundRect(rect, 6 * density, 6 * density, boxPaint)
            
            boxPaint.style = Paint.Style.STROKE
            boxPaint.strokeWidth = 2 * density
            boxPaint.color = if (isDetected) colorGreen else colorBorder
            canvas.drawRoundRect(rect, 6 * density, 6 * density, boxPaint)
            
            // Draw isotope name
            textPaint.color = if (isDetected) colorGreen else colorMuted
            canvas.drawText(isotope, x + boxWidth / 2, centerY + 5 * density, textPaint)
            
            // Draw detected indicator
            if (isDetected) {
                textPaint.textSize = 9 * density
                textPaint.color = colorGreen
                canvas.drawText("DETECTED", x + boxWidth / 2, centerY - boxHeight / 2 - 4 * density, textPaint)
                textPaint.textSize = 12 * density
            }
        }
    }
}
