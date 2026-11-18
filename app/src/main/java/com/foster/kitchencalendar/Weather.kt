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
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM, WIND, UNKNOWN }

/** Current conditions as shown on cards: merged from station, Home Assistant and forecast. */
data class WeatherNow(
    val temp: Int?,
    val sky: Sky,
    val label: String,
    val high: Int?,
    val low: Int?,
    val precipPct: Int?,
    val unit: String,          // "°F" or "°C"
    val source: String,        // e.g. "your station + Open-Meteo"
    val fetchedAt: Long,
    val feelsLike: Int? = null,
    val isDay: Boolean = true,
)

/** Live readings from an Ambient Weather station (Ambient reports °F, mph, inches). */
data class StationNow(
    val name: String,
    val tempF: Double?,
    val feelsLikeF: Double?,
    val humidity: Int?,
    val dewPointF: Double?,
    val windMph: Double?,
    val gustMph: Double?,
    val windDir: Int?,
    val rainTodayIn: Double?,
    val rainRateIn: Double?,
    val uv: Int?,
    val lat: Double?,
    val lon: Double?,
    val observedAt: Long,
    val indoor: IndoorNow? = null,
)

/**
 * Indoor readings from the station console and an AQIN / PM25IN sensor, if present.
 * Ambient's field names vary by sensor, so these are matched loosely (see [WeatherRepository]).
 */
data class IndoorNow(
    val tempF: Double?,
    val humidity: Int?,
    val pm25: Double?,
    val pm25Day: Double?,
    val pm10: Double?,
    val co2: Int?,
    val outdoorPm25: Double?,
) {
    val hasAir get() = pm25 != null || co2 != null
}

data class HourForecast(val time: Long, val temp: Int, val apparent: Int, val sky: Sky, val precipPct: Int, val isDay: Boolean, val uv: Double?)
data class DayForecast(val date: LocalDate, val sky: Sky, val high: Int, val low: Int, val precipPct: Int, val uvMax: Double?, val snow: Boolean)

data class Forecast(
    val currentTemp: Int?, val currentApparent: Int?, val currentSky: Sky, val currentLabel: String, val isDay: Boolean,
    val hourly: List<HourForecast>, val daily: List<DayForecast>, val zone: ZoneId, val fetchedAt: Long,
)

/** Everything the weather widget and the Today card need, fetched together. */
data class WeatherBundle(
    val now: WeatherNow,
    val station: StationNow?,
    val hourly: List<HourForecast>,
    val daily: List<DayForecast>,
    val fahrenheit: Boolean,
    val fetchedAt: Long,
)

/**
 * Weather sources, best first:
 *  - Right now: your Ambient Weather station → Home Assistant → Open-Meteo.
 *  - Forecasts (high/low, hourly, 7-day): Open-Meteo, at the station's location when known.
 * Results are cached so brief outages don't blank the widgets.
 */
object WeatherRepository {
    private const val TAG = "KitchenCalendar"
    private const val BUNDLE_FRESH_MS = 4 * 60_000L
    private const val FORECAST_FRESH_MS = 30 * 60_000L
    private const val STALE_OK_MS = 6 * 60 * 60_000L

    @Volatile private var bundleCache: WeatherBundle? = null
    @Volatile private var forecastCache: Forecast? = null
    @Volatile private var stationCache: Pair<StationNow, Long>? = null
    private const val STATION_KEEP_MS = 30 * 60_000L

    /** Called from background threads only (does network I/O). */
    fun bundle(context: Context, forceRefresh: Boolean = false): WeatherBundle? {
        if (!Prefs.weatherEnabled(context)) return null
        val now = System.currentTimeMillis()
        bundleCache?.let { if (!forceRefresh && now - it.fetchedAt < BUNDLE_FRESH_MS) return it }

        val fahrenheit = Prefs.weatherFahrenheit(context)
        // One failed request shouldn't drop the station tiles: keep the last good reading a while.
        val station = fetchAmbient(context)?.also { stationCache = it to now }
            ?: stationCache?.takeIf { now - it.second < STATION_KEEP_MS && Prefs.ambientApiKey(context).isNotEmpty() }?.first
        val (savedLat, savedLon) = Prefs.weatherLatLon(context)
        val lat = station?.lat ?: savedLat
        val lon = station?.lon ?: savedLon

        val cachedFc = forecastCache?.takeIf { now - it.fetchedAt < FORECAST_FRESH_MS && !forceRefresh }
        val forecast = cachedFc ?: fetchForecast(lat, lon, fahrenheit)?.also { forecastCache = it }
            ?: forecastCache?.takeIf { now - it.fetchedAt < STALE_OK_MS }
        val ha = if (station == null) fetchHomeAssistant(context) else null

        if (station == null && ha == null && forecast == null) {
            return bundleCache?.takeIf { now - it.fetchedAt < STALE_OK_MS }
        }

        fun conv(f: Double?): Int? = f?.let { if (fahrenheit) it.roundToInt() else ((it - 32) * 5 / 9).roundToInt() }
        val today = forecast?.daily?.firstOrNull()
        val raining = (station?.rainRateIn ?: 0.0) > 0.0
        val (sky, label) = when {
            raining -> Sky.RAIN to "Raining"
            ha != null -> ha.sky to ha.label
            forecast != null -> forecast.currentSky to forecast.currentLabel
            else -> Sky.UNKNOWN to "—"
        }
        val sources = listOfNotNull(
            if (station != null) "your station" else null,
            if (ha != null) "Home Assistant" else null,
            if (forecast != null) "Open-Meteo" else null,
        ).joinToString(" + ")
        val nowW = WeatherNow(
            temp = conv(station?.tempF) ?: ha?.temp ?: forecast?.currentTemp,
            sky = sky, label = label,
            high = today?.high ?: ha?.high,
            low = today?.low ?: ha?.low,
            precipPct = today?.precipPct ?: ha?.precipPct,
            unit = if (station != null || forecast != null) (if (fahrenheit) "°F" else "°C") else (ha?.unit ?: "°F"),
            source = sources,
            fetchedAt = now,
            feelsLike = conv(station?.feelsLikeF) ?: forecast?.currentApparent,
            isDay = forecast?.isDay ?: true,
        )
        val b = WeatherBundle(nowW, station, forecast?.hourly.orEmpty(), forecast?.daily.orEmpty(), fahrenheit, now)
        bundleCache = b
        Prefs.saveWeatherCache(context, nowW)
        return b
    }

    /** Current conditions only (Today widget). */
    fun get(context: Context, forceRefresh: Boolean = false): WeatherNow? {
        if (!Prefs.weatherEnabled(context)) return null
        return bundle(context, forceRefresh)?.now
            ?: Prefs.loadWeatherCache(context)?.takeIf { System.currentTimeMillis() - it.fetchedAt < STALE_OK_MS }
    }

    /** Drop cached results (after weather settings change). */
    fun invalidate(context: Context) {
        bundleCache = null
        forecastCache = null
        stationCache = null
        Prefs.clearWeatherCache(context)
    }

    // ---------- Ambient Weather station ----------

    private fun fetchAmbient(context: Context): StationNow? {
        val apiKey = Prefs.ambientApiKey(context)
        val appKey = Prefs.ambientAppKey(context)
        if (apiKey.isEmpty() || appKey.isEmpty()) return null
        return try {
            val url = "https://rt.ambientweather.net/v1/devices?apiKey=" + URLEncoder.encode(apiKey, "UTF-8") +
                "&applicationKey=" + URLEncoder.encode(appKey, "UTF-8")
            val devices = JSONArray(http(url))
            if (devices.length() == 0) return null
            val wantMac = Prefs.ambientMac(context).trim()
            var dev = devices.getJSONObject(0)
            if (wantMac.isNotEmpty()) {
                for (i in 0 until devices.length()) {
                    val d = devices.getJSONObject(i)
                    if (d.optString("macAddress").equals(wantMac, ignoreCase = true)) { dev = d; break }
                }
            }
            val data = dev.getJSONObject("lastData")
            val info = dev.optJSONObject("info")
            val coords = info?.optJSONObject("coords")?.optJSONObject("coords")
            StationNow(
                name = info?.optString("name").orEmpty().ifBlank { "Your station" },
                tempF = data.optDoubleOrNull("tempf"),
                feelsLikeF = data.optDoubleOrNull("feelsLike"),
                humidity = data.optDoubleOrNull("humidity")?.roundToInt(),
                dewPointF = data.optDoubleOrNull("dewPoint"),
                windMph = data.optDoubleOrNull("windspeedmph"),
                gustMph = data.optDoubleOrNull("windgustmph"),
                windDir = data.optDoubleOrNull("winddir")?.roundToInt(),
                rainTodayIn = data.optDoubleOrNull("dailyrainin"),
                rainRateIn = data.optDoubleOrNull("hourlyrainin"),
                uv = data.optDoubleOrNull("uv")?.roundToInt(),
                lat = coords?.optDoubleOrNull("lat"),
                lon = coords?.optDoubleOrNull("lon"),
                observedAt = data.optLong("dateutc", System.currentTimeMillis()),
                indoor = indoorFrom(data),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Ambient Weather fetch failed", t)
            null
        }
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

    // ---------- Open-Meteo forecast ----------

    private fun fetchForecast(lat: Double, lon: Double, fahrenheit: Boolean): Forecast? = try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
            "&hourly=temperature_2m,apparent_temperature,precipitation_probability,weather_code,is_day,uv_index" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max,snowfall_sum" +
            "&temperature_unit=${if (fahrenheit) "fahrenheit" else "celsius"}" +
            "&wind_speed_unit=mph&precipitation_unit=inch&timezone=auto&forecast_days=8&timeformat=unixtime"
        val json = JSONObject(http(url))
        val zone = try { ZoneId.of(json.optString("timezone")) } catch (t: Throwable) { ZoneId.systemDefault() }
        val cur = json.getJSONObject("current")
        val (cSky, cLabel) = wmoSky(cur.optInt("weather_code", -1))

        val h = json.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hourly = (0 until ht.length()).map { i ->
            HourForecast(
                time = ht.getLong(i) * 1000,
                temp = h.getJSONArray("temperature_2m").optDoubleOrNullAt(i)?.roundToInt() ?: 0,
                apparent = h.getJSONArray("apparent_temperature").optDoubleOrNullAt(i)?.roundToInt() ?: 0,
                sky = wmoSky(h.getJSONArray("weather_code").optInt(i, -1)).first,
                precipPct = h.getJSONArray("precipitation_probability").optDoubleOrNullAt(i)?.roundToInt() ?: 0,
                isDay = h.getJSONArray("is_day").optInt(i, 1) == 1,
                uv = h.getJSONArray("uv_index").optDoubleOrNullAt(i),
            )
        }

        val d = json.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val daily = (0 until dt.length()).map { i ->
            val code = d.getJSONArray("weather_code").optInt(i, -1)
            DayForecast(
                date = java.time.Instant.ofEpochSecond(dt.getLong(i)).atZone(zone).toLocalDate(),
                sky = wmoSky(code).first,
                high = d.getJSONArray("temperature_2m_max").optDoubleOrNullAt(i)?.roundToInt() ?: 0,
                low = d.getJSONArray("temperature_2m_min").optDoubleOrNullAt(i)?.roundToInt() ?: 0,
                precipPct = d.getJSONArray("precipitation_probability_max").optDoubleOrNullAt(i)?.roundToInt() ?: 0,
                uvMax = d.getJSONArray("uv_index_max").optDoubleOrNullAt(i),
                snow = (d.getJSONArray("snowfall_sum").optDoubleOrNullAt(i) ?: 0.0) > 0.0 || wmoSky(code).first == Sky.SNOW,
            )
        }
        Forecast(
            currentTemp = cur.optDoubleOrNull("temperature_2m")?.roundToInt(),
            currentApparent = cur.optDoubleOrNull("apparent_temperature")?.roundToInt(),
            currentSky = cSky, currentLabel = cLabel, isDay = cur.optInt("is_day", 1) == 1,
            hourly = hourly, daily = daily, zone = zone, fetchedAt = System.currentTimeMillis(),
        )
    } catch (t: Throwable) {
        Log.w(TAG, "Open-Meteo forecast failed", t)
        null
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

    /**
     * Console: tempinf / humidityin. AQIN: pm25_in_aqin, pm25_in_24h_aqin, pm10_in_aqin, co2_in_aqin
     * (and its own temp/humidity). PM25IN: pm25_in, pm25_in_24h. Matched by pattern so either works.
     */
    private fun indoorFrom(data: JSONObject): IndoorNow? {
        val keys = data.keys().asSequence().toList()
        fun first(pred: (String) -> Boolean): Double? =
            keys.filter { pred(it.lowercase()) }.sorted().firstNotNullOfOrNull { data.optDoubleOrNull(it) }
        val pm25 = first { it.startsWith("pm25_in") && "24h" !in it }
        val pm25Day = first { it.startsWith("pm25_in") && "24h" in it }
        val pm10 = first { it.startsWith("pm10_in") && "24h" !in it }
        val co2 = first { it.startsWith("co2") && "24h" !in it }
        val temp = data.optDoubleOrNull("tempinf") ?: first { it.startsWith("pm_in_temp") }
        val hum = data.optDoubleOrNull("humidityin") ?: first { it.startsWith("pm_in_humidity") }
        if (listOf(pm25, co2, temp, hum).all { it == null }) return null
        return IndoorNow(temp, hum?.roundToInt(), pm25, pm25Day, pm10, co2?.roundToInt(), data.optDoubleOrNull("pm25"))
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
    fun draw(canvas: Canvas, sky: Sky, cx: Float, cy: Float, s: Float, color: Int, accent: Int, night: Boolean = false) {
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
        fun moon(x: Float, y: Float, r: Float) {
            val disc = Path().apply { addCircle(x, y, r * 0.55f, Path.Direction.CW) }
            val bite = Path().apply { addCircle(x + r * 0.3f, y - r * 0.2f, r * 0.45f, Path.Direction.CW) }
            disc.op(bite, Path.Op.DIFFERENCE)
            canvas.drawPath(disc, sunPaint)
        }
        when (sky) {
            Sky.CLEAR -> if (night) moon(cx, cy, s) else sun(cx, cy, s, sunPaint, sunStroke)
            Sky.PARTLY -> {
                if (night) moon(cx - s * 0.3f, cy - s * 0.3f, s * 0.8f) else sun(cx - s * 0.3f, cy - s * 0.3f, s * 0.75f, sunPaint, sunStroke)
                cloud(cx + s * 0.1f, cy + s * 0.1f, s * 0.8f, f)
            }
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
