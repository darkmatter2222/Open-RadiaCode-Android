package com.radiacode.ble

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Helper class for managing the device list UI in the Devices panel.
 */
class DeviceListManager(
    private val context: Context,
    private val container: LinearLayout,
    private val noDevicesView: TextView,
    private val onDevicesChanged: () -> Unit
) {
    private var deviceStates: Map<String, DeviceState> = emptyMap()
    
    fun setDeviceStates(states: Map<String, DeviceState>) {
        this.deviceStates = states
        refresh()
    }
    
    fun refresh() {
        container.removeAllViews()
        
        val devices = Prefs.getDevices(context)
        
        if (devices.isEmpty()) {
            container.visibility = View.GONE
            noDevicesView.visibility = View.VISIBLE
            return
        }
        
        container.visibility = View.VISIBLE
        noDevicesView.visibility = View.GONE
        
        for (device in devices) {
            val itemView = createDeviceItemView(device)
            container.addView(itemView)
        }
    }
    
    private fun createDeviceItemView(device: DeviceConfig): View {
        val view = LayoutInflater.from(context).inflate(R.layout.item_device_card, container, false)
        
        val colorIndicator = view.findViewById<View>(R.id.deviceColorIndicator)
        val nameText = view.findViewById<TextView>(R.id.deviceName)
        val macText = view.findViewById<TextView>(R.id.deviceMac)
        val statusDot = view.findViewById<View>(R.id.statusDot)
        val statusText = view.findViewById<TextView>(R.id.statusText)
        val readingContainer = view.findViewById<View>(R.id.readingContainer)
        val readingValue = view.findViewById<TextView>(R.id.readingValue)
        val enableSwitch = view.findViewById<SwitchMaterial>(R.id.enableSwitch)
        val editButton = view.findViewById<ImageButton>(R.id.editButton)
        
        // Set device color
        val deviceColor = try {
            Color.parseColor("#${device.colorHex}")
        } catch (_: Exception) {
            Color.parseColor("#00E5FF")
        }
        (colorIndicator.background as? GradientDrawable)?.setColor(deviceColor)
            ?: run {
                val drawable = GradientDrawable()
                drawable.cornerRadius = 4f
                drawable.setColor(deviceColor)
                colorIndicator.background = drawable
            }
        
        // Set text
        nameText.text = device.displayName
        macText.text = device.macAddress
        
        // Set connection status
        val state = deviceStates[device.id]
        val isConnected = state?.connectionState == DeviceConnectionState.CONNECTED
        
        val statusColor = when (state?.connectionState) {
            DeviceConnectionState.CONNECTED -> Color.parseColor("#69F0AE")
            DeviceConnectionState.CONNECTING,
            DeviceConnectionState.RECONNECTING -> Color.parseColor("#FFD740")
            DeviceConnectionState.ERROR -> Color.parseColor("#FF5252")
            else -> Color.parseColor("#6E6E78")
        }
        
        (statusDot.background as? GradientDrawable)?.setColor(statusColor)
            ?: run {
                val drawable = GradientDrawable()
                drawable.shape = GradientDrawable.OVAL
                drawable.setColor(statusColor)
                statusDot.background = drawable
            }
        
        statusText.text = when (state?.connectionState) {
            DeviceConnectionState.CONNECTED -> "Connected"
            DeviceConnectionState.CONNECTING -> "Connecting…"
            DeviceConnectionState.RECONNECTING -> "Reconnecting…"
            DeviceConnectionState.ERROR -> state.statusMessage
            DeviceConnectionState.DISCONNECTED -> "Disconnected"
            null -> if (device.enabled) "Waiting…" else "Disabled"
        }
        
        // Show last reading if connected
        val lastReading = state?.lastReading
        if (isConnected && lastReading != null) {
            readingContainer.visibility = View.VISIBLE
            readingValue.text = "%.3f".format(lastReading.uSvPerHour)
            readingValue.setTextColor(deviceColor)
        } else {
            readingContainer.visibility = View.GONE
        }
        
        // Enable switch
        enableSwitch.isChecked = device.enabled
        enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            val updated = device.copy(enabled = isChecked)
            Prefs.updateDevice(context, updated)
            RadiaCodeForegroundService.reloadDevices(context)
            onDevicesChanged()
        }
        
        // Edit button
        editButton.setOnClickListener {
            showEditDeviceDialog(device)
        }
        
        // Long press to delete
        view.setOnLongClickListener {
            showDeleteConfirmDialog(device)
            true
        }
        
        return view
    }
    
    private fun showEditDeviceDialog(device: DeviceConfig) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_device, null)
        
        val macText = dialogView.findViewById<TextView>(R.id.deviceMacText)
        val nameInput = dialogView.findViewById<EditText>(R.id.deviceNameInput)
        val colorPicker = dialogView.findViewById<LinearLayout>(R.id.colorPicker)
        val deleteButton = dialogView.findViewById<View>(R.id.deleteButton)
        val cancelButton = dialogView.findViewById<View>(R.id.cancelButton)
        val saveButton = dialogView.findViewById<View>(R.id.saveButton)
        
        macText.text = device.macAddress
        nameInput.setText(device.customName)
        nameInput.setSelection(nameInput.text.length)
        
        // Setup color picker
        var selectedColorHex = device.colorHex
        colorPicker.removeAllViews()
        
        for (colorHex in DeviceConfig.DEVICE_COLORS) {
            val colorView = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply {
                    marginEnd = 8.dpToPx()
                }
                
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#$colorHex"))
                    if (colorHex == selectedColorHex) {
                        setStroke(3.dpToPx(), Color.WHITE)
                    }
                }
                background = drawable
                
                setOnClickListener {
                    selectedColorHex = colorHex
                    // Update all color views
                    for (i in 0 until colorPicker.childCount) {
                        val child = colorPicker.getChildAt(i)
                        val childDrawable = child.background as? GradientDrawable ?: return@setOnClickListener
                        val childColorHex = DeviceConfig.DEVICE_COLORS.getOrNull(i) ?: return@setOnClickListener
                        if (childColorHex == selectedColorHex) {
                            childDrawable.setStroke(3.dpToPx(), Color.WHITE)
                        } else {
                            childDrawable.setStroke(0, Color.TRANSPARENT)
                        }
                    }
                }
            }
            colorPicker.addView(colorView)
        }
        
        val dialog = AlertDialog.Builder(context, R.style.DarkDialogTheme)
            .setView(dialogView)
            .create()
        
        deleteButton.setOnClickListener {
            dialog.dismiss()
            showDeleteConfirmDialog(device)
        }
        
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        
        saveButton.setOnClickListener {
            val newName = nameInput.text.toString().trim()
            val updated = device.copy(
                customName = newName,
                colorHex = selectedColorHex
            )
            Prefs.updateDevice(context, updated)
            RadiaCodeForegroundService.reloadDevices(context)
            refresh()
            onDevicesChanged()
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun showDeleteConfirmDialog(device: DeviceConfig) {
        AlertDialog.Builder(context, R.style.DarkDialogTheme)
            .setTitle("Delete Device")
            .setMessage("Remove \"${device.displayName}\" from your devices?\n\nThis will stop collecting data from this device.")
            .setPositiveButton("Delete") { _, _ ->
                Prefs.deleteDevice(context, device.id)
                RadiaCodeForegroundService.reloadDevices(context)
                refresh()
                onDevicesChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
