package com.foster.kitchencalendar

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Aquarium checklist: big ✓ buttons, "done on…" for catching up, and editable chores. */
class AquariumActivity : Activity() {
    private val ink = Color.parseColor("#12313F")
    private val muted = Color.parseColor("#5C7380")
    private val sea = Color.parseColor("#1B6F8C")
    private val orange = Color.parseColor("#E07B24")
    private val green = Color.parseColor("#1D9E75")
    private lateinit var list: LinearLayout
    private val fmt = DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault())
    private val today get() = LocalDate.now()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#EEF6F9")) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(28)) }
        scroll.addView(list)
        setContentView(scroll)
        render()
    }

    override fun onPause() {
        super.onPause()
        WidgetUpdater.request(this, WidgetKind.TANK)
    }

    private fun render() {
        list.removeAllViews()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("🐠  Aquarium", 28f, ink, bold = true))
        titles.addView(text("${TankStore.tankName(this)}  ✏️", 15f, muted).apply { setOnClickListener { editTankName() } })
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("✓ Done", green, Color.WHITE) { finish() })
        list.addView(top)

        val tasks = TankStore.tasks(this)
        val dueNow = TankStore.dueThisWeekend(tasks, today)
        list.addView(text(if (dueNow.isEmpty()) "Nothing this weekend 🎉" else "This weekend", 20f, if (dueNow.isEmpty()) green else ink, bold = true), lp(top = 18))
        if (dueNow.size > 1) {
            list.addView(pill("✓ Did all of these today", sea, Color.WHITE) {
                dueNow.forEach { TankStore.markDone(this, it.id, today) }; render()
            }, lp(top = 8))
        }
        dueNow.forEach { list.addView(taskCard(it, highlight = true), full(top = 10)) }

        list.addView(text("Coming up", 20f, ink, bold = true), lp(top = 22))
        tasks.filter { it !in dueNow }.sortedBy { it.due }.forEach { list.addView(taskCard(it, highlight = false), full(top = 10)) }

        list.addView(pill("＋ Add a chore", Color.WHITE, ink) { editTask(null) }, lp(top = 18))
        list.addView(text("Tip: never replace all the filter media at once, which keeps the good bacteria.", 14f, muted), lp(top = 14))
    }

    private fun taskCard(t: TankTask, highlight: Boolean): View {
        val overdue = t.due.isBefore(today)
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = rounded(if (highlight) Color.WHITE else Color.parseColor("#F7FBFC"), 18).apply {
                if (overdue) setStroke(dp(2), orange)
            }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text("${t.emoji}  ${t.name}", 19f, ink, bold = true))
        val days = ChronoUnit.DAYS.between(today, t.due)
        val whenText = when {
            days < 0 -> "Overdue by ${-days} day${if (days == -1L) "" else "s"}"
            days == 0L -> "Due today"
            else -> "Due ${t.due.format(fmt)}"
        }
        info.addView(text("$whenText  ·  ${t.everyLabel}  ·  last ${t.lastDone.format(fmt)}", 14f, if (overdue) orange else muted))
        if (t.note.isNotBlank()) info.addView(text(t.note, 13f, muted))
        info.setOnClickListener { editTask(t) }
        card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(pill("📅", Color.WHITE, ink) { pickDate(t) }, lp(end = 8))
        card.addView(pill("✓", if (highlight) green else Color.WHITE, if (highlight) Color.WHITE else green) {
            TankStore.markDone(this, t.id, today); render()
        })
        return card
    }

    /** "I did it on Saturday": backdate a chore so the schedule stays on weekends. */
    private fun pickDate(t: TankTask) {
        val d = today
        DatePickerDialog(this, { _, y, m, day ->
            TankStore.markDone(this, t.id, LocalDate.of(y, m + 1, day)); render()
        }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
            setTitle("When did you do it?")
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun editTask(t: TankTask?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        val name = EditText(this).apply {
            hint = "Chore"; textSize = 19f; setText(t?.name.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val note = EditText(this).apply { hint = "Note (optional)"; textSize = 15f; setText(t?.note.orEmpty()) }
        box.addView(name); box.addView(note)
        box.addView(text("Every how many weeks?", 15f, muted), lp(top = 12))
        val weeks = NumberPicker(this).apply {
            minValue = 1; maxValue = 52; value = ((t?.intervalDays ?: 14) + 3) / 7
            wrapSelectorWheel = false
        }
        box.addView(weeks)
        val b = AlertDialog.Builder(this).setTitle(if (t == null) "New chore" else "Edit chore").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                val days = weeks.value * 7
                if (t == null) TankStore.add(this, TankTask("t" + System.currentTimeMillis(), "🐠", n, days, today, note.text.toString().trim()))
                else TankStore.update(this, t.copy(name = n, intervalDays = days, note = note.text.toString().trim()))
                render()
            }
            .setNegativeButton("Cancel", null)
        if (t != null) b.setNeutralButton("Delete") { _, _ -> TankStore.remove(this, t.id); render() }
        b.show()
    }

    private fun editTankName() {
        val input = EditText(this).apply { setText(TankStore.tankName(this@AquariumActivity)); textSize = 19f }
        val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle("Tank").setView(wrap)
            .setPositiveButton("Save") { _, _ -> TankStore.setTankName(this, input.text.toString()); render() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- helpers ----------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Draw.bold
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = text(label, 17f, fg, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = rounded(bg, 24).apply { if (bg == Color.WHITE) setStroke(dp(1), Color.parseColor("#CFE0E7")) }
        setOnClickListener { onClick() }
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun lp(top: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top); marginEnd = dp(end) }
}
