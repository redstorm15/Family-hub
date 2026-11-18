package com.foster.kitchencalendar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.min

/**
 * The kid-friendly note editor: a big note to draw on with a finger or S Pen,
 * paper and pen colors, Undo, Erase, Aa (type instead), Throw away, and Stick it.
 * Opens on top of the home screen when the sticky board's "+" or a note is tapped.
 */
class StickyEditorActivity : Activity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"     // -1 = new note
    }

    private var noteId = -1
    private var colorIndex = 0
    private lateinit var noteFrame: FrameLayout
    private lateinit var drawView: DrawView
    private lateinit var textBox: EditText
    private val dots = mutableListOf<DotView>()
    private val penDots = mutableListOf<DotView>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = intent.getIntExtra(EXTRA_NOTE_ID, -1)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val existing = noteId >= 0 && noteId in StickyStore.ids(this)
        if (!existing) noteId = -1
        // A new note gets the next paper color, so the board ends up colorful on its own.
        val note = if (existing) StickyStore.load(this, noteId)
        else StickyData(-1, StickyStore.ids(this).size % Paper.colors.size, "", null)
        colorIndex = note.colorIndex

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnClickListener { finish() }      // tap outside = cancel
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true                   // don't let taps fall through to "cancel"
        }

        val metrics = resources.displayMetrics
        val side = (min(metrics.widthPixels, metrics.heightPixels) * 0.78f).toInt()

        noteFrame = FrameLayout(this)
        drawView = DrawView(this).apply { base = note.drawing }
        textBox = EditText(this).apply {
            setText(note.text)
            hint = "Type a note"
            textSize = 34f
            typeface = Paper.handwriting
            gravity = Gravity.CENTER
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            visibility = if (note.text.isBlank()) View.GONE else View.VISIBLE
            isFocusable = false
        }
        noteFrame.addView(textBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        noteFrame.addView(drawView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        paintNote()
        column.addView(noteFrame, LinearLayout.LayoutParams(side, side).apply { bottomMargin = dp(16) })

        // Row 1: paper colors, then pen colors
        val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        Paper.colors.forEachIndexed { i, c ->
            val d = DotView(this, c, square = true).apply {
                selectedDot = i == colorIndex
                setOnClickListener { colorIndex = i; dots.forEachIndexed { j, v -> v.selectedDot = j == i }; paintNote() }
            }
            dots += d
            row1.addView(d, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(10) })
        }
        row1.addView(View(this), LinearLayout.LayoutParams(dp(18), 1))
        listOf("#2C2C2A", "#185FA5", "#D85A30").map(Color::parseColor).forEachIndexed { i, c ->
            val d = DotView(this, c, square = false).apply {
                selectedDot = i == 0
                setOnClickListener { drawView.penColor = c; penDots.forEachIndexed { j, v -> v.selectedDot = j == i } }
            }
            penDots += d
            row1.addView(d, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) })
        }
        column.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })

        // Row 2: actions
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, bg: Int, fg: Int = Color.parseColor("#2C2C2A"), onClick: () -> Unit) =
            Button(this).apply {
                text = label; textSize = 19f; isAllCaps = false; setTextColor(fg)
                background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(bg) }
                setPadding(dp(18), dp(10), dp(18), dp(10))
                setOnClickListener { onClick() }
            }.also { row2.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(60)).apply { marginEnd = dp(12) }) }

        action("↩  Undo", Color.WHITE) { drawView.undo() }
        action("🧽  Erase", Color.WHITE) {
            drawView.clear(); textBox.setText(""); textBox.visibility = View.GONE; hideKeyboard()
        }
        action("Aa", Color.WHITE) { startTyping() }
        if (existing) action("🗑  Throw away", Color.parseColor("#F7C1C1")) { throwAway() }
        action("✓  Stick it!", Color.parseColor("#1D9E75"), Color.WHITE) { save() }
        column.addView(row2)

        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
    }

    private fun paintNote() {
        noteFrame.background = GradientDrawable().apply { setColor(Paper.color(colorIndex)); cornerRadius = dp(10).toFloat() }
        noteFrame.elevation = dp(10).toFloat()
    }

    private fun startTyping() {
        textBox.visibility = View.VISIBLE
        textBox.isFocusable = true
        textBox.isFocusableInTouchMode = true
        drawView.enabledDrawing = false
        textBox.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(textBox, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(textBox.windowToken, 0)
        drawView.enabledDrawing = true
    }

    private fun save() {
        hideKeyboard()
        val text = textBox.text.toString()
        val drawing = drawView.export()
        when {
            text.isNotBlank() || drawing != null -> StickyStore.save(this, noteId, colorIndex, text, drawing)
            noteId >= 0 -> StickyStore.delete(this, noteId)    // wiped clean = gone
        }
        WidgetUpdater.request(this, WidgetKind.STICKY)
        finish()
    }

    private fun throwAway() {
        hideKeyboard()
        StickyStore.delete(this, noteId)
        WidgetUpdater.request(this, WidgetKind.STICKY)
        finish()
    }

    /** A round (pen) or square (paper) color choice with a ring when selected. */
    class DotView(context: Context, private val color: Int, private val square: Boolean, ringColor: Int = Color.WHITE) : View(context) {
        var selectedDot = false
            set(v) { field = v; invalidate() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ringColor }

        override fun onDraw(canvas: Canvas) {
            val s = min(width, height).toFloat()
            val r = s * 0.36f
            fill.color = color
            if (square) canvas.drawRoundRect(RectF(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r), r * 0.25f, r * 0.25f, fill)
            else canvas.drawCircle(width / 2f, height / 2f, r, fill)
            if (selectedDot) {
                ring.strokeWidth = s * 0.07f
                val rr = s * 0.46f
                if (square) canvas.drawRoundRect(RectF(width / 2f - rr, height / 2f - rr, width / 2f + rr, height / 2f + rr), rr * 0.25f, rr * 0.25f, ring)
                else canvas.drawCircle(width / 2f, height / 2f, rr, ring)
            }
        }
    }

    /** Finger / S Pen drawing surface. Keeps the previous drawing underneath new strokes. */
    class DrawView(context: Context) : View(context) {
        var base: Bitmap? = null
        var penColor = Color.parseColor("#2C2C2A")
        var enabledDrawing = true
        private val strokes = mutableListOf<Pair<Path, Paint>>()
        private var current: Path? = null
        private var currentPaint: Paint? = null
        private var lastX = 0f
        private var lastY = 0f
        private val width0 get() = maxOf(resources.displayMetrics.density * 6f, width * 0.014f)

        private fun newPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = width0; color = penColor
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!enabledDrawing) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    current = Path().apply { moveTo(e.x, e.y) }
                    currentPaint = newPaint()
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    current?.quadTo(lastX, lastY, (e.x + lastX) / 2, (e.y + lastY) / 2)
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    current?.let { p ->
                        p.lineTo(e.x + 0.1f, e.y + 0.1f)     // a tap still leaves a dot
                        strokes += p to currentPaint!!
                    }
                    current = null
                }
                MotionEvent.ACTION_CANCEL -> current = null
            }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            base?.let { b ->
                val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
                val dw = b.width * scale; val dh = b.height * scale
                canvas.drawBitmap(b, Rect(0, 0, b.width, b.height),
                    RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2), null)
            }
            strokes.forEach { (p, paint) -> canvas.drawPath(p, paint) }
            current?.let { canvas.drawPath(it, currentPaint!!) }
        }

        fun undo() {
            if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1) else base = null
            invalidate()
        }

        fun clear() { strokes.clear(); base = null; invalidate() }

        /** The full drawing on a transparent background, or null if there's nothing drawn. */
        fun export(): Bitmap? {
            if (strokes.isEmpty() && base == null) return null
            if (width == 0 || height == 0) return base
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            base?.let { b ->
                val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
                val dw = b.width * scale; val dh = b.height * scale
                c.drawBitmap(b, Rect(0, 0, b.width, b.height),
                    RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2), null)
            }
            strokes.forEach { (p, paint) -> c.drawPath(p, paint) }
            return out
        }
    }
}
