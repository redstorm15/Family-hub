package com.foster.kitchencalendar

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * A streaming service. [packages] are the known app ids (first installed wins); [labels] are
 * fallbacks matched against installed app names, in case an app id changes.
 */
data class Service(
    val name: String,
    val packages: List<String>,
    val labels: List<String>,
    val packagePrefix: String? = null,     // e.g. any "com.apple.atve…" build of the app
)

/** One installed service, ready to draw and launch. */
data class InstalledService(val name: String, val pkg: String, val icon: Drawable)

object WatchServices {
    /** Live TV first, then the rest in rough order of use. */
    val all = listOf(
        Service("YouTube TV", listOf("com.google.android.apps.youtube.unplugged"), listOf("youtube tv")),
        Service("Netflix", listOf("com.netflix.mediaclient"), listOf("netflix")),
        Service("Disney+", listOf("com.disney.disneyplus"), listOf("disney+", "disney plus")),
        Service("Hulu", listOf("com.hulu.plus", "com.hulu.livingroomplus"), listOf("hulu")),
        Service("HBO Max", listOf("com.wbd.stream", "com.hbo.hbonow"), listOf("hbo max", "max")),
        Service("Prime Video", listOf("com.amazon.avod.thirdpartyclient"), listOf("prime video")),
        Service("Apple TV", listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv"), listOf("apple tv", "apple tv+"), "com.apple.atve"),
        Service("Paramount+", listOf("com.cbs.app"), listOf("paramount+", "paramount plus")),
        Service("Peacock", listOf("com.peacocktv.peacockandroid"), listOf("peacock")),
    )

    /** The services installed on this tablet, in order. */
    fun installed(context: Context): List<InstalledService> {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).associate { it.activityInfo.packageName to normalize(it.loadLabel(pm).toString()) }

        return all.mapNotNull { s ->
            val pkg = s.packages.firstOrNull { it in launchable }
                ?: s.packagePrefix?.let { prefix -> launchable.keys.firstOrNull { it.startsWith(prefix) } }
                ?: launchable.entries.firstOrNull { (_, label) -> s.labels.any { label == normalize(it) } }?.key
                ?: return@mapNotNull null
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: PackageManager.NameNotFoundException) { return@mapNotNull null }
            InstalledService(s.name, pkg, icon)
        }
    }

    /** "Apple\u00A0TV" and "Apple TV" should match: fold any kind of space, case and trademark marks. */
    private fun normalize(label: String) =
        label.replace(Regex("[\\s\u00A0\u2007\u202F]+"), " ").replace(Regex("[™®]"), "").trim().lowercase()

    fun launchIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Tile grid for the Watch widget: as few rows as fit, tiles as big as possible. */
object WatchRenderer {
    fun grid(count: Int, w: Float, h: Float): Pair<Int, Int> = StickyBoard.grid(count, w, h)

    fun render(w: Int, h: Int, dp: Float, services: List<InstalledService>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val corner = min(22 * dp, min(w, h) * 0.08f)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#252A35"), Color.parseColor("#14171E"), Shader.TileMode.CLAMP)
        })
        if (services.isEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = min(w, h) * 0.08f; typeface = Draw.medium; textAlign = Paint.Align.CENTER
            }
            c.drawText("No streaming apps found", w / 2f, h / 2f, p)
            return bmp
        }
        val pad = 8 * dp
        val (cols, rows) = grid(services.size, w - pad * 2, h - pad * 2)
        val cw = (w - pad * 2) / cols
        val ch = (h - pad * 2) / rows
        val tileBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 255, 255, 255) }
        val showLabels = min(cw, ch) > 96 * dp
        services.forEachIndexed { i, s ->
            val cell = RectF(pad + (i % cols) * cw, pad + (i / cols) * ch, 0f, 0f)
            cell.right = cell.left + cw; cell.bottom = cell.top + ch
            val inset = min(cw, ch) * 0.06f
            val tile = RectF(cell.left + inset, cell.top + inset, cell.right - inset, cell.bottom - inset)
            c.drawRoundRect(tile, corner * 0.8f, corner * 0.8f, tileBg)

            val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; typeface = Draw.medium; textAlign = Paint.Align.CENTER
                textSize = min(tile.width(), tile.height()) * 0.11f
            }
            val labelH = if (showLabels) labelP.textSize * 1.8f else 0f
            val iconSide = min(tile.width() * 0.62f, (tile.height() - labelH) * 0.78f)
            val cx = tile.centerX()
            val cy = tile.top + (tile.height() - labelH) / 2 + (if (showLabels) labelP.textSize * 0.25f else 0f)
            s.icon.setBounds((cx - iconSide / 2).toInt(), (cy - iconSide / 2).toInt(), (cx + iconSide / 2).toInt(), (cy + iconSide / 2).toInt())
            s.icon.draw(c)
            if (showLabels) c.drawText(Draw.ellipsize(s.name, labelP, tile.width() * 0.9f), cx, tile.bottom - labelP.textSize * 0.75f, labelP)
        }
        return bmp
    }
}
