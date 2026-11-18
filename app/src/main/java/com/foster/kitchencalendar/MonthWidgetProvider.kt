package com.foster.kitchencalendar

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Shared lifecycle for every widget kind. */
abstract class BaseWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        CalendarChangeJob.schedule(context)
        val pending = goAsync()
        WidgetUpdater.request(context, kind, ids) { pending.finish() }
    }

    // Fires on resize, fold/unfold, and when the tablet rotates.
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        val pending = goAsync()
        WidgetUpdater.request(context, kind, intArrayOf(id)) { pending.finish() }
    }

    override fun onEnabled(context: Context) = Scheduler.ensureScheduled(context)

    override fun onDisabled(context: Context) {
        // Only stop the background work once no widget of any kind is left.
        if (!WidgetKind.anyPlaced(context)) Scheduler.cancelAll(context) else Scheduler.ensureScheduled(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach {
            Prefs.clearWidget(context, it)
            ImageStore.deleteAll(context, it)
        }
    }
}

class MonthWidgetProvider : BaseWidgetProvider(WidgetKind.MONTH) {

    companion object {
        const val ACTION_NAV = "com.foster.kitchencalendar.NAV"
        const val ACTION_REFRESH = "com.foster.kitchencalendar.REFRESH"
        const val ACTION_MIDNIGHT = "com.foster.kitchencalendar.MIDNIGHT"
        const val ACTION_RESET_ALL = "com.foster.kitchencalendar.RESET_ALL"
        const val ACTION_TICK = "com.foster.kitchencalendar.TICK"
        const val ACTION_REFRESH_WEATHER = "com.foster.kitchencalendar.REFRESH_WEATHER"
        const val EXTRA_DELTA = "delta"
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
                WidgetUpdater.request(context, WidgetKind.MONTH, intArrayOf(id)) { pending.finish() }
            }
            ACTION_RESET_ALL -> {
                WidgetKind.MONTH.ids(context).forEach { Prefs.setMonthOffset(context, it, 0) }
                val pending = goAsync()
                WidgetUpdater.request(context, WidgetKind.MONTH) { pending.finish() }
            }
            ACTION_TICK -> {
                // Moves the day view's "now" line and "Up next", and refreshes weather.
                val pending = goAsync()
                WidgetUpdater.request(context, WidgetKind.DAY)
                WidgetUpdater.request(context, WidgetKind.WEATHER) { pending.finish() }
            }
            ACTION_REFRESH_WEATHER -> {
                val pending = goAsync()
                WidgetUpdater.refreshWeatherNow(context) { pending.finish() }
            }
            ACTION_MIDNIGHT, ACTION_REFRESH -> {
                val pending = goAsync()
                WidgetUpdater.request(context) { pending.finish() }
            }
        }
    }
}

class WeekWidgetProvider : BaseWidgetProvider(WidgetKind.WEEK)

class DayWidgetProvider : BaseWidgetProvider(WidgetKind.DAY)

class WeatherWidgetProvider : BaseWidgetProvider(WidgetKind.WEATHER)
