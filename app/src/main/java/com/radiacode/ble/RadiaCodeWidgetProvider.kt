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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RadiaCodeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context))
    }

    companion object {
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, RadiaCodeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            
            ids.forEach { id ->
                mgr.updateAppWidget(id, buildViews(context))
            }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.radiacode_widget_simple)

            val last = Prefs.getLastReading(context)
            val isConnected = last != null && (System.currentTimeMillis() - last.timestampMs) < 30000
            
            if (last == null) {
                views.setTextViewText(R.id.widgetDose, "—")
                views.setTextViewText(R.id.widgetCps, "—")
                views.setTextViewText(R.id.widgetStatus, "No data")
            } else {
                // Format dose value
                val doseStr = if (last.uSvPerHour >= 1) {
                    String.format(Locale.US, "%.2f", last.uSvPerHour)
                } else {
                    String.format(Locale.US, "%.3f", last.uSvPerHour)
                }
                views.setTextViewText(R.id.widgetDose, doseStr)
                
                // Format CPS value
                val cpsStr = if (last.cps >= 100) {
                    String.format(Locale.US, "%.0f", last.cps)
                } else {
                    String.format(Locale.US, "%.1f", last.cps)
                }
                views.setTextViewText(R.id.widgetCps, cpsStr)
                
                // Status/Timestamp
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                val statusText = if (isConnected) "LIVE • ${fmt.format(Date(last.timestampMs))}" else fmt.format(Date(last.timestampMs))
                views.setTextViewText(R.id.widgetStatus, statusText)
            }

            // Click handler to launch app
            val launchIntent = Intent(context, MainActivity::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)

            return views
        }
    }
}
