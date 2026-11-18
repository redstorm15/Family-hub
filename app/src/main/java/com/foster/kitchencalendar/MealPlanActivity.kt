package com.foster.kitchencalendar

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The dinner planner: seven nights, tap a night to pick from the meal bank, and an optional
 * ✨ try night with one small new thing on the side. Almost no typing.
 */
class MealPlanActivity : Activity() {

    private val ink = Color.parseColor("#2C2C2A")
    private val muted = Color.parseColor("#6E6A63")
    private val accent = Color.parseColor("#E8833A")
    private val tryColor = Color.parseColor("#7F55C9")
    private val green = Color.parseColor("#1D9E75")
    private lateinit var list: LinearLayout
    private val today get() = LocalDate.now()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#FFF8EE")) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(28)) }
        scroll.addView(list)
        setContentView(scroll)
        render()
    }

    override fun onPause() {
        super.onPause()
        WidgetUpdater.request(this, WidgetKind.MEALS)
    }

    // ---------- layout ----------

    private fun render() {
        list.removeAllViews()

        // Title + quick actions
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("🍽️  Dinner this week", 28f, ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("✓ Done", green, Color.WHITE) { finish() })
        list.addView(top)
        val actions = LinearLayout(this).apply { setPadding(0, dp(10), 0, dp(6)) }
        actions.addView(pill("✨ Fill the week", accent, Color.WHITE) { MealStore.fillWeek(this, today); render() }, lp(end = 10))
        actions.addView(pill("🔁 Repeat last week", Color.WHITE, ink) { MealStore.repeatLastWeek(this, today); render() }, lp(end = 10))
        actions.addView(pill("🧺 Meal bank", Color.WHITE, ink) { editBank() }, lp(end = 10))
        list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })

        // How did the last try night go?
        MealStore.pendingFeedback(this, today)?.let { rec -> list.addView(feedbackCard(rec), full(top = 10)) }

        // Seven nights
        MealStore.week(this, today).forEach { (date, plan) -> list.addView(dayRow(date, plan), full(top = 10)) }

        // Wins: the new foods they've tried
        val wins = MealStore.wins(this)
        if (wins.isNotEmpty()) {
            list.addView(text("🏆  New foods tried", 18f, ink, bold = true), lp(top = 22))
            val row = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
            wins.take(20).forEach { w ->
                row.addView(chip("${w.result?.emoji ?: "🙂"} ${w.food.emoji} ${w.food.name}", Color.WHITE, ink), lp(end = 8))
            }
            list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
        list.addView(text("Backup plate on try nights: ${MealStore.backup(this)}  ✏️", 15f, muted).apply {
            setPadding(0, dp(20), 0, 0)
            setOnClickListener { editBackup() }
        })
    }

    private fun dayRow(date: LocalDate, plan: DayPlan): View {
        val isToday = date == today
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(if (isToday) Color.parseColor("#FFE9D2") else Color.WHITE, 18)
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val dayName = if (isToday) "Tonight" else date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        head.addView(text(dayName, 17f, if (isToday) accent else muted, bold = true), LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        val meal = plan.meal
        head.addView(
            text(if (meal != null) "${meal.emoji}  ${meal.name}" else "＋  Pick dinner", 22f, if (meal != null) ink else muted, bold = meal != null)
                .apply { setOnClickListener { pickMeal(date) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(head)

        if (meal != null) {
            val tryRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(120), dp(8), 0, 0) }
            val t = plan.tryFood
            if (t != null) {
                tryRow.addView(chip("✨ Try: ${t.emoji} ${t.name}", Color.parseColor("#EEE7FA"), tryColor).apply {
                    setOnClickListener { pickTry(date, meal) }
                })
                tryRow.addView(text("  ✕", 18f, muted).apply { setOnClickListener { MealStore.setTry(this@MealPlanActivity, date, null); render() } })
            } else {
                tryRow.addView(text("✨ Make it a try night", 15f, tryColor).apply { setOnClickListener { pickTry(date, meal) } })
            }
            card.addView(tryRow)
        }
        return card
    }

    private fun feedbackCard(rec: TryRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.parseColor("#EEE7FA"), 18)
        }
        val day = rec.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        card.addView(text("Did they try the ${rec.food.emoji} ${rec.food.name} on $day?", 19f, ink, bold = true))
        val row = LinearLayout(this).apply { setPadding(0, dp(10), 0, 0) }
        TryResult.entries.forEach { r ->
            row.addView(pill("${r.emoji}  ${r.label}", Color.WHITE, ink) {
                MealStore.recordResult(this, rec, r); render()
            }, lp(end = 10))
        }
        card.addView(row)
        return card
    }

    // ---------- pickers ----------

    private fun pickMeal(date: LocalDate) {
        val bank = MealStore.bank(this)
        val grid = GridLayout(this).apply { columnCount = 2; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        lateinit var dialog: AlertDialog
        fun tile(label: String, bg: Int, fg: Int, onClick: () -> Unit) = text(label, 19f, fg, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(16))
            background = rounded(bg, 16)
            setOnClickListener { onClick(); dialog.dismiss() }
        }
        bank.forEach { m ->
            grid.addView(tile("${m.emoji}  ${m.name}", Color.parseColor("#FFF1E2"), ink) {
                MealStore.setMeal(this, date, m); render()
            }, GridLayout.LayoutParams().apply {
                width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
        grid.addView(tile("＋  New meal", Color.WHITE, accent) { addMeal { MealStore.setMeal(this, date, it); render() } },
            GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(5), dp(5), dp(5), dp(5)) })
        grid.addView(tile("Clear night", Color.WHITE, muted) { MealStore.setMeal(this, date, null); render() },
            GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(5), dp(5), dp(5), dp(5)) })
        dialog = AlertDialog.Builder(this).setTitle(date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            .setView(ScrollView(this).apply { addView(grid) }).setNegativeButton("Cancel", null).create()
        dialog.show()
    }

    private fun pickTry(date: LocalDate, meal: Meal, page: Int = 0) {
        val ideas = MealStore.suggestions(this, meal, date)
        val shown = if (ideas.isEmpty()) emptyList() else (0 until minOf(4, ideas.size)).map { ideas[(page * 4 + it) % ideas.size] }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(6)) }
        lateinit var dialog: AlertDialog
        box.addView(text("Same ${meal.name.lowercase()}, plus one new thing on the side:", 15f, muted), full(bottom = 8))
        shown.forEach { f ->
            box.addView(text("${f.emoji}  ${f.name}", 20f, ink, bold = true).apply {
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(Color.parseColor("#EEE7FA"), 16)
                setOnClickListener { MealStore.setTry(this@MealPlanActivity, date, f); render(); dialog.dismiss() }
            }, full(bottom = 8))
        }
        val row = LinearLayout(this)
        row.addView(pill("🔀 Other ideas", Color.WHITE, ink) { dialog.dismiss(); pickTry(date, meal, page + 1) }, lp(end = 10))
        row.addView(pill("✏️ My own", Color.WHITE, ink) {
            dialog.dismiss()
            ask("What's the new thing?", "e.g. roasted carrots") { name ->
                MealStore.setTry(this, date, TryFood(name, "✨")); render()
            }
        })
        box.addView(row, lp(top = 4))
        dialog = AlertDialog.Builder(this).setTitle("✨ Try night").setView(box).setNegativeButton("Cancel", null).create()
        dialog.show()
    }

    private fun addMeal(onAdded: (Meal) -> Unit) {
        val emojis = listOf("🍗", "🍝", "🌮", "🍕", "🥡", "🧀", "🌭", "🍔", "🥪", "🍳", "🥞", "🍲", "🍛", "🐟", "🥩", "🍜", "🌯", "🥗")
        var chosen = emojis.first()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(6)) }
        val name = EditText(this).apply { hint = "Dinner name"; textSize = 20f; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        box.addView(name)
        val grid = GridLayout(this).apply { columnCount = 6; setPadding(0, dp(10), 0, 0) }
        val views = mutableListOf<TextView>()
        emojis.forEach { e ->
            val v = text(e, 28f, ink).apply {
                gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6))
                background = rounded(if (e == chosen) Color.parseColor("#FFE9D2") else Color.TRANSPARENT, 12)
            }
            v.setOnClickListener {
                chosen = e
                views.forEach { o -> o.background = rounded(if (o == v) Color.parseColor("#FFE9D2") else Color.TRANSPARENT, 12) }
            }
            views += v
            grid.addView(v)
        }
        box.addView(grid)
        AlertDialog.Builder(this).setTitle("New dinner").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) onAdded(MealStore.addMeal(this, n, chosen))
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun editBank() {
        val bank = MealStore.bank(this)
        val labels = bank.map { "${it.emoji}  ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Meal bank (tap to remove)")
            .setItems(labels) { _, which ->
                val m = bank[which]
                AlertDialog.Builder(this).setMessage("Remove ${m.name} from the meal bank?")
                    .setPositiveButton("Remove") { _, _ -> MealStore.removeMeal(this, m.id); render() }
                    .setNegativeButton("Keep", null).show()
            }
            .setPositiveButton("＋ Add a dinner") { _, _ -> addMeal { render() } }
            .setNegativeButton("Close", null).show()
    }

    private fun editBackup() = ask("Backup plate on try nights", MealStore.backup(this), prefill = true) {
        MealStore.setBackup(this, it); render()
    }

    private fun ask(title: String, hintOrValue: String, prefill: Boolean = false, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            textSize = 20f; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            if (prefill) setText(hintOrValue) else hint = hintOrValue
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle(title).setView(wrap)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- little view helpers ----------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Draw.bold
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = text(label, 17f, fg, bold = true).apply {
        setPadding(dp(18), dp(11), dp(18), dp(11))
        background = rounded(bg, 24).apply { if (bg == Color.WHITE) setStroke(dp(1), Color.parseColor("#E5DED3")) }
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, bg: Int, fg: Int) = text(label, 16f, fg).apply {
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = rounded(bg, 16)
    }

    private fun full(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    private fun lp(top: Int = 0, end: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); marginEnd = dp(end); bottomMargin = dp(bottom)
        }
}
