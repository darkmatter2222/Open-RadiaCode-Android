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
    private lateinit var styleMinimal: View
    private lateinit var styleStatusOnly: View
    private lateinit var styleReadings: View
    private lateinit var styleDetailed: View
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
        styleMinimal = findViewById(R.id.styleMinimal)
        styleStatusOnly = findViewById(R.id.styleStatusOnly)
        styleReadings = findViewById(R.id.styleReadings)
        styleDetailed = findViewById(R.id.styleDetailed)
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
        
        // Show/hide detailed options
        detailedOptionsSection.visibility = if (style == Prefs.NotificationStyle.DETAILED) View.VISIBLE else View.GONE
    }

    private fun setupListeners() {
        // Style selection listeners
        val styleClickListener = View.OnClickListener { view ->
            val style = when (view.id) {
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
            
            updatePreview()
            notifyServiceOfChange()
        }
        
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
    }

    private fun selectStyle(style: Prefs.NotificationStyle) {
        // Clear all
        radioMinimal.isChecked = false
        radioStatusOnly.isChecked = false
        radioReadings.isChecked = false
        radioDetailed.isChecked = false
        
        // Select the appropriate one
        when (style) {
            Prefs.NotificationStyle.OFF -> radioMinimal.isChecked = true
            Prefs.NotificationStyle.STATUS_ONLY -> radioStatusOnly.isChecked = true
            Prefs.NotificationStyle.READINGS -> radioReadings.isChecked = true
            Prefs.NotificationStyle.DETAILED -> radioDetailed.isChecked = true
        }
    }

    private fun updatePreview() {
        val style = Prefs.getNotificationStyle(this)
        
        when (style) {
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
