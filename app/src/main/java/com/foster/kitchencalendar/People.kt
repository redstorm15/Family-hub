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
            .filter { !isBanner(it.name) }
            .map { cal -> Person("cal${cal.id}", prettyName(cal.name), cal.color or 0xFF000000.toInt(), setOf(cal.id)) }
    }

    /** Holiday and birthday calendars aren't people: they show up next to the date instead. */
    fun isBanner(calendarName: String): Boolean {
        val n = calendarName.lowercase()
        return "holiday" in n || "birthday" in n
    }

    /** "nick.foster@gmail.com" → "Nick", "Elaina's Calendar" → "Elaina". */
    fun prettyName(raw: String): String {
        var base = raw.trim()
        if ('@' in base) base = base.substringBefore('@').substringBefore('.').replace('_', ' ')
        base = base.replace(Regex("(?i)['’]s\\s+cal(endar)?$"), "").replace(Regex("(?i)\\s+calendar$"), "").trim()
        return base.replaceFirstChar { it.uppercase() }.ifBlank { raw }
    }

    fun showFamily(c: Context) = prefs(c).getBoolean("family", true)
    fun setShowFamily(c: Context, v: Boolean) = prefs(c).edit().putBoolean("family", v).apply()

    // ---------- photos ----------

    fun photo(c: Context, id: String): Bitmap? = photoFile(c, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    fun hasPhoto(c: Context, id: String) = photoFile(c, id).exists()
    fun rotatePhoto(c: Context, id: String) = Photos.rotate(photoFile(c, id))
    fun removePhoto(c: Context, id: String) { photoFile(c, id).delete() }

    /** Saves a square-ish portrait, at most 600 px, for the round avatar. */
    fun savePhoto(c: Context, id: String, open: () -> java.io.InputStream?) =
        Photos.saveUpright(open, photoFile(c, id), maxSide = 600, quality = 90)
}

/** One column on the widget: a person (or the shared Family column) and their events today. */
data class PersonColumn(val person: Person, val photo: Bitmap?, val family: Boolean, val events: List<CalEvent>)

data class PeopleSpec(
    val date: LocalDate,
    val nowMillis: Long,
    val columns: List<PersonColumn>,
    val banners: List<CalEvent>,          // holidays, birthdays: shown beside the date
    val sharedAllDay: List<CalEvent>,     // all-day items on calendars nobody owns: span every lane
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
        val dateText = "Today · $dow, $month ${spec.date.dayOfMonth}"
        val titleY = Draw.centerBaseline(title, headerH / 2 + pad * 0.3f)
        c.drawText(dateText, pad * 1.5f, titleY, title)
        val dateRight = pad * 1.5f + title.measureText(dateText)
        if (spec.banners.isEmpty()) {
            Draw.decorations(c, w.toFloat(), headerH, spec.date.toEpochDay().toInt(), theme,
                keepClear = listOf(RectF(0f, 0f, dateRight + pad, headerH)))
        } else {
            // Holidays and birthdays as pills next to the date: "🎉 Thanksgiving"
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.bold; textSize = title.textSize * 0.62f }
            val pillH = bp.textSize * 1.9f
            var x = dateRight + pad * 1.5f
            val cy = headerH / 2 + pad * 0.3f
            for (e in spec.banners.distinctBy { it.title }) {
                val label = (if ("birthday" in e.title.lowercase()) "🎂 " else "🎉 ") + e.title
                val room = w - pad * 1.5f - x
                if (room < bp.textSize * 5) break
                val text = Draw.ellipsize(label, bp, room - bp.textSize * 1.2f)
                val r = RectF(x, cy - pillH / 2, x + bp.measureText(text) + bp.textSize * 1.2f, cy + pillH / 2)
                c.drawRoundRect(r, pillH / 2, pillH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.todayFill })
                bp.color = Draw.onColor(theme.todayFill)
                c.drawText(text, r.left + bp.textSize * 0.6f, Draw.centerBaseline(bp, cy), bp)
                x = r.right + pad
            }
        }

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

        // ----- Faces: their own band, with a thick rule under it -----
        val avatarR = min(laneW * 0.3f, u * 2.4f)
        val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.dayText; typeface = Draw.bold; textSize = min(u * 1.0f, laneW * 0.16f); textAlign = Paint.Align.CENTER
        }
        val rowTop = area.top + u * 0.8f
        val avatarCy = rowTop + avatarR
        val facesBottom = avatarCy + avatarR + nameP.textSize * 1.9f
        c.save()
        c.clipRect(area.left, area.top, area.right, facesBottom)
        c.drawRoundRect(RectF(area.left, area.top, area.right, facesBottom + pad), pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.outCellBg })
        c.restore()
        cols.forEachIndexed { i, col ->
            val cx = lanesLeft + laneW * (i + 0.5f)
            drawAvatar(c, cx, avatarCy, avatarR, col, theme)
            c.drawText(Draw.ellipsize(col.person.name, nameP, laneW * 0.92f), cx, avatarCy + avatarR + nameP.textSize * 1.35f, nameP)
        }
        c.drawRect(area.left, facesBottom, area.right, facesBottom + max(2f, u * 0.18f), Paint().apply { color = theme.todayFill })
        val gridTop = facesBottom + max(2f, u * 0.18f)

        // ----- All-day row (inside the calendar, in each person's lane) -----
        // Shared items (calendars nobody owns, like Activities) span every lane; a person's item sits in
        // their lane and stretches right across empty neighbours so long titles fit.
        val maxRows = 2
        val shared = spec.sharedAllDay
        val perLane = cols.map { col -> col.events.filter { it.allDay } }
        val sharedRows = min(shared.size, maxRows)
        val laneRows = min(maxRows - sharedRows, perLane.maxOfOrNull { it.size } ?: 0).coerceAtLeast(0)
        val allDayRows = sharedRows + laneRows
        val chipH = u * 1.9f
        val chipGap = u * 0.3f
        val allDayTop = gridTop + u * 0.5f
        val allDayBottom = if (allDayRows > 0) allDayTop + allDayRows * (chipH + chipGap) + u * 0.2f else gridTop

        // ----- Timeline geometry -----
        val timed = cols.flatMap { col -> col.events.filter { !it.allDay } }
        val dayStart = spec.date.atStartOfDay(spec.zone).toInstant().toEpochMilli()
        fun hourOf(ms: Long) = ((ms - dayStart) / 3_600_000.0).toFloat().coerceIn(0f, 24f)
        var startH = 8f
        var endH = 21f
        timed.forEach { e -> startH = min(startH, floor(hourOf(e.beginMillis))); endH = max(endH, ceil(hourOf(e.endMillis))) }
        val nowH = hourOf(spec.nowMillis)
        startH = min(startH, floor(nowH)).coerceIn(0f, 23f)
        endH = max(endH, ceil(nowH)).coerceIn(startH + 1, 24f)

        val top = allDayBottom + u * 0.9f
        val bottom = area.bottom - u * 0.6f
        if (bottom - top < u * 3) return bmp
        val pxPerHour = (bottom - top) / (endH - startH)
        fun yOf(hour: Float) = top + (hour - startH) * pxPerHour

        // Lane tints + dividers, all the way from the rule to the bottom
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.outCellBg; strokeWidth = max(1f, u * 0.05f) }
        cols.forEachIndexed { i, col ->
            val x1 = lanesLeft + laneW * i
            c.drawRect(x1, gridTop, x1 + laneW, bottom, Paint().apply { color = col.person.color; alpha = 16 })
            if (i > 0) {
                c.drawLine(x1, rowTop, x1, facesBottom - u * 0.3f, line)
                c.drawLine(x1, gridTop, x1, bottom, line)
            }
        }
        val hourP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.mutedText; typeface = Draw.regular; textSize = min(u * 0.8f, pxPerHour * 0.45f); textAlign = Paint.Align.RIGHT
        }
        if (allDayRows > 0) {
            val adP = Paint(hourP).apply { textSize = u * 0.7f; typeface = Draw.medium }
            c.drawText("all", lanesLeft - u * 0.4f, allDayTop + chipH * 0.45f, adP)
            c.drawText("day", lanesLeft - u * 0.4f, allDayTop + chipH * 0.45f + adP.textSize * 1.1f, adP)
            c.drawLine(lanesLeft, allDayBottom + u * 0.4f, lanesRight, allDayBottom + u * 0.4f, line)
        }
        val step = if (pxPerHour < hourP.textSize * 1.6f) 2 else 1
        val nowY = if (nowH in startH..endH) yOf(nowH) else null
        for (hInt in startH.toInt()..endH.toInt()) {
            val ly = yOf(hInt.toFloat())
            c.drawLine(lanesLeft, ly, lanesRight, ly, line)
            val coveredByNow = nowY != null && kotlin.math.abs(ly - nowY) < u * 1.3f
            if ((hInt - startH.toInt()) % step == 0 && !coveredByNow) c.drawText(hourLabel(hInt, spec.use24h), lanesLeft - u * 0.4f, Draw.centerBaseline(hourP, ly), hourP)
        }

        // ----- All-day chips (drawn over the lane lines) -----
        val chipP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Draw.bold; textSize = min(chipH * 0.46f, laneW * 0.11f) }
        fun chip(x1: Float, x2: Float, rowIdx: Int, bg: Int, label: String, tag: String?) {
            val ry = allDayTop + rowIdx * (chipH + chipGap)
            val r = RectF(x1 + u * 0.15f, ry, x2 - u * 0.15f, ry + chipH)
            c.drawRoundRect(r, u * 0.4f, u * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
            chipP.color = Draw.onColor(bg)
            var tx = r.left + u * 0.45f
            if (tag != null) {
                c.drawText(tag, tx, Draw.centerBaseline(chipP, r.centerY()), chipP)
                tx += chipP.measureText(tag) + u * 0.3f
            }
            c.drawText(Draw.ellipsize(label, chipP, r.right - tx - u * 0.3f), tx, Draw.centerBaseline(chipP, r.centerY()), chipP)
        }
        shared.take(sharedRows).forEachIndexed { k, e ->
            val label = if (k == sharedRows - 1 && shared.size > sharedRows) "${e.title}  (+${shared.size - sharedRows} more)" else e.title
            chip(lanesLeft, lanesRight, k, Draw.eventColor(e, theme), label, "🏠")
        }
        if (laneRows > 0) {
            // taken[lane][row]: whether that lane already shows something in that row
            val taken = Array(cols.size) { li -> BooleanArray(laneRows) { r -> r < perLane[li].size } }
            cols.forEachIndexed { li, col ->
                val items = perLane[li]
                for (r in 0 until min(laneRows, items.size)) {
                    val e = items[r]
                    var end = li
                    while (end + 1 < cols.size && !taken[end + 1][r]) { end++; taken[end][r] = true }
                    val more = if (r == laneRows - 1 && items.size > laneRows) "  (+${items.size - laneRows})" else ""
                    chip(lanesLeft + laneW * li, lanesLeft + laneW * (end + 1), sharedRows + r,
                        if (col.family) Draw.eventColor(e, theme) else col.person.color, e.title + more, null)
                }
            }
        }

        // ----- Timed events, in their person's color -----
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
                val bg = if (col.family) Draw.eventColor(e, theme) else col.person.color
                val past = e.endMillis <= spec.nowMillis
                c.drawRoundRect(r, u * 0.3f, u * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg; if (past) alpha = 140 })
                val fg = Draw.onColor(bg)
                title2.color = fg; sub.color = fg
                val tx = r.left + u * 0.35f
                val maxW = r.right - tx - u * 0.2f
                val name = stripOwner(e.title, col.person.name)
                if (r.height() >= title2.textSize * 2.6f) {
                    val lines = Draw.wrap(name, title2, maxW, if (r.height() > title2.textSize * 4.5f) 2 else 1)
                    var ty = r.top + title2.textSize * 1.15f
                    lines.forEach { c.drawText(it, tx, ty, title2); ty += title2.textSize * 1.1f }
                    if (ty + sub.textSize * 0.2f < r.bottom) c.drawText(Draw.ellipsize(Draw.timeRange(e, spec.zone, spec.use24h), sub, maxW), tx, ty + sub.textSize * 0.15f, sub)
                } else {
                    c.drawText(Draw.ellipsize(name, title2, maxW), tx, Draw.centerBaseline(title2, r.centerY()), title2)
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

    /** "Nolan: GOA practice" in Nolan's lane reads as just "GOA practice". */
    private fun stripOwner(title: String, person: String): String {
        val first = person.substringBefore(' ')
        for (p in listOf("$person:", "$person -", "$first:", "$first -")) {
            if (title.startsWith(p, ignoreCase = true)) return title.substring(p.length).trim().ifBlank { title }
        }
        return title
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
