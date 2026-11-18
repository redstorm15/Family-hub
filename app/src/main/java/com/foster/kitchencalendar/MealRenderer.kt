package com.foster.kitchencalendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class MealSpec(
    val today: LocalDate,
    val week: List<Pair<LocalDate, DayPlan>>,
    val backup: String,
    val pending: List<TryRecord>,
    val tonightPhoto: Bitmap?,
)

/**
 * Dinner widget: a hero card for tonight (your photo, or an illustrated card), the plate as
 * Protein | Grain | Fruit/veg, and the rest of the week when there's room. Tap opens the planner.
 */
object MealRenderer {
    private val bg = Color.parseColor("#FFF8EE")
    private val ink = Color.parseColor("#2C2C2A")
    private val muted = Color.parseColor("#7A746B")
    private val accent = Color.parseColor("#E8833A")
    private val tryInk = Color.parseColor("#7F55C9")
    private val tryBg = Color.parseColor("#EEE7FA")
    private val boxBg = Color.parseColor("#F4ECDF")

    private fun paint(sz: Float, face: Typeface, color: Int = ink, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sz; typeface = face; this.color = color; textAlign = align }

    fun render(w: Int, h: Int, dpW: Float, dpH: Float, spec: MealSpec): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val unit = w / dpW                       // px per dp
        val s = min(w, h).toFloat()
        val corner = min(22 * unit, s * 0.08f)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val pad = min(14 * unit, s * 0.06f)

        val tonight = spec.week.first().second
        if (spec.week.all { it.second.meal == null }) {
            c.drawText("🍽️", w / 2f, h * 0.48f, paint(s * 0.2f, Draw.regular, align = Paint.Align.CENTER))
            c.drawText("Tap to plan dinners", w / 2f, h * 0.48f + s * 0.16f, paint(s * 0.075f, Draw.medium, muted, Paint.Align.CENTER))
            return bmp
        }

        val wide = dpW >= dpH * 1.25f && dpW >= 320
        val tall = !wide && dpH >= 330
        val small = !wide && !tall
        val left = when {
            wide -> RectF(pad, pad, w * 0.55f, h - pad)
            tall -> RectF(pad, pad, w - pad, pad + min(h * 0.56f, w * 1.0f))
            else -> RectF(pad, pad, w - pad, h - pad)
        }

        // Hero card + plate row inside the "tonight" area
        val showPlate = tonight.meal?.homeCooked == true && tonight.parts.isNotEmpty() && !small
        val plateH = if (showPlate) min(left.height() * 0.3f, 96 * unit) else 0f
        val extraH = if (showPlate && (tonight.parts[Part.EXTRA] != null || tonight.tryNight)) min(left.height() * 0.08f, 26 * unit) else 0f
        val hero = RectF(left.left, left.top, left.right, left.bottom - plateH - extraH - (if (showPlate) pad * 0.6f else 0f))
        drawHero(c, hero, tonight, spec.tonightPhoto, unit, corner * 0.85f)
        if (showPlate) {
            val row = RectF(left.left, hero.bottom + pad * 0.6f, left.right, hero.bottom + pad * 0.6f + plateH)
            drawPlate(c, row, tonight, unit)
            if (extraH > 0) drawExtraLine(c, RectF(left.left, row.bottom, left.right, row.bottom + extraH), tonight, spec.backup, unit)
        }

        // The rest of the week
        val listArea = when {
            wide -> RectF(w * 0.55f + pad * 1.2f, pad, w - pad, h - pad)
            tall -> RectF(pad, left.bottom + pad, w - pad, h - pad)
            else -> null
        } ?: return bmp
        val bannerH = if (spec.pending.isNotEmpty()) min(listArea.height() * 0.15f, 44 * unit) else 0f
        val rows = spec.week.drop(1)
        val rowH = (listArea.height() - bannerH - (if (bannerH > 0) pad * 0.5f else 0f)) / rows.size
        val dayP = paint(min(rowH * 0.34f, 17 * unit), Draw.bold, muted)
        val mealP = paint(min(rowH * 0.4f, 20 * unit), Draw.medium)
        val line = Paint().apply { color = Color.parseColor("#EFE6D8"); strokeWidth = unit }
        rows.forEachIndexed { i, (date, plan) ->
            val top = listArea.top + i * rowH
            val cy = top + rowH / 2
            if (i > 0) c.drawLine(listArea.left, top, listArea.right, top, line)
            c.drawText(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), listArea.left, Draw.centerBaseline(dayP, cy), dayP)
            val x = listArea.left + dayP.textSize * 2.6f
            val meal = plan.meal
            if (meal == null) { c.drawText("—", x, Draw.centerBaseline(mealP, cy), paint(mealP.textSize, Draw.regular, muted)); return@forEachIndexed }
            var right = listArea.right
            if (plan.tryNight) {
                val tp = paint(mealP.textSize * 0.7f, Draw.bold, tryInk, Paint.Align.CENTER)
                val tw = tp.measureText("✨ TRY") + tp.textSize * 1.2f
                val r = RectF(right - tw, cy - tp.textSize * 0.95f, right, cy + tp.textSize * 0.95f)
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tryBg })
                c.drawText("✨ TRY", r.centerX(), Draw.centerBaseline(tp, cy), tp)
                right = r.left - tp.textSize * 0.6f
            }
            c.drawText(Draw.ellipsize("${meal.emoji}  ${meal.name}", mealP, right - x), x, Draw.centerBaseline(mealP, cy), mealP)
        }
        if (bannerH > 0) {
            val r = RectF(listArea.left, listArea.bottom - bannerH, listArea.right, listArea.bottom)
            c.drawRoundRect(r, bannerH / 2, bannerH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tryBg })
            val bp = paint(bannerH * 0.36f, Draw.medium, tryInk, Paint.Align.CENTER)
            val first = spec.pending.first().item
            val msg = if (spec.pending.size == 1) "Did they try the ${first.name}? Tap to tell" else "How did the try night go? Tap to tell"
            c.drawText(Draw.ellipsize(msg, bp, r.width() * 0.92f), r.centerX(), Draw.centerBaseline(bp, r.centerY()), bp)
        }
        return bmp
    }

    /** Tonight: your photo with the name over it, or a warm illustrated card with the dinner's foods. */
    private fun drawHero(c: Canvas, r: RectF, plan: DayPlan, photo: Bitmap?, unit: Float, radius: Float) {
        val meal = plan.meal
        val clip = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        if (meal != null && photo != null) {
            val scale = max(r.width() / photo.width, r.height() / photo.height)
            val sw = r.width() / scale; val sh = r.height() / scale
            val sx = (photo.width - sw) / 2; val sy = (photo.height - sh) / 2
            c.drawBitmap(photo, Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), r, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val base = meal?.let { MealStore.color(it) } ?: Color.parseColor("#D9CFC1")
            c.drawRect(r, Paint().apply {
                shader = LinearGradient(r.left, r.top, r.right, r.bottom, Motif.blend(base, Color.WHITE, 0.15f), Motif.blend(base, Color.BLACK, 0.18f), Shader.TileMode.CLAMP)
            })
            if (meal != null) {
                // Scatter the plate's foods softly in the background, the dinner's emoji big on the right.
                val foods = (plan.parts.values.map { it.emoji } + meal.emoji).distinct()
                val rnd = Random(meal.id.hashCode())
                val small = min(r.width(), r.height()) * 0.13f
                val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = small; alpha = 70; textAlign = Paint.Align.CENTER }
                repeat(14) { k ->
                    val fx = r.left + rnd.nextFloat() * r.width()
                    val fy = r.top + small + rnd.nextFloat() * (r.height() - small)
                    c.save(); c.rotate(rnd.nextFloat() * 40 - 20, fx, fy)
                    c.drawText(foods[k % foods.size], fx, fy, faint)
                    c.restore()
                }
                val bigSize = min(r.height() * 0.62f, r.width() * 0.42f)
                val big = paint(bigSize, Draw.regular, align = Paint.Align.CENTER).apply { setShadowLayer(bigSize * 0.06f, 0f, bigSize * 0.03f, Color.argb(80, 0, 0, 0)) }
                c.save(); c.rotate(-8f, r.right - bigSize * 0.62f, r.centerY())
                c.drawText(meal.emoji, r.right - bigSize * 0.62f, Draw.centerBaseline(big, r.centerY() - r.height() * 0.04f), big)
                c.restore()
            }
        }
        // Readable text over any background
        c.drawRect(RectF(r.left, r.top + r.height() * 0.35f, r.right, r.bottom), Paint().apply {
            shader = LinearGradient(0f, r.top + r.height() * 0.35f, 0f, r.bottom, Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP)
        })
        c.restore()

        val inset = min(14 * unit, r.width() * 0.06f)
        val hs = min(r.width(), r.height() * 1.4f)
        val label = paint(hs * 0.06f, Draw.bold, Color.WHITE).apply { setShadowLayer(4 * unit, 0f, unit, Color.argb(120, 0, 0, 0)) }
        val labelText = if (plan.tryNight) "TONIGHT  ·  ✨ TRY NIGHT" else "TONIGHT"
        c.drawText(labelText, r.left + inset, r.top + inset + label.textSize, label)
        val name = meal?.name ?: "Nothing planned"
        val nameP = paint(hs * 0.1f, Draw.bold, Color.WHITE).apply { setShadowLayer(6 * unit, 0f, unit * 1.5f, Color.argb(140, 0, 0, 0)) }
        val maxW = if (photo == null && meal != null) r.width() * 0.62f else r.width() - inset * 2
        val lines = Draw.wrap(name, nameP, maxW, 2)
        var y = r.bottom - inset - (lines.size - 1) * nameP.textSize * 1.12f
        lines.forEach { c.drawText(it, r.left + inset, y, nameP); y += nameP.textSize * 1.12f }
    }

    private fun drawPlate(c: Canvas, row: RectF, plan: DayPlan, unit: Float) {
        val parts = listOf(Part.PROTEIN, Part.GRAIN, Part.PRODUCE)
        val gap = 6 * unit
        val bw = (row.width() - gap * 2) / 3
        parts.forEachIndexed { i, part ->
            val r = RectF(row.left + i * (bw + gap), row.top, row.left + i * (bw + gap) + bw, row.bottom)
            val item = plan.parts[part]
            val isNew = item?.isNew == true
            c.drawRoundRect(r, 12 * unit, 12 * unit, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (isNew) tryBg else boxBg })
            val ip = min(10 * unit, r.width() * 0.08f)
            val lp = paint(min(r.height() * 0.15f, 12 * unit), Draw.bold, if (isNew) tryInk else muted)
            c.drawText(Draw.ellipsize(if (isNew) "✨ NEW" else part.short.uppercase(), lp, r.width() - ip * 2), r.left + ip, r.top + ip + lp.textSize, lp)
            if (item == null) {
                c.drawText("—", r.left + ip, r.bottom - ip, paint(lp.textSize * 1.4f, Draw.regular, muted))
                return@forEachIndexed
            }
            val ep = paint(min(r.height() * 0.3f, r.width() * 0.26f), Draw.regular)
            val ey = r.top + ip + lp.textSize + ep.textSize * 1.05f
            c.drawText(item.emoji, r.left + ip, ey, ep)
            val np = paint(min(r.height() * 0.16f, 15 * unit), Draw.medium, ink)
            val nl = Draw.wrap(item.name, np, r.width() - ip * 2, 2)
            var ny = max(ey + np.textSize * 1.25f, r.bottom - ip - (nl.size - 1) * np.textSize * 1.15f)
            if (ny > r.bottom - ip * 0.4f) ny = r.bottom - ip * 0.4f
            nl.forEach { c.drawText(it, r.left + ip, ny, np); ny += np.textSize * 1.15f }
        }
    }

    private fun drawExtraLine(c: Canvas, r: RectF, plan: DayPlan, backup: String, unit: Float) {
        val p = paint(min(r.height() * 0.55f, 14 * unit), Draw.medium, muted)
        val extra = plan.parts[Part.EXTRA]
        val bits = listOfNotNull(
            extra?.let { "${if (it.isNew) "✨ " else ""}Dip: ${it.emoji} ${it.name}" },
            if (plan.tryNight) "Backup: $backup" else null,
        )
        c.drawText(Draw.ellipsize(bits.joinToString("   ·   "), p, r.width()), r.left, Draw.centerBaseline(p, r.centerY() + r.height() * 0.1f), p)
    }
}
