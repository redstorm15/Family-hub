package com.foster.kitchencalendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.min
import kotlin.math.roundToInt

/** How the air is doing, from PM2.5 (US EPA 2024 breakpoints, µg/m³). */
enum class AirLevel(val label: String, val color: Int, val top: Int, val bottom: Int) {
    GOOD("Good", Color.parseColor("#1D9E75"), Color.parseColor("#2E8B6E"), Color.parseColor("#1F5F4E")),
    MODERATE("Moderate", Color.parseColor("#E0A800"), Color.parseColor("#9A7B22"), Color.parseColor("#5E4B1A")),
    SENSITIVE("Unhealthy for some", Color.parseColor("#EF7D22"), Color.parseColor("#A35A26"), Color.parseColor("#633719")),
    UNHEALTHY("Unhealthy", Color.parseColor("#E24B4A"), Color.parseColor("#9E3434"), Color.parseColor("#5F1F22")),
    VERY_UNHEALTHY("Very unhealthy", Color.parseColor("#8E44AD"), Color.parseColor("#6A3A82"), Color.parseColor("#3D2150")),
    UNKNOWN("—", Color.parseColor("#888780"), Color.parseColor("#4A5560"), Color.parseColor("#2C343C"));

    companion object {
        fun fromPm25(v: Double?): AirLevel = when {
            v == null -> UNKNOWN
            v <= 9.0 -> GOOD
            v <= 35.4 -> MODERATE
            v <= 55.4 -> SENSITIVE
            v <= 125.4 -> UNHEALTHY
            else -> VERY_UNHEALTHY
        }
    }
}

data class IndoorSpec(
    val indoor: IndoorNow?,
    val outdoorTemp: Int?,
    val fahrenheit: Boolean,
    val configured: Boolean,     // Ambient keys entered
)

/** Plain-language reading of the numbers, for the tip line. */
object IndoorAdvice {
    fun humidityLabel(h: Int?): String? = when {
        h == null -> null
        h < 30 -> "Dry"
        h <= 55 -> "Comfortable"
        h <= 65 -> "A bit humid"
        else -> "Humid"
    }

    fun co2Label(c: Int?): String? = when {
        c == null -> null
        c < 800 -> "Fresh"
        c < 1200 -> "Getting stuffy"
        else -> "Stuffy"
    }

    /** The single most useful thing to do right now, or null if all is well. */
    fun tip(i: IndoorNow): String? {
        val outsideClean = (i.outdoorPm25 ?: 0.0) <= 12.0
        return when {
            (i.pm25 ?: 0.0) > 35.4 -> "Hazy air inside: run the air purifiers"
            (i.co2 ?: 0) >= 1200 && outsideClean -> "Stuffy: open a window for a few minutes"
            (i.co2 ?: 0) >= 1200 -> "Stuffy: air it out once the outside air clears"
            (i.humidity ?: 50) < 30 -> "Dry: fill and run the humidifiers"
            (i.humidity ?: 50) > 65 -> "Humid: turn the humidifiers down"
            (i.pm25 ?: 0.0) > 9.0 -> "Air's a little dusty: purifiers on low"
            (i.co2 ?: 0) >= 800 && outsideClean -> "Getting stuffy: a window would help"
            else -> null
        }
    }
}

/**
 * Indoor air widget: temperature, humidity, PM2.5 and CO2 with a background tinted by air quality.
 * 1×1 shows the temp and a colored dot; bigger sizes add tiles and a tip.
 */
object IndoorRenderer {
    private val white = Color.WHITE
    private val muted = Color.argb(205, 255, 255, 255)

    fun render(w: Int, h: Int, dpW: Float, dpH: Float, spec: IndoorSpec): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val i = spec.indoor
        val level = AirLevel.fromPm25(i?.pm25)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), level.top, level.bottom, Shader.TileMode.CLAMP)
        })
        val s = min(w, h).toFloat()
        val pad = s * 0.09f
        fun paint(sz: Float, face: android.graphics.Typeface, color: Int = white) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sz; typeface = face; this.color = color }

        if (i == null) {
            val p = paint(s * 0.1f, Draw.medium).apply { textAlign = Paint.Align.CENTER }
            val msg = if (spec.configured) "No indoor readings yet" else "Add Ambient keys in settings"
            Draw.wrap(msg, p, w - pad * 2, 3).forEachIndexed { n, line ->
                c.drawText(line, w / 2f, h / 2f + (n - 0.5f) * p.textSize * 1.3f, p)
            }
            return bmp
        }
        val temp = i.tempF?.let { conv(it, spec.fahrenheit) }?.let { "$it°" } ?: "—"
        val tiny = dpW < 100 || dpH < 100
        val wide = dpW >= dpH * 1.5f && dpW >= 220
        val tall = dpH >= dpW * 1.4f && dpH >= 220

        if (tiny) {
            val big = paint(s * 0.36f, Draw.bold).apply { textAlign = Paint.Align.CENTER }
            c.drawText(temp, w / 2f + s * 0.03f, h * 0.58f, big)
            val sub = paint(s * 0.13f, Draw.medium, muted).apply { textAlign = Paint.Align.CENTER }
            val line = listOfNotNull(i.humidity?.let { "$it%" }, if (i.hasAir) level.label.takeIf { level != AirLevel.UNKNOWN && it.length <= 8 } else null)
                .joinToString(" · ")
            c.drawText(line, w / 2f, h * 0.82f, sub)
            return bmp
        }

        // Header block: "Inside" label, big temp, humidity and air chips.
        val blockW = if (wide) w * 0.5f else w.toFloat()
        val blockH = if (tall) min(h * 0.45f, w * 0.95f) else h.toFloat()
        val bs = min(blockW, blockH)
        val label = paint(bs * 0.085f, Draw.medium, muted)
        val big = paint(bs * 0.32f, Draw.bold)
        val line = paint(bs * 0.095f, Draw.medium)
        val x = pad
        var y = pad + label.textSize
        c.drawText("🏠  Inside", x, y, label)
        y += big.textSize * 0.95f
        c.drawText(temp, x, y, big)
        spec.outdoorTemp?.let {
            val op = paint(bs * 0.08f, Draw.regular, muted).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("Outside $it°", blockW - pad, pad + op.textSize, op)
        }
        i.humidity?.let {
            y += line.textSize * 1.6f
            c.drawText(Draw.ellipsize("💧 $it%  ${IndoorAdvice.humidityLabel(it)}", line, blockW - pad * 2), x, y, line)
        }
        if (i.hasAir) {
            y += line.textSize * 1.55f
            if (y < blockH - pad * 0.2f) {
                val dotR = line.textSize * 0.32f
                c.drawCircle(x + dotR, y - line.textSize * 0.33f, dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = level.color })
                c.drawCircle(x + dotR, y - line.textSize * 0.33f, dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = dotR * 0.3f; color = white
                })
                c.drawText(Draw.ellipsize("Air: ${level.label}", line, blockW - pad * 2 - dotR * 3), x + dotR * 3, y, line)
            }
        }

        // Bigger sizes: tiles for each reading + one tip.
        val area = when {
            wide -> RectF(blockW, pad, w - pad, h - pad)
            tall -> RectF(pad, blockH, w - pad, h - pad)
            else -> null
        } ?: return bmp
        val tiles = buildList {
            i.pm25?.let { add(Triple("PM2.5", it.roundToInt().toString(), "µg/m³")) }
            i.co2?.let { add(Triple("CO₂", it.toString(), IndoorAdvice.co2Label(it) ?: "ppm")) }
            i.pm10?.let { add(Triple("PM10", it.roundToInt().toString(), "µg/m³")) }
            i.pm25Day?.let { add(Triple("24h avg", it.roundToInt().toString(), "PM2.5")) }
            i.humidity?.let { add(Triple("Humidity", "$it%", IndoorAdvice.humidityLabel(it) ?: "")) }
        }.take(4)
        val tip = IndoorAdvice.tip(i)
        val tipH = if (tip != null) area.height() * 0.22f else 0f
        val grid = RectF(area.left, area.top, area.right, area.bottom - tipH)
        if (tiles.isNotEmpty()) {
            val cols = if (tiles.size == 1) 1 else 2
            val rows = (tiles.size + cols - 1) / cols
            val gap = pad * 0.5f
            val tw = (grid.width() - gap * (cols - 1)) / cols
            val th = (grid.height() - gap * (rows - 1)) / rows
            val ts = min(tw, th * 1.3f)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
            tiles.forEachIndexed { n, (name, value, unit) ->
                val r = RectF(grid.left + (n % cols) * (tw + gap), grid.top + (n / cols) * (th + gap), 0f, 0f)
                r.right = r.left + tw; r.bottom = r.top + th
                c.drawRoundRect(r, pad * 0.7f, pad * 0.7f, bg)
                val np = paint(ts * 0.12f, Draw.medium, muted)
                val vp = paint(min(ts * 0.3f, th * 0.42f), Draw.bold)
                val up = paint(ts * 0.1f, Draw.regular, muted)
                val ix = r.left + ts * 0.1f
                c.drawText(Draw.ellipsize(name, np, tw * 0.85f), ix, r.top + th * 0.28f, np)
                c.drawText(value, ix, r.top + th * 0.68f, vp)
                c.drawText(Draw.ellipsize(unit, up, tw * 0.85f), ix, r.top + th * 0.88f, up)
            }
        }
        if (tip != null) {
            val tp = paint(min(tipH * 0.32f, s * 0.06f), Draw.medium)
            val lines = Draw.wrap(tip, tp, area.width(), 2)
            var ty = area.bottom - tipH + (tipH - lines.size * tp.textSize * 1.2f) / 2 + tp.textSize
            lines.forEach { c.drawText(it, area.left, ty, tp); ty += tp.textSize * 1.2f }
        }
        return bmp
    }

    private fun conv(f: Double, fahrenheit: Boolean) = if (fahrenheit) f.roundToInt() else ((f - 32) * 5 / 9).roundToInt()
}
