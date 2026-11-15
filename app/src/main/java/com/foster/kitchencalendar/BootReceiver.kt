package com.foster.kitchencalendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the calendar watcher and redraws after reboot, app update, or clock/time-zone changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (WidgetUpdater.allIds(context).isEmpty()) return
        Scheduler.ensureScheduled(context)
        val pending = goAsync()
        WidgetUpdater.request(context) { pending.finish() }
    }
}
