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
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Set up the people for "Today by person": photo, name, which calendars are theirs, color, order. */
class PeopleActivity : Activity() {
    private var dark = false
    private fun pick(day: String, night: String) = Color.parseColor(if (dark) night else day)
    private val ink get() = pick("#2C2C2A", "#ECEAE4")
    private val muted get() = pick("#6E6A63", "#9C978F")
    private val surface get() = pick("#FFFFFF", "#26262A")
    private val pageBg get() = pick("#F4F2EE", "#151517")
    private val green get() = pick("#1D9E75", "#2BB389")
    private lateinit var list: LinearLayout
    private var photoFor: String? = null

    private val palette = listOf("#E24B4A", "#378ADD", "#1D9E75", "#E0A800", "#7F55C9", "#D85A30", "#2BA4C9", "#D4537E", "#639922", "#5F5E5A")
        .map(Color::parseColor)

    private companion object { const val REQ_CAMERA = 51; const val REQ_PICK = 52 }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = Prefs.isDark(this)
        setTheme(if (dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        photoFor = savedInstanceState?.getString("photoFor")
        // First visit: start from the calendars so there's something to edit.
        if (!PeopleStore.isConfigured(this)) PeopleStore.save(this, PeopleStore.defaults(this))
        val scroll = ScrollView(this).apply { setBackgroundColor(pageBg) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(28)) }
        scroll.addView(list)
        setContentView(scroll)
        render()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("photoFor", photoFor) }

    override fun onPause() {
        super.onPause()
        WidgetUpdater.request(this, WidgetKind.PEOPLE)
    }

    private fun render() {
        list.removeAllViews()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("👨‍👩‍👧  People", 28f, ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("✓ Done", green, Color.WHITE) { finish() })
        list.addView(top)
        list.addView(text("Each person gets a column on the \"Today by person\" widget. Give everyone a photo and pick which calendars are theirs.", 15f, muted), full(top = 6))

        val people = PeopleStore.saved(this)
        val cals = CalendarRepository.listCalendars(this)
        people.forEachIndexed { i, p ->
            val card = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(surface, 18)
                setOnClickListener { editPerson(p) }
            }
            val photo = PeopleStore.photo(this, p.id)
            if (photo != null) card.addView(ImageView(this).apply {
                setImageBitmap(photo); scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.color) }
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })
            else card.addView(text(p.name.take(1).uppercase(), 24f, Draw.onColor(p.color), bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.color) }
            }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(p.name, 19f, ink, bold = true))
            val names = cals.filter { it.id in p.calendarIds }.joinToString(", ") { it.name }.ifBlank { "No calendars yet" }
            info.addView(text(names, 14f, muted))
            card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i > 0) card.addView(text("▲", 20f, muted).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { move(i, -1) } })
            if (i < people.size - 1) card.addView(text("▼", 20f, muted).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { move(i, +1) } })
            list.addView(card, full(top = 12))
        }

        val row = LinearLayout(this).apply { setPadding(0, dp(16), 0, 0) }
        row.addView(pill("＋ Add a person", surface, ink) { editPerson(null) }, lp(end = 10))
        row.addView(pill("↺ Start over from calendars", surface, muted) {
            AlertDialog.Builder(this).setMessage("Replace everyone with one person per calendar?")
                .setPositiveButton("Start over") { _, _ -> PeopleStore.save(this, PeopleStore.defaults(this)); render() }
                .setNegativeButton("Cancel", null).show()
        })
        list.addView(row)

        list.addView(Switch(this).apply {
            text = "Show a 🏠 Family column for calendars nobody owns"
            textSize = 16f; setTextColor(ink)
            isChecked = PeopleStore.showFamily(this@PeopleActivity)
            setOnCheckedChangeListener { _, v -> PeopleStore.setShowFamily(this@PeopleActivity, v) }
        }, full(top = 18))
    }

    private fun move(i: Int, d: Int) {
        val p = PeopleStore.saved(this).toMutableList()
        val j = i + d
        if (j !in p.indices) return
        val t = p[i]; p[i] = p[j]; p[j] = t
        PeopleStore.save(this, p); render()
    }

    private fun editPerson(existing: Person?) {
        val cals = CalendarRepository.listCalendars(this)
        val id = existing?.id ?: "p${System.currentTimeMillis()}"
        var color = existing?.color ?: palette[PeopleStore.saved(this).size % palette.size]
        val chosen = existing?.calendarIds?.toMutableSet() ?: mutableSetOf()

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(6)) }
        val name = EditText(this).apply {
            hint = "Name"; textSize = 20f; setText(existing?.name.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        box.addView(name)

        box.addView(text("Color", 15f, muted, bold = true), full(top = 12))
        val colors = LinearLayout(this)
        val dots = mutableListOf<TextView>()
        palette.forEach { pc ->
            val v = TextView(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pc); if (pc == color) setStroke(dp(3), ink) }
            }
            v.setOnClickListener {
                color = pc
                dots.forEachIndexed { k, o -> (o.background as GradientDrawable).setStroke(if (palette[k] == pc) dp(3) else 0, ink) }
            }
            dots += v
            colors.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
        }
        box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(colors) })

        box.addView(text("Their calendars", 15f, muted, bold = true), full(top = 14))
        cals.forEach { cal ->
            box.addView(android.widget.CheckBox(this).apply {
                text = if (cal.account.isNotBlank() && cal.account != cal.name) "${cal.name}  ·  ${cal.account}" else cal.name
                textSize = 16f; setTextColor(ink)
                isChecked = cal.id in chosen
                buttonTintList = android.content.res.ColorStateList.valueOf(cal.color or 0xFF000000.toInt())
                setOnCheckedChangeListener { _, on -> if (on) chosen += cal.id else chosen -= cal.id }
            })
        }

        box.addView(text("Photo", 15f, muted, bold = true), full(top = 14))
        val photoRow = LinearLayout(this)
        lateinit var dialog: AlertDialog
        fun commit(): Boolean {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { toast("Add a name first"); return false }
            val person = Person(id, n, color, chosen.toSet())
            val all = PeopleStore.saved(this)
            PeopleStore.save(this, if (all.any { it.id == id }) all.map { if (it.id == id) person else it } else all + person)
            return true
        }
        photoRow.addView(pill("📷 Take", surface, ink) { if (commit()) { dialog.dismiss(); takePhoto(id) } }, lp(end = 8))
        photoRow.addView(pill("🖼️ Choose", surface, ink) { if (commit()) { dialog.dismiss(); choosePhoto(id) } }, lp(end = 8))
        if (PeopleStore.hasPhoto(this, id)) photoRow.addView(pill("↻ Rotate", surface, ink) {
            PeopleStore.rotatePhoto(this, id); toast("Rotated"); render()
        }, lp(end = 8))
        if (PeopleStore.hasPhoto(this, id)) photoRow.addView(pill("Remove", surface, muted) {
            PeopleStore.removePhoto(this, id); toast("Photo removed")
        })
        box.addView(photoRow)

        val b = AlertDialog.Builder(this).setTitle(if (existing == null) "New person" else existing.name)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ -> if (commit()) render() }
            .setNegativeButton("Cancel", null)
        if (existing != null) b.setNeutralButton("Remove") { _, _ ->
            PeopleStore.save(this, PeopleStore.saved(this).filter { it.id != existing.id }); PeopleStore.removePhoto(this, existing.id); render()
        }
        dialog = b.create()
        dialog.show()
    }

    // ---------- photos ----------

    private fun cameraFile() = File(File(filesDir, "camera").apply { mkdirs() }, "person.jpg")

    private fun takePhoto(id: String) {
        photoFor = id
        val uri = FileProvider.getUriForFile(this, "$packageName.images", cameraFile())
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(intent, REQ_CAMERA) } catch (_: Exception) { toast("No camera app found") }
    }

    private fun choosePhoto(id: String) {
        photoFor = id
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES) else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try { startActivityForResult(intent, REQ_PICK) } catch (_: Exception) { toast("No photo picker found") }
    }

    @Deprecated("Plain Activity result callback; fine here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = photoFor ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_CAMERA -> { val f = cameraFile(); PeopleStore.savePhoto(this, id) { f.inputStream() }; f.delete() }
                REQ_PICK -> { val uri: Uri = data?.data ?: return; PeopleStore.savePhoto(this, id) { contentResolver.openInputStream(uri) } }
            }
        } catch (_: Throwable) { toast("Couldn't save that photo") }
        photoFor = null
        render()
        WidgetUpdater.request(this, WidgetKind.PEOPLE)
    }

    // ---------- helpers ----------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Draw.bold
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit) = text(label, 16f, fg, bold = true).apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(bg, 24).apply { if (bg == surface) setStroke(dp(1), pick("#E2DED6", "#3A3A3E")) }
        setOnClickListener { onClick() }
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun lp(end: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(end) }
}
