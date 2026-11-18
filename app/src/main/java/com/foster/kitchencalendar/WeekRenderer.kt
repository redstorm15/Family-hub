package com.foster.kitchencalendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Proportions shared with res/layout/widget_week_rows.xml and widget_week_cols.xml
 * (header weight 12 / days weight 88).
 */
object WeekLayout {
    const val HEADER_FRAC = 0.12f
    const val DAYS = 7

    /** Auto picks columns when the widget is clearly wider than tall. */
    fun useColumns(mode: Int, w: Float, h: Float) = when (mode) {
        1 -> false
        2 -> true
        else -> w > h * 1.15f
    }
}

data class WeekSpec(
    val start: LocalDate,                       // today: the week rolls forward from here
    val today: LocalDate,
    val eventsByDay: Map<LocalDate, List<CalEvent>>,
    val showTimes: Boolean,
    val use24h: Boolean,
    val zone: ZoneId,
    val textScale: Float,
)

object WeekRenderer {

    fun render(w: Int, h: Int, spec: WeekSpec, columns: Boolean): Bitmap {
        val theme = Themes.forMonth(spec.today.monthValue)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pad = MonthLayout.pad(w, h)
        val contentH = h - pad
        val headerH = contentH * WeekLayout.HEADER_FRAC
        val innerW = w - 2 * pad

        canvas.drawColor(theme.headerBg)
        Draw.decorations(
            canvas, w.toFloat(), headerH, spec.start.toEpochDay().toInt(), theme,
            keepClear = listOf(RectF(w * 0.22f, 0f, w * 0.78f, headerH)),
        )
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.bodyBg }
        canvas.drawRoundRect(RectF(pad * 0.5f, headerH, w - pad * 0.5f, h - pad * 0.5f), pad, pad, body)
        drawHeader(canvas, w.toFloat(), headerH, spec, theme)

        val area = RectF(pad, headerH, w - pad, contentH)
        if (columns) drawColumns(canvas, area, spec, theme) else drawRows(canvas, area, spec, theme)
        return bmp
    }

    private fun rangeLabel(spec: WeekSpec): String {
        val end = spec.start.plusDays((WeekLayout.DAYS - 1).toLong())
        val m1 = spec.start.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val m2 = end.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return if (m1 == m2) "$m1 ${spec.start.dayOfMonth} – ${end.dayOfMonth}"
        else "$m1 ${spec.start.dayOfMonth} – $m2 ${end.dayOfMonth}"
    }

    private fun drawHeader(canvas: Canvas, w: Float, headerH: Float, spec: WeekSpec, theme: MonthTheme) {
        val title = "This week"
        val range = rangeLabel(spec)
        val size = min(headerH * 0.5f, w * 0.06f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.headerText; typeface = Draw.bold; textSize = size }
        val q = Paint(p).apply { typeface = Draw.medium; textSize = size * 0.6f; letterSpacing = 0.08f }
        val gap = size * 0.35f
        val total = p.measureText(title) + gap + q.measureText(range)
        val x0 = (w - total) / 2
        val baseline = Draw.centerBaseline(p, headerH / 2)
        canvas.drawText(title, x0, baseline, p)
        canvas.drawText(range, x0 + p.measureText(title) + gap, baseline, q)
    }

    // ---------- Portrait: one row per day ----------

    private fun drawRows(canvas: Canvas, area: RectF, spec: WeekSpec, theme: MonthTheme) {
        val rowH = area.height() / WeekLayout.DAYS
        val g = min(area.width(), rowH) * 0.03f
        val unit = min(rowH * 0.2f, area.width() * 0.032f) * spec.textScale
        val chipH = unit * 1.15f
        val chipGap = chipH * 0.16f
        val dateW = max(unit * 3.6f, area.width() * 0.13f)

        val cell = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = g * 0.9f; color = theme.todayFill }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.medium; textAlign = Paint.Align.CENTER; textSize = unit * 0.72f; letterSpacing = 0.08f }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.medium; textAlign = Paint.Align.CENTER; textSize = unit * 1.7f }
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.regular; textSize = chipH * 0.68f }

        for (i in 0 until WeekLayout.DAYS) {
            val date = spec.start.plusDays(i.toLong())
            val isToday = date == spec.today
            val rect = RectF(area.left + g, area.top + i * rowH + g, area.right - g, area.top + (i + 1) * rowH - g)
            val radius = g * 2.5f
            cell.color = theme.cellBg
            canvas.drawRoundRect(rect, radius, radius, cell)
            if (isToday) canvas.drawRoundRect(rect, radius, radius, ring)

            // Date block
            val dcx = rect.left + dateW / 2
            val accent = if (isToday) theme.todayFill else theme.dayText
            label.color = if (isToday) theme.todayFill else theme.mutedText
            num.color = accent
            val dowText = when (date) {
                spec.today -> "TODAY"
                spec.today.plusDays(1) -> "TMRW"
                else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
            }
            val blockH = label.textSize + unit * 0.2f + num.textSize
            val top = rect.centerY() - blockH / 2
            canvas.drawText(dowText, dcx, top + label.textSize * 0.85f, label)
            canvas.drawText(date.dayOfMonth.toString(), dcx, top + blockH - num.descent() * 0.5f, num)

            // Events, flowing into a second column when the row is wide enough
            val evLeft = rect.left + dateW + g * 2
            val evRight = rect.right - g * 2
            val events = spec.eventsByDay[date].orEmpty()
            if (events.isEmpty()) {
                text.color = theme.mutedText; text.alpha = 255
                canvas.drawText("Nothing scheduled", evLeft, Draw.centerBaseline(text, rect.centerY()), text)
                continue
            }
            val cols = if (evRight - evLeft > chipH * 24) 2 else 1
            val colW = (evRight - evLeft - (cols - 1) * g * 3) / cols
            val usableH = rect.height() - g * 2
            val perCol = max(1, floor((usableH + chipGap) / (chipH + chipGap)).toInt())
            val capacity = perCol * cols
            val showCount = if (events.size > capacity) capacity - 1 else events.size
            val items = showCount + if (events.size > showCount) 1 else 0     // chips + "+N more"
            // Balance items across columns (e.g. 2 + 2, not 3 + 1), filling top to bottom.
            val rowsUsed = min(perCol, (items + cols - 1) / cols)
            val listH = rowsUsed * (chipH + chipGap) - chipGap
            val y0 = rect.centerY() - listH / 2
            for (k in 0 until showCount) {
                val c = k / rowsUsed
                val r = k % rowsUsed
                val x = evLeft + c * (colW + g * 3)
                val y = y0 + r * (chipH + chipGap)
                Draw.chip(canvas, events[k], RectF(x, y, x + colW, y + chipH), theme, chipPaint, text, 255,
                    spec.showTimes, spec.use24h, spec.zone)
            }
            val hidden = events.size - showCount
            if (hidden > 0) {
                val k = showCount
                val x = evLeft + (k / rowsUsed) * (colW + g * 3)
                val y = y0 + (k % rowsUsed) * (chipH + chipGap)
                text.color = theme.mutedText; text.alpha = 255; text.typeface = Draw.medium
                canvas.drawText("+$hidden more", x + chipH * 0.2f, Draw.centerBaseline(text, y + chipH / 2), text)
                text.typeface = Draw.regular
            }
        }
    }

    // ---------- Landscape: seven columns ----------

    private fun drawColumns(canvas: Canvas, area: RectF, spec: WeekSpec, theme: MonthTheme) {
        val colW = area.width() / WeekLayout.DAYS
        val g = min(colW, area.height()) * 0.025f
        val unit = min(area.height() * 0.035f, colW * 0.09f) * spec.textScale
        val chipH = unit * 1.15f
        val chipGap = chipH * 0.16f

        val cell = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = g * 1.2f; color = theme.todayFill }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.medium; textAlign = Paint.Align.CENTER; textSize = unit * 0.8f }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.medium; textAlign = Paint.Align.CENTER; textSize = unit * 1.6f }
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.regular; textSize = chipH * 0.68f }

        for (i in 0 until WeekLayout.DAYS) {
            val date = spec.start.plusDays(i.toLong())
            val isToday = date == spec.today
            val rect = RectF(area.left + i * colW + g, area.top + g, area.left + (i + 1) * colW - g, area.bottom - g)
            val radius = g * 2.5f
            cell.color = theme.cellBg
            canvas.drawRoundRect(rect, radius, radius, cell)
            if (isToday) canvas.drawRoundRect(rect, radius, radius, ring)

            val cx = rect.centerX()
            var y = rect.top + unit * 1.3f
            label.color = if (isToday) theme.todayFill else theme.mutedText
            val dow = if (isToday) "Today" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            canvas.drawText(dow, cx, y, label)
            y += unit * 0.5f
            val numCy = y + num.textSize * 0.65f
            if (isToday) {
                cell.color = theme.todayFill
                canvas.drawCircle(cx, numCy, num.textSize * 0.72f, cell)
                num.color = theme.todayText
            } else num.color = theme.dayText
            canvas.drawText(date.dayOfMonth.toString(), cx, Draw.centerBaseline(num, numCy), num)
            y = numCy + num.textSize * 0.95f

            val left = rect.left + g * 1.5f
            val right = rect.right - g * 1.5f
            val events = spec.eventsByDay[date].orEmpty()
            val fit = max(0, floor((rect.bottom - g - y + chipGap) / (chipH + chipGap)).toInt())
            if (events.isEmpty() || fit == 0) continue
            val showCount = if (events.size > fit) fit - 1 else events.size
            for (e in events.take(showCount)) {
                Draw.chip(canvas, e, RectF(left, y, right, y + chipH), theme, chipPaint, text, 255,
                    spec.showTimes, spec.use24h, spec.zone)
                y += chipH + chipGap
            }
            val hidden = events.size - showCount
            if (hidden > 0) {
                text.color = theme.mutedText; text.alpha = 255; text.typeface = Draw.medium
                canvas.drawText("+$hidden more", left + chipH * 0.2f, Draw.centerBaseline(text, y + chipH / 2), text)
                text.typeface = Draw.regular
            }
        }
    }
}
