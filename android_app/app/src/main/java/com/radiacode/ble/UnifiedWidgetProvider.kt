package com.radiacode.ble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Unified Widget Provider V2
 * 
 * This provider uses WidgetRenderer for all widget updates, ensuring
 * that widgets render identically to what users see in the preview.
 * 
 * KEY PRINCIPLE: Same rendering code for preview and widget.
 */
class UnifiedWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            try {
                val options = appWidgetManager.getAppWidgetOptions(id)
                appWidgetManager.updateAppWidget(id, buildViews(context, id, options))
            } catch (e: Exception) {
                android.util.Log.e("RadiaCode", "UnifiedWidget onUpdate error for $id", e)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        try {
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId, newOptions))
        } catch (e: Exception) {
            android.util.Log.e("RadiaCode", "UnifiedWidget onOptionsChanged error", e)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            Prefs.deleteWidgetConfig(context, id)
        }
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        /**
         * Update all widgets of this type.
         */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, UnifiedWidgetProvider::class.java))
            if (ids.isEmpty()) return

            ids.forEach { id ->
                val options = mgr.getAppWidgetOptions(id)
                try {
                    mgr.updateAppWidget(id, buildViews(context, id, options))
                } catch (e: Exception) {
                    android.util.Log.e("RadiaCode", "UnifiedWidget updateAll error", e)
                }
            }
        }

        /**
         * Update a specific widget by ID.
         */
        fun updateWidget(context: Context, widgetId: Int) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val options = mgr.getAppWidgetOptions(widgetId)
            try {
                mgr.updateAppWidget(widgetId, buildViews(context, widgetId, options))
            } catch (e: Exception) {
                android.util.Log.e("RadiaCode", "UnifiedWidget updateWidget error for $widgetId", e)
            }
        }

        /**
         * Update all widgets bound to a specific device.
         */
        fun updateForDevice(context: Context, deviceId: String?) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, UnifiedWidgetProvider::class.java))

            ids.forEach { id ->
                val config = Prefs.getWidgetConfig(context, id)
                if (config == null || config.deviceId == deviceId) {
                    val options = mgr.getAppWidgetOptions(id)
                    try {
                        mgr.updateAppWidget(id, buildViews(context, id, options))
                    } catch (e: Exception) {
                        android.util.Log.e("RadiaCode", "UnifiedWidget updateForDevice error", e)
                    }
                }
            }
        }

        /**
         * Build RemoteViews using the unified WidgetRenderer.
         * This uses THE EXACT SAME rendering logic as the preview.
         */
        private fun buildViews(context: Context, widgetId: Int, options: Bundle?): RemoteViews {
            // Get config (or create default)
            val config = Prefs.getWidgetConfig(context, widgetId) ?: WidgetConfig.createDefault(widgetId)

            // Get widget dimensions
            val density = context.resources.displayMetrics.density
            val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) ?: 250
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120) ?: 120
            val widgetWidth = (minWidth * density).toInt()
            val widgetHeight = (minHeight * density).toInt()

            // Load data for the widget
            val data = WidgetRenderer.loadWidgetData(context, config.deviceId)

            // Render using unified renderer - SAME code as preview uses
            val views = WidgetRenderer.renderToRemoteViews(context, config, data, widgetWidth, widgetHeight)

            // Add click handler to launch app
            val launchIntent = Intent(context, MainActivity::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(context, widgetId, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)

            return views
        }
    }
}
