package com.foster.kitchencalendar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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

/** Board geometry: notes fill a grid; a round "+" floats in the bottom-right corner. */
object StickyBoard {
    /** Columns × rows that make the notes as big as possible for this widget shape. */
    fun grid(tiles: Int, w: Float, h: Float): Pair<Int, Int> {
        val n = maxOf(1, tiles)
        var best = 1 to n
        var bestSide = 0f
        for (cols in 1..n) {
            val rows = (n + cols - 1) / cols
            val side = min(w / cols, h / rows)
            if (side > bestSide + 0.5f) { bestSide = side; best = cols to rows }
        }
        return best
    }

    /**
     * Where the + button sits, in the same units as [w]/[h] ([dp] = one dp in those units).
     * Empty board: a big + in the middle. Otherwise a corner button sized to the notes.
     */
    fun addButton(noteCount: Int, w: Float, h: Float, dp: Float): RectF {
        if (noteCount == 0) {
            val side = min(w, h) * 0.5f
            return RectF(w / 2 - side / 2, h / 2 - side / 2, w / 2 + side / 2, h / 2 + side / 2)
        }
        val (cols, rows) = grid(noteCount, w, h)
        val tile = min(w / cols, h / rows)
        val side = (tile * 0.3f).coerceIn(56 * dp, 104 * dp).coerceAtMost(min(w, h) * 0.45f)
        val m = 4 * dp
        return RectF(w - m - side, h - m - side, w - m, h - m)
    }
}

/** Draws the board: each note with a tilt, shadow and curled corner, plus the floating + button. */
object StickyRenderer {

    /** [dp] = pixels per dp in this bitmap (it may be scaled down from the real view). */
    fun render(w: Int, h: Int, dp: Float, notes: List<StickyData>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (notes.isNotEmpty()) {
            val (cols, rows) = StickyBoard.grid(notes.size, w.toFloat(), h.toFloat())
            val cw = w.toFloat() / cols
            val ch = h.toFloat() / rows
            notes.forEachIndexed { slot, note ->
                drawNote(canvas, RectF((slot % cols) * cw, (slot / cols) * ch, (slot % cols + 1) * cw, (slot / cols + 1) * ch), note)
            }
        }
        drawPlus(canvas, StickyBoard.addButton(notes.size, w.toFloat(), h.toFloat(), dp))
        return bmp
    }

    private fun square(cell: RectF, marginFrac: Float): RectF {
        val s = min(cell.width(), cell.height())
        val side = s * (1 - 2 * marginFrac)
        return RectF(cell.centerX() - side / 2, cell.centerY() - side / 2, cell.centerX() + side / 2, cell.centerY() + side / 2)
    }

    private fun drawPlus(canvas: Canvas, box: RectF) {
        val r = box.width() * 0.44f
        val cx = box.centerX()
        val cy = box.centerY()
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1D9E75")
            setShadowLayer(r * 0.18f, 0f, r * 0.08f, Color.argb(110, 0, 0, 0))
        })
        val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = r * 0.2f; strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(cx - r * 0.48f, cy, cx + r * 0.48f, cy, plus)
        canvas.drawLine(cx, cy - r * 0.48f, cx, cy + r * 0.48f, plus)
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
