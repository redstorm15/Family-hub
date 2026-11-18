package com.foster.kitchencalendar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** The drawing tools. Widths are in dp. */
enum class Brush(val emoji: String, val label: String, val widthDp: Float) {
    CRAYON("🖍️", "Crayon", 14f),
    MARKER("🖊️", "Marker", 11f),
    PAINT("🖌️", "Paint", 38f),
    PENCIL("✏️", "Pencil", 3.5f),
    ERASER("🧽", "Eraser", 44f),
}

/** One stroke on the roll, in roll coordinates (y grows down the paper forever). */
class Stroke(val brush: Brush, val color: Int, val width: Float) {
    val points = ArrayList<Float>()
    val bounds = RectF()

    fun add(x: Float, y: Float) {
        if (points.isEmpty()) bounds.set(x, y, x, y) else bounds.union(x, y)
        points += x; points += y
    }

    fun path(): Path {
        val p = Path()
        if (points.size < 2) return p
        p.moveTo(points[0], points[1])
        var lx = points[0]; var ly = points[1]
        var i = 2
        while (i < points.size) {
            val x = points[i]; val y = points[i + 1]
            p.quadTo(lx, ly, (x + lx) / 2, (y + ly) / 2)
            lx = x; ly = y
            i += 2
        }
        p.lineTo(lx + 0.1f, ly + 0.1f)       // a tap still leaves a dot
        return p
    }
}

/**
 * The roll of paper. It lives in memory: art isn't saved, it just stays put while the
 * tablet is on, so a kid can come back to it. "New paper" clears it.
 */
object EaselRoll {
    const val MAX_STROKES = 4000
    val strokes = ArrayList<Stroke>()
    var scrollY = 0f
    var brush = Brush.CRAYON
    var color = Color.parseColor("#1E88E5")

    private fun previewFile(context: Context) = File(File(context.filesDir, "easel").apply { mkdirs() }, "preview.png")

    fun savePreview(context: Context, bmp: Bitmap?) {
        val f = previewFile(context)
        if (bmp == null) f.delete() else FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun preview(context: Context): Bitmap? = previewFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}

/** Paper grain and crayon wax textures, made once. */
object EaselTextures {
    val paperColor = Color.parseColor("#FBF9F4")
    private val crayonCache = HashMap<Int, Shader>()

    val paper: Shader by lazy {
        val n = 128
        val r = Random(7)
        val px = IntArray(n * n) {
            val d = r.nextInt(-7, 5)
            Color.rgb(
                (Color.red(paperColor) + d).coerceIn(0, 255),
                (Color.green(paperColor) + d).coerceIn(0, 255),
                (Color.blue(paperColor) + d).coerceIn(0, 255),
            )
        }
        BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** Waxy speckle: mostly solid color with little gaps, like crayon on paper. */
    fun crayon(color: Int): Shader = crayonCache.getOrPut(color) {
        val n = 96
        val r = Random(color)
        val px = IntArray(n * n) {
            val a = if (r.nextFloat() < 0.78f) (180 + r.nextInt(76)) else r.nextInt(60)
            Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }
        BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    fun paint(stroke: Stroke, dp: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = stroke.width
        when (stroke.brush) {
            Brush.CRAYON -> shader = crayon(stroke.color)
            Brush.MARKER -> { color = stroke.color; alpha = 235 }
            Brush.PAINT -> { color = stroke.color; alpha = 95; maskFilter = BlurMaskFilter(6 * dp, BlurMaskFilter.Blur.NORMAL) }
            Brush.PENCIL -> { color = stroke.color; alpha = 210 }
            Brush.ERASER -> shader = paper       // rubs back down to plain paper
        }
    }
}

/**
 * The easel: opens straight onto paper. One finger draws; two fingers scroll the roll.
 * With the S Pen, the pen draws and one finger scrolls (so a resting hand doesn't scribble).
 */
class EaselActivity : Activity() {

    private lateinit var easel: EaselView
    private val toolViews = mutableMapOf<Brush, TextView>()
    private val colorViews = mutableListOf<Pair<Int, StickyEditorActivity.DotView>>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val colors = listOf(
        "#E53935", "#FB8C00", "#FDD835", "#7CB342", "#2E7D32", "#26C6DA",
        "#1E88E5", "#283593", "#8E24AA", "#F06292", "#6D4C41", "#212121",
    ).map(Color::parseColor)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#ECEAE4")) }

        // Top: big color dots
        val colorRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(6)) }
        colors.forEach { c ->
            val dot = StickyEditorActivity.DotView(this, c, square = false, ringColor = Color.parseColor("#2C2C2A")).apply {
                setOnClickListener { pickColor(c) }
            }
            colorViews += c to dot
            colorRow.addView(dot, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(4) })
        }
        root.addView(scrollable(colorRow))

        easel = EaselView(this)
        root.addView(easel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom: tools on the left, actions on the right
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(8)) }
        Brush.entries.forEach { b ->
            val v = tile("${b.emoji}\n${b.label}") { pickBrush(b) }
            toolViews[b] = v
            bar.addView(v, LinearLayout.LayoutParams(dp(78), dp(72)).apply { marginEnd = dp(6) })
        }
        bar.addView(View(this), LinearLayout.LayoutParams(dp(24), 1))
        bar.addView(tile("↩️\nUndo") { easel.undo() }, LinearLayout.LayoutParams(dp(78), dp(72)).apply { marginEnd = dp(6) })
        bar.addView(tile("⬇️\nMore paper") { easel.scrollBy(easel.height * 0.85f) }, LinearLayout.LayoutParams(dp(96), dp(72)).apply { marginEnd = dp(6) })
        bar.addView(tile("🧻\nNew paper") { easel.newPaper() }, LinearLayout.LayoutParams(dp(96), dp(72)).apply { marginEnd = dp(6) })
        bar.addView(tile("✓\nDone", Color.parseColor("#1D9E75"), Color.WHITE) { finish() }, LinearLayout.LayoutParams(dp(82), dp(72)))
        root.addView(scrollable(bar))

        setContentView(root)
        pickBrush(EaselRoll.brush)
        pickColor(EaselRoll.color)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun scrollable(content: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun tile(text: String, bg: Int = Color.WHITE, fg: Int = Color.parseColor("#2C2C2A"), onClick: () -> Unit) =
        TextView(this).apply {
            this.text = text; gravity = Gravity.CENTER; textSize = 15f; setTextColor(fg)
            setLineSpacing(0f, 1.1f)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(bg) }
            setOnClickListener { onClick() }
        }

    private fun pickBrush(b: Brush) {
        EaselRoll.brush = b
        toolViews.forEach { (brush, v) ->
            (v.background as GradientDrawable).apply {
                setColor(if (brush == b) Color.parseColor("#FFE9A8") else Color.WHITE)
                setStroke(if (brush == b) dp(3) else 0, Color.parseColor("#E0A800"))
            }
        }
    }

    private fun pickColor(c: Int) {
        EaselRoll.color = c
        colorViews.forEach { (color, v) -> v.selectedDot = color == c }
        if (EaselRoll.brush == Brush.ERASER) pickBrush(Brush.CRAYON)   // picking a color means "draw again"
    }

    override fun onPause() {
        super.onPause()
        // The widget shows whatever was last on the easel.
        EaselRoll.savePreview(this, easel.snapshot())
        WidgetUpdater.request(this, WidgetKind.EASEL)
    }

    /** The paper itself. Keeps a cached picture of the visible part of the roll. */
    class EaselView(context: Context) : View(context) {
        private val dp = resources.displayMetrics.density
        private var cache: Bitmap? = null
        private var current: Stroke? = null
        private var currentPaint: Paint? = null
        private var scrolling = false
        private var lastScrollY = 0f
        private var penSeen = false
        private val paperPaint = Paint().apply { shader = EaselTextures.paper }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            cache?.recycle()
            cache = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
            redraw()
        }

        /** Re-renders the visible window of the roll into the cache. */
        private fun redraw() {
            val bmp = cache ?: return
            val c = Canvas(bmp)
            val top = EaselRoll.scrollY
            val bottom = top + height
            c.save()
            c.translate(0f, -top)
            c.drawRect(0f, top, width.toFloat(), bottom, paperPaint)
            for (s in EaselRoll.strokes) {
                val pad = s.width
                if (s.bounds.bottom + pad < top || s.bounds.top - pad > bottom) continue
                c.drawPath(s.path(), EaselTextures.paint(s, dp))
            }
            c.restore()
            invalidate()
        }

        private fun commit(s: Stroke) {
            EaselRoll.strokes += s
            if (EaselRoll.strokes.size > EaselRoll.MAX_STROKES) EaselRoll.strokes.removeAt(0)
            cache?.let { bmp ->
                val c = Canvas(bmp)
                c.translate(0f, -EaselRoll.scrollY)
                c.drawPath(s.path(), EaselTextures.paint(s, dp))
            }
        }

        fun scrollBy(dy: Float) {
            EaselRoll.scrollY = max(0f, EaselRoll.scrollY + dy)
            redraw()
        }

        fun undo() {
            if (EaselRoll.strokes.isNotEmpty()) EaselRoll.strokes.removeAt(EaselRoll.strokes.size - 1)
            redraw()
        }

        fun newPaper() {
            EaselRoll.strokes.clear()
            EaselRoll.scrollY = 0f
            redraw()
        }

        /** What's on screen now, for the widget, or null if this stretch of paper is blank. */
        fun snapshot(): Bitmap? {
            val bmp = cache ?: return null
            val top = EaselRoll.scrollY
            val visible = EaselRoll.strokes.any { it.bounds.bottom >= top && it.bounds.top <= top + height && it.brush != Brush.ERASER }
            if (!visible) return null
            val scale = min(1f, 1200f / max(bmp.width, bmp.height))
            return Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * scale).toInt()), max(1, (bmp.height * scale).toInt()), true)
        }

        private fun startStroke(e: MotionEvent, eraser: Boolean) {
            val brush = if (eraser) Brush.ERASER else EaselRoll.brush
            val s = Stroke(brush, EaselRoll.color, brush.widthDp * dp)
            s.add(e.x, e.y + EaselRoll.scrollY)
            current = s
            currentPaint = EaselTextures.paint(s, dp)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val tool = e.getToolType(e.actionIndex)
            val pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
            if (pen) penSeen = true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!pen && penSeen) {
                        scrolling = true; lastScrollY = e.y          // pen user: finger scrolls
                    } else {
                        scrolling = false
                        startStroke(e, tool == MotionEvent.TOOL_TYPE_ERASER)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Second finger: this is a scroll, not a drawing. Drop the half-started line.
                    current = null
                    scrolling = true
                    lastScrollY = avgY(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scrolling) {
                        val y = avgY(e)
                        val dy = lastScrollY - y
                        lastScrollY = y
                        if (abs(dy) > 0.5f) scrollBy(dy)
                    } else current?.let { s ->
                        for (h in 0 until e.historySize) s.add(e.getHistoricalX(h), e.getHistoricalY(h) + EaselRoll.scrollY)
                        s.add(e.x, e.y + EaselRoll.scrollY)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Keep scrolling with the remaining finger without a jump.
                    val remaining = (0 until e.pointerCount).filter { it != e.actionIndex }
                    if (remaining.isNotEmpty()) lastScrollY = remaining.map { e.getY(it) }.average().toFloat()
                }
                MotionEvent.ACTION_UP -> {
                    current?.let { commit(it) }
                    current = null
                    scrolling = false
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { current = null; scrolling = false; invalidate() }
            }
            return true
        }

        private fun avgY(e: MotionEvent): Float {
            var sum = 0f
            for (i in 0 until e.pointerCount) sum += e.getY(i)
            return sum / e.pointerCount
        }

        override fun onDraw(canvas: Canvas) {
            cache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            current?.let { s ->
                canvas.save()
                canvas.translate(0f, -EaselRoll.scrollY)
                canvas.drawPath(s.path(), currentPaint!!)
                canvas.restore()
            }
        }
    }
}

/** The easel widget: the last drawing on a sheet of paper, or a few crayons waiting. */
object EaselRenderer {

    fun render(w: Int, h: Int, dp: Float, preview: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val m = 6 * dp
        val sheet = RectF(m, m, w - m, h - m)
        val corner = min(18 * dp, min(w, h) * 0.08f)
        c.drawRoundRect(sheet, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = EaselTextures.paperColor
            setShadowLayer(5 * dp, 0f, 2 * dp, Color.argb(80, 0, 0, 0))
        })
        c.drawRoundRect(sheet, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = EaselTextures.paper })

        if (preview != null) {
            // Fill the sheet (center crop) so the drawing reads big.
            val scale = max(sheet.width() / preview.width, sheet.height() / preview.height)
            val sw = sheet.width() / scale
            val sh = sheet.height() / scale
            val sx = (preview.width - sw) / 2
            val sy = (preview.height - sh) / 2
            c.save()
            val clip = Path().apply { addRoundRect(sheet, corner, corner, Path.Direction.CW) }
            c.clipPath(clip)
            c.drawBitmap(preview, Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), sheet,
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()
        } else {
            drawCrayons(c, sheet)
        }
        drawPaletteBadge(c, sheet, dp)
        return bmp
    }

    private fun drawCrayons(c: Canvas, sheet: RectF) {
        val s = min(sheet.width(), sheet.height())
        val len = s * 0.5f
        val thick = s * 0.085f
        listOf("#E53935" to -22f, "#FDD835" to 0f, "#1E88E5" to 22f).forEachIndexed { i, (hex, angle) ->
            val color = Color.parseColor(hex)
            c.save()
            c.translate(sheet.centerX() + (i - 1) * thick * 1.1f, sheet.centerY())
            c.rotate(angle + 35f)
            val body = RectF(-thick / 2, -len / 2 + thick, thick / 2, len / 2)
            c.drawRoundRect(body, thick * 0.2f, thick * 0.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            // paper wrapper band
            c.drawRect(-thick / 2, -len * 0.05f, thick / 2, len * 0.22f, Paint().apply { this.color = Motif.blend(color, Color.BLACK, 0.25f) })
            // pointed tip
            val tip = Path().apply {
                moveTo(-thick / 2, -len / 2 + thick); lineTo(0f, -len / 2 - thick * 0.2f); lineTo(thick / 2, -len / 2 + thick); close()
            }
            c.drawPath(tip, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Motif.blend(color, Color.WHITE, 0.15f) })
            c.restore()
        }
    }

    /** A little paint palette in the corner, like an "open the easel" button. */
    private fun drawPaletteBadge(c: Canvas, sheet: RectF, dp: Float) {
        val r = min(26 * dp, min(sheet.width(), sheet.height()) * 0.14f)
        val cx = sheet.right - r - 8 * dp
        val cy = sheet.bottom - r - 8 * dp
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8E24AA")
            setShadowLayer(r * 0.2f, 0f, r * 0.08f, Color.argb(90, 0, 0, 0))
        })
        c.drawCircle(cx, cy, r * 0.62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val dots = listOf("#E53935", "#FDD835", "#1E88E5", "#7CB342")
        dots.forEachIndexed { i, hex ->
            val a = Math.toRadians(200.0 + i * 50.0)
            c.drawCircle(cx + (r * 0.34f * Math.cos(a)).toFloat(), cy + (r * 0.34f * Math.sin(a)).toFloat(), r * 0.12f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(hex) })
        }
    }
}
