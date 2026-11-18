package com.foster.kitchencalendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class DaySpec(
    val date: LocalDate,
    val nowMillis: Long,
    val isToday: Boolean,
    val events: List<CalEvent>,     // everything touching [date], all-day first
    val weather: WeatherNow?,
    val wear: WearAdvice?,
    val use24h: Boolean,
    val zone: ZoneId,
    val textScale: Float,
)

/**
 * The big "Today" view: date header, weather, all-day events, what's up next,
 * and a timeline sized to the day. Portrait stacks everything; landscape puts the
 * timeline on the left and the rest on the right.
 */
object DayRenderer {

    fun render(w: Int, h: Int, spec: DaySpec): Bitmap {
        val theme = Themes.forMonth(spec.date.monthValue)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pad = MonthLayout.pad(w, h)
        val landscape = w > h * 1.1f
        val headerH = (h - pad) * (if (landscape) 0.2f else 0.15f)
        val u = min(w, h) * 0.032f * spec.textScale          // base text unit

        canvas.drawColor(theme.headerBg)
        val titleBox = drawHeader(canvas, w.toFloat(), headerH, spec, theme)
        Draw.decorations(canvas, w.toFloat(), headerH, spec.date.toEpochDay().toInt(), theme, keepClear = listOf(titleBox))

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.bodyBg }
        canvas.drawRoundRect(RectF(pad * 0.5f, headerH, w - pad * 0.5f, h - pad * 0.5f), pad, pad, body)

        val area = RectF(pad * 1.5f, headerH + pad, w - pad * 1.5f, h - pad * 1.5f)
        val allDay = spec.events.filter { it.allDay }
        val timed = spec.events.filter { !it.allDay }

        if (landscape) {
            val split = area.left + area.width() * 0.58f
            drawTimeline(canvas, RectF(area.left, area.top, split - pad * 0.5f, area.bottom), timed, spec, theme, u)
            var y = area.top
            val right = RectF(split + pad * 0.5f, area.top, area.right, area.bottom)
            y = drawWeather(canvas, right.left, y, right.right, spec, theme, u)
            y = drawWear(canvas, right.left, y, right.right, spec, theme, u)
            y = drawUpNext(canvas, right.left, y, right.right, timed, spec, theme, u)
            y = drawAllDay(canvas, right.left, y, right.right, allDay, theme, u, maxLines = 3)
            drawEventList(canvas, RectF(right.left, y, right.right, right.bottom), timed, spec, theme, u)
        } else {
            var y = area.top
            y = drawWeather(canvas, area.left, y, area.right, spec, theme, u)
            y = drawWear(canvas, area.left, y, area.right, spec, theme, u)
            y = drawAllDay(canvas, area.left, y, area.right, allDay, theme, u, maxLines = 3)
            y = drawUpNext(canvas, area.left, y, area.right, timed, spec, theme, u)
            drawTimeline(canvas, RectF(area.left, y, area.right, area.bottom), timed, spec, theme, u)
        }
        return bmp
    }

    // ---------- Header ----------

    /** Returns the box the title occupies so decorations can avoid it. */
    private fun drawHeader(canvas: Canvas, w: Float, headerH: Float, spec: DaySpec, theme: MonthTheme): RectF {
        val dow = spec.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val month = spec.date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val title = "$dow, $month ${spec.date.dayOfMonth}"
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.headerText; typeface = Draw.bold }
        big.textSize = min(headerH * 0.42f, w * 0.07f)
        while (big.measureText(title) > w * 0.8f && big.textSize > 10f) big.textSize *= 0.94f
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.headerText; typeface = Draw.medium; textSize = big.textSize * 0.36f; letterSpacing = 0.3f
            textAlign = Paint.Align.CENTER
        }
        big.textAlign = Paint.Align.CENTER
        val label = if (spec.isToday) "TODAY" else "TOMORROW"
        val blockH = small.textSize * 1.4f + big.textSize
        val top = (headerH - blockH) / 2
        canvas.drawText(label, w / 2, top + small.textSize, small)
        canvas.drawText(title, w / 2, top + blockH - big.descent() * 0.6f, big)
        val tw = big.measureText(title) / 2 + big.textSize * 0.4f
        return RectF(w / 2 - tw, 0f, w / 2 + tw, headerH)
    }

    // ---------- Cards ----------

    private fun card(canvas: Canvas, rect: RectF, theme: MonthTheme) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.cellBg }
        // Same corner size for every card, however tall (the timeline used to get huge curves).
        val r = min(rect.height() * 0.18f, min(rect.width(), rect.height()) * 0.04f + 8f)
        canvas.drawRoundRect(rect, r, r, p)
    }

    /** Returns the y below what it drew. */
    private fun drawWeather(canvas: Canvas, left: Float, top: Float, right: Float, spec: DaySpec, theme: MonthTheme, u: Float): Float {
        val wx = spec.weather ?: return top
        val h = u * 3.4f
        val rect = RectF(left, top, right, top + h)
        card(canvas, rect, theme)
        WeatherIcon.draw(canvas, wx.sky, rect.left + h * 0.6f, rect.centerY(), h * 0.36f, theme.mutedText, theme.todayFill, night = !wx.isDay)

        val tempP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.dayText; typeface = Draw.bold; textSize = u * 2.0f }
        val temp = wx.temp?.let { "$it°" } ?: "—"
        val tx = rect.left + h * 1.15f
        canvas.drawText(temp, tx, Draw.centerBaseline(tempP, rect.centerY()), tempP)

        val lx = tx + tempP.measureText(temp) + u * 0.9f
        val line1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.dayText; typeface = Draw.medium; textSize = u * 0.95f }
        val line2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.mutedText; typeface = Draw.regular; textSize = u * 0.82f }
        val details = buildList {
            wx.high?.let { add("H $it°") }
            wx.low?.let { add("L $it°") }
            wx.precipPct?.let { add("$it% rain") }
        }.joinToString("  ·  ")
        val maxW = rect.right - lx - u * 0.6f
        val headline = wx.label + (wx.feelsLike?.let { " · feels like $it°" } ?: "")
        canvas.drawText(Draw.ellipsize(headline, line1, maxW), lx, rect.centerY() - u * 0.15f, line1)
        canvas.drawText(Draw.ellipsize(details, line2, maxW), lx, rect.centerY() + u * 1.0f, line2)
        return rect.bottom + u * 0.6f
    }

    private fun drawWear(canvas: Canvas, left: Float, top: Float, right: Float, spec: DaySpec, theme: MonthTheme, u: Float): Float {
        val advice = spec.wear ?: return top
        val rect = RectF(left, top, right, top + u * 4.4f)
        card(canvas, rect, theme)
        WearIcon.panel(canvas, rect, advice, theme.dayText, theme.mutedText)
        return rect.bottom + u * 0.6f
    }

    private fun drawAllDay(
        canvas: Canvas, left: Float, top: Float, right: Float, events: List<CalEvent>,
        theme: MonthTheme, u: Float, maxLines: Int,
    ): Float {
        if (events.isEmpty()) return top
        val chipH = u * 1.7f
        val gap = u * 0.35f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.medium; textSize = chipH * 0.55f }
        val chip = Paint(Paint.ANTI_ALIAS_FLAG)
        val show = if (events.size > maxLines) maxLines - 1 else events.size
        var y = top
        for (e in events.take(show)) {
            val r = RectF(left, y, right, y + chipH)
            chip.color = Draw.eventColor(e, theme)
            canvas.drawRoundRect(r, chipH * 0.25f, chipH * 0.25f, chip)
            text.color = Draw.onColor(chip.color)
            val tx = r.left + chipH * 0.4f
            canvas.drawText(Draw.ellipsize(e.title, text, r.right - tx - chipH * 0.3f), tx, Draw.centerBaseline(text, r.centerY()), text)
            y += chipH + gap
        }
        if (events.size > show) {
            text.color = theme.mutedText
            canvas.drawText("+${events.size - show} more all-day", left + chipH * 0.2f, Draw.centerBaseline(text, y + chipH * 0.4f), text)
            y += chipH * 0.8f + gap
        }
        return y + u * 0.25f
    }

    private fun drawUpNext(
        canvas: Canvas, left: Float, top: Float, right: Float, timed: List<CalEvent>,
        spec: DaySpec, theme: MonthTheme, u: Float,
    ): Float {
        val h = u * 2.8f
        val rect = RectF(left, top, right, top + h)
        card(canvas, rect, theme)
        val now = spec.nowMillis
        val current = timed.firstOrNull { it.beginMillis <= now && it.endMillis > now }
        val next = timed.firstOrNull { it.beginMillis > now }
        val (label, event) = when {
            !spec.isToday -> "First up" to timed.firstOrNull()
            current != null -> "Happening now" to current
            next != null -> "Up next" to next
            else -> "All done" to null
        }

        // Clock glyph
        val cx = rect.left + h * 0.5f
        val cy = rect.centerY()
        val r = h * 0.24f
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = r * 0.18f; color = theme.todayFill; strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(cx, cy, r, st)
        canvas.drawLine(cx, cy, cx, cy - r * 0.6f, st)
        canvas.drawLine(cx, cy, cx + r * 0.45f, cy, st)

        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.mutedText; typeface = Draw.medium; textSize = u * 0.85f }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.dayText; typeface = Draw.bold; textSize = u * 1.1f }
        val x = rect.left + h * 0.95f
        canvas.drawText(label, x, Draw.centerBaseline(lp, cy), lp)
        val x2 = x + lp.measureText(label) + u * 0.8f
        val text = if (event == null) "Nothing else today" else {
            val time = Draw.timeLabel(event.beginMillis, spec.zone, spec.use24h)
            val mins = (event.beginMillis - now) / 60_000
            val countdown = if (spec.isToday && event === next && mins in 1L..179L) {
                if (mins < 60) " · in ${mins}m" else " · in ${mins / 60}h ${mins % 60}m"
            } else ""
            "${event.title} · $time$countdown"
        }
        canvas.drawText(Draw.ellipsize(text, tp, rect.right - x2 - u * 0.6f), x2, Draw.centerBaseline(tp, cy), tp)
        return rect.bottom + u * 0.6f
    }

    // ---------- Timeline ----------

    private fun drawTimeline(
        canvas: Canvas, area: RectF, timed: List<CalEvent>, spec: DaySpec, theme: MonthTheme, u: Float,
    ) {
        if (area.height() < u * 4) return
        card(canvas, area, theme)
        val dayStart = spec.date.atStartOfDay(spec.zone).toInstant().toEpochMilli()
        fun hourOf(ms: Long) = ((ms - dayStart) / 3_600_000.0).toFloat().coerceIn(0f, 24f)

        var startH = 8f
        var endH = 21f
        for (e in timed) {
            startH = min(startH, floor(hourOf(e.beginMillis)))
            endH = max(endH, ceil(hourOf(e.endMillis)))
        }
        if (spec.isToday) {
            val nowH = hourOf(spec.nowMillis)
            if (nowH in 0f..24f) { startH = min(startH, floor(nowH)); endH = max(endH, ceil(nowH)) }
        }
        startH = startH.coerceIn(0f, 23f); endH = endH.coerceIn(startH + 1, 24f)

        val labelW = u * 2.6f
        val top = area.top + u * 0.8f
        val bottom = area.bottom - u * 0.8f
        val left = area.left + labelW
        val right = area.right - u * 0.6f
        val pxPerHour = (bottom - top) / (endH - startH)
        fun yOf(hour: Float) = top + (hour - startH) * pxPerHour

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.outCellBg; strokeWidth = max(1f, u * 0.05f) }
        val hourP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.mutedText; typeface = Draw.regular; textSize = min(u * 0.8f, pxPerHour * 0.45f)
            textAlign = Paint.Align.RIGHT
        }
        val step = if (pxPerHour < hourP.textSize * 1.6f) 2 else 1
        var hInt = startH.toInt()
        while (hInt <= endH.toInt()) {
            val y = yOf(hInt.toFloat())
            canvas.drawLine(left, y, right, y, line)
            if ((hInt - startH.toInt()) % step == 0) {
                canvas.drawText(hourLabel(hInt, spec.use24h), left - u * 0.4f, Draw.centerBaseline(hourP, y), hourP)
            }
            hInt++
        }

        // Lay out overlapping events side by side.
        val placed = layoutColumns(timed)
        val block = Paint(Paint.ANTI_ALIAS_FLAG)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.bold; textSize = u * 0.95f }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.regular; textSize = u * 0.78f }
        val minH = title.textSize * 1.5f
        for ((e, col, cols) in placed) {
            val y1 = yOf(hourOf(e.beginMillis))
            val y2 = max(y1 + minH, yOf(hourOf(e.endMillis)))
            val colW = (right - left - u * 0.4f) / cols
            val x1 = left + u * 0.4f + col * colW
            val r = RectF(x1, y1 + 1, x1 + colW - u * 0.25f, min(y2, bottom) - 1)
            block.color = Draw.eventColor(e, theme)
            canvas.drawRoundRect(r, u * 0.35f, u * 0.35f, block)
            val fg = Draw.onColor(block.color)
            title.color = fg; sub.color = fg
            val tx = r.left + u * 0.45f
            val maxW = r.right - tx - u * 0.3f
            val range = Draw.timeRange(e, spec.zone, spec.use24h)
            if (r.height() >= title.textSize * 2.6f) {
                canvas.drawText(Draw.ellipsize(e.title, title, maxW), tx, r.top + title.textSize * 1.15f, title)
                canvas.drawText(Draw.ellipsize(range, sub, maxW), tx, r.top + title.textSize * 1.15f + sub.textSize * 1.35f, sub)
            } else {
                val one = "${Draw.timeLabel(e.beginMillis, spec.zone, spec.use24h)} ${e.title}"
                canvas.drawText(Draw.ellipsize(one, title, maxW), tx, Draw.centerBaseline(title, r.centerY()), title)
            }
        }

        if (timed.isEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.mutedText; typeface = Draw.medium; textSize = u * 1.1f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No timed events", (left + right) / 2, Draw.centerBaseline(p, (top + bottom) / 2), p)
        }

        // Current time
        if (spec.isToday) {
            val nowH = hourOf(spec.nowMillis)
            if (nowH in startH..endH) {
                val y = yOf(nowH)
                val nowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E24B4A"); strokeWidth = u * 0.12f }
                canvas.drawLine(left, y, right, y, nowP)
                canvas.drawCircle(left, y, u * 0.28f, nowP)
            }
        }
    }

    private fun hourLabel(h: Int, use24h: Boolean): String {
        if (use24h) return "%02d:00".format(h % 24)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12${if (h % 24 < 12) "a" else "p"}"
    }

    /** Greedy column packing within clusters of overlapping events. Returns (event, column, columnsInCluster). */
    private fun layoutColumns(events: List<CalEvent>): List<Triple<CalEvent, Int, Int>> {
        val sorted = events.sortedWith(compareBy({ it.beginMillis }, { -it.endMillis }))
        val out = mutableListOf<Triple<CalEvent, Int, Int>>()
        var cluster = mutableListOf<Pair<CalEvent, Int>>()
        val colEnds = mutableListOf<Long>()
        var clusterEnd = Long.MIN_VALUE
        fun flush() {
            val n = (cluster.maxOfOrNull { it.second } ?: -1) + 1
            cluster.forEach { (e, c) -> out += Triple(e, c, max(1, n)) }
            cluster = mutableListOf(); colEnds.clear()
        }
        for (e in sorted) {
            if (cluster.isNotEmpty() && e.beginMillis >= clusterEnd) flush()
            var col = colEnds.indexOfFirst { it <= e.beginMillis }
            if (col == -1) { colEnds += e.endMillis; col = colEnds.size - 1 } else colEnds[col] = e.endMillis
            cluster += e to col
            clusterEnd = if (cluster.size == 1) e.endMillis else max(clusterEnd, e.endMillis)
        }
        if (cluster.isNotEmpty()) flush()
        return out
    }

    // ---------- Landscape list ----------

    private fun drawEventList(canvas: Canvas, area: RectF, timed: List<CalEvent>, spec: DaySpec, theme: MonthTheme, u: Float) {
        if (timed.isEmpty() || area.height() < u * 3) return
        card(canvas, area, theme)
        val rowH = u * 2.6f
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val time = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.mutedText; typeface = Draw.medium; textSize = u * 0.85f }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.dayText; typeface = Draw.medium; textSize = u * 1.0f }
        val fit = max(1, floor((area.height() - u) / rowH).toInt())
        val show = if (timed.size > fit) fit - 1 else timed.size
        var y = area.top + u * 0.5f
        val now = spec.nowMillis
        for (e in timed.take(show)) {
            val past = spec.isToday && e.endMillis <= now
            val alpha = if (past) 110 else 255
            val cy = y + rowH / 2
            dot.color = Draw.eventColor(e, theme); dot.alpha = alpha
            canvas.drawCircle(area.left + u * 1.0f, cy, u * 0.35f, dot)
            val tx = area.left + u * 1.9f
            time.alpha = alpha; title.alpha = alpha
            canvas.drawText(Draw.timeRange(e, spec.zone, spec.use24h), tx, cy - u * 0.25f, time)
            canvas.drawText(Draw.ellipsize(e.title, title, area.right - tx - u * 0.6f), tx, cy + u * 0.95f, title)
            y += rowH
        }
        if (timed.size > show) {
            time.alpha = 255
            canvas.drawText("+${timed.size - show} more", area.left + u * 1.9f, y + u * 1.0f, time)
        }
    }
}
