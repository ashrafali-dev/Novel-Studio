package com.ashraf.novelstudio

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.max
import kotlin.math.min

/** Shared colours and labels for the three reader modes. */
object ModeUi {
    val names = arrayOf("Novel", "Scan", "Live")
    private val starts = intArrayOf(UiTheme.accentA, 0xFF4ADEB0.toInt(), 0xFFFF8FB5.toInt())
    private val ends = intArrayOf(UiTheme.accentB, 0xFF1D9E75.toInt(), 0xFFD4537E.toInt())
    fun start(index: Int) = starts[index.coerceIn(0, 2)]
    fun end(index: Int) = ends[index.coerceIn(0, 2)]
    fun mid(index: Int) = lerp(start(index), end(index), 0.5f)
    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    private fun lerp(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }
}

private class ModeGlyphPainter {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    fun draw(canvas: Canvas, mode: Int, cx: Float, cy: Float, size: Float, color: Int) {
        canvas.save()
        val s = size / 24f
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(s, s)
        line.color = color
        line.strokeWidth = 1.9f
        fill.color = color
        when (mode) {
            0 -> {
                path.reset()
                path.moveTo(12f, 6f); path.cubicTo(8f, 4f, 4.5f, 4.5f, 3.5f, 5f)
                path.lineTo(3.5f, 18.5f); path.cubicTo(7f, 17f, 10f, 18f, 12f, 20f)
                path.cubicTo(14f, 18f, 17f, 17f, 20.5f, 18.5f); path.lineTo(20.5f, 5f)
                path.cubicTo(17f, 4.5f, 14f, 4f, 12f, 6f)
                canvas.drawPath(path, line); canvas.drawLine(12f, 6f, 12f, 20f, line)
            }
            1 -> {
                path.reset()
                path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                path.moveTo(4f, 15f); path.lineTo(4f, 20f); path.lineTo(9f, 20f)
                path.moveTo(15f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 15f)
                canvas.drawPath(path, line); canvas.drawLine(4f, 12f, 20f, 12f, line)
            }
            else -> {
                canvas.drawCircle(12f, 12f, 2.2f, fill)
                for (r in floatArrayOf(5.5f, 9f)) {
                    rect.set(12f-r, 12f-r, 12f+r, 12f+r)
                    canvas.drawArc(rect, -42f, 84f, false, line)
                    canvas.drawArc(rect, 138f, 84f, false, line)
                }
            }
        }
        canvas.restore()
    }
}

/** Three-mode icon capsule with an animated coloured thumb. */
class ModeCapsule(ctx: Context) : View(ctx) {
    var onSelect: ((Int) -> Unit)? = null
    private val dens = ctx.resources.displayMetrics.density
    private val glyph = ModeGlyphPainter()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x16FFFFFF }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dens; color = UiTheme.strokeSoft }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var selected = 0
    private var pos = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        isHapticFeedbackEnabled = false
        contentDescription = "Reading mode: Novel"
    }

    fun setSelectedIndex(index: Int, animate: Boolean): Boolean {
        val next = index.coerceIn(0, 2)
        if (next == selected) return false
        selected = next
        contentDescription = "Reading mode: ${ModeUi.names[next]}"
        animator?.cancel()
        if (animate && isAttachedToWindow && width > 0) {
            animator = ValueAnimator.ofFloat(pos, next.toFloat()).apply {
                duration = 360L
                interpolator = OvershootInterpolator(1.25f)
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            pos = next.toFloat()
            invalidate()
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = min(View.MeasureSpec.getSize(widthMeasureSpec), (140f * dens).toInt())
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize((38f * dens).toInt(), heightMeasureSpec))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val slot = ((event.x / max(1f, width.toFloat())) * 3f).toInt().coerceIn(0, 2)
                performClick()
                if (slot != selected) onSelect?.invoke(slot)
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 3f * dens
        rect.set(dens, dens, w - dens, h - dens)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, track)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, stroke)
        val sw = (w - pad * 2f) / 3f
        val left = pad + pos * sw
        rect.set(left, pad, left + sw, h - pad)
        thumb.shader = LinearGradient(left, pad, left + sw, h - pad, ModeUi.start(selected), ModeUi.end(selected), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, (h - pad * 2f) / 2f, (h - pad * 2f) / 2f, thumb)
        thumb.shader = null
        for (i in 0..2) glyph.draw(canvas, i, pad + (i + 0.5f) * sw, h / 2f, 18f * dens, if (i == selected) Color.WHITE else UiTheme.dim)
    }
}

/** Small non-interactive centre pulse shown when the reader mode changes. */
class ModeSwitchOverlay(ctx: Context) : View(ctx) {
    private val dens = ctx.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 14f * dens
    }
    private var mode = 0
    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        visibility = View.GONE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun play(index: Int) {
        mode = index.coerceIn(0, 2)
        animator?.cancel()
        progress = 0f
        visibility = View.VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560L
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { visibility = View.GONE }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val color = ModeUi.mid(mode)
        val eased = progress * progress * (3f - 2f * progress)
        val radius = (12f + eased * 74f) * dens
        ring.color = ModeUi.withAlpha(color, ((1f - progress) * 190f).toInt())
        ring.strokeWidth = (3f - progress * 1.5f) * dens
        canvas.drawCircle(cx, cy, radius, ring)
        fill.color = ModeUi.withAlpha(color, ((1f - progress) * 32f).toInt())
        canvas.drawCircle(cx, cy, max(0f, radius - 3f * dens), fill)
        label.alpha = ((1f - progress) * 255f).toInt().coerceIn(0, 255)
        canvas.drawText(ModeUi.names[mode].uppercase(), cx, cy + 5f * dens, label)
        label.alpha = 255
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}
