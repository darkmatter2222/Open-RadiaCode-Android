package com.radiacode.ble.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.radiacode.ble.MainActivity
import com.radiacode.ble.R
import com.radiacode.ble.RadiaCodeForegroundService
import com.radiacode.ble.UnifiedWidgetProvider

/**
 * Widget Quick Actions
 * 
 * Provides quick action buttons for the widget:
 * - Start/Stop measurement
 * - Share current reading
 * - Open full app
 * - Toggle notifications
 */
object WidgetQuickActions {

    const val ACTION_START_MEASUREMENT = "com.radiacode.ble.widget.ACTION_START"
    const val ACTION_STOP_MEASUREMENT = "com.radiacode.ble.widget.ACTION_STOP"
    const val ACTION_TOGGLE_CONNECTION = "com.radiacode.ble.widget.ACTION_TOGGLE"
    const val ACTION_SHARE_READING = "com.radiacode.ble.widget.ACTION_SHARE"
    const val ACTION_OPEN_MAP = "com.radiacode.ble.widget.ACTION_MAP"
    const val ACTION_OPEN_SPECTRUM = "com.radiacode.ble.widget.ACTION_SPECTRUM"
    const val ACTION_OPEN_SETTINGS = "com.radiacode.ble.widget.ACTION_SETTINGS"
    const val ACTION_REFRESH = "com.radiacode.ble.widget.ACTION_REFRESH"

    /**
     * Handle a quick action intent.
     */
    fun handleAction(context: Context, action: String) {
        when (action) {
            ACTION_START_MEASUREMENT -> startMeasurement(context)
            ACTION_STOP_MEASUREMENT -> stopMeasurement(context)
            ACTION_TOGGLE_CONNECTION -> toggleConnection(context)
            ACTION_SHARE_READING -> shareCurrentReading(context)
            ACTION_OPEN_MAP -> openMap(context)
            ACTION_OPEN_SPECTRUM -> openSpectrum(context)
            ACTION_OPEN_SETTINGS -> openSettings(context)
            ACTION_REFRESH -> refreshWidget(context)
        }
    }

    private fun startMeasurement(context: Context) {
        val intent = Intent(context, RadiaCodeForegroundService::class.java).apply {
            action = "START_SERVICE"
        }
        context.startForegroundService(intent)
    }

    private fun stopMeasurement(context: Context) {
        val intent = Intent(context, RadiaCodeForegroundService::class.java).apply {
            action = "STOP_SERVICE"
        }
        context.startService(intent)
    }

    private fun toggleConnection(context: Context) {
        val prefs = context.getSharedPreferences("radiacode_prefs", Context.MODE_PRIVATE)
        val isConnected = prefs.getBoolean("is_connected", false)
        
        if (isConnected) {
            stopMeasurement(context)
        } else {
            startMeasurement(context)
        }
    }

    private fun shareCurrentReading(context: Context) {
        val prefs = context.getSharedPreferences("radiacode_prefs", Context.MODE_PRIVATE)
        val doseRate = prefs.getFloat("last_dose_rate", 0f)
        val countRate = prefs.getFloat("last_count_rate", 0f)
        
        val shareText = """
            ☢️ RadiaCode Reading
            
            Dose Rate: %.3f µSv/h
            Count Rate: %.0f CPS
            
            Measured with Open RadiaCode
        """.trimIndent().format(doseRate, countRate)
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        val chooserIntent = Intent.createChooser(shareIntent, "Share Reading")
        chooserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(chooserIntent)
    }

    private fun openMap(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "map")
        }
        context.startActivity(intent)
    }

    private fun openSpectrum(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "spectrum")
        }
        context.startActivity(intent)
    }

    private fun openSettings(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "settings")
        }
        context.startActivity(intent)
    }

    private fun refreshWidget(context: Context) {
        // Trigger widget update
        val intent = Intent(context, UnifiedWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }
        context.sendBroadcast(intent)
    }

    /**
     * Create PendingIntent for a quick action.
     */
    fun createActionIntent(context: Context, action: String, widgetId: Int = 0): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        
        return PendingIntent.getBroadcast(
            context,
            action.hashCode() + widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Add quick action buttons to a RemoteViews layout.
     * Called during widget rendering.
     * Note: Requires widget layout to have these IDs defined:
     * - quick_actions_container
     * - action_share, action_map, action_spectrum, action_refresh
     */
    fun setupQuickActions(context: Context, views: RemoteViews, widgetId: Int, showActions: Boolean) {
        // Quick actions setup - to be integrated when widget layout is updated
        // Currently a stub that can be enabled later
    }

    /**
     * Data class representing the available actions and their states.
     */
    data class ActionState(
        val isConnected: Boolean,
        val isMeasuring: Boolean,
        val hasLocation: Boolean
    ) {
        fun canShare() = isMeasuring
        fun canShowMap() = hasLocation
    }
}
