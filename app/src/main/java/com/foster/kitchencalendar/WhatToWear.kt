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
    JACKET("Jacket"),
    SWEATER("Sweater"),
    LONG_SLEEVES("Long sleeves"),
    TSHIRT("T-shirt"),
    SHORTS("Shorts"),
    RAINCOAT("Raincoat"),
    UMBRELLA("Umbrella"),
    RAIN_BOOTS("Rain boots"),
    SNOW_BOOTS("Snow boots"),
    SUNSCREEN("Sunscreen"),
}

data class WearAdvice(val items: List<Wear>, val headline: String, val note: String?)

/**
 * Kid-friendly clothing suggestions for the school day (7am–6pm), based on the
 * coldest and warmest "feels like" temperatures, rain and snow chances, and UV.
 */
object WhatToWear {

    fun advise(b: WeatherBundle, kidsRun: Int, zone: ZoneId, today: LocalDate): WearAdvice? {
        val start = today.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        val end = today.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
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
        val today0 = b.daily.firstOrNull()
        val snow = window.any { it.sky == Sky.SNOW } || (today0?.snow == true && cold < 36)
        val uv = max(window.maxOf { it.uv ?: 0.0 }, b.station?.uv?.toDouble() ?: 0.0)

        val items = mutableListOf<Wear>()
        val headline = when {
            cold < 25 -> { items += Wear.WINTER_COAT; items += Wear.HAT_GLOVES; "Bundle up!" }
            cold < 40 -> { items += Wear.WINTER_COAT; "Winter coat weather" }
            cold < 50 -> { items += Wear.JACKET; "Jacket weather" }
            cold < 60 -> { items += Wear.SWEATER; "Sweater weather" }
            cold < 70 -> { items += Wear.LONG_SLEEVES; "Long sleeves today" }
            cold < 78 -> { items += Wear.TSHIRT; "T-shirt weather" }
            else -> { items += Wear.TSHIRT; items += Wear.SHORTS; "Shorts weather" }
        }.let { if (snow) "Snow gear today" else it }

        if (snow) {
            items += Wear.SNOW_BOOTS
            if (Wear.HAT_GLOVES !in items) items += Wear.HAT_GLOVES
        } else if (rain >= 50) {
            items += Wear.RAINCOAT
            if (rain >= 70) items += Wear.RAIN_BOOTS
        } else if (rain >= 30) {
            items += Wear.UMBRELLA
        }
        if (uv >= 6) items += Wear.SUNSCREEN

        val note = when {
            warm - cold >= 18 -> "Layers: $rawCold° this morning, $rawWarm° this afternoon"
            rain in 30..49 && !snow -> "$rain% chance of rain"
            else -> null
        }
        return WearAdvice(items.take(4), headline, note)
    }
}

/** Simple, colorful clothing glyphs a pre-reader can recognize. */
object WearIcon {

    fun draw(canvas: Canvas, wear: Wear, cx: Float, cy: Float, s: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        when (wear) {
            Wear.WINTER_COAT -> garment(canvas, s, c("#D85A30"), longSleeves = true, bottom = 0.9f, zip = true, hood = false)
            Wear.JACKET -> garment(canvas, s, c("#378ADD"), longSleeves = true, bottom = 0.5f, zip = true, hood = false)
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
        }
        canvas.restore()
    }

    private fun c(hex: String) = Color.parseColor(hex)
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun dark(color: Int) = Motif.blend(color, Color.BLACK, 0.3f)

    private fun garment(
        canvas: Canvas, s: Float, color: Int, longSleeves: Boolean, bottom: Float,
        zip: Boolean, hood: Boolean, stripe: Boolean = false, buttons: Boolean = false,
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
        if (buttons) for (i in 0 until 3) canvas.drawCircle(0f, -s * 0.3f + i * s * 0.32f, s * 0.05f, fill(d))
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
        canvas.drawText(Draw.ellipsize(advice.headline, head, textW - pad), x, rect.centerY() + head.textSize * 0.35f, head)
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
}
