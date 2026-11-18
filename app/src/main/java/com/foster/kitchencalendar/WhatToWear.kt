package com.foster.kitchencalendar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

enum class Wear(val label: String) {
    WINTER_COAT("Winter coat"),
    HAT_GLOVES("Hat & gloves"),
    JACKET("Fall jacket"),
    SWEATER("Sweater"),
    LONG_SLEEVES("Long sleeves"),
    TSHIRT("T-shirt"),
    SHORTS("Shorts"),
    RAINCOAT("Rain jacket"),
    UMBRELLA("Umbrella"),
    RAIN_BOOTS("Rain boots"),
    SNOW_BOOTS("Snow boots"),
    SUNSCREEN("Sunscreen"),
    SUN_HAT("Sun hat"),
    WATER_BOTTLE("Water bottle"),
}

data class WearAdvice(val items: List<Wear>, val headline: String, val note: String?, val forTomorrow: Boolean = false)

/**
 * Kid-friendly clothing suggestions for the school day (7am–6pm), based on the
 * coldest and warmest "feels like" temperatures, rain and snow chances, and UV.
 */
object WhatToWear {

    /**
     * Before 7am: the whole school day. During the day: only the hours left until 6pm
     * (a cold morning that's already over shouldn't mean a winter coat at noon).
     * After 6pm: tomorrow, so it's ready for laying out clothes the night before.
     */
    fun advise(b: WeatherBundle, kidsRun: Int, zone: ZoneId, today: LocalDate): WearAdvice? {
        val now = System.currentTimeMillis()
        fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        val forTomorrow = now >= at(today, 18)
        val day = if (forTomorrow) today.plusDays(1) else today
        val start = if (forTomorrow) at(day, 7) else maxOf(at(day, 7), now - 3_600_000)
        val end = at(day, 18)
        var window = b.hourly.filter { it.time in start..end }
        if (window.isEmpty()) window = b.hourly.filter { it.time >= now }.take(10)
        if (window.isEmpty()) return null

        // Rules are written in °F; convert if the forecast is in °C.
        fun toF(x: Int): Double = if (b.fahrenheit) x.toDouble() else x * 9.0 / 5.0 + 32
        val shift = kidsRun * 5.0   // runs cold (-1) → treat the day as 5° colder
        val rawCold = window.minOf { it.apparent }
        val rawWarm = window.maxOf { it.apparent }
        val cold = toF(rawCold) + shift
        val warm = toF(rawWarm) + shift
        val rain = window.maxOf { it.precipPct }
        val today0 = b.daily.firstOrNull { it.date == day }
        val snow = window.any { it.sky == Sky.SNOW } || (today0?.snow == true && cold < 36)
        val uv = max(window.maxOf { it.uv ?: 0.0 }, b.station?.uv?.toDouble() ?: 0.0)

        val items = mutableListOf<Wear>()
        val wet = rain >= 50 && !snow
        val headline = when {
            snow -> {
                items += Wear.WINTER_COAT; items += Wear.HAT_GLOVES; items += Wear.SNOW_BOOTS
                "Snow gear"
            }
            cold < 25 -> {
                items += Wear.WINTER_COAT; items += Wear.HAT_GLOVES
                if (wet) items += Wear.RAIN_BOOTS
                "Bundle up!"
            }
            cold < 40 -> {
                items += Wear.WINTER_COAT
                if (wet) items += if (rain >= 70) Wear.RAIN_BOOTS else Wear.UMBRELLA
                if (wet) "Cold and rainy" else "Winter coat weather"
            }
            cold < 50 -> if (wet) {
                items += Wear.SWEATER; items += Wear.RAINCOAT; "Rain jacket day"
            } else {
                items += Wear.JACKET; "Fall jacket weather"
            }
            cold < 60 -> {
                items += if (wet) Wear.LONG_SLEEVES else Wear.SWEATER
                if (wet) { items += Wear.RAINCOAT; "Rain jacket day" } else "Sweater weather"
            }
            cold < 70 -> {
                items += Wear.LONG_SLEEVES
                if (wet) { items += Wear.RAINCOAT; "Rain jacket day" } else "Long sleeves today"
            }
            cold < 78 -> {
                items += Wear.TSHIRT
                if (wet) { items += Wear.RAINCOAT; "Warm and rainy" } else "T-shirt weather"
            }
            else -> {
                items += Wear.TSHIRT; items += Wear.SHORTS
                if (warm >= 85) items += Wear.WATER_BOTTLE
                if (wet) items += Wear.UMBRELLA
                if (warm >= 85) "Hot one today!" else "Shorts weather"
            }
        }
        if (!wet && !snow && rain >= 30 && Wear.UMBRELLA !in items) items += Wear.UMBRELLA
        if (wet && rain >= 70 && cold >= 40 && Wear.RAIN_BOOTS !in items) items += Wear.RAIN_BOOTS
        if (uv >= 6) items += Wear.SUNSCREEN
        if (uv >= 8 && !wet) items += Wear.SUN_HAT

        val note = when {
            warm - cold >= 15 -> "Layers: $rawCold° to $rawWarm°"
            rain in 30..49 && !snow -> "$rain% chance of rain"
            rain >= 50 && !snow -> "$rain% chance of rain"
            else -> "Feels like $rawCold°–$rawWarm°"
        }
        return WearAdvice(items.take(4), if (forTomorrow) "Tomorrow: $headline" else headline, note, forTomorrow)
    }
}

/** Simple, colorful clothing glyphs a pre-reader can recognize. */
object WearIcon {

    fun draw(canvas: Canvas, wear: Wear, cx: Float, cy: Float, s: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        when (wear) {
            Wear.WINTER_COAT -> garment(canvas, s, c("#D85A30"), longSleeves = true, bottom = 0.92f, zip = true, hood = true, quilted = true)
            Wear.JACKET -> garment(canvas, s, c("#BA7517"), longSleeves = true, bottom = 0.42f, zip = false, hood = false, buttons = true, collar = true)
            Wear.SWEATER -> garment(canvas, s, c("#1D9E75"), longSleeves = true, bottom = 0.55f, zip = false, hood = false, stripe = true)
            Wear.LONG_SLEEVES -> garment(canvas, s, c("#7F77DD"), longSleeves = true, bottom = 0.5f, zip = false, hood = false)
            Wear.TSHIRT -> garment(canvas, s, c("#EF9F27"), longSleeves = false, bottom = 0.5f, zip = false, hood = false)
            Wear.RAINCOAT -> garment(canvas, s, c("#FAC775"), longSleeves = true, bottom = 0.85f, zip = false, hood = true, buttons = true)
            Wear.SHORTS -> shorts(canvas, s)
            Wear.HAT_GLOVES -> hat(canvas, s)
            Wear.UMBRELLA -> umbrella(canvas, s)
            Wear.RAIN_BOOTS -> boot(canvas, s, c("#EF9F27"), fur = false)
            Wear.SNOW_BOOTS -> boot(canvas, s, c("#854F0B"), fur = true)
            Wear.SUNSCREEN -> sunscreen(canvas, s)
            Wear.SUN_HAT -> sunHat(canvas, s)
            Wear.WATER_BOTTLE -> bottle(canvas, s)
        }
        canvas.restore()
    }

    private fun c(hex: String) = Color.parseColor(hex)
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun dark(color: Int) = Motif.blend(color, Color.BLACK, 0.3f)

    private fun garment(
        canvas: Canvas, s: Float, color: Int, longSleeves: Boolean, bottom: Float,
        zip: Boolean, hood: Boolean, stripe: Boolean = false, buttons: Boolean = false,
        quilted: Boolean = false, collar: Boolean = false,
    ) {
        val body = fill(color)
        val sleeve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.27f; strokeCap = Paint.Cap.ROUND
        }
        if (hood) canvas.drawCircle(0f, -s * 0.68f, s * 0.26f, body)
        if (longSleeves) {
            canvas.drawLine(-s * 0.36f, -s * 0.48f, -s * 0.72f, s * 0.28f, sleeve)
            canvas.drawLine(s * 0.36f, -s * 0.48f, s * 0.72f, s * 0.28f, sleeve)
        } else {
            canvas.drawLine(-s * 0.36f, -s * 0.48f, -s * 0.66f, -s * 0.22f, sleeve)
            canvas.drawLine(s * 0.36f, -s * 0.48f, s * 0.66f, -s * 0.22f, sleeve)
        }
        canvas.drawRoundRect(RectF(-s * 0.42f, -s * 0.62f, s * 0.42f, s * bottom), s * 0.12f, s * 0.12f, body)
        val d = dark(color)
        // Neckline
        canvas.drawArc(RectF(-s * 0.16f, -s * 0.74f, s * 0.16f, -s * 0.46f), 0f, 180f, true, fill(d))
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = d; strokeWidth = s * 0.05f; strokeCap = Paint.Cap.ROUND }
        if (zip) canvas.drawLine(0f, -s * 0.46f, 0f, s * bottom - s * 0.06f, line)
        if (stripe) {
            canvas.drawLine(-s * 0.4f, -s * 0.05f, s * 0.4f, -s * 0.05f, line)
            canvas.drawLine(-s * 0.4f, s * 0.08f, s * 0.4f, s * 0.08f, line)
        }
        if (buttons) {
            val n = if (bottom < 0.6f) 2 else 3
            for (i in 0 until n) canvas.drawCircle(0f, -s * 0.3f + i * s * 0.32f, s * 0.05f, fill(d))
        }
        if (quilted) {
            // Puffy stitching across body and sleeves
            var yq = -s * 0.32f
            while (yq < s * bottom - s * 0.1f) {
                canvas.drawLine(-s * 0.4f, yq, -s * 0.06f, yq, line)
                canvas.drawLine(s * 0.06f, yq, s * 0.4f, yq, line)
                yq += s * 0.26f
            }
            if (hood) canvas.drawArc(RectF(-s * 0.3f, -s * 0.96f, s * 0.3f, -s * 0.4f), 200f, 140f, false,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = s * 0.09f; strokeCap = Paint.Cap.ROUND })
        }
        if (collar) {
            val cp = fill(dark(color))
            val l = Path().apply { moveTo(-s * 0.02f, -s * 0.5f); lineTo(-s * 0.26f, -s * 0.66f); lineTo(-s * 0.22f, -s * 0.32f); close() }
            val r = Path().apply { moveTo(s * 0.02f, -s * 0.5f); lineTo(s * 0.26f, -s * 0.66f); lineTo(s * 0.22f, -s * 0.32f); close() }
            canvas.drawPath(l, cp); canvas.drawPath(r, cp)
        }
    }

    private fun sunHat(canvas: Canvas, s: Float) {
        val color = c("#FAC775")
        canvas.drawOval(RectF(-s * 0.85f, -s * 0.05f, s * 0.85f, s * 0.35f), fill(color))
        canvas.drawArc(RectF(-s * 0.42f, -s * 0.6f, s * 0.42f, s * 0.3f), 180f, 180f, true, fill(color))
        canvas.drawRect(-s * 0.42f, -s * 0.05f, s * 0.42f, s * 0.08f, fill(c("#D85A30")))
    }

    private fun bottle(canvas: Canvas, s: Float) {
        canvas.drawRoundRect(RectF(-s * 0.3f, -s * 0.5f, s * 0.3f, s * 0.85f), s * 0.16f, s * 0.16f, fill(c("#378ADD")))
        canvas.drawRoundRect(RectF(-s * 0.17f, -s * 0.78f, s * 0.17f, -s * 0.48f), s * 0.06f, s * 0.06f, fill(c("#185FA5")))
        canvas.drawRoundRect(RectF(-s * 0.3f, s * 0.05f, s * 0.3f, s * 0.35f), 0f, 0f, fill(c("#B5D4F4")))
    }

    private fun shorts(canvas: Canvas, s: Float) {
        val p = fill(c("#185FA5"))
        val path = Path().apply {
            moveTo(-s * 0.55f, -s * 0.45f); lineTo(s * 0.55f, -s * 0.45f); lineTo(s * 0.65f, s * 0.45f)
            lineTo(s * 0.1f, s * 0.45f); lineTo(0f, -s * 0.05f); lineTo(-s * 0.1f, s * 0.45f)
            lineTo(-s * 0.65f, s * 0.45f); close()
        }
        canvas.drawPath(path, p)
        canvas.drawRect(-s * 0.55f, -s * 0.45f, s * 0.55f, -s * 0.3f, fill(dark(c("#185FA5"))))
    }

    private fun hat(canvas: Canvas, s: Float) {
        val color = c("#D4537E")
        canvas.drawArc(RectF(-s * 0.55f, -s * 0.6f, s * 0.55f, s * 0.5f), 180f, 180f, true, fill(color))
        canvas.drawRoundRect(RectF(-s * 0.6f, -s * 0.08f, s * 0.6f, s * 0.2f), s * 0.08f, s * 0.08f, fill(dark(color)))
        canvas.drawCircle(0f, -s * 0.65f, s * 0.13f, fill(Color.WHITE))
        // Mitten
        val m = fill(c("#7F77DD"))
        canvas.drawRoundRect(RectF(s * 0.25f, s * 0.28f, s * 0.7f, s * 0.85f), s * 0.2f, s * 0.2f, m)
        canvas.drawCircle(s * 0.2f, s * 0.48f, s * 0.11f, m)
    }

    private fun umbrella(canvas: Canvas, s: Float) {
        val color = c("#7F77DD")
        canvas.drawArc(RectF(-s * 0.8f, -s * 0.7f, s * 0.8f, s * 0.3f), 180f, 180f, true, fill(color))
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = dark(color); style = Paint.Style.STROKE; strokeWidth = s * 0.08f; strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(0f, -s * 0.2f, 0f, s * 0.65f, st)
        canvas.drawArc(RectF(-s * 0.22f, s * 0.5f, 0f, s * 0.8f), 0f, 180f, false, st)
    }

    private fun boot(canvas: Canvas, s: Float, color: Int, fur: Boolean) {
        val path = Path().apply {
            moveTo(-s * 0.35f, -s * 0.7f); lineTo(s * 0.1f, -s * 0.7f); lineTo(s * 0.1f, s * 0.2f)
            lineTo(s * 0.62f, s * 0.35f); lineTo(s * 0.62f, s * 0.7f); lineTo(-s * 0.35f, s * 0.7f); close()
        }
        canvas.drawPath(path, fill(color))
        canvas.drawRect(-s * 0.38f, s * 0.6f, s * 0.65f, s * 0.75f, fill(dark(color)))
        if (fur) canvas.drawRoundRect(RectF(-s * 0.45f, -s * 0.82f, s * 0.2f, -s * 0.55f), s * 0.12f, s * 0.12f, fill(Color.WHITE))
    }

    private fun sunscreen(canvas: Canvas, s: Float) {
        canvas.drawRoundRect(RectF(-s * 0.32f, -s * 0.4f, s * 0.32f, s * 0.8f), s * 0.14f, s * 0.14f, fill(c("#F0997B")))
        canvas.drawRect(-s * 0.14f, -s * 0.62f, s * 0.14f, -s * 0.4f, fill(c("#993C1D")))
        val sun = fill(c("#FAC775"))
        canvas.drawCircle(0f, s * 0.22f, s * 0.16f, sun)
        val st = Paint(sun).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.05f; strokeCap = Paint.Cap.ROUND }
        for (i in 0 until 8) {
            val a = i * PI / 4
            canvas.drawLine(
                (cos(a) * s * 0.21).toFloat(), (s * 0.22 + sin(a) * s * 0.21).toFloat(),
                (cos(a) * s * 0.29).toFloat(), (s * 0.22 + sin(a) * s * 0.29).toFloat(), st,
            )
        }
    }

    /**
     * "What to wear" panel: headline (and note) on the left, clothing icons with labels
     * on the right. Draws only text and icons; the caller paints any card behind it.
     */
    fun panel(canvas: Canvas, rect: RectF, advice: WearAdvice, textColor: Int, mutedColor: Int) {
        if (rect.height() > rect.width() * 0.42f) return tallPanel(canvas, rect, advice, textColor, mutedColor)
        val h = rect.height()
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; typeface = Draw.bold; textSize = h * 0.2f }
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mutedColor; typeface = Draw.regular; textSize = h * 0.14f }
        val kicker = Paint(note).apply { typeface = Draw.medium; letterSpacing = 0.12f }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor; typeface = Draw.medium; textSize = h * 0.13f; textAlign = Paint.Align.CENTER
        }
        val textW = rect.width() * 0.42f
        val pad = h * 0.14f
        val x = rect.left + pad
        canvas.drawText("WHAT TO WEAR", x, rect.top + pad + kicker.textSize, kicker)
        val lines = Draw.wrap(advice.headline, head, textW - pad, 2)
        val firstBase = rect.centerY() + head.textSize * 0.35f - (lines.size - 1) * head.textSize * 0.55f
        lines.forEachIndexed { i, l -> canvas.drawText(l, x, firstBase + i * head.textSize * 1.1f, head) }
        advice.note?.let { canvas.drawText(Draw.ellipsize(it, note, textW - pad), x, rect.bottom - pad, note) }

        val n = advice.items.size
        if (n == 0) return
        val areaL = rect.left + textW
        val slot = (rect.right - pad - areaL) / n
        val iconS = minOf(slot * 0.32f, h * 0.27f)
        advice.items.forEachIndexed { i, wear ->
            val cx = areaL + slot * (i + 0.5f)
            draw(canvas, wear, cx, rect.top + h * 0.42f, iconS)
            canvas.drawText(Draw.ellipsize(wear.label, label, slot * 0.95f), cx, rect.bottom - pad * 0.9f, label)
        }
    }

    /** Big layout: headline on top, large clothing pictures in a grid, note at the bottom. */
    private fun tallPanel(canvas: Canvas, rect: RectF, advice: WearAdvice, textColor: Int, mutedColor: Int) {
        val w = rect.width()
        val pad = minOf(w, rect.height()) * 0.07f
        val kicker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mutedColor; typeface = Draw.medium; textSize = w * 0.045f; letterSpacing = 0.12f
        }
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; typeface = Draw.bold; textSize = w * 0.085f }
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mutedColor; typeface = Draw.regular; textSize = w * 0.05f }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor; typeface = Draw.medium; textSize = w * 0.055f; textAlign = Paint.Align.CENTER
        }
        val x = rect.left + pad
        var y = rect.top + pad + kicker.textSize
        canvas.drawText("WHAT TO WEAR", x, y, kicker)
        for (line in Draw.wrap(advice.headline, head, w - 2 * pad, 2)) {
            y += head.textSize * 1.15f
            canvas.drawText(line, x, y, head)
        }
        val noteH = if (advice.note != null) note.textSize * 1.8f else 0f
        advice.note?.let { canvas.drawText(Draw.ellipsize(it, note, w - 2 * pad), x, rect.bottom - pad, note) }

        val n = advice.items.size
        if (n == 0) return
        val gridTop = y + pad * 0.8f
        val gridBottom = rect.bottom - pad - noteH
        val cols = if (n <= 2) n else 2
        val rows = (n + cols - 1) / cols
        val cellW = (w - 2 * pad) / cols
        val cellH = (gridBottom - gridTop) / rows
        if (cellH <= 0) return
        val iconS = minOf(cellW * 0.3f, (cellH - label.textSize * 1.6f) * 0.42f)
        advice.items.forEachIndexed { i, wear ->
            val c = i % cols
            val r = i / cols
            val cx = rect.left + pad + cellW * (c + 0.5f)
            val cy = gridTop + cellH * r + (cellH - label.textSize * 1.6f) / 2
            draw(canvas, wear, cx, cy, iconS)
            canvas.drawText(Draw.ellipsize(wear.label, label, cellW * 0.95f), cx, gridTop + cellH * (r + 1) - label.textSize * 0.4f, label)
        }
    }
}
