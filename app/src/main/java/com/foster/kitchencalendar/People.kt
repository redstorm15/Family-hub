package com.foster.kitchencalendar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A family member: a name, a color, the calendars that are theirs, and (optionally) a photo. */
data class Person(val id: String, val name: String, val color: Int, val calendarIds: Set<Long>)

/**
 * The people for the "Today by person" widget. Until someone sets them up, every selected
 * calendar becomes its own person (named after the calendar), so the widget works right away.
 * Calendars nobody owns go in a shared "Family" column.
 */
object PeopleStore {
    private fun prefs(c: Context) = c.getSharedPreferences("people", Context.MODE_PRIVATE)
    private fun photoFile(c: Context, id: String) = File(File(c.filesDir, "people").apply { mkdirs() }, "$id.jpg")

    fun isConfigured(c: Context) = prefs(c).contains("people")

    fun saved(c: Context): List<Person> {
        val arr = JSONArray(prefs(c).getString("people", "[]")!!)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ids = o.optJSONArray("cals")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() }.orEmpty()
            Person(o.getString("id"), o.getString("name"), o.getInt("color"), ids)
        }
    }

    fun save(c: Context, people: List<Person>) {
        val arr = JSONArray()
        people.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("color", p.color)
                .put("cals", JSONArray().also { a -> p.calendarIds.forEach { a.put(it) } }))
        }
        prefs(c).edit().putString("people", arr.toString()).apply()
    }

    /** Saved people, or one per selected calendar if nobody has been set up yet. */
    fun people(c: Context): List<Person> {
        if (isConfigured(c)) return saved(c)
        return defaults(c)
    }

    fun defaults(c: Context): List<Person> {
        val selected = Prefs.selectedCalendars(c)
        return CalendarRepository.listCalendars(c)
            .filter { if (selected != null) it.id in selected else it.visible }
            .map { cal -> Person("cal${cal.id}", prettyName(cal.name), cal.color or 0xFF000000.toInt(), setOf(cal.id)) }
    }

    /** "nick.foster@gmail.com" → "Nick". */
    fun prettyName(raw: String): String {
        val base = raw.substringBefore('@').substringBefore('.').replace('_', ' ').trim()
        return base.replaceFirstChar { it.uppercase() }.ifBlank { raw }
    }

    fun showFamily(c: Context) = prefs(c).getBoolean("family", true)
    fun setShowFamily(c: Context, v: Boolean) = prefs(c).edit().putBoolean("family", v).apply()

    // ---------- photos ----------

    fun photo(c: Context, id: String): Bitmap? = photoFile(c, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    fun hasPhoto(c: Context, id: String) = photoFile(c, id).exists()
    fun removePhoto(c: Context, id: String) { photoFile(c, id).delete() }

    /** Saves a square-ish portrait, at most 600 px, for the round avatar. */
    fun savePhoto(c: Context, id: String, open: () -> java.io.InputStream?) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 600) sample *= 2
        val bmp = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return
        FileOutputStream(photoFile(c, id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
    }
}

/** One column on the widget: a person (or the shared Family column) and their events today. */
data class PersonColumn(val person: Person, val photo: Bitmap?, val family: Boolean, val events: List<CalEvent>)

data class PeopleSpec(
    val date: LocalDate,
    val nowMillis: Long,
    val columns: List<PersonColumn>,
    val use24h: Boolean,
    val zone: ZoneId,
    val textScale: Float,
)

/**
 * "Today by person": avatars across the top, each person's all-day items under their face,
 * and a shared timeline below with one lane per person (like a family hub's day view).
 */
object PeopleRenderer {

    fun render(w: Int, h: Int, spec: PeopleSpec): Bitmap {
        val theme = Themes.forMonth(spec.date.monthValue)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pad = MonthLayout.pad(w, h)
        val u = min(w, h) * 0.03f * spec.textScale
        c.drawColor(theme.headerBg)

        // Slim date header
        val headerH = max(u * 3.2f, h * 0.075f)
        val dow = spec.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val month = spec.date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.headerText; typeface = Draw.bold; textSize = min(headerH * 0.5f, u * 1.7f) }
        c.drawText("Today · $dow, $month ${spec.date.dayOfMonth}", pad * 1.5f, Draw.centerBaseline(title, headerH / 2 + pad * 0.3f), title)
        Draw.decorations(c, w.toFloat(), headerH, spec.date.toEpochDay().toInt(), theme,
            keepClear = listOf(RectF(0f, 0f, pad * 2 + title.measureText("Today · $dow, $month ${spec.date.dayOfMonth}"), headerH)))

        val body = RectF(pad * 0.5f, headerH, w - pad * 0.5f, h - pad * 0.5f)
        c.drawRoundRect(body, pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.bodyBg })
        val area = RectF(body.left + pad, body.top + pad, body.right - pad, body.bottom - pad)
        c.drawRoundRect(area, pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.cellBg })

        val cols = spec.columns
        if (cols.isEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.mutedText; textSize = u * 1.1f; typeface = Draw.medium; textAlign = Paint.Align.CENTER }
            c.drawText("Pick calendars in settings", area.centerX(), area.centerY(), p)
            return bmp
        }

        val labelW = u * 2.6f
        val lanesLeft = area.left + labelW
        val lanesRight = area.right - u * 0.4f
        val laneW = (lanesRight - lanesLeft) / cols.size

        // ----- People row -----
        val avatarR = min(laneW * 0.3f, u * 2.4f)
        val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.dayText; typeface = Draw.bold; textSize = min(u * 1.0f, laneW * 0.16f); textAlign = Paint.Align.CENTER
        }
        val rowTop = area.top + u * 0.8f
        val avatarCy = rowTop + avatarR
        cols.forEachIndexed { i, col ->
            val cx = lanesLeft + laneW * (i + 0.5f)
            drawAvatar(c, cx, avatarCy, avatarR, col, theme)
            c.drawText(Draw.ellipsize(col.person.name, nameP, laneW * 0.92f), cx, avatarCy + avatarR + nameP.textSize * 1.35f, nameP)
        }
        var y = avatarCy + avatarR + nameP.textSize * 1.9f

        // ----- All-day row (one chip per person, "+n" if more) -----
        val allDayH = u * 1.5f
        if (cols.any { col -> col.events.any { it.allDay } }) {
            val chipP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.medium; textSize = allDayH * 0.56f }
            val maxRows = 2
            cols.forEachIndexed { i, col ->
                val ad = col.events.filter { it.allDay }
                val x1 = lanesLeft + laneW * i + u * 0.2f
                val x2 = lanesLeft + laneW * (i + 1) - u * 0.2f
                ad.take(maxRows).forEachIndexed { k, e ->
                    val r = RectF(x1, y + k * (allDayH + u * 0.25f), x2, y + k * (allDayH + u * 0.25f) + allDayH)
                    val bg = Draw.eventColor(e, theme)
                    c.drawRoundRect(r, allDayH * 0.25f, allDayH * 0.25f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
                    chipP.color = Draw.onColor(bg)
                    val label = if (k == maxRows - 1 && ad.size > maxRows) "+${ad.size - k} more" else e.title
                    c.drawText(Draw.ellipsize(label, chipP, r.width() - u * 0.5f), r.left + u * 0.3f, Draw.centerBaseline(chipP, r.centerY()), chipP)
                }
            }
            val rowsUsed = cols.maxOf { col -> min(maxRows, col.events.count { it.allDay }) }
            y += rowsUsed * (allDayH + u * 0.25f) + u * 0.2f
        }

        // ----- Timeline -----
        val timed = cols.flatMap { col -> col.events.filter { !it.allDay } }
        val dayStart = spec.date.atStartOfDay(spec.zone).toInstant().toEpochMilli()
        fun hourOf(ms: Long) = ((ms - dayStart) / 3_600_000.0).toFloat().coerceIn(0f, 24f)
        var startH = 8f
        var endH = 21f
        timed.forEach { e -> startH = min(startH, floor(hourOf(e.beginMillis))); endH = max(endH, ceil(hourOf(e.endMillis))) }
        val nowH = hourOf(spec.nowMillis)
        startH = min(startH, floor(nowH)).coerceIn(0f, 23f)
        endH = max(endH, ceil(nowH)).coerceIn(startH + 1, 24f)

        val top = y + u * 0.6f
        val bottom = area.bottom - u * 0.6f
        if (bottom - top < u * 3) return bmp
        val pxPerHour = (bottom - top) / (endH - startH)
        fun yOf(hour: Float) = top + (hour - startH) * pxPerHour

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.outCellBg; strokeWidth = max(1f, u * 0.05f) }
        val hourP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.mutedText; typeface = Draw.regular; textSize = min(u * 0.8f, pxPerHour * 0.45f); textAlign = Paint.Align.RIGHT
        }
        val step = if (pxPerHour < hourP.textSize * 1.6f) 2 else 1
        for (hInt in startH.toInt()..endH.toInt()) {
            val ly = yOf(hInt.toFloat())
            c.drawLine(lanesLeft, ly, lanesRight, ly, line)
            if ((hInt - startH.toInt()) % step == 0) c.drawText(hourLabel(hInt, spec.use24h), lanesLeft - u * 0.4f, Draw.centerBaseline(hourP, ly), hourP)
        }
        // Lane dividers, and a faint tint of each person's color down their lane
        cols.forEachIndexed { i, col ->
            val x1 = lanesLeft + laneW * i
            c.drawRect(x1, top, x1 + laneW, bottom, Paint().apply { color = col.person.color; alpha = 14 })
            if (i > 0) c.drawLine(x1, rowTop, x1, bottom, line)
        }

        val title2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.bold; textSize = min(u * 0.95f, laneW * 0.13f) }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.regular; textSize = title2.textSize * 0.82f }
        val minH = title2.textSize * 1.5f
        cols.forEachIndexed { i, col ->
            val laneLeft = lanesLeft + laneW * i + u * 0.2f
            val laneInner = laneW - u * 0.4f
            for ((e, sub2, n) in layoutColumns(col.events.filter { !it.allDay })) {
                val y1 = yOf(hourOf(e.beginMillis))
                val y2 = max(y1 + minH, yOf(hourOf(e.endMillis)))
                val cw = laneInner / n
                val r = RectF(laneLeft + sub2 * cw, y1 + 1, laneLeft + (sub2 + 1) * cw - u * 0.12f, min(y2, bottom) - 1)
                val bg = Draw.eventColor(e, theme)
                val past = e.endMillis <= spec.nowMillis
                c.drawRoundRect(r, u * 0.3f, u * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg; if (past) alpha = 140 })
                val fg = Draw.onColor(bg)
                title2.color = fg; sub.color = fg
                val tx = r.left + u * 0.35f
                val maxW = r.right - tx - u * 0.2f
                if (r.height() >= title2.textSize * 2.6f) {
                    val lines = Draw.wrap(e.title, title2, maxW, if (r.height() > title2.textSize * 4.5f) 2 else 1)
                    var ty = r.top + title2.textSize * 1.15f
                    lines.forEach { c.drawText(it, tx, ty, title2); ty += title2.textSize * 1.1f }
                    if (ty + sub.textSize * 0.2f < r.bottom) c.drawText(Draw.ellipsize(Draw.timeRange(e, spec.zone, spec.use24h), sub, maxW), tx, ty + sub.textSize * 0.15f, sub)
                } else {
                    c.drawText(Draw.ellipsize(e.title, title2, maxW), tx, Draw.centerBaseline(title2, r.centerY()), title2)
                }
            }
        }

        // Now line across every lane, with the time in a red tag
        if (nowH in startH..endH) {
            val ny = yOf(nowH)
            val red = Color.parseColor("#E24B4A")
            c.drawLine(lanesLeft, ny, lanesRight, ny, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; strokeWidth = u * 0.12f })
            val tagP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Draw.bold; textSize = u * 0.7f; textAlign = Paint.Align.CENTER }
            val label = Draw.timeLabel(spec.nowMillis, spec.zone, spec.use24h)
            val tw = tagP.measureText(label) + u * 0.6f
            val tag = RectF(lanesLeft - tw - u * 0.1f, ny - u * 0.6f, lanesLeft - u * 0.1f, ny + u * 0.6f)
            c.drawRoundRect(tag, u * 0.3f, u * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red })
            c.drawText(label, tag.centerX(), Draw.centerBaseline(tagP, tag.centerY()), tagP)
        }
        return bmp
    }

    /** Round photo, or the person's initial on their color; a 🏠 for the shared Family column. */
    private fun drawAvatar(c: Canvas, cx: Float, cy: Float, r: Float, col: PersonColumn, theme: MonthTheme) {
        c.drawCircle(cx, cy, r + r * 0.09f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col.person.color })
        val photo = col.photo
        if (photo != null) {
            val side = min(photo.width, photo.height).toFloat()
            val scale = (r * 2) / side
            val m = Matrix().apply {
                postTranslate(-(photo.width - side) / 2, -(photo.height - side) / 2)
                postScale(scale, scale)
                postTranslate(cx - r, cy - r)
            }
            val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        } else {
            val bg = if (col.family) theme.todayFill else Motif.blend(col.person.color, Color.WHITE, 0.15f)
            c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
            val label = if (col.family) "🏠" else col.person.name.take(1).uppercase()
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Draw.onColor(bg); typeface = Draw.bold; textSize = r * 0.95f; textAlign = Paint.Align.CENTER
            }
            c.drawText(label, cx, Draw.centerBaseline(p, cy), p)
        }
        // Event count badge, like a little notification dot
        val n = col.events.size
        if (n > 0) {
            val br = r * 0.34f
            val bx = cx + r * 0.72f
            val by = cy + r * 0.72f
            c.drawCircle(bx, by, br, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.cellBg })
            c.drawCircle(bx, by, br * 0.82f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col.person.color })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Draw.onColor(col.person.color); typeface = Draw.bold; textSize = br * 1.05f; textAlign = Paint.Align.CENTER }
            c.drawText(if (n > 9) "9+" else n.toString(), bx, Draw.centerBaseline(p, by), p)
        }
    }

    private fun hourLabel(h: Int, use24h: Boolean): String {
        if (use24h) return "%02d:00".format(h % 24)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12${if (h % 24 < 12) "a" else "p"}"
    }

    /** Side-by-side packing of overlapping events inside one person's lane. */
    private fun layoutColumns(events: List<CalEvent>): List<Triple<CalEvent, Int, Int>> {
        val sorted = events.sortedWith(compareBy({ it.beginMillis }, { -it.endMillis }))
        val out = mutableListOf<Triple<CalEvent, Int, Int>>()
        var cluster = mutableListOf<Pair<CalEvent, Int>>()
        val colEnds = mutableListOf<Long>()
        var clusterEnd = Long.MIN_VALUE
        fun flush() {
            val n = (cluster.maxOfOrNull { it.second } ?: -1) + 1
            cluster.forEach { (e, col) -> out += Triple(e, col, max(1, n)) }
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
}
