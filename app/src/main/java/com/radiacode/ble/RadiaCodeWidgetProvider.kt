package com.radiacode.ble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
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

    companion object {
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, RadiaCodeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.radiacode_widget)

            val last = Prefs.getLastReading(context)
            if (last == null) {
                views.setTextViewText(R.id.widgetDose, "— μSv/h")
                views.setTextViewText(R.id.widgetCps, "— cps")
                views.setTextViewText(R.id.widgetUpdated, "")
            } else {
                views.setTextViewText(R.id.widgetDose, String.format(Locale.US, "%.3f μSv/h", last.uSvPerHour))
                views.setTextViewText(R.id.widgetCps, String.format(Locale.US, "%.1f cps", last.cps))
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                views.setTextViewText(R.id.widgetUpdated, "Updated: ${fmt.format(Date(last.timestampMs))}")
            }

            val launchIntent = Intent(context, MainActivity::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or piFlags)
            views.setOnClickPendingIntent(R.id.widgetTitle, pi)
            views.setOnClickPendingIntent(R.id.widgetDose, pi)
            views.setOnClickPendingIntent(R.id.widgetCps, pi)

            return views
        }
    }
}
