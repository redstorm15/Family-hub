package com.foster.kitchencalendar

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class MonthWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_NAV = "com.foster.kitchencalendar.NAV"
        const val ACTION_REFRESH = "com.foster.kitchencalendar.REFRESH"
        const val ACTION_MIDNIGHT = "com.foster.kitchencalendar.MIDNIGHT"
        const val ACTION_RESET_ALL = "com.foster.kitchencalendar.RESET_ALL"
        const val EXTRA_DELTA = "delta"
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        CalendarChangeJob.schedule(context)
        val pending = goAsync()
        WidgetUpdater.request(context, ids) { pending.finish() }
    }

    // Fires on resize and when the tablet rotates.
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        val pending = goAsync()
        WidgetUpdater.request(context, intArrayOf(id)) { pending.finish() }
    }

    override fun onEnabled(context: Context) = Scheduler.ensureScheduled(context)

    override fun onDisabled(context: Context) = Scheduler.cancelAll(context)

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach {
            Prefs.clearWidget(context, it)
            ImageStore.deleteAll(context, it)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_NAV -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val delta = intent.getIntExtra(EXTRA_DELTA, 0)
                val newOffset = if (delta == 0) 0 else Prefs.monthOffset(context, id) + delta
                Prefs.setMonthOffset(context, id, newOffset)
                // Wander off to another month and it drifts back to today after a few minutes.
                Scheduler.scheduleAutoReset(context, newOffset != 0)
                val pending = goAsync()
                WidgetUpdater.request(context, intArrayOf(id)) { pending.finish() }
            }
            ACTION_RESET_ALL -> {
                WidgetUpdater.allIds(context).forEach { Prefs.setMonthOffset(context, it, 0) }
                val pending = goAsync()
                WidgetUpdater.request(context) { pending.finish() }
            }
            ACTION_MIDNIGHT, ACTION_REFRESH -> {
                val pending = goAsync()
                WidgetUpdater.request(context) { pending.finish() }
            }
        }
    }
}
