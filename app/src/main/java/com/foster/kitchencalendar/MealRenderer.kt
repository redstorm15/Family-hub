package com.foster.kitchencalendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

data class MealSpec(
    val today: LocalDate,
    val week: List<Pair<LocalDate, DayPlan>>,
    val backup: String,
    val pending: TryRecord?,
)

/**
 * Dinner widget: tonight big (with the ✨ try food and the backup plate), the rest of the
 * week as rows when there's room. Tap opens the planner.
 */
object MealRenderer {
    private val bg = Color.parseColor("#FFF8EE")
    private val ink = Color.parseColor("#2C2C2A")
    private val muted = Color.parseColor("#7A746B")
    private val accent = Color.parseColor("#E8833A")
    private val tryInk = Color.parseColor("#7F55C9")
    private val tryBg = Color.parseColor("#EEE7FA")

    fun render(w: Int, h: Int, dpW: Float, dpH: Float, spec: MealSpec): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = min(w, h).toFloat()
        val unit = w / dpW                       // px per dp
        val corner = min(22 * unit, s * 0.08f)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        fun paint(sz: Float, face: android.graphics.Typeface, color: Int = ink, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sz; typeface = face; this.color = color; textAlign = align }

        val pad = min(16 * unit, s * 0.07f)
        val (_, tonight) = spec.week.first()
        val nothingPlanned = spec.week.all { it.second.meal == null }
        if (nothingPlanned) {
            val p = paint(s * 0.2f, Draw.regular, align = Paint.Align.CENTER)
            c.drawText("🍽️", w / 2f, h * 0.48f, p)
            c.drawText("Tap to plan dinners", w / 2f, h * 0.48f + s * 0.16f, paint(s * 0.075f, Draw.medium, muted, Paint.Align.CENTER))
            return bmp
        }

        // Layout: list beside (wide) or below (tall) the tonight block, if there's room.
        val wide = dpW >= dpH * 1.3f && dpW >= 300
        val tall = !wide && dpH >= 300
        val block = when {
            wide -> RectF(pad, pad, w * 0.48f, h - pad)
            tall -> RectF(pad, pad, w - pad, pad + min(h * 0.45f, w * 0.75f))
            else -> RectF(pad, pad, w - pad, h - pad)
        }
        drawTonight(c, block, tonight, spec.backup) { sz, face, col, al -> paint(sz, face, col, al) }

        val listArea = when {
            wide -> RectF(w * 0.52f, pad, w - pad, h - pad)
            tall -> RectF(pad, block.bottom + pad * 0.6f, w - pad, h - pad)
            else -> null
        }
        if (listArea != null) {
            val banner = spec.pending != null
            val bannerH = if (banner) min(listArea.height() * 0.16f, 44 * unit) else 0f
            val rows = spec.week.drop(1)
            val rowH = (listArea.height() - bannerH - (if (banner) pad * 0.4f else 0f)) / rows.size
            val dayP = paint(min(rowH * 0.36f, 18 * unit), Draw.bold, muted)
            val mealP = paint(min(rowH * 0.42f, 21 * unit), Draw.medium)
            rows.forEachIndexed { i, (date, plan) ->
                val top = listArea.top + i * rowH
                val cy = top + rowH / 2
                if (i > 0) c.drawLine(listArea.left, top, listArea.right, top, Paint().apply { color = Color.parseColor("#EFE6D8"); strokeWidth = unit })
                val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                c.drawText(dayName, listArea.left, Draw.centerBaseline(dayP, cy), dayP)
                val x = listArea.left + dayP.textSize * 2.8f
                val sparkle = if (plan.tryFood != null) "  ✨" else ""
                val label = plan.meal?.let { "${it.emoji}  ${it.name}" } ?: "—"
                c.drawText(Draw.ellipsize(label + sparkle, mealP, listArea.right - x), x, Draw.centerBaseline(mealP, cy),
                    if (plan.meal == null) paint(mealP.textSize, Draw.regular, muted) else mealP)
            }
            spec.pending?.let { rec ->
                val r = RectF(listArea.left, listArea.bottom - bannerH, listArea.right, listArea.bottom)
                c.drawRoundRect(r, bannerH / 2, bannerH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tryBg })
                val bp = paint(bannerH * 0.36f, Draw.medium, tryInk, Paint.Align.CENTER)
                c.drawText(Draw.ellipsize("Did they try the ${rec.food.name}? Tap to tell", bp, r.width() * 0.92f), r.centerX(), Draw.centerBaseline(bp, r.centerY()), bp)
            }
        }
        return bmp
    }

    private fun drawTonight(
        c: Canvas, box: RectF, plan: DayPlan, backup: String,
        paint: (Float, android.graphics.Typeface, Int, Paint.Align) -> Paint,
    ) {
        val bs = min(box.width(), box.height() * 1.25f)
        val label = paint(bs * 0.075f, Draw.bold, accent, Paint.Align.LEFT)
        var y = box.top + label.textSize
        c.drawText("TONIGHT", box.left, y, label)
        val meal = plan.meal
        if (meal == null) {
            c.drawText("Nothing planned", box.left, y + bs * 0.16f, paint(bs * 0.1f, Draw.medium, muted, Paint.Align.LEFT))
            return
        }
        val emojiP = paint(bs * 0.24f, Draw.regular, ink, Paint.Align.LEFT)
        y += emojiP.textSize * 1.05f
        c.drawText(meal.emoji, box.left, y, emojiP)
        val nameP = paint(bs * 0.105f, Draw.bold, ink, Paint.Align.LEFT)
        val lines = Draw.wrap(meal.name, nameP, box.width(), 2)
        lines.forEach { y += nameP.textSize * 1.2f; c.drawText(it, box.left, y, nameP) }

        val t = plan.tryFood ?: return
        val chipP = paint(bs * 0.068f, Draw.medium, tryInk, Paint.Align.LEFT)
        val chipText = Draw.ellipsize("✨ Try: ${t.emoji} ${t.name}", chipP, box.width() - chipP.textSize)
        val chipH = chipP.textSize * 1.9f
        val top = y + chipP.textSize * 0.8f
        if (top + chipH > box.bottom) return
        val r = RectF(box.left, top, box.left + chipP.measureText(chipText) + chipP.textSize * 1.2f, top + chipH)
        c.drawRoundRect(r, chipH / 2, chipH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tryBg })
        c.drawText(chipText, r.left + chipP.textSize * 0.6f, Draw.centerBaseline(chipP, r.centerY()), chipP)
        val backP = paint(bs * 0.06f, Draw.regular, muted, Paint.Align.LEFT)
        val by = r.bottom + backP.textSize * 1.5f
        if (by < box.bottom) c.drawText(Draw.ellipsize("Backup: $backup", backP, box.width()), box.left, by, backP)
    }
}
