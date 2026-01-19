package com.radiacode.ble

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
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
 * 
 * Each sound type has an enable/disable toggle and a volume slider.
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
    
    // Test buttons
    private lateinit var testDataTick: View
    private lateinit var testConnected: View
    private lateinit var testDisconnected: View
    private lateinit var testAlarm: View
    private lateinit var testAnomaly: View
    
    private var soundManager: SoundManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sound_settings)

        soundManager = SoundManager.getInstance(this)
        
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
            if (switchDataTick.isChecked) {
                // Temporarily enable for test
                val wasEnabled = Prefs.isSoundEnabled(this, Prefs.SoundType.DATA_TICK)
                Prefs.setSoundEnabled(this, Prefs.SoundType.DATA_TICK, true)
                soundManager?.play(Prefs.SoundType.DATA_TICK)
                if (!wasEnabled) {
                    Prefs.setSoundEnabled(this, Prefs.SoundType.DATA_TICK, false)
                }
            }
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
