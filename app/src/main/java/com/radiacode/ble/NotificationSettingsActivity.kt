package com.radiacode.ble

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Notification Settings Activity
 * 
 * Allows users to configure what appears in the persistent foreground service notification:
 * - Minimal: Just "Open RadiaCode" title
 * - Status Only: Connection status (Connected/Disconnected)
 * - Readings: Current dose/count readings from connected devices (default)
 * - Detailed: Full information with optional toggles for device count, alerts, anomalies
 */
class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    
    // Style selection
    private lateinit var styleNone: View
    private lateinit var styleMinimal: View
    private lateinit var styleStatusOnly: View
    private lateinit var styleReadings: View
    private lateinit var styleDetailed: View
    private lateinit var radioNone: RadioButton
    private lateinit var radioMinimal: RadioButton
    private lateinit var radioStatusOnly: RadioButton
    private lateinit var radioReadings: RadioButton
    private lateinit var radioDetailed: RadioButton
    
    // Detailed options section
    private lateinit var detailedOptionsSection: LinearLayout
    private lateinit var switchShowReadings: SwitchMaterial
    private lateinit var switchShowDeviceCount: SwitchMaterial
    private lateinit var switchShowConnectionStatus: SwitchMaterial
    private lateinit var switchShowAlerts: SwitchMaterial
    private lateinit var switchShowAnomalies: SwitchMaterial
    
    // Alert sound options
    private lateinit var switchSystemSound: SwitchMaterial
    
    // Preview
    private lateinit var previewTitle: TextView
    private lateinit var previewContent: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)

        bindViews()
        setupToolbar()
        loadCurrentSettings()
        setupListeners()
        updatePreview()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        
        // Style options
        styleNone = findViewById(R.id.styleNone)
        styleMinimal = findViewById(R.id.styleMinimal)
        styleStatusOnly = findViewById(R.id.styleStatusOnly)
        styleReadings = findViewById(R.id.styleReadings)
        styleDetailed = findViewById(R.id.styleDetailed)
        radioNone = findViewById(R.id.radioNone)
        radioMinimal = findViewById(R.id.radioMinimal)
        radioStatusOnly = findViewById(R.id.radioStatusOnly)
        radioReadings = findViewById(R.id.radioReadings)
        radioDetailed = findViewById(R.id.radioDetailed)
        
        // Detailed options
        detailedOptionsSection = findViewById(R.id.detailedOptionsSection)
        switchShowReadings = findViewById(R.id.switchShowReadings)
        switchShowDeviceCount = findViewById(R.id.switchShowDeviceCount)
        switchShowConnectionStatus = findViewById(R.id.switchShowConnectionStatus)
        switchShowAlerts = findViewById(R.id.switchShowAlerts)
        switchShowAnomalies = findViewById(R.id.switchShowAnomalies)
        
        // Alert sound options
        switchSystemSound = findViewById(R.id.switchSystemSound)
        
        // Preview
        previewTitle = findViewById(R.id.previewTitle)
        previewContent = findViewById(R.id.previewContent)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Notification Settings"
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadCurrentSettings() {
        // Load notification style
        val style = Prefs.getNotificationStyle(this)
        selectStyle(style)
        
        // Load detailed options
        switchShowReadings.isChecked = Prefs.isNotificationShowReadings(this)
        switchShowDeviceCount.isChecked = Prefs.isNotificationShowDeviceCount(this)
        switchShowConnectionStatus.isChecked = Prefs.isNotificationShowConnectionStatus(this)
        switchShowAlerts.isChecked = Prefs.isNotificationShowAlerts(this)
        switchShowAnomalies.isChecked = Prefs.isNotificationShowAnomalies(this)
        
        // Load alert sound options
        switchSystemSound.isChecked = Prefs.isNotificationSystemSoundEnabled(this)
        
        // Show/hide detailed options
        detailedOptionsSection.visibility = if (style == Prefs.NotificationStyle.DETAILED) View.VISIBLE else View.GONE
    }

    private fun setupListeners() {
        // Style selection listeners
        val styleClickListener = View.OnClickListener { view ->
            val style = when (view.id) {
                R.id.styleNone -> Prefs.NotificationStyle.NONE
                R.id.styleMinimal -> Prefs.NotificationStyle.OFF
                R.id.styleStatusOnly -> Prefs.NotificationStyle.STATUS_ONLY
                R.id.styleReadings -> Prefs.NotificationStyle.READINGS
                R.id.styleDetailed -> Prefs.NotificationStyle.DETAILED
                else -> Prefs.NotificationStyle.READINGS
            }
            selectStyle(style)
            Prefs.setNotificationStyle(this, style)
            
            // Show/hide detailed options
            detailedOptionsSection.visibility = if (style == Prefs.NotificationStyle.DETAILED) View.VISIBLE else View.GONE
            
            // If "None" is selected, offer to open Android notification settings
            if (style == Prefs.NotificationStyle.NONE) {
                openNotificationChannelSettings()
            }
            
            updatePreview()
            notifyServiceOfChange()
        }
        
        styleNone.setOnClickListener(styleClickListener)
        styleMinimal.setOnClickListener(styleClickListener)
        styleStatusOnly.setOnClickListener(styleClickListener)
        styleReadings.setOnClickListener(styleClickListener)
        styleDetailed.setOnClickListener(styleClickListener)
        
        // Detailed option toggles
        switchShowReadings.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setNotificationShowReadings(this, isChecked)
            updatePreview()
            notifyServiceOfChange()
        }
        
        switchShowDeviceCount.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setNotificationShowDeviceCount(this, isChecked)
            updatePreview()
            notifyServiceOfChange()
        }
        
        switchShowConnectionStatus.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setNotificationShowConnectionStatus(this, isChecked)
            updatePreview()
            notifyServiceOfChange()
        }
        
        switchShowAlerts.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setNotificationShowAlerts(this, isChecked)
            updatePreview()
            notifyServiceOfChange()
        }
        
        switchShowAnomalies.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setNotificationShowAnomalies(this, isChecked)
            updatePreview()
            notifyServiceOfChange()
        }
        
        // Alert sound toggle
        switchSystemSound.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setNotificationSystemSoundEnabled(this, isChecked)
            // Recreate notification channels with updated sound settings
            recreateNotificationChannels(isChecked)
        }
    }
    
    /**
     * Recreate notification channels with updated sound settings.
     * Android caches channel settings, so we need to delete and recreate.
     */
    private fun recreateNotificationChannels(enableSound: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // Delete old channels
            manager.deleteNotificationChannel("radiacode_alerts_v2")
            manager.deleteNotificationChannel("radiation_alerts_v2")
            
            // Recreate with updated sound setting
            val soundUri = if (enableSound) {
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            } else null
            
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .build()
            
            // RadiaCode Alerts channel
            val alertChannel = android.app.NotificationChannel(
                "radiacode_alerts_v2",
                "RadiaCode Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Radiation level alerts"
                enableVibration(true)
                enableLights(true)
                setSound(soundUri, audioAttributes)
            }
            manager.createNotificationChannel(alertChannel)
            
            // Radiation Alerts channel (DangerZone)
            val dangerChannel = android.app.NotificationChannel(
                "radiation_alerts_v2",
                "Radiation Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for elevated radiation levels"
                enableVibration(true)
                enableLights(true)
                setSound(soundUri, audioAttributes)
            }
            manager.createNotificationChannel(dangerChannel)
        }
    }

    private fun selectStyle(style: Prefs.NotificationStyle) {
        // Clear all
        radioNone.isChecked = false
        radioMinimal.isChecked = false
        radioStatusOnly.isChecked = false
        radioReadings.isChecked = false
        radioDetailed.isChecked = false
        
        // Select the appropriate one
        when (style) {
            Prefs.NotificationStyle.NONE -> radioNone.isChecked = true
            Prefs.NotificationStyle.OFF -> radioMinimal.isChecked = true
            Prefs.NotificationStyle.STATUS_ONLY -> radioStatusOnly.isChecked = true
            Prefs.NotificationStyle.READINGS -> radioReadings.isChecked = true
            Prefs.NotificationStyle.DETAILED -> radioDetailed.isChecked = true
        }
    }
    
    private fun openNotificationChannelSettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "radiacode_live")
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Fallback to app notification settings
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {
                // Ignore if settings can't be opened
            }
        }
    }

    private fun updatePreview() {
        val style = Prefs.getNotificationStyle(this)
        
        when (style) {
            Prefs.NotificationStyle.NONE -> {
                previewTitle.text = "Open RadiaCode"
                previewContent.text = "(Notification hidden - disable in Android settings)"
            }
            Prefs.NotificationStyle.OFF -> {
                previewTitle.text = "Open RadiaCode"
                previewContent.text = "Service running"
            }
            Prefs.NotificationStyle.STATUS_ONLY -> {
                previewTitle.text = "Open RadiaCode"
                previewContent.text = "2 devices connected"
            }
            Prefs.NotificationStyle.READINGS -> {
                previewTitle.text = "Open RadiaCode (2 connected)"
                previewContent.text = "RC-102: 0.123 μSv/h • RC-103: 0.098 μSv/h"
            }
            Prefs.NotificationStyle.DETAILED -> {
                val showReadings = Prefs.isNotificationShowReadings(this)
                val showDeviceCount = Prefs.isNotificationShowDeviceCount(this)
                val showAlerts = Prefs.isNotificationShowAlerts(this)
                val showAnomalies = Prefs.isNotificationShowAnomalies(this)
                
                // Build title
                previewTitle.text = if (showDeviceCount) {
                    "Open RadiaCode (2 connected)"
                } else {
                    "Open RadiaCode"
                }
                
                // Build content
                val parts = mutableListOf<String>()
                if (showReadings) {
                    parts.add("RC-102: 0.123 μSv/h • RC-103: 0.098 μSv/h")
                }
                if (showAlerts) {
                    parts.add("⚠️ 1 alert active")
                }
                if (showAnomalies) {
                    parts.add("🔬 Anomaly detected")
                }
                
                previewContent.text = if (parts.isEmpty()) {
                    "All systems normal"
                } else {
                    parts.joinToString("\n")
                }
            }
        }
    }

    private fun notifyServiceOfChange() {
        // Send broadcast to service to update notification
        try {
            val intent = android.content.Intent("com.radiacode.ble.action.REFRESH_NOTIFICATION")
            sendBroadcast(intent)
        } catch (_: Exception) {
            // Service might not be running
        }
        
        // Also try direct service update
        RadiaCodeForegroundService.refreshNotification(this)
    }
}
