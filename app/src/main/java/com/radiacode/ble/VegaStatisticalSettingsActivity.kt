package com.radiacode.ble

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radiacode.ble.ui.VegaFeatureInfoDialog

/**
 * Settings activity for VEGA Statistical Intelligence.
 * 
 * Allows users to configure all Tier 1 and Tier 2 statistical features:
 * - Z-Score anomaly detection (with sigma threshold)
 * - Rate-of-Change detection (with % threshold)
 * - CUSUM change-point detection
 * - Forecast alerting (with µSv/h threshold)
 * - Predictive threshold crossing (with time window)
 * - Voice announcements for statistical alerts
 */
class VegaStatisticalSettingsActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var toolbar: MaterialToolbar
    
    // Master toggle
    private lateinit var masterToggle: SwitchMaterial
    private lateinit var settingsContainer: LinearLayout
    
    // Z-Score
    private lateinit var zScoreToggle: SwitchMaterial
    private lateinit var zScoreSection: LinearLayout
    private lateinit var zScoreSigmaSeek: SeekBar
    private lateinit var zScoreSigmaValue: TextView
    private lateinit var zScoreDescription: TextView
    private lateinit var zScoreInfoBtn: ImageView
    
    // Rate of Change
    private lateinit var rocToggle: SwitchMaterial
    private lateinit var rocSection: LinearLayout
    private lateinit var rocThresholdSeek: SeekBar
    private lateinit var rocThresholdValue: TextView
    private lateinit var rocDescription: TextView
    private lateinit var rocInfoBtn: ImageView
    
    // CUSUM
    private lateinit var cusumToggle: SwitchMaterial
    private lateinit var cusumDescription: TextView
    private lateinit var cusumInfoBtn: ImageView
    
    // Forecast
    private lateinit var forecastToggle: SwitchMaterial
    private lateinit var forecastSection: LinearLayout
    private lateinit var forecastThresholdSeek: SeekBar
    private lateinit var forecastThresholdValue: TextView
    private lateinit var forecastDescription: TextView
    private lateinit var forecastChartToggle: SwitchMaterial
    private lateinit var forecastInfoBtn: ImageView
    
    // Predictive Crossing
    private lateinit var predictiveToggle: SwitchMaterial
    private lateinit var predictiveSection: LinearLayout
    private lateinit var predictiveSecondsSeek: SeekBar
    private lateinit var predictiveSecondsValue: TextView
    private lateinit var predictiveDescription: TextView
    private lateinit var predictiveInfoBtn: ImageView
    
    // Voice
    private lateinit var voiceToggle: SwitchMaterial
    private lateinit var voiceDescription: TextView
    private lateinit var voiceInfoBtn: ImageView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vega_statistical_settings)
        
        initViews()
        setupToolbar()
        loadSettings()
        setupListeners()
    }
    
    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        
        // Master
        masterToggle = findViewById(R.id.masterToggle)
        settingsContainer = findViewById(R.id.settingsContainer)
        
        // Z-Score
        zScoreToggle = findViewById(R.id.zScoreToggle)
        zScoreSection = findViewById(R.id.zScoreSection)
        zScoreSigmaSeek = findViewById(R.id.zScoreSigmaSeek)
        zScoreSigmaValue = findViewById(R.id.zScoreSigmaValue)
        zScoreDescription = findViewById(R.id.zScoreDescription)
        zScoreInfoBtn = findViewById(R.id.zScoreInfoBtn)
        
        // ROC
        rocToggle = findViewById(R.id.rocToggle)
        rocSection = findViewById(R.id.rocSection)
        rocThresholdSeek = findViewById(R.id.rocThresholdSeek)
        rocThresholdValue = findViewById(R.id.rocThresholdValue)
        rocDescription = findViewById(R.id.rocDescription)
        rocInfoBtn = findViewById(R.id.rocInfoBtn)
        
        // CUSUM
        cusumToggle = findViewById(R.id.cusumToggle)
        cusumDescription = findViewById(R.id.cusumDescription)
        cusumInfoBtn = findViewById(R.id.cusumInfoBtn)
        
        // Forecast
        forecastToggle = findViewById(R.id.forecastToggle)
        forecastSection = findViewById(R.id.forecastSection)
        forecastThresholdSeek = findViewById(R.id.forecastThresholdSeek)
        forecastThresholdValue = findViewById(R.id.forecastThresholdValue)
        forecastDescription = findViewById(R.id.forecastDescription)
        forecastChartToggle = findViewById(R.id.forecastChartToggle)
        forecastInfoBtn = findViewById(R.id.forecastInfoBtn)
        
        // Predictive
        predictiveToggle = findViewById(R.id.predictiveToggle)
        predictiveSection = findViewById(R.id.predictiveSection)
        predictiveSecondsSeek = findViewById(R.id.predictiveSecondsSeek)
        predictiveSecondsValue = findViewById(R.id.predictiveSecondsValue)
        predictiveDescription = findViewById(R.id.predictiveDescription)
        predictiveInfoBtn = findViewById(R.id.predictiveInfoBtn)
        
        // Voice
        voiceToggle = findViewById(R.id.voiceToggle)
        voiceDescription = findViewById(R.id.voiceDescription)
        voiceInfoBtn = findViewById(R.id.voiceInfoBtn)
    }
    
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "VEGA Intelligence"
        toolbar.setNavigationOnClickListener { finish() }
    }
    
    private fun loadSettings() {
        // Check if any feature is enabled to determine master toggle state
        val anyEnabled = Prefs.isStatisticalZScoreEnabled(this) ||
                Prefs.isStatisticalRocEnabled(this) ||
                Prefs.isStatisticalCusumEnabled(this) ||
                Prefs.isStatisticalForecastEnabled(this) ||
                Prefs.isStatisticalPredictiveCrossingEnabled(this)
        
        masterToggle.isChecked = anyEnabled
        settingsContainer.visibility = if (anyEnabled) View.VISIBLE else View.GONE
        
        // Z-Score
        zScoreToggle.isChecked = Prefs.isStatisticalZScoreEnabled(this)
        val sigma = Prefs.getStatisticalZScoreSigma(this)
        zScoreSigmaSeek.progress = sigma - 1  // Seek is 0-indexed, sigma is 1-4
        updateZScoreUI(sigma)
        zScoreSection.visibility = if (zScoreToggle.isChecked) View.VISIBLE else View.GONE
        
        // ROC
        rocToggle.isChecked = Prefs.isStatisticalRocEnabled(this)
        val rocThreshold = Prefs.getStatisticalRocThreshold(this)
        rocThresholdSeek.progress = ((rocThreshold - 1f) / 0.5f).toInt().coerceIn(0, 98)  // 1-50% mapped to seek
        updateRocUI(rocThreshold)
        rocSection.visibility = if (rocToggle.isChecked) View.VISIBLE else View.GONE
        
        // CUSUM
        cusumToggle.isChecked = Prefs.isStatisticalCusumEnabled(this)
        
        // Forecast
        forecastToggle.isChecked = Prefs.isStatisticalForecastEnabled(this)
        val forecastThreshold = Prefs.getStatisticalForecastThreshold(this)
        forecastThresholdSeek.progress = ((forecastThreshold * 10).toInt()).coerceIn(0, 100)  // 0-10 µSv/h
        updateForecastUI(forecastThreshold)
        forecastSection.visibility = if (forecastToggle.isChecked) View.VISIBLE else View.GONE
        forecastChartToggle.isChecked = Prefs.isStatisticalForecastEnabled(this)  // Chart visibility tied to forecast enabled
        
        // Predictive
        predictiveToggle.isChecked = Prefs.isStatisticalPredictiveCrossingEnabled(this)
        val predictiveSeconds = Prefs.getStatisticalPredictiveCrossingSeconds(this)
        predictiveSecondsSeek.progress = ((predictiveSeconds - 10) / 10).coerceIn(0, 29)  // 10-300s
        updatePredictiveUI(predictiveSeconds)
        predictiveSection.visibility = if (predictiveToggle.isChecked) View.VISIBLE else View.GONE
        
        // Voice
        voiceToggle.isChecked = Prefs.isStatisticalVoiceEnabled(this)
    }
    
    private fun setupListeners() {
        // Master toggle
        masterToggle.setOnCheckedChangeListener { _, isChecked ->
            settingsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                // Disable all features when master is off
                Prefs.setStatisticalZScoreEnabled(this, false)
                Prefs.setStatisticalRocEnabled(this, false)
                Prefs.setStatisticalCusumEnabled(this, false)
                Prefs.setStatisticalForecastEnabled(this, false)
                Prefs.setStatisticalPredictiveCrossingEnabled(this, false)
                Prefs.setStatisticalVoiceEnabled(this, false)
                
                // Update toggles
                zScoreToggle.isChecked = false
                rocToggle.isChecked = false
                cusumToggle.isChecked = false
                forecastToggle.isChecked = false
                predictiveToggle.isChecked = false
                voiceToggle.isChecked = false
            }
        }
        
        // Z-Score toggle
        zScoreToggle.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setStatisticalZScoreEnabled(this, isChecked)
            zScoreSection.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        // Z-Score sigma slider
        zScoreSigmaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sigma = progress + 1  // 1-4
                updateZScoreUI(sigma)
                if (fromUser) {
                    Prefs.setStatisticalZScoreSigma(this@VegaStatisticalSettingsActivity, sigma)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // ROC toggle
        rocToggle.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setStatisticalRocEnabled(this, isChecked)
            rocSection.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        // ROC threshold slider
        rocThresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = 1f + (progress * 0.5f)  // 1-50%
                updateRocUI(threshold)
                if (fromUser) {
                    Prefs.setStatisticalRocThreshold(this@VegaStatisticalSettingsActivity, threshold)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // CUSUM toggle
        cusumToggle.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setStatisticalCusumEnabled(this, isChecked)
        }
        
        // Forecast toggle
        forecastToggle.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setStatisticalForecastEnabled(this, isChecked)
            forecastSection.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        // Forecast threshold slider
        forecastThresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress / 10f  // 0-10 µSv/h
                updateForecastUI(threshold)
                if (fromUser) {
                    Prefs.setStatisticalForecastThreshold(this@VegaStatisticalSettingsActivity, threshold)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Forecast chart toggle - reuses the forecast enabled pref for now
        forecastChartToggle.setOnCheckedChangeListener { _, isChecked ->
            // For now, chart visibility is tied to forecast enabled
            // In future could be separate pref
        }
        
        // Predictive toggle
        predictiveToggle.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setStatisticalPredictiveCrossingEnabled(this, isChecked)
            predictiveSection.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        // Predictive seconds slider
        predictiveSecondsSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = 10 + (progress * 10)  // 10-300 seconds
                updatePredictiveUI(seconds)
                if (fromUser) {
                    Prefs.setStatisticalPredictiveCrossingSeconds(this@VegaStatisticalSettingsActivity, seconds)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Voice toggle
        voiceToggle.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setStatisticalVoiceEnabled(this, isChecked)
        }
        
        // Info button click handlers
        zScoreInfoBtn.setOnClickListener { 
            showFeatureInfo(VegaFeatureInfoDialog.VegaFeature.ZSCORE) 
        }
        rocInfoBtn.setOnClickListener { 
            showFeatureInfo(VegaFeatureInfoDialog.VegaFeature.ROC) 
        }
        cusumInfoBtn.setOnClickListener { 
            showFeatureInfo(VegaFeatureInfoDialog.VegaFeature.CUSUM) 
        }
        forecastInfoBtn.setOnClickListener { 
            showFeatureInfo(VegaFeatureInfoDialog.VegaFeature.FORECAST) 
        }
        predictiveInfoBtn.setOnClickListener { 
            showFeatureInfo(VegaFeatureInfoDialog.VegaFeature.PREDICTIVE_CROSSING) 
        }
        voiceInfoBtn.setOnClickListener { 
            showFeatureInfo(VegaFeatureInfoDialog.VegaFeature.VOICE) 
        }
    }
    
    private fun showFeatureInfo(feature: VegaFeatureInfoDialog.VegaFeature) {
        VegaFeatureInfoDialog(this, feature).show()
    }
    
    private fun updateZScoreUI(sigma: Int) {
        zScoreSigmaValue.text = "${sigma}σ"
        val confidence = when (sigma) {
            1 -> "68%"
            2 -> "95%"
            3 -> "99.7%"
            4 -> "99.99%"
            else -> "95%"
        }
        val sensitivity = when (sigma) {
            1 -> "Very sensitive - alerts on minor deviations"
            2 -> "Balanced - alerts on significant anomalies"
            3 -> "Conservative - only extreme anomalies"
            4 -> "Very conservative - rare alerts"
            else -> ""
        }
        zScoreDescription.text = "Confidence: $confidence\n$sensitivity"
    }
    
    private fun updateRocUI(threshold: Float) {
        rocThresholdValue.text = String.format("%.1f%%/s", threshold)
        val description = when {
            threshold <= 3f -> "Very sensitive - detects gradual changes"
            threshold <= 7f -> "Balanced - detects moderate rate changes"
            threshold <= 15f -> "Conservative - only rapid changes"
            else -> "Very conservative - only extreme changes"
        }
        rocDescription.text = description
    }
    
    private fun updateForecastUI(threshold: Float) {
        forecastThresholdValue.text = String.format("%.1f µSv/h", threshold)
        forecastDescription.text = "Alert when forecasted dose rate will exceed this threshold within 60 seconds"
    }
    
    private fun updatePredictiveUI(seconds: Int) {
        val display = when {
            seconds < 60 -> "${seconds}s"
            seconds == 60 -> "1 min"
            seconds < 120 -> "1:${String.format("%02d", seconds - 60)}"
            else -> "${seconds / 60}:${String.format("%02d", seconds % 60)}"
        }
        predictiveSecondsValue.text = display
        predictiveDescription.text = "Warn $display before crossing any active Smart Alert threshold"
    }
}
