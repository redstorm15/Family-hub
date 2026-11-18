package com.foster.kitchencalendar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.random.Random

/** Paper colors for sticky notes. */
object Paper {
    val colors: List<Int> = listOf("#FFF27A", "#F9D9B6", "#F8BBD0", "#C8F0D8", "#C6E2FF").map(Color::parseColor)
    fun color(index: Int) = colors[index.coerceIn(0, colors.size - 1)]
    val handwriting: Typeface = Typeface.create("casual", Typeface.NORMAL)
}

data class StickyData(val id: Int, val colorIndex: Int, val text: String, val drawing: Bitmap?) {
    val isEmpty get() = text.isBlank() && drawing == null
}

/**
 * All the family's notes, shared by every sticky board on the home screen.
 * Color + typed text live in prefs, a hand drawing as a PNG. Newest first.
 */
object StickyStore {
    const val MAX_NOTES = 12
    private fun prefs(context: Context) = context.getSharedPreferences("sticky_notes", Context.MODE_PRIVATE)
    private fun file(context: Context, id: Int) = File(File(context.filesDir, "sticky").apply { mkdirs() }, "note_$id.png")

    fun ids(context: Context): List<Int> =
        prefs(context).getString("order", "")!!.split(',').mapNotNull { it.trim().toIntOrNull() }

    fun all(context: Context): List<StickyData> = ids(context).map { load(context, it) }

    fun load(context: Context, id: Int): StickyData {
        val p = prefs(context)
        val f = file(context, id)
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        return StickyData(id, p.getInt("color_$id", 0), p.getString("text_$id", "")!!, bmp)
    }

    /** Saves a note; [id] < 0 makes a new one at the front of the board. */
    fun save(context: Context, id: Int, colorIndex: Int, text: String, drawing: Bitmap?) {
        val p = prefs(context)
        val order = ids(context).toMutableList()
        val noteId = if (id >= 0) id else p.getInt("next_id", 1)
        if (noteId !in order) order.add(0, noteId)
        val extra = order.drop(MAX_NOTES)       // oldest notes fall off a full board
        p.edit()
            .putInt("next_id", maxOf(p.getInt("next_id", 1), noteId + 1))
            .putString("order", order.take(MAX_NOTES).joinToString(","))
            .putInt("color_$noteId", colorIndex)
            .putString("text_$noteId", text.trim())
            .apply()
        extra.forEach { removeData(context, it) }
        val f = file(context, noteId)
        if (drawing == null) f.delete() else FileOutputStream(f).use { drawing.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun delete(context: Context, id: Int) {
        prefs(context).edit().putString("order", ids(context).filter { it != id }.joinToString(",")).apply()
        removeData(context, id)
    }

    private fun removeData(context: Context, id: Int) {
        prefs(context).edit().remove("color_$id").remove("text_$id").apply()
        file(context, id).delete()
    }
}

/** How the board splits into tiles: slot 0 is always "+ New note" (unless full), then the notes. */
object StickyBoard {
    fun hasAddTile(noteCount: Int) = noteCount < StickyStore.MAX_NOTES
    fun tiles(noteCount: Int) = if (hasAddTile(noteCount)) noteCount + 1 else noteCount

    /** Columns × rows that make the tiles as big as possible for this widget shape. */
    fun grid(tiles: Int, w: Float, h: Float): Pair<Int, Int> {
        var best = 1 to tiles
        var bestSide = 0f
        for (cols in 1..tiles) {
            val rows = (tiles + cols - 1) / cols
            val side = min(w / cols, h / rows)
            if (side > bestSide + 0.5f) { bestSide = side; best = cols to rows }
        }
        return best
    }

    /** Which note a slot shows, or null for the "+" tile / an empty slot. */
    fun noteAt(slot: Int, notes: List<StickyData>): StickyData? {
        val i = if (hasAddTile(notes.size)) slot - 1 else slot
        return notes.getOrNull(i)
    }
}

/** Draws the board: a "+ New note" tile, then each note with a tilt, shadow and curled corner. */
object StickyRenderer {

    fun render(w: Int, h: Int, notes: List<StickyData>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val tiles = StickyBoard.tiles(notes.size)
        val (cols, rows) = StickyBoard.grid(tiles, w.toFloat(), h.toFloat())
        val cw = w.toFloat() / cols
        val ch = h.toFloat() / rows
        for (slot in 0 until tiles) {
            val cell = RectF((slot % cols) * cw, (slot / cols) * ch, (slot % cols + 1) * cw, (slot / cols + 1) * ch)
            val note = StickyBoard.noteAt(slot, notes)
            if (note == null) drawAddTile(canvas, cell, alone = notes.isEmpty()) else drawNote(canvas, cell, note)
        }
        return bmp
    }

    private fun square(cell: RectF, marginFrac: Float): RectF {
        val s = min(cell.width(), cell.height())
        val side = s * (1 - 2 * marginFrac)
        return RectF(cell.centerX() - side / 2, cell.centerY() - side / 2, cell.centerX() + side / 2, cell.centerY() + side / 2)
    }

    private fun drawAddTile(canvas: Canvas, cell: RectF, alone: Boolean) {
        val rect = square(cell, 0.1f)
        val s = rect.width()
        val corner = s * 0.08f
        canvas.drawRoundRect(rect, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) })
        canvas.drawRoundRect(rect, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = s * 0.02f; color = Color.argb(140, 0, 0, 0)
            pathEffect = DashPathEffect(floatArrayOf(s * 0.05f, s * 0.035f), 0f)
        })
        // A big round "+" kids can't miss
        val r = s * 0.2f
        val cx = rect.centerX()
        val cy = rect.centerY() - s * 0.08f
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D9E75") })
        val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = r * 0.24f; strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, plus)
        canvas.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.5f, plus)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2C2C2A"); typeface = Paper.handwriting; textAlign = Paint.Align.CENTER
            textSize = s * if (alone) 0.085f else 0.12f
        }
        canvas.drawText(if (alone) "Tap to add a note" else "New note", cx, cy + r + label.textSize * 1.3f, label)
    }

    private fun drawNote(canvas: Canvas, cell: RectF, note: StickyData) {
        val rect = square(cell, 0.09f)
        val s = min(cell.width(), cell.height())
        val tilt = Random(note.id).nextFloat() * 6f - 3f      // each note sits a little differently

        canvas.save()
        canvas.rotate(tilt, rect.centerX(), rect.centerY())
        val paperColor = Paper.color(note.colorIndex)
        val curl = s * 0.16f
        val l = rect.left; val t = rect.top; val r = rect.right; val b = rect.bottom

        // Paper with the bottom-right corner lifting up
        val paper = Path().apply {
            moveTo(l, t); lineTo(r, t); lineTo(r, b - curl)
            quadTo(r - curl * 0.1f, b - curl * 0.1f, r - curl, b)
            lineTo(l, b); close()
        }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paperColor
            setShadowLayer(s * 0.035f, s * 0.012f, s * 0.022f, Color.argb(90, 0, 0, 0))
        }
        canvas.drawPath(paper, shadow)
        canvas.drawPath(paper, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paperColor })

        // Sticky strip along the top
        canvas.save()
        canvas.clipPath(paper)
        canvas.drawRect(l, t, r, t + rect.height() * 0.13f, Paint().apply { color = Color.argb(16, 0, 0, 0) })
        canvas.restore()

        // The curled flap
        val flap = Path().apply {
            moveTo(r, b - curl)
            quadTo(r - curl * 0.55f, b - curl * 0.75f, r - curl * 0.62f, b - curl * 0.62f)
            quadTo(r - curl * 0.75f, b - curl * 0.55f, r - curl, b)
            quadTo(r - curl * 0.1f, b - curl * 0.1f, r, b - curl)
            close()
        }
        canvas.drawPath(flap, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Motif.blend(paperColor, Color.BLACK, 0.12f) })

        // Content: typed text, then the drawing on top
        val inner = RectF(l + s * 0.07f, t + rect.height() * 0.15f, r - s * 0.07f, b - curl * 0.55f)
        if (note.text.isNotBlank()) drawFittedText(canvas, note.text, inner, s)
        note.drawing?.let { d ->
            val scale = min(inner.width() / d.width, inner.height() / d.height)
            val dw = d.width * scale
            val dh = d.height * scale
            val dst = RectF(inner.centerX() - dw / 2, inner.centerY() - dh / 2, inner.centerX() + dw / 2, inner.centerY() + dh / 2)
            canvas.drawBitmap(d, Rect(0, 0, d.width, d.height), dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        canvas.restore()
    }

    /** Biggest size that fits: "hi" is huge, a longer message still fits. */
    private fun drawFittedText(canvas: Canvas, text: String, box: RectF, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2C2C2A"); typeface = Paper.handwriting; textAlign = Paint.Align.CENTER
        }
        var size = box.height() * 0.42f
        var lines: List<String>
        while (true) {
            p.textSize = size
            lines = Draw.wrap(text, p, box.width(), 8)
            val tooTall = lines.size * size * 1.15f > box.height()
            val cut = lines.lastOrNull()?.endsWith("…") == true
            if ((!tooTall && !cut) || size < s * 0.05f) break
            size *= 0.9f
        }
        val total = lines.size * size * 1.15f
        var y = box.centerY() - total / 2 + size * 0.9f
        for (line in lines) {
            canvas.drawText(line, box.centerX(), y, p)
            y += size * 1.15f
        }
    }
}
