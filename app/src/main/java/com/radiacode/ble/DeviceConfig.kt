package com.radiacode.ble

import java.util.UUID

/**
 * Configuration for a RadiaCode device.
 * 
 * @param id Unique identifier for this device config
 * @param macAddress BLE MAC address (e.g., "AA:BB:CC:DD:EE:FF")
 * @param customName User-defined friendly name (e.g., "Kitchen Detector", "Car RadiaCode")
 * @param enabled Whether this device should be auto-connected
 * @param colorHex Accent color for this device in charts (hex without #)
 * @param addedAtMs Timestamp when device was added
 */
data class DeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val macAddress: String,
    val customName: String = "",
    val enabled: Boolean = true,
    val colorHex: String = "00E5FF",  // Default cyan
    val addedAtMs: Long = System.currentTimeMillis()
) {
    /**
     * Display name: custom name if set, otherwise MAC address
     */
    val displayName: String
        get() = customName.ifBlank { macAddress }
    
    /**
     * Short display name for compact UIs (first 8 chars of custom name or last 8 of MAC)
     */
    val shortDisplayName: String
        get() = if (customName.isNotBlank()) {
            customName.take(12)
        } else {
            macAddress.takeLast(8)
        }
    
    fun toJson(): String {
        val escapedName = customName.replace("\"", "\\\"").replace("\n", "\\n")
        return """{"id":"$id","macAddress":"$macAddress","customName":"$escapedName","enabled":$enabled,"colorHex":"$colorHex","addedAtMs":$addedAtMs}"""
    }
    
    companion object {
        // Predefined colors for devices (cycle through these)
        val DEVICE_COLORS = listOf(
            "00E5FF",  // Cyan (primary)
            "E040FB",  // Magenta
            "69F0AE",  // Green
            "FFD740",  // Amber
            "FF5252",  // Red
            "40C4FF",  // Light blue
            "B388FF",  // Purple
            "CCFF90",  // Light green
        )
        
        fun getColorForIndex(index: Int): String {
            return DEVICE_COLORS[index % DEVICE_COLORS.size]
        }
        
        fun fromJson(json: String): DeviceConfig? {
            return try {
                val id = json.substringAfter("\"id\":\"").substringBefore("\"")
                val macAddress = json.substringAfter("\"macAddress\":\"").substringBefore("\"")
                val customName = json.substringAfter("\"customName\":\"").substringBefore("\"")
                    .replace("\\\"", "\"").replace("\\n", "\n")
                val enabled = json.substringAfter("\"enabled\":").substringBefore(",").substringBefore("}").toBoolean()
                val colorHex = json.substringAfter("\"colorHex\":\"").substringBefore("\"").ifBlank { "00E5FF" }
                val addedAtMs = json.substringAfter("\"addedAtMs\":").substringBefore(",").substringBefore("}").toLongOrNull() 
                    ?: System.currentTimeMillis()
                
                if (macAddress.isBlank()) return null
                
                DeviceConfig(
                    id = id.ifBlank { UUID.randomUUID().toString() },
                    macAddress = macAddress,
                    customName = customName,
                    enabled = enabled,
                    colorHex = colorHex,
                    addedAtMs = addedAtMs
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Current reading from a specific device.
 */
data class DeviceReading(
    val deviceId: String,
    val macAddress: String,
    val uSvPerHour: Float,
    val cps: Float,
    val timestampMs: Long,
    val isConnected: Boolean = true
) {
    companion object {
        fun fromJson(json: String): DeviceReading? {
            return try {
                val deviceId = json.substringAfter("\"deviceId\":\"").substringBefore("\"")
                val macAddress = json.substringAfter("\"macAddress\":\"").substringBefore("\"")
                val uSvPerHour = json.substringAfter("\"uSvPerHour\":").substringBefore(",").toFloat()
                val cps = json.substringAfter("\"cps\":").substringBefore(",").toFloat()
                val timestampMs = json.substringAfter("\"timestampMs\":").substringBefore(",").substringBefore("}").toLong()
                val isConnected = json.substringAfter("\"isConnected\":").substringBefore(",").substringBefore("}").toBooleanStrictOrNull() ?: true
                
                DeviceReading(deviceId, macAddress, uSvPerHour, cps, timestampMs, isConnected)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun toJson(): String {
        return """{"deviceId":"$deviceId","macAddress":"$macAddress","uSvPerHour":$uSvPerHour,"cps":$cps,"timestampMs":$timestampMs,"isConnected":$isConnected}"""
    }
}

/**
 * Connection status for a device.
 */
enum class DeviceConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * Runtime state for a connected device (not persisted).
 */
data class DeviceState(
    val config: DeviceConfig,
    var connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    var lastReading: DeviceReading? = null,
    var statusMessage: String = "Not connected",
    var consecutiveFailures: Int = 0,
    var lastSuccessfulPollMs: Long = 0L
)
