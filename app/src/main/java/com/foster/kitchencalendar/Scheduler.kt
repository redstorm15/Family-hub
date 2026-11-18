package com.foster.kitchencalendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.ZoneId

/** Midnight redraw (so "today" moves), the day view tick, and the drift-back-to-this-month timer. */
object Scheduler {
    private const val AUTO_RESET_MS = 3 * 60_000L

    fun ensureScheduled(context: Context) {
        CalendarChangeJob.schedule(context)
        scheduleMidnight(context)
    }

    fun scheduleMidnight(context: Context) {
        val zone = ZoneId.systemDefault()
        val next = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 5_000
        alarms(context).setAndAllowWhileIdle(AlarmManager.RTC, next, broadcast(context, MonthWidgetProvider.ACTION_MIDNIGHT, 1))
    }

    fun scheduleAutoReset(context: Context, enabled: Boolean) {
        val pi = broadcast(context, MonthWidgetProvider.ACTION_RESET_ALL, 2)
        if (enabled) {
            alarms(context).set(AlarmManager.RTC, System.currentTimeMillis() + AUTO_RESET_MS, pi)
        } else {
            alarms(context).cancel(pi)
        }
    }

    /** Every ~15 minutes while a day widget exists, so "now" and "Up next" stay current. */
    fun scheduleTick(context: Context, enabled: Boolean) {
        val pi = broadcast(context, MonthWidgetProvider.ACTION_TICK, 3)
        if (enabled) {
            val every = AlarmManager.INTERVAL_FIFTEEN_MINUTES
            alarms(context).setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + every, every, pi)
        } else {
            alarms(context).cancel(pi)
        }
    }

    fun cancelAll(context: Context) {
        alarms(context).cancel(broadcast(context, MonthWidgetProvider.ACTION_TICK, 3))
        alarms(context).cancel(broadcast(context, MonthWidgetProvider.ACTION_MIDNIGHT, 1))
        alarms(context).cancel(broadcast(context, MonthWidgetProvider.ACTION_RESET_ALL, 2))
        CalendarChangeJob.cancel(context)
    }

    fun refreshAll(context: Context) {
        context.sendBroadcast(
            Intent(context, MonthWidgetProvider::class.java).setAction(MonthWidgetProvider.ACTION_REFRESH)
        )
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, MonthWidgetProvider::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
