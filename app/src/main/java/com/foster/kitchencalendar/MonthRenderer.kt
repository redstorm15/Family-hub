package com.foster.kitchencalendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Proportions shared by the renderer and the tap-target overlay (res/layout/widget_month.xml).
 * Keep these in sync with the XML weights 20 / 5 / 75.
 */
object MonthLayout {
    const val HEADER_FRAC = 0.20f
    const val DOW_FRAC = 0.05f
    const val GRID_FRAC = 0.75f
    const val ROWS = 6
    const val COLS = 7

    /** Outer margin in px; also applied as padding to the overlay so taps line up. */
    fun pad(w: Int, h: Int): Float = min(w, h) * 0.02f

    fun gridStart(month: YearMonth, weekStartsMonday: Boolean): LocalDate {
        val first = if (weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        return month.atDay(1).with(TemporalAdjusters.previousOrSame(first))
    }
}

data class MonthSpec(
    val month: YearMonth,
    val today: LocalDate,
    val weekStartsMonday: Boolean,
    val showTimes: Boolean,
    val use24h: Boolean,
    val zone: ZoneId,
    val eventsByDay: Map<LocalDate, List<CalEvent>>,
)

object MonthRenderer {

    private val boldFace: Typeface = Typeface.create(Typeface.SANS_SERIF, 700, false)
    private val mediumFace: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
    private val regularFace: Typeface = Typeface.create(Typeface.SANS_SERIF, 400, false)

    fun render(w: Int, h: Int, spec: MonthSpec): Bitmap {
        val theme = Themes.forMonth(spec.month.monthValue)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val pad = MonthLayout.pad(w, h)
        val contentH = h - pad                         // overlay has bottom padding = pad
        val headerH = contentH * MonthLayout.HEADER_FRAC
        val dowH = contentH * MonthLayout.DOW_FRAC
        val gridTop = headerH + dowH
        val rowH = contentH * MonthLayout.GRID_FRAC / MonthLayout.ROWS
        val innerW = w - 2 * pad
        val colW = innerW / MonthLayout.COLS

        canvas.drawColor(theme.headerBg)
        drawDecorations(canvas, w.toFloat(), headerH, pad, innerW, spec.month, theme)

        // Body panel behind weekday labels and grid
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.bodyBg }
        canvas.drawRoundRect(RectF(pad * 0.5f, headerH, w - pad * 0.5f, h - pad * 0.5f), pad, pad, body)

        drawHeaderText(canvas, w.toFloat(), headerH, spec, theme)
        drawNav(canvas, w.toFloat(), headerH, pad, innerW, theme)
        drawWeekdays(canvas, pad, headerH, dowH, colW, spec, theme)
        drawGrid(canvas, pad, gridTop, rowH, colW, spec, theme)
        return bmp
    }

    // ---------- Header ----------

    private fun drawDecorations(
        canvas: Canvas, w: Float, headerH: Float, pad: Float, innerW: Float, month: YearMonth, theme: MonthTheme,
    ) {
        val rnd = Random(month.year * 100 + month.monthValue)   // same art every redraw of a given month
        val base = min(headerH * 0.16f, w * 0.05f)
        val count = if (w > headerH * 6) 16 else 11
        // Keep clear of the title block and the two nav arrows.
        val titleBox = RectF(w * 0.3f, headerH * 0.08f, w * 0.7f, headerH * 0.95f)
        val navR = headerH * 0.2f
        val navL = pad + innerW / 10f to headerH / 2
        val navRt = w - pad - innerW / 10f to headerH / 2
        val placed = mutableListOf<Triple<Float, Float, Float>>()
        var attempts = 0
        while (placed.size < count && attempts < 400) {
            attempts++
            val s = base * (0.6f + rnd.nextFloat() * 0.8f)
            val x = s + rnd.nextFloat() * (w - 2 * s)
            val y = s * 0.8f + rnd.nextFloat() * (headerH - 1.6f * s)
            if (RectF(x - s, y - s, x + s, y + s).intersects(titleBox.left, titleBox.top, titleBox.right, titleBox.bottom)) continue
            if (hypot(x - navL.first, y - navL.second) < navR + s || hypot(x - navRt.first, y - navRt.second) < navR + s) continue
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

    private fun drawHeaderText(canvas: Canvas, w: Float, headerH: Float, spec: MonthSpec, theme: MonthTheme) {
        val name = spec.month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
        val monthSize = min(headerH * 0.40f, w * 0.085f)
        val smallSize = monthSize * 0.3f
        val gap = monthSize * 0.12f
        val blockH = smallSize + gap + monthSize + gap + smallSize
        var y = (headerH - blockH) / 2

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.headerText; textAlign = Paint.Align.CENTER }

        p.typeface = mediumFace; p.textSize = smallSize; p.letterSpacing = 0.25f
        y += smallSize
        canvas.drawText(spec.month.year.toString(), w / 2, y - p.descent() * 0.5f, p)

        p.typeface = boldFace; p.textSize = monthSize; p.letterSpacing = 0.02f
        y += gap + monthSize
        canvas.drawText(name, w / 2, y - p.descent() * 0.6f, p)

        p.typeface = regularFace; p.textSize = smallSize; p.letterSpacing = 0.05f
        y += gap + smallSize
        val isCurrent = YearMonth.from(spec.today) == spec.month
        val line = if (isCurrent) theme.tagline else "Tap here for today"
        canvas.drawText(line, w / 2, y - p.descent() * 0.5f, p)
    }

    private fun drawNav(canvas: Canvas, w: Float, headerH: Float, pad: Float, innerW: Float, theme: MonthTheme) {
        val r = min(headerH * 0.17f, innerW * 0.04f)
        val cy = headerH / 2
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(48, Color.red(theme.headerText), Color.green(theme.headerText), Color.blue(theme.headerText))
        }
        val chev = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = theme.headerText; strokeWidth = r * 0.16f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        for ((cx, dir) in listOf(pad + innerW / 10f to -1f, w - pad - innerW / 10f to 1f)) {
            canvas.drawCircle(cx, cy, r, circle)
            val a = r * 0.32f
            val path = Path().apply {
                moveTo(cx - dir * a * 0.5f, cy - a); lineTo(cx + dir * a * 0.6f, cy); lineTo(cx - dir * a * 0.5f, cy + a)
            }
            canvas.drawPath(path, chev)
        }
    }

    // ---------- Grid ----------

    private fun drawWeekdays(
        canvas: Canvas, pad: Float, headerH: Float, dowH: Float, colW: Float, spec: MonthSpec, theme: MonthTheme,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.dayText; typeface = mediumFace; textAlign = Paint.Align.CENTER
            textSize = min(dowH * 0.45f, colW * 0.16f)
        }
        val start = MonthLayout.gridStart(spec.month, spec.weekStartsMonday)
        val labels = (0 until 7).map { start.plusDays(it.toLong()).dayOfWeek }
        val short = labels.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        val useNarrow = short.any { p.measureText(it) > colW * 0.85f }
        labels.forEachIndexed { i, dow ->
            val text = if (useNarrow) dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()) else short[i]
            val cx = pad + colW * (i + 0.5f)
            val baseline = headerH + dowH / 2 - (p.ascent() + p.descent()) / 2
            canvas.drawText(text, cx, baseline, p)
        }
    }

    private fun drawGrid(
        canvas: Canvas, pad: Float, gridTop: Float, rowH: Float, colW: Float, spec: MonthSpec, theme: MonthTheme,
    ) {
        val start = MonthLayout.gridStart(spec.month, spec.weekStartsMonday)
        val g = min(colW, rowH) * 0.025f
        val radius = g * 2.5f
        val numSize = min(rowH * 0.15f, colW * 0.15f)
        val chipH = numSize * 1.15f
        val chipGap = chipH * 0.14f

        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = numSize; typeface = mediumFace; textAlign = Paint.Align.CENTER }
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = chipH * 0.68f; typeface = regularFace }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = g * 1.6f; color = theme.todayFill }

        for (i in 0 until MonthLayout.ROWS * MonthLayout.COLS) {
            val r = i / MonthLayout.COLS
            val c = i % MonthLayout.COLS
            val date = start.plusDays(i.toLong())
            val inMonth = YearMonth.from(date) == spec.month
            val isToday = date == spec.today
            val rect = RectF(
                pad + c * colW + g, gridTop + r * rowH + g,
                pad + (c + 1) * colW - g, gridTop + (r + 1) * rowH - g,
            )
            cellPaint.color = if (inMonth) theme.cellBg else theme.outCellBg
            canvas.drawRoundRect(rect, radius, radius, cellPaint)
            if (isToday) canvas.drawRoundRect(rect, radius, radius, ring)

            // Day number
            val numCx = rect.left + numSize * 0.95f
            val numCy = rect.top + numSize * 0.95f
            if (isToday) {
                cellPaint.color = theme.todayFill
                canvas.drawCircle(numCx, numCy, numSize * 0.78f, cellPaint)
                numPaint.color = theme.todayText
            } else {
                numPaint.color = if (inMonth) theme.dayText else theme.mutedText
            }
            canvas.drawText(date.dayOfMonth.toString(), numCx, numCy - (numPaint.ascent() + numPaint.descent()) / 2, numPaint)

            // Events
            val events = spec.eventsByDay[date].orEmpty()
            if (events.isEmpty()) continue
            val top = rect.top + numSize * 1.95f
            val avail = rect.bottom - g - top
            val fit = max(0, floor((avail + chipGap) / (chipH + chipGap)).toInt())
            if (fit == 0) continue
            val showCount = if (events.size > fit) fit - 1 else events.size
            val alpha = if (inMonth) 255 else 140
            var y = top
            val left = rect.left + g * 1.5f
            val right = rect.right - g * 1.5f
            for (e in events.take(showCount)) {
                drawChip(canvas, e, RectF(left, y, right, y + chipH), spec, theme, chipPaint, textPaint, alpha)
                y += chipH + chipGap
            }
            val hidden = events.size - showCount
            if (hidden > 0) {
                textPaint.color = theme.mutedText; textPaint.alpha = alpha; textPaint.typeface = mediumFace
                canvas.drawText("+$hidden more", left + chipH * 0.2f, y + chipH * 0.5f - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
                textPaint.typeface = regularFace
            }
        }
    }

    private fun drawChip(
        canvas: Canvas, e: CalEvent, box: RectF, spec: MonthSpec, theme: MonthTheme,
        chipPaint: Paint, textPaint: Paint, alpha: Int,
    ) {
        val color = if (e.color == 0) theme.todayFill else (e.color or 0xFF000000.toInt())
        val r = box.height() * 0.22f
        val baseline = box.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
        if (e.allDay) {
            // All-day / multi-day: solid colored bar, like Google Calendar.
            chipPaint.color = color; chipPaint.alpha = alpha
            canvas.drawRoundRect(box, r, r, chipPaint)
            textPaint.color = if (luminance(color) > 0.6) Color.parseColor("#2C2C2A") else Color.WHITE
            textPaint.alpha = alpha
            val tx = box.left + box.height() * 0.3f
            canvas.drawText(ellipsize(e.title, textPaint, box.right - tx - box.height() * 0.2f), tx, baseline, textPaint)
        } else {
            // Timed: colored dot + "3p Title".
            chipPaint.color = color; chipPaint.alpha = alpha
            val dotR = box.height() * 0.2f
            canvas.drawCircle(box.left + dotR + box.height() * 0.1f, box.centerY(), dotR, chipPaint)
            textPaint.color = theme.dayText; textPaint.alpha = alpha
            val tx = box.left + dotR * 2 + box.height() * 0.3f
            val label = if (spec.showTimes) "${timeLabel(e.beginMillis, spec)} ${e.title}" else e.title
            canvas.drawText(ellipsize(label, textPaint, box.right - tx), tx, baseline, textPaint)
        }
    }

    private fun timeLabel(millis: Long, spec: MonthSpec): String {
        val t = Instant.ofEpochMilli(millis).atZone(spec.zone).toLocalTime()
        if (spec.use24h) return "%d:%02d".format(t.hour, t.minute)
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        val ampm = if (t.hour < 12) "a" else "p"
        return if (t.minute == 0) "$h12$ampm" else "$h12:%02d$ampm".format(t.minute)
    }

    private fun ellipsize(text: String, p: Paint, maxW: Float): String {
        if (maxW <= 0) return ""
        if (p.measureText(text) <= maxW) return text
        val ell = "…"
        val n = p.breakText(text, true, maxW - p.measureText(ell), null)
        return if (n <= 0) "" else text.substring(0, n).trimEnd() + ell
    }

    private fun luminance(c: Int): Double =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
}
