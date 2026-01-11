package com.radiacode.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    private companion object {
        private const val TAG = "RadiaCode"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_USER_UNLOCKED) return

        if (!Prefs.isAutoConnectEnabled(context)) return
        val addr = Prefs.getPreferredAddress(context) ?: return

        Log.d(TAG, "BootReceiver: starting service for $addr")
        RadiaCodeForegroundService.start(context, addr)
    }
}
