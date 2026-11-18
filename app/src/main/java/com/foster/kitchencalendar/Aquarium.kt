package com.foster.kitchencalendar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.min

/** A repeating tank chore. [intervalDays] is how often; [lastDone] when it was last done. */
data class TankTask(
    val id: String, val emoji: String, val name: String, val intervalDays: Int,
    val lastDone: LocalDate, val note: String = "",
) {
    val due: LocalDate get() = lastDone.plusDays(intervalDays.toLong())
    val everyLabel: String get() = when {
        intervalDays % 365 == 0 -> if (intervalDays == 365) "yearly" else "every ${intervalDays / 365} years"
        intervalDays % 30 == 0 && intervalDays >= 60 -> "every ${intervalDays / 30} months"
        intervalDays % 7 == 0 -> if (intervalDays == 7) "weekly" else "every ${intervalDays / 7} weeks"
        else -> "every $intervalDays days"
    }
}

/**
 * The aquarium checklist. Seeded with the family's routine (25% water change every 2 weeks;
 * carbon + filter floss every 4 weeks) plus the Fluval 307's longer-interval upkeep.
 * Chores are planned by weekend: anything due on or before the coming Sunday is "this weekend".
 */
object TankStore {
    private fun prefs(c: Context) = c.getSharedPreferences("aquarium", Context.MODE_PRIVATE)
    private val installed = LocalDate.of(2026, 9, 27)       // Fluval 307 went in
    private val lastWaterChange = LocalDate.of(2026, 11, 15)

    private fun seed() = listOf(
        TankTask("water", "💧", "25% water change", 14, lastWaterChange),
        TankTask("carbon", "⚫", "Change carbon", 28, lastWaterChange),
        TankTask("floss", "☁️", "Change filter floss (Quick Clear)", 28, lastWaterChange),
        TankTask("sponges", "🧽", "Rinse sponges in old tank water", 28, lastWaterChange, "Only if flow is slowing"),
        TankTask("impeller", "⚙️", "Clean impeller, intake strainer & hoses", 90, installed),
        TankTask("foam", "🔁", "Replace one set of foams", 182, installed, "Only if worn. Never all media at once"),
        TankTask("yearly", "🛠️", "Replace impeller, lube O-rings", 365, installed),
    )

    fun tasks(c: Context): List<TankTask> {
        val raw = prefs(c).getString("tasks", null) ?: return seed().also { save(c, it) }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TankTask(o.getString("id"), o.optString("emoji", "🐠"), o.getString("name"), o.getInt("every"),
                LocalDate.parse(o.getString("last")), o.optString("note"))
        }
    }

    fun save(c: Context, list: List<TankTask>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("emoji", t.emoji).put("name", t.name).put("every", t.intervalDays)
                .put("last", t.lastDone.toString()).put("note", t.note))
        }
        prefs(c).edit().putString("tasks", arr.toString()).apply()
    }

    fun markDone(c: Context, id: String, on: LocalDate) =
        save(c, tasks(c).map { if (it.id == id) it.copy(lastDone = on) else it })

    fun update(c: Context, task: TankTask) = save(c, tasks(c).map { if (it.id == task.id) task else it })
    fun add(c: Context, task: TankTask) = save(c, tasks(c) + task)
    fun remove(c: Context, id: String) = save(c, tasks(c).filter { it.id != id })

    fun tankName(c: Context): String = prefs(c).getString("tank", "29 gal · Fluval 307")!!
    fun setTankName(c: Context, s: String) = prefs(c).edit().putString("tank", s.trim()).apply()

    /** The Sunday that ends the current weekend window (today if it's Sunday). */
    fun weekendEnd(today: LocalDate): LocalDate = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

    fun dueThisWeekend(tasks: List<TankTask>, today: LocalDate) = tasks.filter { !it.due.isAfter(weekendEnd(today)) }.sortedBy { it.due }

    /** The next weekend with chores, after this one, and what's on it. */
    fun nextUp(tasks: List<TankTask>, today: LocalDate): Pair<LocalDate, List<TankTask>>? {
        val after = tasks.filter { it.due.isAfter(weekendEnd(today)) }.ifEmpty { return null }
        val firstSunday = weekendEnd(after.minOf { it.due })
        return firstSunday to after.filter { weekendEnd(it.due) == firstSunday }.sortedBy { it.due }
    }
}

data class TankSpec(val today: LocalDate, val tasks: List<TankTask>, val tankName: String)

/** Aquarium widget: what's due this weekend (or "nothing"), then what's next. Tap opens the checklist. */
object TankRenderer {
    private val top = Color.parseColor("#1B6F8C")
    private val bottom = Color.parseColor("#0D3B52")
    private val white = Color.WHITE
    private val muted = Color.argb(200, 255, 255, 255)
    private val due = Color.parseColor("#FFB45E")
    private val fmt = DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault())

    fun render(w: Int, h: Int, dpW: Float, dpH: Float, spec: TankSpec): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val unit = w / dpW
        val s = min(w, h).toFloat()
        val corner = min(22 * unit, s * 0.08f)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        // A few bubbles for character
        val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * unit; color = Color.argb(60, 255, 255, 255) }
        listOf(0.88f to 0.2f, 0.93f to 0.32f, 0.86f to 0.42f, 0.95f to 0.55f).forEachIndexed { i, (fx, fy) ->
            c.drawCircle(w * fx, h * fy, s * (0.02f + i * 0.008f), bubble)
        }
        fun paint(sz: Float, face: android.graphics.Typeface, color: Int = white) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sz; typeface = face; this.color = color }

        val pad = min(16 * unit, s * 0.07f)
        val tiny = dpW < 150 || dpH < 110
        val dueNow = TankStore.dueThisWeekend(spec.tasks, spec.today)
        val overdue = dueNow.any { it.due.isBefore(spec.today) }
        val head = paint(min(s * 0.075f, 16 * unit), Draw.bold, muted)
        var y = pad + head.textSize
        c.drawText("🐠  AQUARIUM", pad, y, head)

        if (tiny) {
            val big = paint(s * 0.16f, Draw.bold, if (dueNow.isEmpty()) white else due)
            c.drawText(if (dueNow.isEmpty()) "All good" else "${dueNow.size} due", pad, h - pad * 1.4f, big)
            return bmp
        }

        val titleP = paint(min(s * 0.11f, 30 * unit), Draw.bold, if (overdue) due else white)
        y += titleP.textSize * 1.45f
        val title = when {
            dueNow.isEmpty() -> "Nothing this weekend 🎉"
            overdue -> "Overdue"
            else -> "This weekend"
        }
        c.drawText(Draw.ellipsize(title, titleP, w - pad * 2), pad, y, titleP)

        val rowP = paint(min(s * 0.075f, 19 * unit), Draw.medium)
        val noteP = paint(min(s * 0.055f, 14 * unit), Draw.regular, muted)
        y += rowP.textSize * 0.6f
        val listBottom = h - pad - (noteP.textSize * 2.6f)
        dueNow.forEach { t ->
            if (y + rowP.textSize * 1.6f > listBottom) return@forEach
            y += rowP.textSize * 1.55f
            val box = RectF(pad, y - rowP.textSize * 0.85f, pad + rowP.textSize, y + rowP.textSize * 0.15f)
            c.drawRoundRect(box, rowP.textSize * 0.22f, rowP.textSize * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2 * unit; color = if (t.due.isBefore(spec.today)) due else white
            })
            c.drawText(Draw.ellipsize("${t.emoji}  ${t.name}", rowP, w - pad * 3 - rowP.textSize), pad + rowP.textSize * 1.6f, y, rowP)
        }

        // What's next
        val next = TankStore.nextUp(spec.tasks, spec.today)
        if (next != null) {
            val (sunday, list) = next
            val sat = sunday.minusDays(1)
            val days = ChronoUnit.DAYS.between(spec.today, sat).coerceAtLeast(0)
            val label = "Next: weekend of ${sat.format(fmt)}" + (if (days in 1..20) " (${days}d)" else "")
            c.drawText(Draw.ellipsize(label, noteP, w - pad * 2), pad, h - pad - noteP.textSize * 1.35f, noteP)
            val what = list.joinToString(", ") { it.name.substringBefore(" (") }
            c.drawText(Draw.ellipsize(what, paint(noteP.textSize, Draw.medium, white), w - pad * 2), pad, h - pad, paint(noteP.textSize, Draw.medium, white))
        }
        return bmp
    }
}
