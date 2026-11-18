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

    fun compactHeader(context: Context) = sp(context).getBoolean("compact_header", false)
    fun setCompactHeader(context: Context, v: Boolean) = sp(context).edit().putBoolean("compact_header", v).apply()

    /** 0 = normal, 1 = large, 2 = extra large. */
    fun textSize(context: Context) = sp(context).getInt("text_size", 0).coerceIn(0, 2)
    fun setTextSize(context: Context, v: Int) = sp(context).edit().putInt("text_size", v.coerceIn(0, 2)).apply()
    fun textScale(context: Context) = floatArrayOf(1.0f, 1.2f, 1.4f)[textSize(context)]

    /** How many months away from "now" a given widget is currently showing. */
    fun monthOffset(context: Context, widgetId: Int) = sp(context).getInt("offset_$widgetId", 0)
    fun setMonthOffset(context: Context, widgetId: Int, v: Int) =
        sp(context).edit().putInt("offset_$widgetId", v).apply()

    fun clearWidget(context: Context, widgetId: Int) = sp(context).edit().remove("offset_$widgetId").apply()

    // ---------- Week view ----------

    /** 0 = auto (schedule in portrait, columns in landscape), 1 = always schedule, 2 = always columns. */
    fun weekLayout(context: Context) = sp(context).getInt("week_layout", 0).coerceIn(0, 2)
    fun setWeekLayout(context: Context, v: Int) = sp(context).edit().putInt("week_layout", v.coerceIn(0, 2)).apply()

    // ---------- Weather ----------

    fun weatherEnabled(context: Context) = sp(context).getBoolean("weather_on", true)
    fun setWeatherEnabled(context: Context, v: Boolean) = sp(context).edit().putBoolean("weather_on", v).apply()

    fun haUrl(context: Context) = sp(context).getString("ha_url", "")!!
    fun haToken(context: Context) = sp(context).getString("ha_token", "")!!
    fun haEntity(context: Context) = sp(context).getString("ha_entity", "weather.forecast_home")!!
    fun setHomeAssistant(context: Context, url: String, token: String, entity: String) =
        sp(context).edit().putString("ha_url", url.trim()).putString("ha_token", token.trim())
            .putString("ha_entity", entity.trim()).apply()

    /** Fallback location for Open-Meteo. Defaults to Greenwich, CT. */
    fun weatherLatLon(context: Context): Pair<Double, Double> {
        val p = sp(context)
        return p.getFloat("wx_lat", 41.0262f).toDouble() to p.getFloat("wx_lon", -73.6282f).toDouble()
    }
    fun weatherPlace(context: Context) = sp(context).getString("wx_place", "Greenwich, Connecticut, US")!!
    fun setWeatherPlace(context: Context, lat: Double, lon: Double, name: String) =
        sp(context).edit().putFloat("wx_lat", lat.toFloat()).putFloat("wx_lon", lon.toFloat())
            .putString("wx_place", name).apply()

    fun weatherFahrenheit(context: Context) = sp(context).getBoolean("wx_f", true)
    fun setWeatherFahrenheit(context: Context, v: Boolean) = sp(context).edit().putBoolean("wx_f", v).apply()

    fun saveWeatherCache(context: Context, w: WeatherNow) {
        val j = org.json.JSONObject()
            .put("temp", w.temp ?: org.json.JSONObject.NULL).put("sky", w.sky.name).put("label", w.label)
            .put("high", w.high ?: org.json.JSONObject.NULL).put("low", w.low ?: org.json.JSONObject.NULL)
            .put("precip", w.precipPct ?: org.json.JSONObject.NULL).put("unit", w.unit)
            .put("source", w.source).put("at", w.fetchedAt)
        sp(context).edit().putString("wx_cache", j.toString()).apply()
    }

    fun loadWeatherCache(context: Context): WeatherNow? = try {
        sp(context).getString("wx_cache", null)?.let {
            val j = org.json.JSONObject(it)
            fun int(k: String) = if (j.isNull(k)) null else j.optInt(k)
            WeatherNow(
                temp = int("temp"), sky = Sky.valueOf(j.optString("sky", "UNKNOWN")), label = j.optString("label"),
                high = int("high"), low = int("low"), precipPct = int("precip"), unit = j.optString("unit", "°F"),
                source = j.optString("source"), fetchedAt = j.optLong("at"),
            )
        }
    } catch (t: Throwable) { null }

    fun clearWeatherCache(context: Context) = sp(context).edit().remove("wx_cache").apply()

    // ---------- Ambient Weather station ----------

    fun ambientApiKey(context: Context) = sp(context).getString("aw_api", "")!!.trim()
    fun ambientAppKey(context: Context) = sp(context).getString("aw_app", "")!!.trim()
    fun ambientMac(context: Context) = sp(context).getString("aw_mac", "")!!
    fun setAmbient(context: Context, apiKey: String, appKey: String, mac: String) =
        sp(context).edit().putString("aw_api", apiKey.trim()).putString("aw_app", appKey.trim())
            .putString("aw_mac", mac.trim()).apply()

    // ---------- What to wear ----------

    /** -1 = kids run cold, 0 = normal, 1 = kids run warm. */
    fun kidsRun(context: Context) = sp(context).getInt("kids_run", 0).coerceIn(-1, 1)
    fun setKidsRun(context: Context, v: Int) = sp(context).edit().putInt("kids_run", v.coerceIn(-1, 1)).apply()

    fun wearOnToday(context: Context) = sp(context).getBoolean("wear_today", true)
    fun setWearOnToday(context: Context, v: Boolean) = sp(context).edit().putBoolean("wear_today", v).apply()
}
