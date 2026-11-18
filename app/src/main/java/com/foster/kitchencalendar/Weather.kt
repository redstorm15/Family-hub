package com.foster.kitchencalendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM, WIND, UNKNOWN }

data class WeatherNow(
    val temp: Int?,
    val sky: Sky,
    val label: String,
    val high: Int?,
    val low: Int?,
    val precipPct: Int?,
    val unit: String,          // "°F" or "°C"
    val source: String,        // "Home Assistant" or "Open-Meteo"
    val fetchedAt: Long,
)

/**
 * Weather for the day widget. Tries Home Assistant first (your own weather entity);
 * if that isn't set up or fails, falls back to Open-Meteo (free, no key) for the saved
 * location. Results are cached so a brief outage doesn't blank the widget.
 */
object WeatherRepository {
    private const val TAG = "KitchenCalendar"
    private const val FRESH_MS = 30 * 60_000L
    private const val STALE_OK_MS = 6 * 60 * 60_000L

    @Volatile private var cached: WeatherNow? = null

    /** Called from the background updater thread. */
    fun get(context: Context, forceRefresh: Boolean = false): WeatherNow? {
        if (!Prefs.weatherEnabled(context)) return null
        val now = System.currentTimeMillis()
        val c = cached ?: Prefs.loadWeatherCache(context)?.also { cached = it }
        if (!forceRefresh && c != null && now - c.fetchedAt < FRESH_MS) return c

        val fresh = fetchHomeAssistant(context) ?: fetchOpenMeteo(context)
        if (fresh != null) {
            cached = fresh
            Prefs.saveWeatherCache(context, fresh)
            return fresh
        }
        return c?.takeIf { now - it.fetchedAt < STALE_OK_MS }
    }

    /** Drop cached results (after weather settings change). */
    fun invalidate(context: Context) {
        cached = null
        Prefs.clearWeatherCache(context)
    }

    // ---------- Home Assistant ----------

    private fun fetchHomeAssistant(context: Context): WeatherNow? {
        val base = Prefs.haUrl(context).trim().trimEnd('/')
        val token = Prefs.haToken(context).trim()
        val entity = Prefs.haEntity(context).trim()
        if (base.isEmpty() || token.isEmpty() || entity.isEmpty()) return null
        return try {
            val state = JSONObject(http("$base/api/states/$entity", token = token))
            val attrs = state.getJSONObject("attributes")
            val condition = state.optString("state")
            if (condition == "unavailable" || condition == "unknown") return null
            val unit = attrs.optString("temperature_unit", "°F").ifEmpty { "°F" }
            var high: Int? = null; var low: Int? = null; var precip: Int? = null

            // Today's high/low via the get_forecasts service (HA 2023.9+); older installs
            // still include a forecast attribute.
            val forecast: JSONArray? = try {
                val body = JSONObject().put("entity_id", entity).put("type", "daily").toString()
                val resp = JSONObject(http("$base/api/services/weather/get_forecasts?return_response", token = token, postBody = body))
                resp.optJSONObject("service_response")?.optJSONObject(entity)?.optJSONArray("forecast")
            } catch (t: Throwable) {
                attrs.optJSONArray("forecast")
            }
            forecast?.optJSONObject(0)?.let { day ->
                high = day.optDoubleOrNull("temperature")?.roundToInt()
                low = day.optDoubleOrNull("templow")?.roundToInt()
                precip = day.optDoubleOrNull("precipitation_probability")?.roundToInt()
            }
            val (sky, label) = haSky(condition)
            WeatherNow(
                temp = attrs.optDoubleOrNull("temperature")?.roundToInt(),
                sky = sky, label = label, high = high, low = low, precipPct = precip,
                unit = unit, source = "Home Assistant", fetchedAt = System.currentTimeMillis(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Home Assistant weather failed, falling back", t)
            null
        }
    }

    private fun haSky(state: String): Pair<Sky, String> = when (state) {
        "sunny", "clear-night" -> Sky.CLEAR to "Clear"
        "partlycloudy" -> Sky.PARTLY to "Partly cloudy"
        "cloudy" -> Sky.CLOUDY to "Cloudy"
        "fog" -> Sky.FOG to "Fog"
        "rainy" -> Sky.RAIN to "Rain"
        "pouring" -> Sky.RAIN to "Heavy rain"
        "snowy" -> Sky.SNOW to "Snow"
        "snowy-rainy" -> Sky.SNOW to "Sleet"
        "hail" -> Sky.SNOW to "Hail"
        "lightning", "lightning-rainy" -> Sky.STORM to "Thunderstorms"
        "windy", "windy-variant" -> Sky.WIND to "Windy"
        else -> Sky.UNKNOWN to state.replaceFirstChar { it.uppercase() }
    }

    // ---------- Open-Meteo fallback ----------

    private fun fetchOpenMeteo(context: Context): WeatherNow? {
        val (lat, lon) = Prefs.weatherLatLon(context)
        val fahrenheit = Prefs.weatherFahrenheit(context)
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&temperature_unit=${if (fahrenheit) "fahrenheit" else "celsius"}" +
                "&timezone=auto&forecast_days=1"
            val json = JSONObject(http(url))
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val (sky, label) = wmoSky(current.optInt("weather_code", -1))
            WeatherNow(
                temp = current.optDoubleOrNull("temperature_2m")?.roundToInt(),
                sky = sky, label = label,
                high = daily.optJSONArray("temperature_2m_max")?.optDoubleOrNullAt(0)?.roundToInt(),
                low = daily.optJSONArray("temperature_2m_min")?.optDoubleOrNullAt(0)?.roundToInt(),
                precipPct = daily.optJSONArray("precipitation_probability_max")?.optDoubleOrNullAt(0)?.roundToInt(),
                unit = if (fahrenheit) "°F" else "°C", source = "Open-Meteo", fetchedAt = System.currentTimeMillis(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Open-Meteo weather failed", t)
            null
        }
    }

    /** WMO weather interpretation codes used by Open-Meteo. */
    private fun wmoSky(code: Int): Pair<Sky, String> = when (code) {
        0 -> Sky.CLEAR to "Clear"
        1 -> Sky.CLEAR to "Mostly clear"
        2 -> Sky.PARTLY to "Partly cloudy"
        3 -> Sky.CLOUDY to "Cloudy"
        45, 48 -> Sky.FOG to "Fog"
        51, 53, 55, 56, 57 -> Sky.RAIN to "Drizzle"
        61, 63, 66, 80, 81 -> Sky.RAIN to "Rain"
        65, 67, 82 -> Sky.RAIN to "Heavy rain"
        71, 73, 75, 77, 85, 86 -> Sky.SNOW to "Snow"
        95, 96, 99 -> Sky.STORM to "Thunderstorms"
        else -> Sky.UNKNOWN to "—"
    }

    /** Looks up a city ("Greenwich, CT") or ZIP. Returns (lat, lon, display name) or null. */
    fun geocode(query: String): Triple<Double, Double, String>? {
        val parts = query.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val name = parts[0]
        val region = parts.getOrNull(1)?.let { US_STATES[it.uppercase()] ?: it }
        return try {
            val url = "https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name=" +
                URLEncoder.encode(name, "UTF-8")
            val results = JSONObject(http(url)).optJSONArray("results") ?: return null
            var pick: JSONObject? = null
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                if (region == null || r.optString("admin1").equals(region, ignoreCase = true) ||
                    r.optString("country_code").equals(region, ignoreCase = true)
                ) { pick = r; break }
            }
            pick = pick ?: results.optJSONObject(0) ?: return null
            val display = listOf(pick.optString("name"), pick.optString("admin1"), pick.optString("country_code"))
                .filter { it.isNotBlank() }.joinToString(", ")
            Triple(pick.getDouble("latitude"), pick.getDouble("longitude"), display)
        } catch (t: Throwable) {
            Log.w(TAG, "Geocoding failed", t)
            null
        }
    }

    private fun http(url: String, token: String? = null, postBody: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 8_000
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.setRequestProperty("Accept", "application/json")
            if (postBody != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(postBody.toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun JSONArray.optDoubleOrNullAt(i: Int): Double? =
        if (i < length() && !isNull(i)) optDouble(i).takeIf { !it.isNaN() } else null

    private val US_STATES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California",
        "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware", "FL" to "Florida", "GA" to "Georgia",
        "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa",
        "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
        "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi", "MO" to "Missouri",
        "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada", "NH" to "New Hampshire", "NJ" to "New Jersey",
        "NM" to "New Mexico", "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio",
        "OK" to "Oklahoma", "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
        "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont",
        "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
        "DC" to "District of Columbia",
    )
}

/** Simple weather glyphs drawn to match the motif style. */
object WeatherIcon {
    fun draw(canvas: Canvas, sky: Sky, cx: Float, cy: Float, s: Float, color: Int, accent: Int) {
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; this.color = color; strokeWidth = s * 0.1f; strokeCap = Paint.Cap.ROUND
        }
        fun sun(x: Float, y: Float, r: Float, paint: Paint, stroke: Paint) {
            canvas.drawCircle(x, y, r * 0.45f, paint)
            for (i in 0 until 8) {
                val a = i * PI / 4
                canvas.drawLine(
                    x + cos(a).toFloat() * r * 0.62f, y + sin(a).toFloat() * r * 0.62f,
                    x + cos(a).toFloat() * r * 0.9f, y + sin(a).toFloat() * r * 0.9f, stroke,
                )
            }
        }
        fun cloud(x: Float, y: Float, r: Float, paint: Paint) {
            canvas.drawCircle(x - r * 0.42f, y + r * 0.1f, r * 0.38f, paint)
            canvas.drawCircle(x + r * 0.05f, y - r * 0.15f, r * 0.5f, paint)
            canvas.drawCircle(x + r * 0.5f, y + r * 0.12f, r * 0.34f, paint)
            canvas.drawRoundRect(RectF(x - r * 0.8f, y + r * 0.05f, x + r * 0.84f, y + r * 0.46f), r * 0.2f, r * 0.2f, paint)
        }
        val sunPaint = Paint(f).apply { this.color = accent }
        val sunStroke = Paint(st).apply { this.color = accent }
        when (sky) {
            Sky.CLEAR -> sun(cx, cy, s, sunPaint, sunStroke)
            Sky.PARTLY -> { sun(cx - s * 0.3f, cy - s * 0.3f, s * 0.75f, sunPaint, sunStroke); cloud(cx + s * 0.1f, cy + s * 0.1f, s * 0.8f, f) }
            Sky.CLOUDY, Sky.UNKNOWN -> cloud(cx, cy, s, f)
            Sky.FOG -> {
                cloud(cx, cy - s * 0.2f, s * 0.85f, f)
                for (i in 0 until 2) canvas.drawLine(cx - s * 0.7f, cy + s * (0.5f + i * 0.25f), cx + s * 0.7f, cy + s * (0.5f + i * 0.25f), st)
            }
            Sky.RAIN -> {
                cloud(cx, cy - s * 0.25f, s * 0.85f, f)
                for (i in -1..1) canvas.drawLine(cx + i * s * 0.35f, cy + s * 0.4f, cx + i * s * 0.35f - s * 0.12f, cy + s * 0.75f, st)
            }
            Sky.SNOW -> {
                cloud(cx, cy - s * 0.25f, s * 0.85f, f)
                for (i in -1..1) canvas.drawCircle(cx + i * s * 0.35f, cy + s * 0.6f, s * 0.08f, f)
            }
            Sky.STORM -> {
                cloud(cx, cy - s * 0.25f, s * 0.85f, f)
                val bolt = Path().apply {
                    moveTo(cx + s * 0.05f, cy + s * 0.25f); lineTo(cx - s * 0.18f, cy + s * 0.6f)
                    lineTo(cx + s * 0.02f, cy + s * 0.6f); lineTo(cx - s * 0.12f, cy + s * 0.95f)
                    lineTo(cx + s * 0.25f, cy + s * 0.5f); lineTo(cx + s * 0.05f, cy + s * 0.5f); close()
                }
                canvas.drawPath(bolt, sunPaint)
            }
            Sky.WIND -> for (i in 0 until 3) {
                val y = cy + (i - 1) * s * 0.35f
                val p = Path().apply { moveTo(cx - s * 0.8f, y); quadTo(cx + s * 0.4f, y - s * 0.05f, cx + s * 0.6f, y - s * 0.2f) }
                canvas.drawPath(p, st)
            }
        }
    }
}
