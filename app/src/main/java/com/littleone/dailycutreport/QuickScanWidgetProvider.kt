package com.littleone.dailycutreport

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuickScanWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, widgetViews(context, "Loading…")) }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DailyCutWidgetUpdater.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    internal companion object {
        const val ACTION_QUICK_SCAN = "com.littleone.dailycutreport.action.QUICK_SCAN"

        fun widgetViews(context: Context, deficitLabel: String): RemoteViews {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_QUICK_SCAN
            putExtra(EXTRA_OPEN_SCANNER, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteViews(context.packageName, R.layout.widget_quick_scan).apply {
            setTextViewText(R.id.quick_scan_widget_status, deficitLabel)
            setOnClickPendingIntent(R.id.quick_scan_widget_root, pendingIntent)
            setOnClickPendingIntent(R.id.quick_scan_widget_button, pendingIntent)
        }
    }
    }
}
