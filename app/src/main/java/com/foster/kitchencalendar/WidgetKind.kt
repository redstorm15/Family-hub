package com.foster.kitchencalendar

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context

/** The widgets this app offers. Each has its own provider class (see the manifest). */
enum class WidgetKind(val providerClass: Class<out AppWidgetProvider>) {
    MONTH(MonthWidgetProvider::class.java),
    WEEK(WeekWidgetProvider::class.java),
    DAY(DayWidgetProvider::class.java),
    WEATHER(WeatherWidgetProvider::class.java),
    DATE(DateWidgetProvider::class.java),
    MINI_WEATHER(MiniWeatherWidgetProvider::class.java),
    STICKY(StickyWidgetProvider::class.java);

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, providerClass))

    companion object {
        fun anyPlaced(context: Context) = entries.any { it.ids(context).isNotEmpty() }
    }
}
