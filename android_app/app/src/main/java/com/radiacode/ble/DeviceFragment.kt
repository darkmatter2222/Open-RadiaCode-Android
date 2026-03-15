package com.radiacode.ble

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Device management tab: connection status, auto-connect, device list,
 * and find/reconnect/stop controls.
 */
class DeviceFragment : Fragment() {

    // Connection status
    private lateinit var connectionDot: View
    private lateinit var connectionStatus: TextView
    private lateinit var preferredDeviceText: TextView

    // Controls
    private lateinit var autoConnectSwitch: SwitchMaterial
    private lateinit var findDevicesButton: MaterialButton
    private lateinit var reconnectButton: MaterialButton
    private lateinit var stopServiceButton: MaterialButton

    // Device list
    private lateinit var deviceListContainer: LinearLayout
    private lateinit var noDevicesText: TextView

    private var deviceListManager: DeviceListManager? = null

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    private val findDevicesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val address = result.data?.getStringExtra(FindDevicesActivity.EXTRA_DEVICE_ADDRESS) ?: return@registerForActivityResult
        mainActivity?.onDeviceDiscovered(address)
        refreshDeviceList()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_device, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupControls()
        setupDeviceListManager()
        refreshDeviceList()
    }

    private fun bindViews(view: View) {
        connectionDot = view.findViewById(R.id.connectionDot)
        connectionStatus = view.findViewById(R.id.connectionStatus)
        preferredDeviceText = view.findViewById(R.id.preferredDeviceText)
        autoConnectSwitch = view.findViewById(R.id.autoConnectSwitch)
        findDevicesButton = view.findViewById(R.id.findDevicesButton)
        reconnectButton = view.findViewById(R.id.reconnectButton)
        stopServiceButton = view.findViewById(R.id.stopServiceButton)
        deviceListContainer = view.findViewById(R.id.deviceListContainer)
        noDevicesText = view.findViewById(R.id.noDevicesText)
    }

    private fun setupControls() {
        val ctx = requireContext()

        autoConnectSwitch.isChecked = Prefs.isAutoConnectEnabled(ctx)
        autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAutoConnectEnabled(ctx, isChecked)
            if (isChecked) {
                RadiaCodeForegroundService.start(ctx)
            } else {
                RadiaCodeForegroundService.stop(ctx)
            }
        }

        findDevicesButton.setOnClickListener {
            findDevicesLauncher.launch(Intent(ctx, FindDevicesActivity::class.java))
        }

        reconnectButton.setOnClickListener {
            RadiaCodeForegroundService.reconnect(ctx)
        }

        stopServiceButton.setOnClickListener {
            RadiaCodeForegroundService.stop(ctx)
        }
    }

    private fun setupDeviceListManager() {
        if (deviceListManager != null) return
        deviceListManager = DeviceListManager(
            context = requireContext(),
            container = deviceListContainer,
            noDevicesView = noDevicesText,
            onDevicesChanged = { refreshDeviceList() }
        )
    }

    private fun refreshDeviceList() {
        if (!isAdded) return
        val ctx = requireContext()
        val devices = Prefs.getDevices(ctx)
        deviceListManager?.refresh()

        val enabledCount = devices.count { it.enabled }
        val preferred = Prefs.getPreferredAddress(ctx)
        preferredDeviceText.text = when {
            enabledCount > 1 -> "$enabledCount devices"
            enabledCount == 1 -> devices.first { it.enabled }.displayName
            !preferred.isNullOrBlank() -> preferred
            else -> "Not set"
        }
    }

    /**
     * Update the connection status indicator.
     * Called by MainActivity when device state changes.
     */
    fun updateConnectionStatus(connected: Boolean, message: String) {
        if (!isAdded || view == null) return
        connectionStatus.text = message
        val color = if (connected) {
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.pro_status_live)
        } else {
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.pro_text_muted)
        }
        (connectionDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
    }

    /**
     * No-arg overload: reads connection state from MainActivity.
     */
    fun updateConnectionStatus() {
        val ma = activity as? MainActivity ?: return
        val states = ma.getDeviceConnectionStates()
        val anyConnected = states.values.any { it == DeviceConnectionState.CONNECTED }
        val message = when {
            states.isEmpty() -> "No devices"
            anyConnected -> "Connected"
            states.values.any { it == DeviceConnectionState.CONNECTING } -> "Connecting..."
            else -> "Disconnected"
        }
        updateConnectionStatus(anyConnected, message)
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceList()
    }
}
