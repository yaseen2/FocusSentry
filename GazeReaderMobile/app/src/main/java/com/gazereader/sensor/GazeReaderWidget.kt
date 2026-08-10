package com.gazereader.sensor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

class GazeReaderWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences("GazeReaderWidgetPrefs", Context.MODE_PRIVATE)
            val activeSec = prefs.getInt("active_seconds", 0)
            val targetSec = prefs.getInt("target_seconds", 32400)
            val debtSec = prefs.getInt("debt_seconds", 0)
            val debtFormatted = prefs.getString("debt_formatted", "0m") ?: "0m"

            val views = RemoteViews(context.packageName, R.layout.widget_gaze_reader)

            // Format Active / Target
            val actH = activeSec / 3600
            val actM = (activeSec % 3600) / 60
            val actStr = if (actH > 0) "${actH}h ${actM}m" else "${actM}m"

            val targetH = targetSec / 3600
            val targetM = (targetSec % 3600) / 60
            val targetStr = if (targetH > 0) "${targetH}h ${targetM}m" else "${targetM}m"

            views.setTextViewText(R.id.widgetTvActive, "$actStr / $targetStr")
            views.setProgressBar(R.id.widgetProgressBar, targetSec.coerceAtLeast(1), activeSec.coerceAtMost(targetSec), false)

            // Format Debt
            if (debtSec > 0) {
                val dh = debtSec / 3600
                val dm = (debtSec % 3600) / 60
                val dStr = if (dm > 0) "${dh}h ${dm}m" else "${dh}h"
                views.setTextViewText(R.id.widgetTvDebt, dStr)
                views.setTextColor(R.id.widgetTvDebt, Color.parseColor("#f59e0b")) // Amber
                views.setTextViewText(R.id.widgetTvDebtSub, "Focus Deficit")
            } else {
                views.setTextViewText(R.id.widgetTvDebt, "0m")
                views.setTextColor(R.id.widgetTvDebt, Color.parseColor("#10b981")) // Emerald
                views.setTextViewText(R.id.widgetTvDebtSub, "Debt Free")
            }

            // Launch MainActivity on widget click
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetContainer, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun saveAndRefreshWidget(context: Context, today: TodayMetrics) {
            val prefs = context.getSharedPreferences("GazeReaderWidgetPrefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt("active_seconds", today.active_seconds)
                putInt("target_seconds", today.target_seconds)
                putInt("debt_seconds", today.debt_seconds)
                putString("debt_formatted", today.debt_formatted)
                apply()
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, GazeReaderWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }
}
