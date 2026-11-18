package com.foster.kitchencalendar

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The dinner planner. Each night: pick a dinner (its usual plate comes along), adjust the
 * protein / grain / fruit-veg if you like, and flip on ✨ Try night to swap in new foods,
 * one column at a time. Almost no typing.
 */
class MealPlanActivity : Activity() {

    // Day / night palette (night follows the same setting as the widgets).
    private var dark = false
    private fun pick(day: String, night: String) = Color.parseColor(if (dark) night else day)
    private val ink get() = pick("#2C2C2A", "#F1EAE0")
    private val muted get() = pick("#6E6A63", "#A69C8F")
    private val accent get() = pick("#E8833A", "#F2A05E")
    private val tryInk get() = pick("#7F55C9", "#C9B2F2")
    private val tryBg get() = pick("#EEE7FA", "#352B48")
    private val green get() = pick("#1D9E75", "#2BB389")
    private val surface get() = pick("#FFFFFF", "#2A241F")       // cards, white pills
    private val pageBg get() = pick("#FFF8EE", "#1A1612")
    private val todayCard get() = pick("#FFE9D2", "#3A2C1F")
    private val tryCard get() = pick("#F6F1FD", "#2A2337")
    private val boxColor get() = pick("#F7F2EA", "#332C25")
    private val chipBg get() = pick("#F4EFE7", "#332C25")
    private val mealTile get() = pick("#FFF1E2", "#3A2F24")
    private val stroke get() = pick("#E5DED3", "#463D34")
    private lateinit var list: LinearLayout
    private val today get() = LocalDate.now()
    private var photoMealId: String? = null

    private companion object {
        const val REQ_CAMERA = 41
        const val REQ_PICK = 42
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = Prefs.isDark(this)
        setTheme(if (dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        photoMealId = savedInstanceState?.getString("photoMeal")
        val scroll = ScrollView(this).apply { setBackgroundColor(pageBg) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(28)) }
        scroll.addView(list)
        setContentView(scroll)
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("photoMeal", photoMealId)
    }

    override fun onPause() {
        super.onPause()
        WidgetUpdater.request(this, WidgetKind.MEALS)
    }

    // ---------- layout ----------

    private fun render() {
        list.removeAllViews()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("🍽️  Dinner this week", 28f, ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("✓ Done", green, Color.WHITE) { finish() })
        list.addView(top)
        val actions = LinearLayout(this).apply { setPadding(0, dp(10), 0, dp(6)) }
        actions.addView(pill("✨ Fill the week", accent, Color.WHITE) { MealStore.fillWeek(this, today); render() }, lp(end = 10))
        actions.addView(pill("🔁 Repeat last week", surface, ink) { MealStore.repeatLastWeek(this, today); render() }, lp(end = 10))
        actions.addView(pill("🧺 Meal bank & photos", surface, ink) { editBank() }, lp(end = 10))
        list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })

        val pending = MealStore.pendingFeedback(this, today)
        if (pending.isNotEmpty()) list.addView(feedbackCard(pending), full(top = 10))

        MealStore.week(this, today).forEach { (date, plan) -> list.addView(dayCard(date, plan), full(top = 12)) }

        val wins = MealStore.wins(this)
        if (wins.isNotEmpty()) {
            list.addView(text("🏆  New foods tried", 18f, ink, bold = true), lp(top = 22))
            val row = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
            wins.take(30).forEach { w -> row.addView(chip("${w.result?.emoji ?: "🙂"} ${w.item.emoji} ${w.item.name}", surface, ink), lp(end = 8)) }
            list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
        list.addView(text("Backup plate on try nights: ${MealStore.backup(this)}  ✏️", 15f, muted).apply {
            setPadding(0, dp(20), 0, 0)
            setOnClickListener { editBackup() }
        })
    }

    private fun dayCard(date: LocalDate, plan: DayPlan): View {
        val isToday = date == today
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = rounded(if (plan.tryNight) tryCard else if (isToday) todayCard else surface, 18)
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val dayName = if (isToday) "Tonight" else date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        head.addView(text(dayName, 17f, if (isToday) accent else muted, bold = true), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        val meal = plan.meal
        head.addView(
            text(if (meal != null) "${meal.emoji}  ${meal.name}" else "＋  Pick dinner", 22f, if (meal != null) ink else muted, bold = meal != null)
                .apply { setOnClickListener { pickMeal(date) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (meal?.homeCooked == true) {
            head.addView(pill(if (plan.tryNight) "✨ Try night" else "✨ Try night?", if (plan.tryNight) tryInk else surface,
                if (plan.tryNight) Color.WHITE else tryInk) {
                if (plan.tryNight) MealStore.setTryNight(this, date, false) else MealStore.makeTryNight(this, date)
                render()
            })
        }
        card.addView(head)
        if (meal == null || !meal.homeCooked) return card

        // Protein | Grain | Fruit/veg
        val cols = LinearLayout(this).apply { setPadding(0, dp(10), 0, 0) }
        listOf(Part.PROTEIN, Part.GRAIN, Part.PRODUCE).forEachIndexed { i, part ->
            cols.addView(partBox(date, plan, part), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i < 2) marginEnd = dp(8)
            })
        }
        card.addView(cols)

        val foot = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val extra = plan.parts[Part.EXTRA]
        foot.addView(chip(extra?.let { "${if (it.isNew) "✨ " else ""}${it.emoji} ${it.name}" } ?: "＋ Dip / sauce",
            if (extra?.isNew == true) tryBg else chipBg, if (extra?.isNew == true) tryInk else muted).apply {
            setOnClickListener { pickPart(date, Part.EXTRA) }
        })
        val differs = plan.parts.filterValues { !it.isNew } != meal.usual && plan.newItems.isEmpty()
        if (differs) {
            foot.addView(text("   Save as usual plate", 14f, accent).apply {
                setOnClickListener { MealStore.saveAsUsual(this@MealPlanActivity, date); toast("Saved for ${meal.name}"); render() }
            })
        }
        card.addView(foot)
        return card
    }

    private fun partBox(date: LocalDate, plan: DayPlan, part: Part): View {
        val item = plan.parts[part]
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = rounded(if (item?.isNew == true) tryBg else boxColor, 14)
            setOnClickListener { pickPart(date, part) }
        }
        box.addView(text(if (item?.isNew == true) "✨ NEW ${part.short.uppercase()}" else part.short.uppercase(), 11f,
            if (item?.isNew == true) tryInk else muted, bold = true))
        box.addView(text(item?.let { "${it.emoji}  ${it.name}" } ?: "＋", 17f, if (item != null) ink else muted, bold = item != null).apply {
            setPadding(0, dp(3), 0, 0); maxLines = 2
        })
        return box
    }

    private fun feedbackCard(pending: List<TryRecord>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(tryBg, 18)
        }
        val day = pending.first().date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        card.addView(text("How did $day's try night go?", 19f, ink, bold = true))
        pending.forEach { rec ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
            row.addView(text("${rec.item.emoji}  ${rec.item.name}", 17f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            TryResult.entries.forEach { r ->
                row.addView(pill("${r.emoji} ${r.label}", surface, ink) { MealStore.recordResult(this, rec, r); render() }, lp(end = 6))
            }
            card.addView(row, full())
        }
        return card
    }

    // ---------- pickers ----------

    private fun gridParams() = GridLayout.LayoutParams().apply {
        width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(5), dp(5), dp(5), dp(5))
    }

    private fun pickMeal(date: LocalDate) {
        val grid = GridLayout(this).apply { columnCount = 2; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        lateinit var dialog: AlertDialog
        fun tile(label: String, bg: Int, fg: Int, onClick: () -> Unit) = text(label, 19f, fg, bold = true).apply {
            setPadding(dp(14), dp(16), dp(14), dp(16)); background = rounded(bg, 16)
            setOnClickListener { onClick(); dialog.dismiss() }
        }
        MealStore.bank(this).forEach { m ->
            grid.addView(tile("${m.emoji}  ${m.name}", mealTile, ink) { MealStore.setMeal(this, date, m); render() }, gridParams())
        }
        grid.addView(tile("＋  New dinner", surface, accent) { addMeal { MealStore.setMeal(this, date, it); render() } }, gridParams())
        grid.addView(tile("Clear night", surface, muted) { MealStore.setMeal(this, date, null); render() }, gridParams())
        dialog = AlertDialog.Builder(this).setTitle(date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            .setView(ScrollView(this).apply { addView(grid) }).setNegativeButton("Cancel", null).create()
        dialog.show()
    }

    /** Pick a food for one column: the usual choices, or ✨ something new (which makes it a try night). */
    private fun pickPart(date: LocalDate, part: Part, page: Int = 0) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(8)) }
        lateinit var dialog: AlertDialog
        fun choose(item: PlateItem?) {
            if (item?.isNew == true && !MealStore.day(this, date).tryNight) MealStore.setTryNight(this, date, true)
            MealStore.setPart(this, date, part, item); render(); dialog.dismiss()
        }
        box.addView(text("✨ Something new", 16f, tryInk, bold = true), full(top = 4, bottom = 4))
        val ideas = MealStore.suggestions(this, part, date, page)
        val newGrid = GridLayout(this).apply { columnCount = 2 }
        ideas.take(4).forEach { f ->
            newGrid.addView(text("${f.emoji}  ${f.name}", 16f, ink, bold = true).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(tryBg, 14); maxLines = 2
                setOnClickListener { choose(f) }
            }, gridParams())
        }
        box.addView(newGrid)
        val newRow = LinearLayout(this).apply { setPadding(dp(5), dp(4), 0, dp(8)) }
        newRow.addView(pill("🔀 Other ideas", surface, ink) { dialog.dismiss(); pickPart(date, part, page + 1) }, lp(end = 8))
        newRow.addView(pill("✏️ Type a new one", surface, ink) {
            dialog.dismiss()
            ask("New ${part.label.lowercase()}", "e.g. roasted carrots") { choose(PlateItem(it, "✨", true)) }
        })
        box.addView(newRow)

        box.addView(text("The usual", 16f, muted, bold = true), full(top = 6, bottom = 4))
        val grid = GridLayout(this).apply { columnCount = 3 }
        PlateFoods.usual[part].orEmpty().forEach { f ->
            grid.addView(text("${f.emoji}  ${f.name}", 15f, ink).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10)); background = rounded(boxColor, 12); maxLines = 2
                setOnClickListener { choose(f) }
            }, gridParams())
        }
        box.addView(grid)
        val bottom = LinearLayout(this).apply { setPadding(dp(5), dp(8), 0, 0) }
        bottom.addView(pill("✏️ Type one", surface, ink) {
            dialog.dismiss()
            ask(part.label, "e.g. pierogies") { choose(PlateItem(it, "🍽️")) }
        }, lp(end = 8))
        bottom.addView(pill("None", surface, muted) { choose(null) })
        box.addView(bottom)

        dialog = AlertDialog.Builder(this).setTitle(part.label).setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null).create()
        dialog.show()
    }

    private fun addMeal(onAdded: (Meal) -> Unit) {
        var chosen = MEAL_EMOJI.first()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(6)) }
        val name = EditText(this).apply { hint = "Dinner name"; textSize = 20f; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        box.addView(name)
        val grid = GridLayout(this).apply { columnCount = 8; setPadding(0, dp(10), 0, 0) }
        val views = mutableListOf<TextView>()
        MEAL_EMOJI.forEach { e ->
            val v = text(e, 26f, ink).apply { gravity = Gravity.CENTER; setPadding(dp(5), dp(5), dp(5), dp(5)) }
            v.background = rounded(if (e == chosen) todayCard else Color.TRANSPARENT, 12)
            v.setOnClickListener {
                chosen = e
                views.forEach { o -> o.background = rounded(if (o === v) todayCard else Color.TRANSPARENT, 12) }
            }
            views += v
            grid.addView(v)
        }
        box.addView(grid)
        AlertDialog.Builder(this).setTitle("New dinner").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) onAdded(MealStore.addMeal(this, n, chosen))
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- meal bank + photos ----------

    private fun editBank() {
        val bank = MealStore.bank(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(6)) }
        lateinit var dialog: AlertDialog
        box.addView(text("Add your own photo of a dinner and it shows on the widget on that night.", 14f, muted), full(bottom = 8))
        bank.forEach { m ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(mealTile, 14)
                setOnClickListener { dialog.dismiss(); mealOptions(m) }
            }
            val thumb = MealStore.photo(this, m.id)
            if (thumb != null) row.addView(ImageView(this).apply {
                setImageBitmap(thumb); scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true; background = rounded(surface, 10)
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
            else row.addView(text(m.emoji, 30f, ink).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
            row.addView(text(m.name, 18f, ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text(if (thumb != null) "📷" else "＋📷", 18f, muted))
            box.addView(row, full(bottom = 8))
        }
        dialog = AlertDialog.Builder(this).setTitle("🧺 Meal bank").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("＋ Add a dinner") { _, _ -> addMeal { render() } }
            .setNegativeButton("Close", null).create()
        dialog.show()
    }

    private fun mealOptions(m: Meal) {
        val hasPhoto = MealStore.photoFile(this, m.id).exists()
        val options = listOfNotNull(
            "📷  Take a photo", "🖼️  Choose a photo", if (hasPhoto) "Remove photo" else null, "Remove from meal bank",
        )
        AlertDialog.Builder(this).setTitle("${m.emoji}  ${m.name}").setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "📷  Take a photo" -> takePhoto(m)
                "🖼️  Choose a photo" -> choosePhoto(m)
                "Remove photo" -> { MealStore.removePhoto(this, m.id); editBank() }
                else -> AlertDialog.Builder(this).setMessage("Remove ${m.name} from the meal bank?")
                    .setPositiveButton("Remove") { _, _ -> MealStore.removeMeal(this, m.id); render() }
                    .setNegativeButton("Keep", null).show()
            }
        }.setNegativeButton("Back") { _, _ -> editBank() }.show()
    }

    private fun cameraFile() = File(File(filesDir, "camera").apply { mkdirs() }, "shot.jpg")

    private fun takePhoto(m: Meal) {
        photoMealId = m.id
        val uri = FileProvider.getUriForFile(this, "$packageName.images", cameraFile())
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(intent, REQ_CAMERA) } catch (_: Exception) { toast("No camera app found") }
    }

    private fun choosePhoto(m: Meal) {
        photoMealId = m.id
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try { startActivityForResult(intent, REQ_PICK) } catch (_: Exception) { toast("No photo picker found") }
    }

    @Deprecated("Activity result API isn't available on plain Activity; this is fine here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = photoMealId ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_CAMERA -> { val f = cameraFile(); MealStore.savePhoto(this, id) { f.inputStream() }; f.delete() }
                REQ_PICK -> { val uri: Uri = data?.data ?: return; MealStore.savePhoto(this, id) { contentResolver.openInputStream(uri) } }
            }
            toast("Photo saved")
        } catch (t: Throwable) {
            toast("Couldn't save that photo")
        }
        photoMealId = null
        render()
        WidgetUpdater.request(this, WidgetKind.MEALS)
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Draw.bold
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = text(label, 16f, fg, bold = true).apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(bg, 24).apply { if (bg == surface) setStroke(dp(1), stroke) }
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, bg: Int, fg: Int) = text(label, 15f, fg).apply {
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
