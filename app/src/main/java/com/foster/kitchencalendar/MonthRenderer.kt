package com.foster.kitchencalendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Proportions shared by the renderer and the tap-target overlays.
 * Keep in sync with the XML weights:
 *   widget_month.xml          20 / 5 / 75  (standard header)
 *   widget_month_compact.xml  13 / 5 / 82  (compact header)
 */
object MonthLayout {
    const val DOW_FRAC = 0.05f
    const val COLS = 7
    /** Text is sized as if the grid always had this many rows, so dropping a row adds room for events. */
    const val REFERENCE_ROWS = 6

    fun headerFrac(compact: Boolean) = if (compact) 0.13f else 0.20f
    fun gridFrac(compact: Boolean) = 1f - headerFrac(compact) - DOW_FRAC

    /** Outer margin in px; also applied as padding to the overlay so taps line up. */
    fun pad(w: Int, h: Int): Float = min(w, h) * 0.02f

    fun gridStart(month: YearMonth, weekStartsMonday: Boolean): LocalDate {
        val first = if (weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        return month.atDay(1).with(TemporalAdjusters.previousOrSame(first))
    }

    /** Weeks the month actually spans: usually 5, sometimes 6, rarely 4. */
    fun rowsFor(month: YearMonth, weekStartsMonday: Boolean): Int {
        val lead = java.time.temporal.ChronoUnit.DAYS.between(gridStart(month, weekStartsMonday), month.atDay(1)).toInt()
        return (lead + month.lengthOfMonth() + COLS - 1) / COLS
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
    val rows: Int,
    val compactHeader: Boolean,
    /** 1.0 normal, ~1.2 large, ~1.4 extra large. */
    val textScale: Float,
)

object MonthRenderer {

    private val boldFace get() = Draw.bold
    private val mediumFace get() = Draw.medium
    private val regularFace get() = Draw.regular

    fun render(w: Int, h: Int, spec: MonthSpec): Bitmap {
        val theme = Themes.forMonth(spec.month.monthValue)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val pad = MonthLayout.pad(w, h)
        val contentH = h - pad                         // overlay has bottom padding = pad
        val headerH = contentH * MonthLayout.headerFrac(spec.compactHeader)
        val dowH = contentH * MonthLayout.DOW_FRAC
        val gridTop = headerH + dowH
        val gridH = contentH * MonthLayout.gridFrac(spec.compactHeader)
        val rowH = gridH / spec.rows
        val innerW = w - 2 * pad
        val colW = innerW / MonthLayout.COLS

        canvas.drawColor(theme.headerBg)
        drawDecorations(canvas, w.toFloat(), headerH, pad, innerW, spec.month, theme, spec.compactHeader)

        // Body panel behind weekday labels and grid
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.bodyBg }
        canvas.drawRoundRect(RectF(pad * 0.5f, headerH, w - pad * 0.5f, h - pad * 0.5f), pad, pad, body)

        drawHeaderText(canvas, w.toFloat(), headerH, spec, theme)
        drawNav(canvas, w.toFloat(), headerH, pad, innerW, theme)
        drawWeekdays(canvas, pad, headerH, dowH, colW, spec, theme)
        drawGrid(canvas, pad, gridTop, rowH, gridH / MonthLayout.REFERENCE_ROWS, colW, spec, theme)
        return bmp
    }

    // ---------- Header ----------

    private fun drawDecorations(
        canvas: Canvas, w: Float, headerH: Float, pad: Float, innerW: Float, month: YearMonth, theme: MonthTheme,
        compact: Boolean,
    ) {
        val titleBox = if (compact) RectF(w * 0.2f, 0f, w * 0.8f, headerH)
                       else RectF(w * 0.3f, headerH * 0.08f, w * 0.7f, headerH * 0.95f)
        val navR = headerH * 0.2f
        Draw.decorations(
            canvas, w, headerH, month.year * 100 + month.monthValue, theme,
            keepClear = listOf(titleBox),
            avoidCircles = listOf(
                Triple(pad + innerW / 10f, headerH / 2, navR),
                Triple(w - pad - innerW / 10f, headerH / 2, navR),
            ),
        )
    }

    private fun drawHeaderText(canvas: Canvas, w: Float, headerH: Float, spec: MonthSpec, theme: MonthTheme) {
        val name = spec.month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
        if (spec.compactHeader) {
            drawCompactHeaderText(canvas, w, headerH, name, spec, theme)
            return
        }
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

    /** One line: "November 2026", with a small hint underneath when viewing another month. */
    private fun drawCompactHeaderText(
        canvas: Canvas, w: Float, headerH: Float, name: String, spec: MonthSpec, theme: MonthTheme,
    ) {
        val isCurrent = YearMonth.from(spec.today) == spec.month
        val monthSize = min(headerH * (if (isCurrent) 0.55f else 0.45f), w * 0.07f)
        val yearSize = monthSize * 0.55f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.headerText }

        p.typeface = boldFace; p.textSize = monthSize; p.letterSpacing = 0.02f
        val nameW = p.measureText(name)
        val gap = monthSize * 0.3f
        val yearPaint = Paint(p).apply { typeface = mediumFace; textSize = yearSize; letterSpacing = 0.15f }
        val yearText = spec.month.year.toString()
        val totalW = nameW + gap + yearPaint.measureText(yearText)
        val x0 = (w - totalW) / 2

        val hintSize = monthSize * 0.32f
        val blockH = monthSize + if (isCurrent) 0f else hintSize * 1.4f
        val baseline = (headerH - blockH) / 2 + monthSize - p.descent() * 0.6f
        canvas.drawText(name, x0, baseline, p)
        canvas.drawText(yearText, x0 + nameW + gap, baseline, yearPaint)

        if (!isCurrent) {
            val hint = Paint(p).apply {
                typeface = regularFace; textSize = hintSize; letterSpacing = 0.05f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Tap here for today", w / 2, baseline + hintSize * 1.6f, hint)
        }
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
        canvas: Canvas, pad: Float, gridTop: Float, rowH: Float, refRowH: Float, colW: Float,
        spec: MonthSpec, theme: MonthTheme,
    ) {
        val start = MonthLayout.gridStart(spec.month, spec.weekStartsMonday)
        val g = min(colW, refRowH) * 0.025f
        val radius = g * 2.5f
        // Sized off the 6-row reference height: a 5-row month gets taller cells, which
        // means more events per day rather than just bigger text.
        val numSize = min(refRowH * 0.15f, colW * 0.15f) * spec.textScale
        val chipH = numSize * 1.15f
        val chipGap = chipH * 0.14f

        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = numSize; typeface = mediumFace; textAlign = Paint.Align.CENTER }
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = chipH * 0.68f; typeface = regularFace }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = g * 1.6f; color = theme.todayFill }

        for (i in 0 until spec.rows * MonthLayout.COLS) {
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
                Draw.chip(canvas, e, RectF(left, y, right, y + chipH), theme, chipPaint, textPaint, alpha, spec.showTimes, spec.use24h, spec.zone)
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
}
