package com.foster.kitchencalendar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.Instant
import java.time.ZoneId
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/** Drawing helpers shared by the month, week and day renderers. */
object Draw {
    val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, 700, false)
    val medium: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
    val regular: Typeface = Typeface.create(Typeface.SANS_SERIF, 400, false)

    val darkText: Int = Color.parseColor("#2C2C2A")

    /** Baseline that vertically centres [p]'s text on [cy]. */
    fun centerBaseline(p: Paint, cy: Float) = cy - (p.ascent() + p.descent()) / 2

    fun ellipsize(text: String, p: Paint, maxW: Float): String {
        if (maxW <= 0) return ""
        if (p.measureText(text) <= maxW) return text
        val ell = "…"
        val n = p.breakText(text, true, maxW - p.measureText(ell), null)
        return if (n <= 0) "" else text.substring(0, n).trimEnd() + ell
    }

    /** Greedy word wrap into at most [maxLines] lines; the last line is ellipsized. */
    fun wrap(text: String, p: Paint, maxW: Float, maxLines: Int): List<String> {
        val words = text.split(" ").filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var cur = ""
        for ((i, w) in words.withIndex()) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(trial) <= maxW || cur.isEmpty()) {
                cur = trial
            } else {
                lines += cur
                cur = w
                if (lines.size == maxLines - 1) {
                    cur = words.drop(i).joinToString(" ")
                    break
                }
            }
        }
        if (cur.isNotEmpty()) lines += cur
        return lines.take(maxLines).mapIndexed { i, l -> if (i == maxLines - 1) ellipsize(l, p, maxW) else l }
    }

    fun luminance(c: Int): Double =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    /** Readable text color to place on top of [bg]. */
    fun onColor(bg: Int) = if (luminance(bg) > 0.6) darkText else Color.WHITE

    fun eventColor(e: CalEvent, theme: MonthTheme): Int =
        if (e.color == 0) theme.todayFill else (e.color or 0xFF000000.toInt())

    /** "3p", "3:30p", or "15:30". */
    fun timeLabel(millis: Long, zone: ZoneId, use24h: Boolean): String {
        val t = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        if (use24h) return "%d:%02d".format(t.hour, t.minute)
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        val ampm = if (t.hour < 12) "a" else "p"
        return if (t.minute == 0) "$h12$ampm" else "$h12:%02d$ampm".format(t.minute)
    }

    /** "3:35 – 4:45p" style range. */
    fun timeRange(e: CalEvent, zone: ZoneId, use24h: Boolean): String =
        "${timeLabel(e.beginMillis, zone, use24h)} – ${timeLabel(e.endMillis, zone, use24h)}"

    /**
     * One event line. All-day events are a solid colored bar; timed events are a
     * colored dot followed by "3p Title".
     */
    fun chip(
        canvas: Canvas, e: CalEvent, box: RectF, theme: MonthTheme,
        chipPaint: Paint, textPaint: Paint, alpha: Int,
        showTimes: Boolean, use24h: Boolean, zone: ZoneId,
    ) {
        val color = eventColor(e, theme)
        val r = box.height() * 0.22f
        val baseline = centerBaseline(textPaint, box.centerY())
        chipPaint.color = color; chipPaint.alpha = alpha
        if (e.allDay) {
            canvas.drawRoundRect(box, r, r, chipPaint)
            textPaint.color = onColor(color); textPaint.alpha = alpha
            val tx = box.left + box.height() * 0.3f
            canvas.drawText(ellipsize(e.title, textPaint, box.right - tx - box.height() * 0.2f), tx, baseline, textPaint)
        } else {
            val dotR = box.height() * 0.2f
            canvas.drawCircle(box.left + dotR + box.height() * 0.1f, box.centerY(), dotR, chipPaint)
            textPaint.color = theme.dayText; textPaint.alpha = alpha
            val tx = box.left + dotR * 2 + box.height() * 0.3f
            val label = if (showTimes) "${timeLabel(e.beginMillis, zone, use24h)} ${e.title}" else e.title
            canvas.drawText(ellipsize(label, textPaint, box.right - tx), tx, baseline, textPaint)
        }
    }

    /**
     * Scatters the theme's motifs across a header band, keeping clear of text
     * ([keepClear]) and controls ([avoidCircles] as x, y, radius).
     */
    fun decorations(
        canvas: Canvas, w: Float, headerH: Float, seed: Int, theme: MonthTheme,
        keepClear: List<RectF>, avoidCircles: List<Triple<Float, Float, Float>> = emptyList(),
    ) {
        val rnd = Random(seed)
        val base = min(headerH * 0.16f, w * 0.05f)
        val count = if (w > headerH * 6) 16 else 11
        val placed = mutableListOf<Triple<Float, Float, Float>>()
        var attempts = 0
        while (placed.size < count && attempts < 500) {
            attempts++
            val s = base * (0.6f + rnd.nextFloat() * 0.8f)
            if (headerH < 2.2f * s) break
            val x = s + rnd.nextFloat() * (w - 2 * s)
            val y = s * 0.8f + rnd.nextFloat() * (headerH - 1.6f * s)
            if (keepClear.any { RectF(x - s, y - s, x + s, y + s).intersects(it.left, it.top, it.right, it.bottom) }) continue
            if (avoidCircles.any { (cx, cy, cr) -> hypot(x - cx, y - cy) < cr + s }) continue
            if (placed.any { (px, py, ps) -> hypot(x - px, y - py) < (s + ps) * 1.05f }) continue
            placed += Triple(x, y, s)
        }
        placed.forEachIndexed { i, (x, y, s) ->
            val motif = theme.motifs[i % theme.motifs.size]
            val fill = theme.decoColors[(i * 7 + rnd.nextInt(3)) % theme.decoColors.size]
            val rot = if (motif == Motif.TREE || motif == Motif.GHOST || motif == Motif.PUMPKIN) {
                rnd.nextFloat() * 16f - 8f
            } else rnd.nextFloat() * 50f - 25f
            motif.draw(canvas, x, y, s, rot, fill, theme.decoAccent, theme.headerBg)
        }
    }
}
