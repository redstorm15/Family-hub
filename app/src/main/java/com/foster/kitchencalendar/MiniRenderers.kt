package com.foster.kitchencalendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** An upcoming event with a short lead label ("3:35p", "All day", "Tmrw 9a"). */
data class Upcoming(val lead: String, val event: CalEvent)

data class DateMiniSpec(val today: LocalDate, val upcoming: List<Upcoming>)

/**
 * Small, resizable widgets. Each picks a layout from its size in dp:
 * tiny (about 1×1), medium (about 2×2), wide, or tall.
 */
private enum class MiniSize { TINY, MEDIUM, WIDE, TALL }

private fun sizeOf(dpW: Float, dpH: Float): MiniSize = when {
    min(dpW, dpH) < 115f -> MiniSize.TINY
    dpW > dpH * 1.5f -> MiniSize.WIDE
    dpH > dpW * 1.3f -> MiniSize.TALL
    else -> MiniSize.MEDIUM
}

object DateMiniRenderer {

    fun render(w: Int, h: Int, dpW: Float, dpH: Float, spec: DateMiniSpec): Bitmap {
        val theme = Themes.forMonth(spec.today.monthValue)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(theme.headerBg)
        val size = sizeOf(dpW, dpH)
        val s = min(w, h).toFloat()
        val pad = s * 0.08f
        val dow = spec.today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val dowShort = spec.today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
        val monthYear = "${spec.today.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()} ${spec.today.year}"
        val day = spec.today.dayOfMonth.toString()

        fun paint(size: Float, face: android.graphics.Typeface, color: Int = theme.headerText, center: Boolean = true) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; typeface = face; this.color = color
                if (center) textAlign = Paint.Align.CENTER
            }

        if (size == MiniSize.TINY) {
            val k = paint(s * 0.17f, Draw.medium).apply { letterSpacing = 0.15f }
            val big = paint(s * 0.56f, Draw.bold)
            val total = k.textSize * 1.25f + big.textSize * 0.78f
            val top = (h - total) / 2
            canvas.drawText(dowShort, w / 2f, top + k.textSize, k)
            canvas.drawText(day, w / 2f, top + total, big)
            return bmp
        }

        // Block with month, weekday and the big number.
        val blockW = if (size == MiniSize.WIDE) w * 0.42f else w.toFloat()
        val blockH = when (size) {
            MiniSize.WIDE -> h.toFloat()
            MiniSize.TALL -> min(h * 0.5f, w * 0.95f)
            else -> h * 0.66f
        }
        val bs = min(blockW, blockH)
        val my = paint(bs * 0.13f, Draw.bold)
        val dw = paint(bs * 0.11f, Draw.regular)
        val big = paint(bs * 0.42f, Draw.bold)
        val cx = blockW / 2
        val total = my.textSize + dw.textSize * 1.3f + big.textSize * 0.95f
        val top = (blockH - total) / 2 + my.textSize * 0.2f
        canvas.drawText(monthYear, cx, top + my.textSize * 0.9f, my)
        canvas.drawText(Draw.ellipsize(dow, dw, blockW * 0.9f), cx, top + my.textSize + dw.textSize * 1.15f, dw)
        canvas.drawText(day, cx, top + total, big)

        Draw.decorations(
            canvas, w.toFloat(), if (size == MiniSize.WIDE) h * 0.3f else blockH * 0.32f,
            spec.today.toEpochDay().toInt(), theme,
            keepClear = listOf(RectF(cx - bs * 0.45f, 0f, cx + bs * 0.45f, blockH)),
        )

        // Events card
        val card = when (size) {
            MiniSize.WIDE -> RectF(blockW, pad, w - pad, h - pad)
            else -> RectF(pad, blockH, w - pad, h - pad)
        }
        if (card.height() < s * 0.12f || card.width() < s * 0.3f) return bmp
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.bodyBg }
        canvas.drawRoundRect(card, pad * 0.8f, pad * 0.8f, cardPaint)

        val lineH = max(s * 0.115f, min(card.height() / 4.2f, s * 0.16f))
        val text = paint(lineH * 0.56f, Draw.medium, theme.dayText, center = false)
        val lead = paint(lineH * 0.5f, Draw.regular, theme.mutedText, center = false)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val inner = RectF(card.left + pad * 0.7f, card.top + pad * 0.4f, card.right - pad * 0.7f, card.bottom - pad * 0.4f)
        val fit = max(1, floor(inner.height() / lineH).toInt())
        if (spec.upcoming.isEmpty()) {
            canvas.drawText("Nothing else today", inner.left, Draw.centerBaseline(text, inner.centerY()), text)
            return bmp
        }
        val items = spec.upcoming.take(fit)
        val y0 = inner.centerY() - items.size * lineH / 2
        items.forEachIndexed { i, up ->
            val cy = y0 + lineH * (i + 0.5f)
            dot.color = Draw.eventColor(up.event, theme)
            canvas.drawCircle(inner.left + lineH * 0.14f, cy, lineH * 0.13f, dot)
            var x = inner.left + lineH * 0.42f
            canvas.drawText(up.lead, x, Draw.centerBaseline(lead, cy), lead)
            x += lead.measureText(up.lead) + lineH * 0.22f
            canvas.drawText(Draw.ellipsize(up.event.title, text, inner.right - x), x, Draw.centerBaseline(text, cy), text)
        }
        return bmp
    }
}

data class WeatherMiniSpec(val now: WeatherNow?, val wear: WearAdvice?)

object WeatherMiniRenderer {
    private val white = Color.WHITE
    private val muted = Color.argb(205, 255, 255, 255)
    private val sun = Color.parseColor("#FAC775")

    fun render(w: Int, h: Int, dpW: Float, dpH: Float, spec: WeatherMiniSpec): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val now = spec.now
        val (top, bottom) = WeatherRenderer.skyColors(now?.sky ?: Sky.CLOUDY, now?.isDay ?: true)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(),
            Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP) })
        val size = sizeOf(dpW, dpH)
        val s = min(w, h).toFloat()
        val pad = s * 0.09f
        fun paint(sz: Float, face: android.graphics.Typeface, color: Int = white) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sz; typeface = face; this.color = color }

        if (now == null) {
            val p = paint(s * 0.12f, Draw.medium).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText("No weather", w / 2f, h / 2f, p)
            return bmp
        }
        val temp = now.temp?.let { "$it°" } ?: "—"

        if (size == MiniSize.TINY) {
            WeatherIcon.draw(canvas, now.sky, w / 2f, h * 0.32f, s * 0.2f, white, sun, night = !now.isDay)
            val big = paint(s * 0.36f, Draw.bold).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText(temp, w / 2f + s * 0.03f, h * 0.86f, big)
            return bmp
        }

        // "Now" block, like the top-left of the big weather widget.
        val blockW = if (size == MiniSize.WIDE) w * 0.55f else w.toFloat()
        val blockH = if (size == MiniSize.TALL) min(h * 0.6f, w * 1.0f) else h.toFloat()
        val bs = min(blockW, blockH)
        val big = paint(bs * 0.34f, Draw.bold)
        val line = paint(bs * 0.1f, Draw.medium)
        val sub = paint(bs * 0.085f, Draw.regular, muted)
        val x = pad
        var y = pad + big.textSize * 0.82f
        canvas.drawText(temp, x, y, big)
        WeatherIcon.draw(canvas, now.sky, blockW - pad - bs * 0.15f, pad + bs * 0.15f, bs * 0.14f, white, sun, night = !now.isDay)
        y += line.textSize * 1.7f
        canvas.drawText(Draw.ellipsize(now.label, line, blockW - 2 * pad), x, y, line)
        y += sub.textSize * 1.55f
        val hl = listOfNotNull(now.high?.let { "H $it°" }, now.low?.let { "L $it°" }).joinToString("  ")
        canvas.drawText(Draw.ellipsize(hl, sub, blockW - 2 * pad), x, y, sub)
        now.feelsLike?.let {
            y += sub.textSize * 1.45f
            if (y < blockH - pad * 0.3f) canvas.drawText("Feels like $it°", x, y, sub)
        }

        // Wider or taller: clothing pick and rain chance.
        val wear = spec.wear ?: return bmp
        if (size == MiniSize.MEDIUM) {
            // 2×2: a small clothing pick along the bottom if there's room under the text.
            val rowTop = y + sub.textSize * 0.9f
            val rowH = h - pad - rowTop
            val first = wear.items.firstOrNull() ?: return bmp
            if (rowH < bs * 0.16f) return bmp
            val iconS = min(rowH * 0.4f, bs * 0.11f)
            val cy = rowTop + rowH / 2
            WearIcon.draw(canvas, first, pad + iconS, cy, iconS)
            val lp = paint(min(rowH * 0.38f, bs * 0.09f), Draw.medium)
            val label = wear.items.take(2).joinToString(" + ") { it.label }
            canvas.drawText(Draw.ellipsize(label, lp, w - pad * 2 - iconS * 2.6f), pad + iconS * 2.4f, Draw.centerBaseline(lp, cy), lp)
            return bmp
        }
        val area = when (size) {
            MiniSize.WIDE -> RectF(blockW, pad, w - pad, h - pad)
            MiniSize.TALL -> RectF(pad, blockH, w - pad, h - pad)
            else -> return bmp
        }
        canvas.drawRoundRect(area, pad * 0.8f, pad * 0.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) })
        val first = wear.items.firstOrNull() ?: return bmp
        val a = min(area.width(), area.height())
        val labelP = paint(a * 0.12f, Draw.medium).apply { textAlign = Paint.Align.CENTER }
        val noteP = paint(a * 0.095f, Draw.regular, muted).apply { textAlign = Paint.Align.CENTER }
        val extra = wear.items.drop(1).firstOrNull()
        val iconY = area.top + area.height() * 0.4f
        if (extra != null && area.width() > a * 1.2f) {
            WearIcon.draw(canvas, first, area.centerX() - a * 0.3f, iconY, a * 0.2f)
            WearIcon.draw(canvas, extra, area.centerX() + a * 0.3f, iconY, a * 0.2f)
            canvas.drawText(Draw.ellipsize("${first.label} + ${extra.label}", labelP, area.width() * 0.92f), area.centerX(), area.bottom - a * 0.22f, labelP)
        } else {
            WearIcon.draw(canvas, first, area.centerX(), iconY, a * 0.24f)
            canvas.drawText(Draw.ellipsize(first.label, labelP, area.width() * 0.92f), area.centerX(), area.bottom - a * 0.22f, labelP)
        }
        val rain = now.precipPct?.takeIf { it >= 10 }?.let { "$it% rain" } ?: if (wear.forTomorrow) "for tomorrow" else "today"
        canvas.drawText(rain, area.centerX(), area.bottom - a * 0.07f, noteP)
        return bmp
    }
}
