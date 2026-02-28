package com.radiacode.ble

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Sound Settings Activity
 * 
 * Allows users to configure audio feedback for different events:
 * - Data Tick: Subtle tick when data arrives (disabled by default)
 * - Connected: Sound when device connects
 * - Disconnected: Sound when device disconnects
 * - Alarm: Alert sound when a smart alert triggers
 * - Anomaly: Sound for statistical anomaly detection
 * - Ambient: Background sci-fi ambient drone (looping, disabled by default)
 * - Geiger Tick: Synthesized Geiger counter clicks matching CPS rate
 * 
 * Each sound type has an enable/disable toggle and a volume slider.
 * The Geiger tick section has full synthesizer parameter controls.
 */
class SoundSettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    
    // Data Tick
    private lateinit var switchDataTick: SwitchMaterial
    private lateinit var sliderDataTick: SeekBar
    private lateinit var volumeDataTick: TextView
    private lateinit var sliderContainerDataTick: LinearLayout
    
    // Connected
    private lateinit var switchConnected: SwitchMaterial
    private lateinit var sliderConnected: SeekBar
    private lateinit var volumeConnected: TextView
    private lateinit var sliderContainerConnected: LinearLayout
    
    // Disconnected
    private lateinit var switchDisconnected: SwitchMaterial
    private lateinit var sliderDisconnected: SeekBar
    private lateinit var volumeDisconnected: TextView
    private lateinit var sliderContainerDisconnected: LinearLayout
    
    // Alarm
    private lateinit var switchAlarm: SwitchMaterial
    private lateinit var sliderAlarm: SeekBar
    private lateinit var volumeAlarm: TextView
    private lateinit var sliderContainerAlarm: LinearLayout
    
    // Anomaly
    private lateinit var switchAnomaly: SwitchMaterial
    private lateinit var sliderAnomaly: SeekBar
    private lateinit var volumeAnomaly: TextView
    private lateinit var sliderContainerAnomaly: LinearLayout
    
    // Ambient
    private lateinit var switchAmbient: SwitchMaterial
    private lateinit var sliderAmbient: SeekBar
    private lateinit var volumeAmbient: TextView
    private lateinit var sliderContainerAmbient: LinearLayout
    
    // VEGA TTS
    private lateinit var switchVegaTts: SwitchMaterial
    private lateinit var vegaSettingsContainer: LinearLayout
    private lateinit var testVegaTts: MaterialButton
    
    // Geiger Tick Synthesizer
    private lateinit var switchGeigerTick: SwitchMaterial
    private lateinit var geigerSettingsContainer: LinearLayout
    private lateinit var spinnerGeigerPreset: android.widget.Spinner
    private lateinit var sliderGeigerVolume: SeekBar
    private lateinit var valueGeigerVolume: TextView
    private lateinit var sliderGeigerToneFreq: SeekBar
    private lateinit var valueGeigerToneFreq: TextView
    private lateinit var sliderGeigerToneAmount: SeekBar
    private lateinit var valueGeigerToneAmount: TextView
    private lateinit var sliderGeigerNoise: SeekBar
    private lateinit var valueGeigerNoise: TextView
    private lateinit var sliderGeigerDuration: SeekBar
    private lateinit var valueGeigerDuration: TextView
    private lateinit var sliderGeigerAttack: SeekBar
    private lateinit var valueGeigerAttack: TextView
    private lateinit var sliderGeigerDecay: SeekBar
    private lateinit var valueGeigerDecay: TextView
    private lateinit var sliderGeigerHarmonic: SeekBar
    private lateinit var valueGeigerHarmonic: TextView
    private lateinit var sliderGeigerHarmonicRatio: SeekBar
    private lateinit var valueGeigerHarmonicRatio: TextView
    private lateinit var sliderGeigerResonanceFreq: SeekBar
    private lateinit var valueGeigerResonanceFreq: TextView
    private lateinit var sliderGeigerResonanceAmount: SeekBar
    private lateinit var valueGeigerResonanceAmount: TextView
    private lateinit var sliderGeigerLowPass: SeekBar
    private lateinit var valueGeigerLowPass: TextView
    private lateinit var sliderGeigerPreviewCps: SeekBar
    private lateinit var valueGeigerPreviewCps: TextView
    private lateinit var btnGeigerPreview: MaterialButton
    private lateinit var btnGeigerReset: MaterialButton
    
    // Test buttons
    private lateinit var testDataTick: View
    private lateinit var testConnected: View
    private lateinit var testDisconnected: View
    private lateinit var testAlarm: View
    private lateinit var testAnomaly: View
    
    private var soundManager: SoundManager? = null
    private var geigerEngine: GeigerTickEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sound_settings)

        soundManager = SoundManager.getInstance(this)
        geigerEngine = GeigerTickEngine.getInstance(this)
        
        bindViews()
        setupToolbar()
        loadCurrentSettings()
        setupListeners()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Refresh ambient state when leaving settings
        soundManager?.refreshAmbientState()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        
        // Data Tick
        switchDataTick = findViewById(R.id.switchDataTick)
        sliderDataTick = findViewById(R.id.sliderDataTick)
        volumeDataTick = findViewById(R.id.volumeDataTick)
        sliderContainerDataTick = findViewById(R.id.sliderContainerDataTick)
        testDataTick = findViewById(R.id.testDataTick)
        
        // Connected
        switchConnected = findViewById(R.id.switchConnected)
        sliderConnected = findViewById(R.id.sliderConnected)
        volumeConnected = findViewById(R.id.volumeConnected)
        sliderContainerConnected = findViewById(R.id.sliderContainerConnected)
        testConnected = findViewById(R.id.testConnected)
        
        // Disconnected
        switchDisconnected = findViewById(R.id.switchDisconnected)
        sliderDisconnected = findViewById(R.id.sliderDisconnected)
        volumeDisconnected = findViewById(R.id.volumeDisconnected)
        sliderContainerDisconnected = findViewById(R.id.sliderContainerDisconnected)
        testDisconnected = findViewById(R.id.testDisconnected)
        
        // Alarm
        switchAlarm = findViewById(R.id.switchAlarm)
        sliderAlarm = findViewById(R.id.sliderAlarm)
        volumeAlarm = findViewById(R.id.volumeAlarm)
        sliderContainerAlarm = findViewById(R.id.sliderContainerAlarm)
        testAlarm = findViewById(R.id.testAlarm)
        
        // Anomaly
        switchAnomaly = findViewById(R.id.switchAnomaly)
        sliderAnomaly = findViewById(R.id.sliderAnomaly)
        volumeAnomaly = findViewById(R.id.volumeAnomaly)
        sliderContainerAnomaly = findViewById(R.id.sliderContainerAnomaly)
        testAnomaly = findViewById(R.id.testAnomaly)
        
        // Ambient
        switchAmbient = findViewById(R.id.switchAmbient)
        sliderAmbient = findViewById(R.id.sliderAmbient)
        volumeAmbient = findViewById(R.id.volumeAmbient)
        sliderContainerAmbient = findViewById(R.id.sliderContainerAmbient)
        
        // VEGA TTS
        switchVegaTts = findViewById(R.id.switchVegaTts)
        vegaSettingsContainer = findViewById(R.id.vegaSettingsContainer)
        testVegaTts = findViewById(R.id.testVegaTts)
        
        // Geiger Tick Synthesizer
        switchGeigerTick = findViewById(R.id.switchGeigerTick)
        geigerSettingsContainer = findViewById(R.id.geigerSettingsContainer)
        spinnerGeigerPreset = findViewById(R.id.spinnerGeigerPreset)
        sliderGeigerVolume = findViewById(R.id.sliderGeigerVolume)
        valueGeigerVolume = findViewById(R.id.valueGeigerVolume)
        sliderGeigerToneFreq = findViewById(R.id.sliderGeigerToneFreq)
        valueGeigerToneFreq = findViewById(R.id.valueGeigerToneFreq)
        sliderGeigerToneAmount = findViewById(R.id.sliderGeigerToneAmount)
        valueGeigerToneAmount = findViewById(R.id.valueGeigerToneAmount)
        sliderGeigerNoise = findViewById(R.id.sliderGeigerNoise)
        valueGeigerNoise = findViewById(R.id.valueGeigerNoise)
        sliderGeigerDuration = findViewById(R.id.sliderGeigerDuration)
        valueGeigerDuration = findViewById(R.id.valueGeigerDuration)
        sliderGeigerAttack = findViewById(R.id.sliderGeigerAttack)
        valueGeigerAttack = findViewById(R.id.valueGeigerAttack)
        sliderGeigerDecay = findViewById(R.id.sliderGeigerDecay)
        valueGeigerDecay = findViewById(R.id.valueGeigerDecay)
        sliderGeigerHarmonic = findViewById(R.id.sliderGeigerHarmonic)
        valueGeigerHarmonic = findViewById(R.id.valueGeigerHarmonic)
        sliderGeigerHarmonicRatio = findViewById(R.id.sliderGeigerHarmonicRatio)
        valueGeigerHarmonicRatio = findViewById(R.id.valueGeigerHarmonicRatio)
        sliderGeigerResonanceFreq = findViewById(R.id.sliderGeigerResonanceFreq)
        valueGeigerResonanceFreq = findViewById(R.id.valueGeigerResonanceFreq)
        sliderGeigerResonanceAmount = findViewById(R.id.sliderGeigerResonanceAmount)
        valueGeigerResonanceAmount = findViewById(R.id.valueGeigerResonanceAmount)
        sliderGeigerLowPass = findViewById(R.id.sliderGeigerLowPass)
        valueGeigerLowPass = findViewById(R.id.valueGeigerLowPass)
        sliderGeigerPreviewCps = findViewById(R.id.sliderGeigerPreviewCps)
        valueGeigerPreviewCps = findViewById(R.id.valueGeigerPreviewCps)
        btnGeigerPreview = findViewById(R.id.btnGeigerPreview)
        btnGeigerReset = findViewById(R.id.btnGeigerReset)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Sound Settings"
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadCurrentSettings() {
        // Data Tick
        switchDataTick.isChecked = Prefs.isSoundEnabled(this, Prefs.SoundType.DATA_TICK)
        sliderDataTick.progress = (Prefs.getSoundVolume(this, Prefs.SoundType.DATA_TICK) * 100).toInt()
        volumeDataTick.text = "${sliderDataTick.progress}%"
        sliderContainerDataTick.visibility = if (switchDataTick.isChecked) View.VISIBLE else View.GONE
        
        // Connected
        switchConnected.isChecked = Prefs.isSoundEnabled(this, Prefs.SoundType.CONNECTED)
        sliderConnected.progress = (Prefs.getSoundVolume(this, Prefs.SoundType.CONNECTED) * 100).toInt()
        volumeConnected.text = "${sliderConnected.progress}%"
        sliderContainerConnected.visibility = if (switchConnected.isChecked) View.VISIBLE else View.GONE
        
        // Disconnected
        switchDisconnected.isChecked = Prefs.isSoundEnabled(this, Prefs.SoundType.DISCONNECTED)
        sliderDisconnected.progress = (Prefs.getSoundVolume(this, Prefs.SoundType.DISCONNECTED) * 100).toInt()
        volumeDisconnected.text = "${sliderDisconnected.progress}%"
        sliderContainerDisconnected.visibility = if (switchDisconnected.isChecked) View.VISIBLE else View.GONE
        
        // Alarm
        switchAlarm.isChecked = Prefs.isSoundEnabled(this, Prefs.SoundType.ALARM)
        sliderAlarm.progress = (Prefs.getSoundVolume(this, Prefs.SoundType.ALARM) * 100).toInt()
        volumeAlarm.text = "${sliderAlarm.progress}%"
        sliderContainerAlarm.visibility = if (switchAlarm.isChecked) View.VISIBLE else View.GONE
        
        // Anomaly
        switchAnomaly.isChecked = Prefs.isSoundEnabled(this, Prefs.SoundType.ANOMALY)
        sliderAnomaly.progress = (Prefs.getSoundVolume(this, Prefs.SoundType.ANOMALY) * 100).toInt()
        volumeAnomaly.text = "${sliderAnomaly.progress}%"
        sliderContainerAnomaly.visibility = if (switchAnomaly.isChecked) View.VISIBLE else View.GONE
        
        // Ambient
        switchAmbient.isChecked = Prefs.isSoundEnabled(this, Prefs.SoundType.AMBIENT)
        sliderAmbient.progress = (Prefs.getSoundVolume(this, Prefs.SoundType.AMBIENT) * 100).toInt()
        volumeAmbient.text = "${sliderAmbient.progress}%"
        sliderContainerAmbient.visibility = if (switchAmbient.isChecked) View.VISIBLE else View.GONE
        
        // VEGA TTS
        switchVegaTts.isChecked = Prefs.isVegaTtsEnabled(this)
        vegaSettingsContainer.visibility = if (switchVegaTts.isChecked) View.VISIBLE else View.GONE
        
        // Geiger Tick Synthesizer
        switchGeigerTick.isChecked = Prefs.isGeigerTickEnabled(this)
        geigerSettingsContainer.visibility = if (switchGeigerTick.isChecked) View.VISIBLE else View.GONE
        loadGeigerSliders()
    }
    
    private fun loadGeigerSliders() {
        sliderGeigerVolume.progress = (Prefs.getGeigerVolume(this) * 100).toInt()
        valueGeigerVolume.text = "${sliderGeigerVolume.progress}%"
        
        // Tone freq: slider 0-1190 maps to 100-12000 Hz (step 10)
        sliderGeigerToneFreq.progress = ((Prefs.getGeigerToneFrequency(this) - 100f) / 10f).toInt()
        valueGeigerToneFreq.text = "${(sliderGeigerToneFreq.progress * 10 + 100)} Hz"
        
        sliderGeigerToneAmount.progress = (Prefs.getGeigerToneAmount(this) * 100).toInt()
        valueGeigerToneAmount.text = "${sliderGeigerToneAmount.progress}%"
        
        sliderGeigerNoise.progress = (Prefs.getGeigerNoiseAmount(this) * 100).toInt()
        valueGeigerNoise.text = "${sliderGeigerNoise.progress}%"
        
        // Duration: slider 0-79 maps to 1-80 ms
        sliderGeigerDuration.progress = (Prefs.getGeigerClickDuration(this) - 1f).toInt()
        valueGeigerDuration.text = "${sliderGeigerDuration.progress + 1} ms"
        
        // Attack: slider 0-99 maps to 0.1-10.0 ms (step 0.1)
        sliderGeigerAttack.progress = ((Prefs.getGeigerAttackTime(this) - 0.1f) * 10f).toInt()
        val attackMs = (sliderGeigerAttack.progress * 0.1f + 0.1f)
        valueGeigerAttack.text = "%.1f ms".format(attackMs)
        
        // Decay: slider 0-99 maps to 0.5-50 (step 0.5)
        sliderGeigerDecay.progress = ((Prefs.getGeigerDecayRate(this) - 0.5f) * 2f).toInt()
        val decayVal = sliderGeigerDecay.progress * 0.5f + 0.5f
        valueGeigerDecay.text = "%.1f".format(decayVal)
        
        // Harmonic: slider 0-100 maps to 0-100%
        sliderGeigerHarmonic.progress = (Prefs.getGeigerHarmonicAmount(this) * 100).toInt()
        valueGeigerHarmonic.text = "${sliderGeigerHarmonic.progress}%"
        
        // Harmonic ratio: slider 0-75 maps to 0.5-8.0 (step 0.1)
        sliderGeigerHarmonicRatio.progress = ((Prefs.getGeigerHarmonicFreqRatio(this) - 0.5f) * 10f).toInt()
        val ratio = sliderGeigerHarmonicRatio.progress * 0.1f + 0.5f
        valueGeigerHarmonicRatio.text = "%.1fx".format(ratio)
        
        // Resonance freq: slider 0-1190 maps to 100-12000 Hz (step 10)
        sliderGeigerResonanceFreq.progress = ((Prefs.getGeigerResonanceFreq(this) - 100f) / 10f).toInt()
        valueGeigerResonanceFreq.text = "${(sliderGeigerResonanceFreq.progress * 10 + 100)} Hz"
        
        sliderGeigerResonanceAmount.progress = (Prefs.getGeigerResonanceAmount(this) * 100).toInt()
        valueGeigerResonanceAmount.text = "${sliderGeigerResonanceAmount.progress}%"
        
        sliderGeigerLowPass.progress = (Prefs.getGeigerLowPassCutoff(this) * 100).toInt()
        valueGeigerLowPass.text = "${sliderGeigerLowPass.progress}%"
        
        // Preview CPS: slider 0-499 maps to 1-500
        sliderGeigerPreviewCps.progress = Prefs.getGeigerPreviewCps(this) - 1
        valueGeigerPreviewCps.text = "${sliderGeigerPreviewCps.progress + 1}"
        
        // Preset spinner
        loadPresetSpinner()
    }
    
    private var isPresetSpinnerInit = false
    
    private fun loadPresetSpinner() {
        val presets = GeigerTickEngine.GeigerPreset.entries
        val names = presets.map { it.displayName }.toTypedArray()
        
        val adapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, names
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                (view as? android.widget.TextView)?.apply {
                    setTextColor(resources.getColor(R.color.pro_amber, theme))
                    textSize = 13f
                    setPadding(16, 8, 16, 8)
                }
                return view
            }
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as? android.widget.TextView)?.apply {
                    setTextColor(resources.getColor(R.color.pro_text_primary, theme))
                    setBackgroundColor(resources.getColor(R.color.pro_bg_card, theme))
                    textSize = 14f
                    setPadding(24, 16, 24, 16)
                }
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGeigerPreset.adapter = adapter
        
        // Set current selection
        val currentPresetName = Prefs.getGeigerPreset(this)
        val currentPreset = GeigerTickEngine.GeigerPreset.fromName(currentPresetName)
        val idx = presets.indexOf(currentPreset)
        if (idx >= 0) {
            isPresetSpinnerInit = false
            spinnerGeigerPreset.setSelection(idx)
        }
        
        // Post to ensure the initial setSelection doesn't trigger the listener
        spinnerGeigerPreset.post { isPresetSpinnerInit = true }
    }

    private fun setupListeners() {
        // Data Tick
        setupSoundTypeListeners(
            switch = switchDataTick,
            slider = sliderDataTick,
            volumeLabel = volumeDataTick,
            sliderContainer = sliderContainerDataTick,
            soundType = Prefs.SoundType.DATA_TICK
        )
        testDataTick.setOnClickListener {
            // Always play for test, regardless of switch state (use forcePlay)
            soundManager?.play(Prefs.SoundType.DATA_TICK, forcePlay = true)
        }
        
        // Connected
        setupSoundTypeListeners(
            switch = switchConnected,
            slider = sliderConnected,
            volumeLabel = volumeConnected,
            sliderContainer = sliderContainerConnected,
            soundType = Prefs.SoundType.CONNECTED
        )
        testConnected.setOnClickListener {
            if (switchConnected.isChecked) {
                soundManager?.play(Prefs.SoundType.CONNECTED)
            }
        }
        
        // Disconnected
        setupSoundTypeListeners(
            switch = switchDisconnected,
            slider = sliderDisconnected,
            volumeLabel = volumeDisconnected,
            sliderContainer = sliderContainerDisconnected,
            soundType = Prefs.SoundType.DISCONNECTED
        )
        testDisconnected.setOnClickListener {
            if (switchDisconnected.isChecked) {
                soundManager?.play(Prefs.SoundType.DISCONNECTED)
            }
        }
        
        // Alarm
        setupSoundTypeListeners(
            switch = switchAlarm,
            slider = sliderAlarm,
            volumeLabel = volumeAlarm,
            sliderContainer = sliderContainerAlarm,
            soundType = Prefs.SoundType.ALARM
        )
        testAlarm.setOnClickListener {
            if (switchAlarm.isChecked) {
                soundManager?.play(Prefs.SoundType.ALARM)
            }
        }
        
        // Anomaly
        setupSoundTypeListeners(
            switch = switchAnomaly,
            slider = sliderAnomaly,
            volumeLabel = volumeAnomaly,
            sliderContainer = sliderContainerAnomaly,
            soundType = Prefs.SoundType.ANOMALY
        )
        testAnomaly.setOnClickListener {
            if (switchAnomaly.isChecked) {
                soundManager?.play(Prefs.SoundType.ANOMALY)
            }
        }
        
        // Ambient (special handling - toggle ambient playback)
        switchAmbient.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setSoundEnabled(this, Prefs.SoundType.AMBIENT, isChecked)
            sliderContainerAmbient.visibility = if (isChecked) View.VISIBLE else View.GONE
            
            // Immediately start/stop ambient
            if (isChecked) {
                soundManager?.startAmbient()
            } else {
                soundManager?.stopAmbient()
            }
        }
        
        sliderAmbient.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeAmbient.text = "$progress%"
                if (fromUser) {
                    Prefs.setSoundVolume(this@SoundSettingsActivity, Prefs.SoundType.AMBIENT, progress / 100f)
                    soundManager?.updateAmbientVolume()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // VEGA TTS
        switchVegaTts.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setVegaTtsEnabled(this, isChecked)
            vegaSettingsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        testVegaTts.setOnClickListener {
            // Test VEGA TTS with a sample announcement
            Toast.makeText(this, "Connecting to VEGA...", Toast.LENGTH_SHORT).show()
            VegaTTS.speak(
                context = this,
                text = "VEGA online. Radiation monitoring systems nominal. Standing by for alert conditions.",
                onComplete = {
                    runOnUiThread {
                        Toast.makeText(this, "VEGA test complete", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        Toast.makeText(this, "VEGA error: $error", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
        
        // Geiger Tick Synthesizer
        switchGeigerTick.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setGeigerTickEnabled(this, isChecked)
            geigerSettingsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        // Preset spinner listener
        spinnerGeigerPreset.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!isPresetSpinnerInit) return
                val preset = GeigerTickEngine.GeigerPreset.entries[position]
                if (preset == GeigerTickEngine.GeigerPreset.CUSTOM) {
                    Prefs.setGeigerPreset(this@SoundSettingsActivity, preset.name)
                    return
                }
                Prefs.applyGeigerPreset(this@SoundSettingsActivity, preset.params, preset.name)
                geigerEngine?.invalidateTickBuffer()
                loadGeigerSliders()
                Toast.makeText(this@SoundSettingsActivity, "${preset.displayName}: ${preset.description}", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        setupGeigerSliderListener(sliderGeigerVolume, valueGeigerVolume) { progress ->
            Prefs.setGeigerVolume(this, progress / 100f)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "$progress%"
        }
        
        setupGeigerSliderListener(sliderGeigerToneFreq, valueGeigerToneFreq) { progress ->
            val freq = progress * 10f + 100f
            Prefs.setGeigerToneFrequency(this, freq)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "${freq.toInt()} Hz"
        }
        
        setupGeigerSliderListener(sliderGeigerToneAmount, valueGeigerToneAmount) { progress ->
            Prefs.setGeigerToneAmount(this, progress / 100f)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "$progress%"
        }
        
        setupGeigerSliderListener(sliderGeigerNoise, valueGeigerNoise) { progress ->
            Prefs.setGeigerNoiseAmount(this, progress / 100f)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "$progress%"
        }
        
        setupGeigerSliderListener(sliderGeigerDuration, valueGeigerDuration) { progress ->
            val ms = progress + 1f
            Prefs.setGeigerClickDuration(this, ms)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "${ms.toInt()} ms"
        }
        
        setupGeigerSliderListener(sliderGeigerAttack, valueGeigerAttack) { progress ->
            val ms = progress * 0.1f + 0.1f
            Prefs.setGeigerAttackTime(this, ms)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "%.1f ms".format(ms)
        }
        
        setupGeigerSliderListener(sliderGeigerDecay, valueGeigerDecay) { progress ->
            val rate = progress * 0.5f + 0.5f
            Prefs.setGeigerDecayRate(this, rate)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "%.1f".format(rate)
        }
        
        setupGeigerSliderListener(sliderGeigerHarmonic, valueGeigerHarmonic) { progress ->
            Prefs.setGeigerHarmonicAmount(this, progress / 100f)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "$progress%"
        }
        
        setupGeigerSliderListener(sliderGeigerHarmonicRatio, valueGeigerHarmonicRatio) { progress ->
            val ratio = progress * 0.1f + 0.5f
            Prefs.setGeigerHarmonicFreqRatio(this, ratio)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "%.1fx".format(ratio)
        }
        
        setupGeigerSliderListener(sliderGeigerResonanceFreq, valueGeigerResonanceFreq) { progress ->
            val freq = progress * 10f + 100f
            Prefs.setGeigerResonanceFreq(this, freq)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "${freq.toInt()} Hz"
        }
        
        setupGeigerSliderListener(sliderGeigerResonanceAmount, valueGeigerResonanceAmount) { progress ->
            Prefs.setGeigerResonanceAmount(this, progress / 100f)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "$progress%"
        }
        
        setupGeigerSliderListener(sliderGeigerLowPass, valueGeigerLowPass) { progress ->
            Prefs.setGeigerLowPassCutoff(this, progress / 100f)
            geigerEngine?.invalidateTickBuffer()
            markPresetCustom()
            "$progress%"
        }
        
        setupGeigerSliderListener(sliderGeigerPreviewCps, valueGeigerPreviewCps) { progress ->
            val cps = progress + 1
            Prefs.setGeigerPreviewCps(this, cps)
            "$cps"
        }
        
        btnGeigerPreview.setOnClickListener {
            val cps = Prefs.getGeigerPreviewCps(this).toFloat()
            geigerEngine?.playPreview(cps, durationSec = 3f)
            Toast.makeText(this, "Playing ${cps.toInt()} CPS preview...", Toast.LENGTH_SHORT).show()
        }
        
        btnGeigerReset.setOnClickListener {
            Prefs.resetGeigerDefaults(this)
            geigerEngine?.invalidateTickBuffer()
            loadGeigerSliders()
            Toast.makeText(this, "Geiger tick reset to defaults", Toast.LENGTH_SHORT).show()
        }
    }
    
    /** When the user manually tweaks a slider, switch preset to Custom. */
    private fun markPresetCustom() {
        if (Prefs.getGeigerPreset(this) != "CUSTOM") {
            Prefs.setGeigerPreset(this, "CUSTOM")
            isPresetSpinnerInit = false
            spinnerGeigerPreset.setSelection(0) // CUSTOM is first entry
            spinnerGeigerPreset.post { isPresetSpinnerInit = true }
        }
    }
    
    /** Helper for Geiger slider listeners that saves on change. */
    private fun setupGeigerSliderListener(
        slider: SeekBar,
        label: TextView,
        onChanged: (Int) -> String
    ) {
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = onChanged(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun setupSoundTypeListeners(
        switch: SwitchMaterial,
        slider: SeekBar,
        volumeLabel: TextView,
        sliderContainer: LinearLayout,
        soundType: Prefs.SoundType
    ) {
        switch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setSoundEnabled(this, soundType, isChecked)
            sliderContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeLabel.text = "$progress%"
                if (fromUser) {
                    Prefs.setSoundVolume(this@SoundSettingsActivity, soundType, progress / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
}
