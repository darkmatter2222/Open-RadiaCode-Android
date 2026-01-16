package com.radiacode.ble.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.radiacode.ble.DeviceConfig
import com.radiacode.ble.DeviceConnectionState
import com.radiacode.ble.DeviceState
import com.radiacode.ble.Prefs
import com.radiacode.ble.R

/**
 * A device selector component for the dashboard.
 * Shows a dropdown to select which device(s) to view.
 * Options:
 *   - "All Devices" (aggregate view)
 *   - Individual device names
 * 
 * Also displays integrated device metadata (signal, temp, battery).
 */
class DeviceSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface OnDeviceSelectedListener {
        fun onDeviceSelected(deviceId: String?)  // null = "All Devices"
    }
    
    interface OnSettingsClickListener {
        fun onSettingsClick()
    }

    private val spinner: Spinner
    private val statusDot: View
    private val settingsButton: ImageButton
    
    // Device stats row
    private val deviceStatsRow: View
    private val signalChip: View
    private val signalIcon: ImageView
    private val signalValue: TextView
    private val tempChip: View
    private val tempIcon: ImageView
    private val tempValue: TextView
    private val batteryChip: View
    private val batteryIcon: ImageView
    private val batteryValue: TextView
    
    private var deviceSelectedListener: OnDeviceSelectedListener? = null
    private var settingsClickListener: OnSettingsClickListener? = null

    private var devices: List<DeviceConfig> = emptyList()
    private var deviceStates: Map<String, DeviceState> = emptyMap()
    private var suppressCallback = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_device_selector, this, true)
        orientation = VERTICAL
        
        spinner = findViewById(R.id.deviceSpinner)
        statusDot = findViewById(R.id.deviceStatusDot)
        settingsButton = findViewById(R.id.chartSettingsButton)
        
        // Stats row elements
        deviceStatsRow = findViewById(R.id.deviceStatsRow)
        signalChip = findViewById(R.id.signalChip)
        signalIcon = findViewById(R.id.signalIcon)
        signalValue = findViewById(R.id.signalValue)
        tempChip = findViewById(R.id.tempChip)
        tempIcon = findViewById(R.id.tempIcon)
        tempValue = findViewById(R.id.tempValue)
        batteryChip = findViewById(R.id.batteryChip)
        batteryIcon = findViewById(R.id.batteryIcon)
        batteryValue = findViewById(R.id.batteryValue)
        
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCallback) return
                
                val selectedId = if (position == 0) null else devices.getOrNull(position - 1)?.id
                Prefs.setSelectedDeviceId(context, selectedId)
                deviceSelectedListener?.onDeviceSelected(selectedId)
                updateStatusDot(selectedId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        settingsButton.setOnClickListener {
            settingsClickListener?.onSettingsClick()
        }
    }

    fun setOnDeviceSelectedListener(l: OnDeviceSelectedListener?) {
        deviceSelectedListener = l
    }
    
    fun setOnSettingsClickListener(l: OnSettingsClickListener?) {
        settingsClickListener = l
    }

    /**
     * Update the list of devices.
     */
    fun setDevices(devices: List<DeviceConfig>, states: Map<String, DeviceState> = emptyMap()) {
        this.devices = devices
        this.deviceStates = states
        
        val adapter = DeviceSpinnerAdapter(context, devices, states)
        spinner.adapter = adapter
        
        // Restore selection
        val selectedId = Prefs.getSelectedDeviceId(context)
        suppressCallback = true
        if (selectedId == null) {
            spinner.setSelection(0)
        } else {
            val idx = devices.indexOfFirst { it.id == selectedId }
            spinner.setSelection(if (idx >= 0) idx + 1 else 0)
        }
        suppressCallback = false
        
        updateStatusDot(selectedId)
        updateSelectorVisibility()
    }
    
    private var hasMetadata = false
    
    private fun updateSelectorVisibility() {
        // Show selector row if: multiple devices OR we have metadata to display
        // The spinner itself is hidden for single device, but stats row shows
        val showSelector = devices.size > 1 || hasMetadata
        visibility = if (showSelector) VISIBLE else GONE
        
        // Hide the top row (label, spinner, dot, settings) in single-device mode
        // but keep the stats row visible
        val topRow = findViewById<View>(R.id.deviceSpinner)?.parent as? View
        // Actually, let's keep the entire selector visible - single device still shows name
    }
    
    /**
     * Update connection states without rebuilding the list.
     */
    fun updateStates(states: Map<String, DeviceState>) {
        this.deviceStates = states
        (spinner.adapter as? DeviceSpinnerAdapter)?.updateStates(states)
        
        val selectedId = Prefs.getSelectedDeviceId(context)
        updateStatusDot(selectedId)
    }
    
    /**
     * Update device metadata display (signal, temperature, battery).
     * @param signalStrength RSSI in dBm (null to hide)
     * @param temperature Temperature in Celsius (null to hide)
     * @param batteryLevel Battery percentage 0-100 (null to hide)
     */
    fun updateDeviceMetadata(signalStrength: Int?, temperature: Float?, batteryLevel: Int?) {
        val hasAnyData = signalStrength != null || temperature != null || batteryLevel != null
        hasMetadata = hasAnyData
        
        deviceStatsRow.visibility = if (hasAnyData) View.VISIBLE else View.GONE
        
        // Update visibility of the whole component
        updateSelectorVisibility()
        
        // Signal strength chip
        if (signalStrength != null) {
            signalChip.visibility = View.VISIBLE
            signalValue.text = signalStrength.toString()
            // Color code based on signal strength
            val signalColor = when {
                signalStrength >= -60 -> Color.parseColor("#69F0AE")  // Excellent - green
                signalStrength >= -70 -> Color.parseColor("#00E5FF")  // Good - cyan
                signalStrength >= -80 -> Color.parseColor("#FFD740")  // Fair - amber
                else -> Color.parseColor("#FF5252")  // Poor - red
            }
            signalIcon.setColorFilter(signalColor)
            signalValue.setTextColor(signalColor)
        } else {
            signalChip.visibility = View.GONE
        }
        
        // Temperature chip
        if (temperature != null) {
            tempChip.visibility = View.VISIBLE
            tempValue.text = "%.0f".format(temperature)
            // Color code based on temperature
            val tempColor = when {
                temperature < 10 -> Color.parseColor("#00E5FF")  // Cold - cyan
                temperature <= 35 -> Color.parseColor("#69F0AE")  // Normal - green
                temperature <= 45 -> Color.parseColor("#FFD740")  // Warm - amber
                else -> Color.parseColor("#FF5252")  // Hot - red
            }
            tempIcon.setColorFilter(tempColor)
            tempValue.setTextColor(tempColor)
        } else {
            tempChip.visibility = View.GONE
        }
        
        // Battery chip
        if (batteryLevel != null) {
            batteryChip.visibility = View.VISIBLE
            batteryValue.text = batteryLevel.toString()
            // Color code based on battery level
            val batteryColor = when {
                batteryLevel >= 50 -> Color.parseColor("#69F0AE")  // Good - green
                batteryLevel >= 20 -> Color.parseColor("#FFD740")  // Low - amber
                else -> Color.parseColor("#FF5252")  // Critical - red
            }
            batteryIcon.setColorFilter(batteryColor)
            batteryValue.setTextColor(batteryColor)
        } else {
            batteryChip.visibility = View.GONE
        }
    }
    
    private fun updateStatusDot(selectedId: String?) {
        val isConnected = if (selectedId == null) {
            // All devices - show green if ANY device is connected
            deviceStates.values.any { it.connectionState == DeviceConnectionState.CONNECTED }
        } else {
            deviceStates[selectedId]?.connectionState == DeviceConnectionState.CONNECTED
        }
        
        val color = if (isConnected) Color.parseColor("#69F0AE") else Color.parseColor("#FF5252")
        (statusDot.background as? GradientDrawable)?.setColor(color)
            ?: run {
                val drawable = GradientDrawable()
                drawable.shape = GradientDrawable.OVAL
                drawable.setColor(color)
                statusDot.background = drawable
            }
    }

    /**
     * Get currently selected device ID (null = All Devices).
     */
    fun getSelectedDeviceId(): String? {
        val pos = spinner.selectedItemPosition
        return if (pos <= 0) null else devices.getOrNull(pos - 1)?.id
    }

    private class DeviceSpinnerAdapter(
        context: Context,
        private val devices: List<DeviceConfig>,
        private var states: Map<String, DeviceState>
    ) : ArrayAdapter<String>(context, R.layout.item_device_spinner, mutableListOf()) {

        init {
            rebuildItems()
        }
        
        private fun rebuildItems() {
            clear()
            add("All Devices (${devices.size})")
            devices.forEach { add(it.displayName) }
        }
        
        fun updateStates(newStates: Map<String, DeviceState>) {
            states = newStates
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createItemView(position, convertView, parent, false)
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createItemView(position, convertView, parent, true)
        }
        
        private fun createItemView(position: Int, convertView: View?, parent: ViewGroup, isDropdown: Boolean): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_device_spinner, parent, false)
            
            val colorDot = view.findViewById<View>(R.id.deviceColorDot)
            val nameText = view.findViewById<TextView>(R.id.deviceName)
            val statusText = view.findViewById<TextView>(R.id.deviceStatus)
            
            if (position == 0) {
                // "All Devices"
                nameText.text = "All Devices"
                
                val connectedCount = states.values.count { 
                    it.connectionState == DeviceConnectionState.CONNECTED 
                }
                
                // Multi-color dot for "All Devices"
                val allConnected = connectedCount == devices.size && devices.isNotEmpty()
                val anyConnected = connectedCount > 0
                val dotColor = when {
                    allConnected -> Color.parseColor("#69F0AE")  // Green
                    anyConnected -> Color.parseColor("#FFD740")  // Amber
                    else -> Color.parseColor("#FF5252")  // Red
                }
                setDotColor(colorDot, dotColor)
                
                if (isDropdown) {
                    statusText.visibility = View.VISIBLE
                    statusText.text = "$connectedCount/${devices.size} connected"
                } else {
                    statusText.visibility = View.GONE
                }
            } else {
                // Individual device
                val device = devices[position - 1]
                nameText.text = device.displayName
                
                // Device color dot
                val deviceColor = try {
                    Color.parseColor("#${device.colorHex}")
                } catch (_: Exception) {
                    Color.parseColor("#00E5FF")
                }
                setDotColor(colorDot, deviceColor)
                
                if (isDropdown) {
                    val state = states[device.id]
                    statusText.visibility = View.VISIBLE
                    statusText.text = when (state?.connectionState) {
                        DeviceConnectionState.CONNECTED -> "● Connected"
                        DeviceConnectionState.CONNECTING -> "○ Connecting…"
                        DeviceConnectionState.RECONNECTING -> "○ Reconnecting…"
                        DeviceConnectionState.ERROR -> "✕ Error"
                        else -> "○ Disconnected"
                    }
                    statusText.setTextColor(when (state?.connectionState) {
                        DeviceConnectionState.CONNECTED -> Color.parseColor("#69F0AE")
                        DeviceConnectionState.CONNECTING,
                        DeviceConnectionState.RECONNECTING -> Color.parseColor("#FFD740")
                        else -> Color.parseColor("#6E6E78")
                    })
                } else {
                    statusText.visibility = View.GONE
                }
            }
            
            return view
        }
        
        private fun setDotColor(dot: View, color: Int) {
            val drawable = dot.background as? GradientDrawable
                ?: GradientDrawable().also { dot.background = it }
            drawable.shape = GradientDrawable.OVAL
            drawable.setColor(color)
        }
    }
}
