package com.radiacode.ble

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    private companion object {
        private const val TAG = "RadiaCode"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "BootReceiver: received action=$action")
        
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_USER_UNLOCKED) {
            Log.d(TAG, "BootReceiver: ignoring action=$action")
            return
        }

        val autoConnect = Prefs.isAutoConnectEnabled(context)
        val addr = Prefs.getPreferredAddress(context)
        
        Log.d(TAG, "BootReceiver: autoConnect=$autoConnect, address=$addr")
        
        if (!autoConnect) {
            Log.d(TAG, "BootReceiver: auto-connect disabled, skipping")
            return
        }
        
        if (addr.isNullOrBlank()) {
            Log.d(TAG, "BootReceiver: no preferred address, skipping")
            return
        }

        // Check permissions
        val hasBlePermission = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasNotifPermission = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        
        Log.d(TAG, "BootReceiver: hasBlePermission=$hasBlePermission, hasNotifPermission=$hasNotifPermission")
        
        if (!hasBlePermission || !hasNotifPermission) {
            Log.w(TAG, "BootReceiver: missing permissions, will try starting service anyway")
        }

        Log.d(TAG, "BootReceiver: starting service for $addr")
        RadiaCodeForegroundService.start(context, addr)
    }
}
