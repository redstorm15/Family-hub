package com.foster.kitchencalendar

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences("kitchen_calendar", Context.MODE_PRIVATE)

    /** null = show every calendar that's marked visible on the device. */
    fun selectedCalendars(context: Context): Set<Long>? {
        val p = sp(context)
        if (!p.getBoolean("calendars_custom", false)) return null
        return p.getStringSet("calendars", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun setSelectedCalendars(context: Context, ids: Set<Long>?) {
        sp(context).edit().apply {
            if (ids == null) {
                putBoolean("calendars_custom", false)
                remove("calendars")
            } else {
                putBoolean("calendars_custom", true)
                putStringSet("calendars", ids.map { it.toString() }.toSet())
            }
        }.apply()
    }

    fun weekStartsMonday(context: Context) = sp(context).getBoolean("week_monday", false)
    fun setWeekStartsMonday(context: Context, v: Boolean) = sp(context).edit().putBoolean("week_monday", v).apply()

    fun showTimes(context: Context) = sp(context).getBoolean("show_times", true)
    fun setShowTimes(context: Context, v: Boolean) = sp(context).edit().putBoolean("show_times", v).apply()

    /** How many months away from "now" a given widget is currently showing. */
    fun monthOffset(context: Context, widgetId: Int) = sp(context).getInt("offset_$widgetId", 0)
    fun setMonthOffset(context: Context, widgetId: Int, v: Int) =
        sp(context).edit().putInt("offset_$widgetId", v).apply()

    fun clearWidget(context: Context, widgetId: Int) = sp(context).edit().remove("offset_$widgetId").apply()
}
