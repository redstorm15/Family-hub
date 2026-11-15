package com.foster.kitchencalendar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Simple, original vector illustrations drawn with Canvas so the app ships with
 * no image assets. Each draws centred on (0,0) with a nominal radius [s].
 */
enum class Motif {
    SNOWFLAKE, HEART, CLOVER, FLOWER, SUN, STAR, FIREWORK, APPLE, PUMPKIN,
    BAT, GHOST, LEAF, TREE, GIFT, EGG, RAINDROP, WAVE, MOON;

    fun draw(canvas: Canvas, cx: Float, cy: Float, s: Float, rotationDeg: Float, fill: Int, accent: Int, bg: Int) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotationDeg)
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill }
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = fill; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        when (this) {
            SNOWFLAKE -> {
                st.strokeWidth = s * 0.11f
                for (i in 0 until 6) {
                    val a = (i * 60.0) * PI / 180
                    val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                    canvas.drawLine(0f, 0f, dx * s, dy * s, st)
                    val bx = dx * s * 0.55f; val by = dy * s * 0.55f
                    for (sign in listOf(-1, 1)) {
                        val b = a + sign * PI / 4
                        canvas.drawLine(bx, by, bx + cos(b).toFloat() * s * 0.3f, by + sin(b).toFloat() * s * 0.3f, st)
                    }
                }
            }
            HEART -> canvas.drawPath(heartPath(s), f)
            CLOVER -> {
                st.color = fill; st.strokeWidth = s * 0.1f
                val stem = Path().apply { moveTo(0f, 0f); quadTo(s * 0.1f, s * 0.6f, s * 0.35f, s * 0.95f) }
                canvas.drawPath(stem, st)
                for (deg in listOf(-90.0, 30.0, 150.0)) {
                    val a = deg * PI / 180
                    canvas.drawCircle(cos(a).toFloat() * s * 0.36f, sin(a).toFloat() * s * 0.36f, s * 0.38f, f)
                }
            }
            FLOWER -> {
                for (i in 0 until 5) {
                    val a = (i * 72.0 - 90) * PI / 180
                    canvas.drawCircle(cos(a).toFloat() * s * 0.48f, sin(a).toFloat() * s * 0.48f, s * 0.38f, f)
                }
                f.color = accent
                canvas.drawCircle(0f, 0f, s * 0.3f, f)
            }
            SUN -> {
                canvas.drawCircle(0f, 0f, s * 0.5f, f)
                st.strokeWidth = s * 0.12f
                for (i in 0 until 8) {
                    val a = (i * 45.0) * PI / 180
                    val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                    canvas.drawLine(dx * s * 0.67f, dy * s * 0.67f, dx * s * 0.95f, dy * s * 0.95f, st)
                }
            }
            STAR -> canvas.drawPath(starPath(s, s * 0.45f), f)
            FIREWORK -> {
                st.strokeWidth = s * 0.07f
                for (i in 0 until 12) {
                    val a = (i * 30.0) * PI / 180
                    val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                    canvas.drawLine(dx * s * 0.25f, dy * s * 0.25f, dx * s * 0.8f, dy * s * 0.8f, st)
                    canvas.drawCircle(dx * s * 0.95f, dy * s * 0.95f, s * 0.08f, f)
                }
            }
            APPLE -> {
                canvas.drawCircle(-s * 0.22f, s * 0.08f, s * 0.55f, f)
                canvas.drawCircle(s * 0.22f, s * 0.08f, s * 0.55f, f)
                st.color = accent; st.strokeWidth = s * 0.1f
                canvas.drawLine(0f, -s * 0.4f, s * 0.08f, -s * 0.85f, st)
                f.color = Color.parseColor("#639922")
                canvas.drawOval(RectF(s * 0.1f, -s * 0.85f, s * 0.55f, -s * 0.6f), f)
            }
            PUMPKIN -> {
                val shade = blend(fill, Color.BLACK, 0.18f)
                f.color = shade
                canvas.drawOval(RectF(-s * 0.9f, -s * 0.55f, -s * 0.05f, s * 0.75f), f)
                canvas.drawOval(RectF(s * 0.05f, -s * 0.55f, s * 0.9f, s * 0.75f), f)
                f.color = fill
                canvas.drawOval(RectF(-s * 0.55f, -s * 0.6f, s * 0.55f, s * 0.8f), f)
                f.color = accent
                canvas.drawRoundRect(RectF(-s * 0.09f, -s * 0.95f, s * 0.09f, -s * 0.5f), s * 0.05f, s * 0.05f, f)
            }
            BAT -> {
                val p = Path()
                val left = listOf(
                    -0.1f to -0.12f, -0.5f to -0.45f, -1.0f to -0.25f, -0.82f to 0.05f,
                    -0.62f to -0.02f, -0.48f to 0.18f, -0.3f to 0.02f, -0.1f to 0.18f,
                )
                p.moveTo(left[0].first * s, left[0].second * s)
                for ((x, y) in left.drop(1)) p.lineTo(x * s, y * s)
                for ((x, y) in left.reversed()) p.lineTo(-x * s, y * s)
                p.close()
                canvas.drawPath(p, f)
                canvas.drawOval(RectF(-s * 0.17f, -s * 0.3f, s * 0.17f, s * 0.28f), f)
                val ears = Path().apply {
                    moveTo(-s * 0.15f, -s * 0.2f); lineTo(-s * 0.12f, -s * 0.45f); lineTo(-s * 0.02f, -s * 0.25f)
                    moveTo(s * 0.15f, -s * 0.2f); lineTo(s * 0.12f, -s * 0.45f); lineTo(s * 0.02f, -s * 0.25f)
                }
                canvas.drawPath(ears, f)
            }
            GHOST -> {
                val p = Path().apply {
                    moveTo(-s * 0.6f, -s * 0.2f)
                    arcTo(RectF(-s * 0.6f, -s * 0.8f, s * 0.6f, s * 0.4f), 180f, 180f)
                    lineTo(s * 0.6f, s * 0.75f)
                    quadTo(s * 0.4f, s * 0.55f, s * 0.2f, s * 0.75f)
                    quadTo(0f, s * 0.95f, -s * 0.2f, s * 0.75f)
                    quadTo(-s * 0.4f, s * 0.55f, -s * 0.6f, s * 0.75f)
                    close()
                }
                canvas.drawPath(p, f)
                f.color = bg
                canvas.drawOval(RectF(-s * 0.3f, -s * 0.35f, -s * 0.1f, -s * 0.05f), f)
                canvas.drawOval(RectF(s * 0.1f, -s * 0.35f, s * 0.3f, -s * 0.05f), f)
            }
            LEAF -> {
                val p = Path().apply {
                    moveTo(0f, -s); quadTo(s * 0.8f, 0f, 0f, s * 0.8f); quadTo(-s * 0.8f, 0f, 0f, -s); close()
                }
                canvas.drawPath(p, f)
                st.color = blend(fill, Color.BLACK, 0.3f); st.strokeWidth = s * 0.07f
                canvas.drawLine(0f, -s * 0.75f, 0f, s * 1.05f, st)
            }
            TREE -> {
                f.color = Color.parseColor("#97C459")
                for ((top, half, bottom) in listOf(
                    Triple(-1.0f, 0.45f, -0.35f), Triple(-0.65f, 0.65f, 0.1f), Triple(-0.25f, 0.85f, 0.6f),
                )) {
                    val p = Path().apply {
                        moveTo(0f, top * s); lineTo(half * s, bottom * s); lineTo(-half * s, bottom * s); close()
                    }
                    canvas.drawPath(p, f)
                }
                f.color = Color.parseColor("#633806")
                canvas.drawRect(-s * 0.12f, s * 0.6f, s * 0.12f, s * 0.9f, f)
                f.color = accent
                canvas.drawPath(starPath(s * 0.22f, s * 0.1f).apply { offset(0f, -s) }, f)
            }
            GIFT -> {
                val ribbon = if (accent == fill) Color.WHITE else accent
                canvas.drawRect(-s * 0.6f, -s * 0.35f, s * 0.6f, s * 0.75f, f)
                canvas.drawRect(-s * 0.72f, -s * 0.55f, s * 0.72f, -s * 0.3f, f)
                f.color = ribbon
                canvas.drawRect(-s * 0.09f, -s * 0.55f, s * 0.09f, s * 0.75f, f)
                st.color = ribbon; st.strokeWidth = s * 0.1f
                canvas.drawOval(RectF(-s * 0.4f, -s * 0.85f, -s * 0.02f, -s * 0.55f), st)
                canvas.drawOval(RectF(s * 0.02f, -s * 0.85f, s * 0.4f, -s * 0.55f), st)
            }
            EGG -> {
                val egg = Path().apply { addOval(RectF(-s * 0.55f, -s * 0.78f, s * 0.55f, s * 0.72f), Path.Direction.CW) }
                canvas.drawPath(egg, f)
                canvas.save()
                canvas.clipPath(egg)
                st.color = accent; st.strokeWidth = s * 0.12f
                val zig = Path().apply {
                    moveTo(-s * 0.7f, 0f)
                    var x = -s * 0.7f; var up = true
                    while (x < s * 0.7f) { x += s * 0.2f; lineTo(x, if (up) -s * 0.15f else 0f); up = !up }
                }
                canvas.drawPath(zig, st)
                canvas.restore()
            }
            RAINDROP -> {
                val p = Path().apply {
                    moveTo(0f, -s)
                    quadTo(s * 0.6f, -s * 0.1f, s * 0.55f, s * 0.4f)
                    arcTo(RectF(-s * 0.55f, -s * 0.15f, s * 0.55f, s * 0.95f), 0f, 180f)
                    quadTo(-s * 0.6f, -s * 0.1f, 0f, -s)
                    close()
                }
                canvas.drawPath(p, f)
            }
            WAVE -> {
                st.strokeWidth = s * 0.14f
                for (row in 0 until 2) {
                    val y0 = (row - 0.5f) * s * 0.45f
                    val p = Path().apply {
                        moveTo(-s, y0)
                        var x = -s
                        while (x < s) {
                            quadTo(x + s * 0.25f, y0 - s * 0.25f, x + s * 0.5f, y0)
                            x += s * 0.5f
                            quadTo(x + s * 0.25f, y0 + s * 0.25f, x + s * 0.5f, y0)
                            x += s * 0.5f
                        }
                    }
                    canvas.drawPath(p, st)
                }
            }
            MOON -> {
                f.color = Color.parseColor("#FAC775")
                canvas.drawCircle(0f, 0f, s * 0.7f, f)
                f.color = bg
                canvas.drawCircle(s * 0.32f, -s * 0.18f, s * 0.6f, f)
            }
        }
        canvas.restore()
    }

    companion object {
        fun heartPath(s: Float) = Path().apply {
            moveTo(0f, s * 0.85f)
            cubicTo(-s * 1.1f, s * 0.1f, -s * 0.6f, -s * 0.9f, 0f, -s * 0.35f)
            cubicTo(s * 0.6f, -s * 0.9f, s * 1.1f, s * 0.1f, 0f, s * 0.85f)
            close()
        }

        fun starPath(outer: Float, inner: Float) = Path().apply {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) outer else inner
                val a = (i * 36.0 - 90) * PI / 180
                val x = cos(a).toFloat() * r; val y = sin(a).toFloat() * r
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }

        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
            return Color.argb(
                ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)),
                ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)),
            )
        }
    }
}
