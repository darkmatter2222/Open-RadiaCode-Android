package com.radiacode.ble.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver for handling widget quick action intents.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        Log.d("WidgetAction", "Received action: $action")
        
        WidgetQuickActions.handleAction(context, action)
    }
}
