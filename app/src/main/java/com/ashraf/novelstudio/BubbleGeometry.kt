package com.ashraf.novelstudio

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The usable *inside* of a speech bubble, from the balloon's real outline.
 *
 * Why this exists: the old writer fitted the translation into the OCR text rectangle with a fixed
 * 0.88 x 0.86 inset (WebModeWriter.fit). An OCR box is the union of letters, not the balloon, so the
 * same inset was too small for an oval balloon and far too large for a bubble with a tail - text then
 * sat outside the white area or left a wide empty margin. The fix is the one manga-translator uses:
 * rasterise the outline, then take the largest axis-aligned rectangle that is entirely *inside* the
 * shape (histogram / monotonic-stack algorithm). That rectangle is the only area where text is
 * guaranteed to stay on the bubble, whatever the translation length is.
 *
 * Everything here is pure Kotlin (no Android types) so it runs in a unit test.
 */
internal object BubbleGeometry {
    private const val MASK_MAX = 96
    private const val MIN_RELATIVE = 0.18f

    /** Normalized balloon outline (x0,y0,x1,y1,...) plus its bounds, all in 0..1 image fractions. */
    class Frame(val poly: FloatArray, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    fun frame(poly: FloatArray?): Frame? {
        if (poly == null || poly.size < 6 || poly.size % 2 != 0) return null
        var l = 1f; var t = 1f; var r = 0f; var b = 0f
        for (i in poly.indices step 2) {
            if (!poly[i].isFinite() || !poly[i + 1].isFinite()) return null
            l = min(l, poly[i]); r = max(r, poly[i])
            t = min(t, poly[i + 1]); b = max(b, poly[i + 1])
        }
        if (r - l < 0.002f || b - t < 0.002f) return null
        return Frame(poly, l, t, r, b)
    }

    /**
     * Largest rectangle fully inside [frame.poly], expressed inside [box] (the same normalized space).
     * Returns `null` when the outline covers too little of the box to be trusted (then the caller keeps
     * its rectangle fit) or when the safe area would be a sliver.
     */
    fun safeTextRect(
        frame: Frame,
        boxLeft: Float, boxTop: Float, boxRight: Float, boxBottom: Float,
        shrink: Float = 0.025f
    ): FloatArray? {
        val fw = frame.width
        val fh = frame.height
        if (fw <= 0f || fh <= 0f) return null
        val mw = (fw * 1000f).toInt().coerceIn(16, MASK_MAX)
        val mh = (fh * 1000f).toInt().coerceIn(16, MASK_MAX)
        if (mw < 8 || mh < 8) return null

        // Rasterise the polygon into a coarse grid (even-odd fill rule, the same rule Android's
        // Path.FillType.EVEN_ODD uses for the sampled contours).
        val filled = rasterize(frame.poly, mw, mh)
        val rect = largestInsideRect(filled, mw, mh) ?: return null
        val (rl, rt, rr, rb) = rect

        // Back to fractions of the image, then to the same coordinate space as the caller's [box].
        val x0 = frame.left + (rl.toFloat() / mw) * fw
        val y0 = frame.top + (rt.toFloat() / mh) * fh
        val x1 = frame.left + (rr.toFloat() / mw) * fw
        val y1 = frame.top + (rb.toFloat() / mh) * fh
        val padX = min(shrink, (x1 - x0) * 0.06f)
        val padY = min(shrink, (y1 - y0) * 0.06f)
        val L = max(x0 + padX, boxLeft)
        val T = max(y0 + padY, boxTop)
        val R = min(x1 - padX, boxRight)
        val B = min(y1 - padY, boxBottom)
        if (R - L <= 0f || B - T <= 0f) return null
        // Refuse a "safe area" that is only a fragment of the box: in that case the detector polygon
        // was the lettering itself, not the balloon, and the rectangle fit is better.
        if (R - L < (boxRight - boxLeft) * MIN_RELATIVE &&
            B - T < (boxBottom - boxTop) * MIN_RELATIVE) return null
        return floatArrayOf(L - boxLeft, T - boxTop, R - boxLeft, B - boxTop)
    }

    /** Even-odd polygon fill into a [w]x[h] boolean grid. */
    fun rasterize(poly: FloatArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        val n = poly.size / 2
        if (n < 3) return out
        var minY = 1f; var maxY = 0f
        for (i in 0 until n) {
            val y = poly[i * 2 + 1]
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        var l = 1f; var r = 0f
        for (i in 0 until n) {
            val x = poly[i * 2]
            if (x < l) l = x
            if (x > r) r = x
        }
        val wf = max(1e-6f, r - l)
        val hf = max(1e-6f, maxY - minY)
        val xs = FloatArray(n)
        for (y in 0 until h) {
            val sy = minY + (y + 0.5f) / h * hf
            var cnt = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                val yi = poly[i * 2 + 1]; val yj = poly[j * 2 + 1]
                if ((yi > sy) != (yj > sy)) {
                    val xi = poly[i * 2]; val xj = poly[j * 2]
                    xs[cnt++] = xi + (sy - yi) / (yj - yi) * (xj - xi)
                }
            }
            if (cnt < 2) continue
            java.util.Arrays.sort(xs, 0, cnt)
            var k = 0
            while (k + 1 < cnt) {
                val xa = ((xs[k] - l) / wf * w)
                val xb = ((xs[k + 1] - l) / wf * w)
                val from = max(0, floor(xa).toInt())
                val to = min(w - 1, xb.roundToInt()-1)
                for (x in from..to) out[y * w + x] = true
                k += 2
            }
        }
        return out
    }

    /** Histogram (monotonic stack) largest all-true rectangle. Returns [left, top, right, bottom) in cells. */
    fun largestInsideRect(filled: BooleanArray, w: Int, h: Int): IntArray? {
        val heights = IntArray(w)
        val stack = IntArray(w + 1)
        var best: IntArray? = null
        var bestArea = 0
        for (y in 0 until h) {
            for (x in 0 until w) heights[x] = if (filled[y * w + x]) heights[x] + 1 else 0
            var size = 0
            var x = 0
            while (x <= w) {
                val cur = if (x == w) 0 else heights[x]
                if (size == 0 || cur >= heights[stack[size - 1]]) {
                    stack[size++] = x
                    x++
                } else {
                    val top = stack[--size]
                    val rh = heights[top]
                    if (rh <= 0) continue
                    val right = x
                    val left = if (size == 0) 0 else stack[size - 1] + 1
                    val area = (right - left) * rh
                    if (area > bestArea) {
                        bestArea = area
                        best = intArrayOf(left, y - rh + 1, right, y + 1)
                    }
                }
            }
        }
        return best
    }

    /** Cheap guard: a polygon that is just the OCR box adds nothing. */
    fun isBoxLike(poly: FloatArray?, w: Float, h: Float): Boolean {
        if (poly == null || poly.size != 8) return true
        val f = frame(poly) ?: return true
        return abs((f.width * f.height) - (w * h)) < 0.02f
    }
}
