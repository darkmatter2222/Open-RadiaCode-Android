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
 */
class DeviceSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface OnDeviceSelectedListener {
        fun onDeviceSelected(deviceId: String?)  // null = "All Devices"
    }

    private val spinner: Spinner
    private val statusDot: View
    private var listener: OnDeviceSelectedListener? = null

    private var devices: List<DeviceConfig> = emptyList()
    private var deviceStates: Map<String, DeviceState> = emptyMap()
    private var suppressCallback = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_device_selector, this, true)
        orientation = HORIZONTAL
        
        spinner = findViewById(R.id.deviceSpinner)
        statusDot = findViewById(R.id.deviceStatusDot)
        
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCallback) return
                
                val selectedId = if (position == 0) null else devices.getOrNull(position - 1)?.id
                Prefs.setSelectedDeviceId(context, selectedId)
                listener?.onDeviceSelected(selectedId)
                updateStatusDot(selectedId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    fun setOnDeviceSelectedListener(l: OnDeviceSelectedListener?) {
        listener = l
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
        
        // Hide if only one device
        visibility = if (devices.size <= 1) GONE else VISIBLE
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
