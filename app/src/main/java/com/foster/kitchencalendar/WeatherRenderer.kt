package com.foster.kitchencalendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class WeatherSpec(
    val bundle: WeatherBundle?,
    val wear: WearAdvice?,
    val use24h: Boolean,
    val zone: ZoneId,
    val textScale: Float,
    val placeName: String,
)

/**
 * The big weather widget: your station's live readings, what to wear, the hourly
 * forecast and the 7-day forecast, on a sky-colored background that follows the
 * conditions and time of day.
 */
object WeatherRenderer {
    private val white = Color.WHITE
    private val muted = Color.argb(200, 255, 255, 255)
    private val cardFill = Color.argb(46, 255, 255, 255)
    private val rainBlue = Color.parseColor("#B5D4F4")

    fun skyColors(sky: Sky, day: Boolean): Pair<Int, Int> {
        fun c(a: String, b: String) = Color.parseColor(a) to Color.parseColor(b)
        if (!day) return when (sky) {
            Sky.CLEAR, Sky.PARTLY -> c("#0C1A3A", "#2A3F6E")
            Sky.RAIN, Sky.STORM -> c("#141A24", "#323B4A")
            else -> c("#1A2130", "#3A4456")
        }
        return when (sky) {
            Sky.CLEAR -> c("#2E6CCF", "#79AEEB")
            Sky.PARTLY -> c("#4479BE", "#93B6DC")
            Sky.CLOUDY, Sky.UNKNOWN, Sky.WIND -> c("#5F6B7A", "#9AA5B2")
            Sky.FOG -> c("#7D8792", "#B6BEC6")
            Sky.RAIN -> c("#3F4E60", "#738396")
            Sky.SNOW -> c("#7F94AC", "#C4D2E0")
            Sky.STORM -> c("#2A303C", "#56606F")
        }
    }

    fun render(w: Int, h: Int, spec: WeatherSpec): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val b = spec.bundle
        val (top, bottom) = skyColors(b?.now?.sky ?: Sky.CLOUDY, b?.now?.isDay ?: true)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val pad = min(w, h) * 0.035f
        val u = min(w, h) * 0.03f * spec.textScale
        if (b == null) {
            val p = text(u * 1.3f, Draw.medium, white).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("Weather unavailable", w / 2f, h / 2f, p)
            val q = text(u * 0.9f, Draw.regular, muted).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("Check Kitchen Calendar → Weather settings", w / 2f, h / 2f + u * 1.8f, q)
            return bmp
        }

        val area = RectF(pad, pad, w - pad, h - pad)
        if (w > h * 0.8f) {   // square-ish screens (like a Fold) get two columns too
            // Landscape: now + what to wear on the left, forecasts on the right.
            val split = area.left + area.width() * 0.4f
            val left = RectF(area.left, area.top, split - pad / 2, area.bottom)
            var y = drawNow(canvas, left, b, spec, u)
            y = drawTiles(canvas, RectF(left.left, y, left.right, left.bottom), b, u, cols = 2)
            // What to wear takes whatever is left of the column, so the pictures can be big.
            spec.wear?.let { drawWear(canvas, RectF(left.left, y, left.right, left.bottom), it, u) }
            val right = RectF(split + pad / 2, area.top, area.right, area.bottom)
            val hourlyBottom = right.top + right.height() * 0.36f
            drawHourly(canvas, RectF(right.left, right.top, right.right, hourlyBottom), b, spec, u)
            drawDaily(canvas, RectF(right.left, hourlyBottom + pad / 2, right.right, right.bottom), b, u)
        } else {
            var y = drawNow(canvas, area, b, spec, u)
            y = drawTiles(canvas, RectF(area.left, y, area.right, area.bottom), b, u, cols = 4)
            spec.wear?.let {
                val wearH = u * 7.2f
                drawWear(canvas, RectF(area.left, y, area.right, y + wearH), it, u)
                y += wearH + u * 0.6f
            }
            val hourlyH = u * 6.6f
            drawHourly(canvas, RectF(area.left, y, area.right, y + hourlyH), b, spec, u)
            drawDaily(canvas, RectF(area.left, y + hourlyH + u * 0.6f, area.right, area.bottom), b, u)
        }
        return bmp
    }

    private fun text(size: Float, face: android.graphics.Typeface, color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; this.color = color }

    private fun card(canvas: Canvas, r: RectF, u: Float) =
        canvas.drawRoundRect(r, u * 0.8f, u * 0.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardFill })

    // ---------- Now ----------

    private fun drawNow(canvas: Canvas, area: RectF, b: WeatherBundle, spec: WeatherSpec, u: Float): Float {
        val now = b.now
        val station = b.station
        val observed = station?.observedAt ?: b.fetchedAt
        val where = station?.name ?: spec.placeName.substringBefore(",")
        val kicker = text(u * 0.85f, Draw.medium, muted).apply { letterSpacing = 0.12f }
        var y = area.top + kicker.textSize
        canvas.drawText(
            Draw.ellipsize("${where.uppercase()} · ${Draw.timeLabel(observed, spec.zone, spec.use24h)}", kicker, area.width()),
            area.left, y, kicker,
        )

        val big = text(u * 5.2f, Draw.bold, white)
        val temp = now.temp?.let { "$it°" } ?: "—"
        y += big.textSize * 1.02f
        canvas.drawText(temp, area.left, y, big)
        val iconS = u * 2.4f
        WeatherIcon.draw(
            canvas, now.sky, area.left + big.measureText(temp) + iconS * 1.4f, y - big.textSize * 0.35f, iconS,
            Color.argb(235, 255, 255, 255), Color.parseColor("#FAC775"), night = !now.isDay,
        )

        val line = text(u * 1.15f, Draw.medium, white)
        y += line.textSize * 1.6f
        val feels = now.feelsLike?.let { " · Feels like $it°" } ?: ""
        canvas.drawText(Draw.ellipsize("${now.label}$feels", line, area.width()), area.left, y, line)
        val sub = text(u * 1.0f, Draw.regular, muted)
        y += sub.textSize * 1.6f
        val hl = listOfNotNull(now.high?.let { "H $it°" }, now.low?.let { "L $it°" }, now.precipPct?.let { "$it% rain" })
        canvas.drawText(hl.joinToString("  ·  "), area.left, y, sub)
        return y + u * 1.1f
    }

    private fun drawTiles(canvas: Canvas, area: RectF, b: WeatherBundle, u: Float, cols: Int): Float {
        val st = b.station ?: return area.top
        fun f(x: Double?) = x?.let { if (b.fahrenheit) it.roundToInt() else ((it - 32) * 5 / 9).roundToInt() }
        val tiles = listOf(
            Triple("WIND", st.windMph?.let { if (it < 1) "Calm" else "${it.roundToInt()} ${compass(st.windDir)}" } ?: "—",
                st.gustMph?.takeIf { it >= 1 }?.let { "gusts ${it.roundToInt()} mph" } ?: "mph"),
            Triple("HUMIDITY", st.humidity?.let { "$it%" } ?: "—", f(st.dewPointF)?.let { "dew $it°" } ?: ""),
            Triple("RAIN TODAY", st.rainTodayIn?.let { "%.2f\"".format(it) } ?: "—",
                if ((st.rainRateIn ?: 0.0) > 0) "%.2f\"/hr now".format(st.rainRateIn) else "Dry now"),
            Triple("UV INDEX", st.uv?.toString() ?: "—", uvLabel(st.uv)),
        )
        val rows = (tiles.size + cols - 1) / cols
        val gap = u * 0.5f
        val tileH = u * 3.4f
        val tileW = (area.width() - gap * (cols - 1)) / cols
        val k = text(u * 0.72f, Draw.medium, muted).apply { letterSpacing = 0.1f }
        val v = text(u * 1.25f, Draw.bold, white)
        val s2 = text(u * 0.8f, Draw.regular, muted)
        tiles.forEachIndexed { i, (key, value, note) ->
            val r = i / cols
            val c = i % cols
            val rect = RectF(
                area.left + c * (tileW + gap), area.top + r * (tileH + gap),
                area.left + c * (tileW + gap) + tileW, area.top + r * (tileH + gap) + tileH,
            )
            card(canvas, rect, u)
            val x = rect.left + u * 0.7f
            val maxW = rect.width() - u * 1.2f
            canvas.drawText(key, x, rect.top + u * 1.05f, k)
            canvas.drawText(Draw.ellipsize(value, v, maxW), x, rect.top + u * 2.3f, v)
            canvas.drawText(Draw.ellipsize(note, s2, maxW), x, rect.top + u * 3.05f, s2)
        }
        return area.top + rows * tileH + (rows - 1) * gap + u * 0.6f
    }

    private fun drawWear(canvas: Canvas, rect: RectF, advice: WearAdvice, u: Float) {
        if (rect.height() < u * 3) return
        card(canvas, rect, u)
        WearIcon.panel(canvas, rect, advice, white, muted)
    }

    // ---------- Forecasts ----------

    private fun drawHourly(canvas: Canvas, rect: RectF, b: WeatherBundle, spec: WeatherSpec, u: Float) {
        if (rect.height() < u * 4 || b.hourly.isEmpty()) return
        card(canvas, rect, u)
        val k = text(u * 0.75f, Draw.medium, muted).apply { letterSpacing = 0.12f }
        canvas.drawText("HOURLY", rect.left + u * 0.8f, rect.top + u * 1.2f, k)
        val now = System.currentTimeMillis()
        val startIdx = b.hourly.indexOfFirst { it.time + 3_600_000 > now }.coerceAtLeast(0)
        val perRow = (floor(rect.width() / (u * 3.4f)).toInt()).coerceIn(6, 24)
        val rowH = u * 5.2f
        val rows = if (rect.height() >= u * 1.6f + rowH * 2 + u * 0.4f) 2 else 1
        val hours = b.hourly.drop(startIdx).take(min(24, perRow * rows))
        if (hours.isEmpty()) return
        val cols = (hours.size + rows - 1) / rows
        val slot = (rect.width() - u * 0.8f) / cols
        val tP = text(u * 0.85f, Draw.medium, muted).apply { textAlign = Paint.Align.CENTER }
        val dP = text(u * 1.1f, Draw.bold, white).apply { textAlign = Paint.Align.CENTER }
        val rP = text(u * 0.75f, Draw.medium, rainBlue).apply { textAlign = Paint.Align.CENTER }
        val block = rows * rowH
        val top0 = rect.top + u * 1.6f + max(0f, (rect.height() - u * 1.6f - block) / 2 - u * 0.4f)
        hours.forEachIndexed { i, hr ->
            val top = top0 + (i / cols) * rowH
            val cx = rect.left + u * 0.4f + slot * (i % cols + 0.5f)
            val label = if (i == 0) "Now" else Draw.timeLabel(hr.time, spec.zone, spec.use24h)
            canvas.drawText(label, cx, top + u * 0.9f, tP)
            WeatherIcon.draw(canvas, hr.sky, cx, top + u * 2.3f, u * 0.75f, white, Color.parseColor("#FAC775"), night = !hr.isDay)
            canvas.drawText("${hr.temp}°", cx, top + u * 3.9f, dP)
            if (hr.precipPct >= 10) canvas.drawText("${hr.precipPct}%", cx, top + u * 4.8f, rP)
        }
    }

    private fun drawDaily(canvas: Canvas, rect: RectF, b: WeatherBundle, u: Float) {
        if (rect.height() < u * 4 || b.daily.isEmpty()) return
        card(canvas, rect, u)
        val k = text(u * 0.75f, Draw.medium, muted).apply { letterSpacing = 0.12f }
        canvas.drawText("7-DAY FORECAST", rect.left + u * 0.8f, rect.top + u * 1.2f, k)
        val today = LocalDate.now(ZoneId.systemDefault())
        val days = b.daily.filter { !it.date.isBefore(today) }
        val listTop = rect.top + u * 1.8f
        val rowH = max(u * 1.7f, (rect.bottom - u * 0.5f - listTop) / min(8, max(1, days.size)))
        val fit = max(1, floor((rect.bottom - u * 0.5f - listTop) / rowH).toInt())
        val shown = days.take(min(fit, 8))
        val lo = shown.minOf { it.low }
        val hi = shown.maxOf { it.high }

        val dayP = text(u * 1.05f, Draw.medium, white)
        val loP = text(u * 1.0f, Draw.medium, muted).apply { textAlign = Paint.Align.RIGHT }
        val hiP = text(u * 1.0f, Draw.bold, white)
        val rP = text(u * 0.8f, Draw.medium, rainBlue)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
        val left = rect.left + u * 0.8f
        val right = rect.right - u * 0.8f
        val dayW = u * 4.2f
        val iconX = left + dayW + u * 0.8f
        val rainX = iconX + u * 1.2f
        val loX = rainX + u * 4.2f
        val barL = loX + u * 0.6f
        val barR = right - u * 2.6f
        shown.forEachIndexed { i, d ->
            val cy = listTop + rowH * (i + 0.5f)
            val name = if (d.date == today) "Today" else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            canvas.drawText(name, left, Draw.centerBaseline(dayP, cy), dayP)
            WeatherIcon.draw(canvas, d.sky, iconX, cy, u * 0.8f, white, Color.parseColor("#FAC775"))
            if (d.precipPct >= 10) canvas.drawText("${d.precipPct}%", rainX, Draw.centerBaseline(rP, cy), rP)
            canvas.drawText("${d.low}°", loX, Draw.centerBaseline(loP, cy), loP)
            canvas.drawText("${d.high}°", barR + u * 0.6f, Draw.centerBaseline(hiP, cy), hiP)
            if (barR > barL) {
                val bh = u * 0.42f
                canvas.drawRoundRect(RectF(barL, cy - bh / 2, barR, cy + bh / 2), bh / 2, bh / 2, track)
                val span = max(1, hi - lo).toFloat()
                val x1 = barL + (d.low - lo) / span * (barR - barL)
                val x2 = barL + (d.high - lo) / span * (barR - barL)
                val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(barL, 0f, barR, 0f, Color.parseColor("#85B7EB"), Color.parseColor("#EF9F27"), Shader.TileMode.CLAMP)
                }
                canvas.drawRoundRect(RectF(x1, cy - bh / 2, max(x2, x1 + bh), cy + bh / 2), bh / 2, bh / 2, bar)
            }
        }
    }

    private fun compass(deg: Int?): String {
        if (deg == null) return ""
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[((deg % 360 + 360) % 360 + 22) / 45 % 8]
    }

    private fun uvLabel(uv: Int?): String = when {
        uv == null -> ""
        uv <= 2 -> "Low"
        uv <= 5 -> "Moderate"
        uv <= 7 -> "High"
        uv <= 10 -> "Very high"
        else -> "Extreme"
    }
}
