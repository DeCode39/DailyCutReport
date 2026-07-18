package com.littleone.dailycutreport

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuickScanWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            manager.updateAppWidget(
                id,
                widgetViews(context, loadingState(), isExpanded(manager.getAppWidgetOptions(id)))
            )
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DailyCutWidgetUpdater.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { DailyCutWidgetUpdater.updateAll(context) } finally { pending.finish() }
        }
    }

    internal companion object {
        const val ACTION_QUICK_SCAN = "com.littleone.dailycutreport.action.QUICK_SCAN"

        fun widgetViews(context: Context, state: TodayWidgetState, expanded: Boolean): RemoteViews {
        val scanIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_QUICK_SCAN
            putExtra(EXTRA_OPEN_SCANNER, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val scanPendingIntent = PendingIntent.getActivity(
            context,
            1001,
            scanIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val todayPendingIntent = PendingIntent.getActivity(
            context,
            1002,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteViews(context.packageName, R.layout.widget_quick_scan).apply {
            setTextViewText(R.id.quick_scan_widget_status, state.balance)
            setTextViewText(R.id.quick_scan_widget_intake, state.intakeProgress)
            setTextViewText(R.id.quick_scan_widget_protein, state.proteinProgress)
            setTextViewText(R.id.quick_scan_widget_spending, state.spendingProgress)
            setProgressBar(R.id.quick_scan_widget_intake_bar, 100, state.intakePercent, false)
            setProgressBar(R.id.quick_scan_widget_protein_bar, 100, state.proteinPercent, false)
            setProgressBar(R.id.quick_scan_widget_spending_bar, 100, state.spendingPercent, false)
            setViewVisibility(R.id.quick_scan_widget_details, if (expanded) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.quick_scan_widget_root, todayPendingIntent)
            setOnClickPendingIntent(R.id.quick_scan_widget_button, scanPendingIntent)
        }
    }

        fun isExpanded(options: Bundle): Boolean =
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) >= 110

        fun loadingState() = TodayWidgetState("Loading…", "", 0, "", 0, "", 0)
    }
}
