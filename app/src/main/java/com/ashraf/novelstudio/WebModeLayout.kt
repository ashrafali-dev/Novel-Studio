package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Where one piece of recognised text sat on its image. Everything is a fraction of the image, so it
 * still fits when the page is shown at another width: [cx]/[cy] centre, [w]/[h] box size, [font]
 * glyph size as a fraction of the image WIDTH. [angle] is in degrees, [bg]/[fg] are ARGB colours
 * sampled from the original artwork.
 */
internal data class WmBubble(
    val src: String,
    val cx: Float, val cy: Float, val w: Float, val h: Float,
    val angle: Float, val font: Float,
    val bg: Int, val fg: Int,
    /** Colour at the bottom of the lettering; differs from [fg] when the original text was a gradient. */
    val fg2: Int = fg,
    /** True when the old text sat on artwork (not a flat bubble): the cover gets soft, feathered edges. */
    val soft: Boolean = false,
    /** Exact normalized OCR text captured at this position; used to bind output to the right bubble. */
    val sourceText: String = "",
    /** Bengali text already translated for this position. Empty means not translated yet. */
    val translatedText: String = ""
)

/**
 * The bubbles of one chapter, in the same order as their text paragraphs in the chapter text that is
 * sent to the chatbot. [titleOffset] is 1 when the chapter text starts with a title paragraph.
 */
internal class WebModeLayout(val titleOffset: Int, val bubbles: List<WmBubble>) {

    fun toJson(): String {
        val a = JSONArray()
        for (b in bubbles) a.put(JSONObject()
            .put("s", b.src).put("cx", r5(b.cx)).put("cy", r5(b.cy)).put("w", r5(b.w)).put("h", r5(b.h))
            .put("a", r5(b.angle)).put("f", r5(b.font)).put("bg", b.bg).put("fg", b.fg)
            .put("fg2", b.fg2).put("sf", b.soft)
            .put("srcText", b.sourceText).put("tr", b.translatedText))
        return JSONObject().put("v", 2).put("to", titleOffset).put("b", a).toString()
    }

    companion object {
        private fun r5(x: Float): Double = Math.round(x * 100000.0) / 100000.0

        fun fromJson(s: String): WebModeLayout? = try {
            val o = JSONObject(s)
            val a = o.getJSONArray("b")
            val list = ArrayList<WmBubble>(a.length())
            for (i in 0 until a.length()) {
                val b = a.getJSONObject(i)
                list.add(WmBubble(
                    b.getString("s"),
                    b.getDouble("cx").toFloat(), b.getDouble("cy").toFloat(),
                    b.getDouble("w").toFloat(), b.getDouble("h").toFloat(),
                    b.getDouble("a").toFloat(), b.getDouble("f").toFloat(),
                    b.getInt("bg"), b.getInt("fg"),
                    b.optInt("fg2", b.getInt("fg")), b.optBoolean("sf", false),
                    b.optString("srcText", ""), b.optString("tr", "")
                ))
            }
            WebModeLayout(o.optInt("to", 1), list)
        } catch (_: Exception) { null }

        private val MARK = Regex("\\[(\\d{3,})\\]")
        private val HASH_NOISE = Regex("[\\s\\p{P}\\p{S}]+")

        /** Canonical OCR text: OCR whitespace, case and punctuation variation must not resend it. */
        fun normalizeOcrText(text: String): String =
            text.lowercase().replace(HASH_NOISE, "").trim()

        /** Stable per-bubble fingerprint: normalized OCR text plus its image and relative position. */
        fun fingerprint(b: WmBubble): String {
            val src = WebModeLogic.normalizeUrl(b.src)
            val text = normalizeOcrText(b.sourceText)
            val geometry = listOf(b.cx, b.cy, b.w, b.h).joinToString(",") {
                Math.round(it * 10000f).toInt().toString()
            }
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$src|$geometry|$text".toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Map the current reply to the current scan, then retain the result on the matching bubbles. */
        fun translated(layout: WebModeLayout, values: List<String?>): WebModeLayout =
            WebModeLayout(layout.titleOffset, layout.bubbles.mapIndexed { i, b ->
                val value = values.getOrNull(i)?.trim().orEmpty()
                if (value.isEmpty()) b else b.copy(translatedText = value)
            })
        private val BLANK = Regex("\\n\\s*\\n")
        private val NL = Regex("\\s*\\n\\s*")

        /**
         * One translated string per bubble (null = no translation for that bubble, leave it alone),
         * or null when the translation cannot be matched to the bubbles with confidence.
         *
         * With [NNN] markers the match is exact by number, so a dropped marker only loses that one
         * bubble (at least 70% must be present). Without markers the paragraph count must equal the
         * paragraph count that was sent; guessing a position would put text in the wrong bubble.
         */
        fun align(text: String, layout: WebModeLayout): List<String?>? {
            val n = layout.bubbles.size
            if (n == 0) return null
            val ms = MARK.findAll(text).toList()
            if (ms.isNotEmpty()) {
                val byNo = HashMap<Int, String>()
                for ((k, m) in ms.withIndex()) {
                    val no = m.groupValues[1].toIntOrNull() ?: continue
                    val end = if (k + 1 < ms.size) ms[k + 1].range.first else text.length
                    val body = text.substring(m.range.last + 1, end).trim()
                    if (body.isNotEmpty() && no !in byNo) byNo[no] = body
                }
                // Some models preserve the title marker; others omit the title entirely and
                // renumber the first bubble as [001]. Evaluate both layouts and use the one
                // that maps the most actual bubbles instead of shifting every translation.
                val withTitle = List(n) { i -> byNo[i + 1 + layout.titleOffset]?.replace(NL, " ") }
                val withoutTitle = List(n) { i -> byNo[i + 1]?.replace(NL, " ") }
                val a = withTitle.count { !it.isNullOrBlank() }
                val b = withoutTitle.count { !it.isNullOrBlank() }
                val out = if (b > a) withoutTitle else withTitle
                val found = maxOf(a, b)
                // A preserved marker identifies its bubble exactly. In live mode, place any
                // correctly marked answers and leave missing bubbles untranslated instead of
                // abandoning the whole batch and switching to reader view.
                if (found > 0 && (found * 100 >= n * 70 || layout.titleOffset == 0)) return out
                return null
            }
            val blankLineParas = text.split(BLANK).map { it.trim() }.filter { it.isNotEmpty() }
            val lineParas = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val expected = n + layout.titleOffset
            val paras = when {
                blankLineParas.size == expected -> blankLineParas
                lineParas.size == expected -> lineParas
                else -> blankLineParas
            }
            if (paras.size != expected) return null
            return List(n) { i -> paras[i + layout.titleOffset].replace(NL, " ") }
        }
    }
}

/**
 * Layouts of the chapters opened recently. Memory first; [persist] also keeps the last few on disk
 * so a saved translation can still be put into its bubbles after the app was restarted.
 */
internal object WebModeLayoutStore {
    private const val KEY = "wmLayouts"
    private const val MAX = 8

    // Latest OCR batch (used to align this answer) and cumulative translated layout (kept while scrolling).
    private val scans = object : LinkedHashMap<String, WebModeLayout>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebModeLayout>?): Boolean = size > MAX
    }
    private val mem = object : LinkedHashMap<String, WebModeLayout>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebModeLayout>?): Boolean = size > MAX
    }

    private fun key(url: String) = WebModeLogic.normalizeUrl(url)

    @Synchronized
    fun put(url: String, layout: WebModeLayout) {
        val k = key(url)
        scans[k] = layout
        // If this page already has a cumulative layout in memory, merge newly visible bubbles into it.
        mem[k]?.let { mem[k] = mergeLayouts(it, layout) }
    }

    @Synchronized
    fun setScan(url: String, layout: WebModeLayout) { scans[key(url)] = layout }

    @Synchronized
    fun getScan(url: String): WebModeLayout? = scans[key(url)]

    @Synchronized
    fun get(ctx: Context, url: String): WebModeLayout? {
        val k = key(url)
        mem[k]?.let { return it }
        return try {
            val data = JSONObject(Prefs.get(ctx, KEY, "{}")).optJSONObject("data") ?: return null
            val l = WebModeLayout.fromJson(data.optString(k).ifEmpty { return null }) ?: return null
            mem[k] = l
            l
        } catch (_: Exception) { null }
    }

    /** Merge translated current bubbles into the full-page layout so previously translated bubbles stay visible. */
    @Synchronized
    fun mergeTranslated(
        ctx: Context,
        url: String,
        scan: WebModeLayout,
        translated: List<String?>
    ): WebModeLayout {
        val current = WebModeLayout.translated(scan, translated)
        val old = get(ctx, url)
        val merged = mergeLayouts(old, current)
        mem[key(url)] = merged
        persist(ctx, url)
        return merged
    }

    /** Replace the stored layout for a deliberate whole-page Web Mode translation. */
    @Synchronized
    fun replace(ctx: Context, url: String, layout: WebModeLayout) {
        scans[key(url)] = layout
        mem[key(url)] = layout
        persist(ctx, url)
    }

    private fun samePosition(a: WmBubble, b: WmBubble): Boolean =
        WebModeLogic.normalizeUrl(a.src) == WebModeLogic.normalizeUrl(b.src) &&
            kotlin.math.abs(a.cx - b.cx) < 0.012f &&
            kotlin.math.abs(a.cy - b.cy) < 0.012f &&
            kotlin.math.abs(a.w - b.w) < 0.018f &&
            kotlin.math.abs(a.h - b.h) < 0.018f

    private fun mergeLayouts(old: WebModeLayout?, incoming: WebModeLayout): WebModeLayout {
        val result = old?.bubbles?.toMutableList() ?: mutableListOf()
        for (fresh in incoming.bubbles) {
            val at = result.indexOfFirst { samePosition(it, fresh) }
            if (at < 0) {
                result.add(fresh)
            } else {
                val prior = result[at]
                val sameText = WebModeLayout.normalizeOcrText(prior.sourceText) ==
                    WebModeLayout.normalizeOcrText(fresh.sourceText)
                result[at] = fresh.copy(
                    sourceText = fresh.sourceText.ifBlank { prior.sourceText },
                    translatedText = if (sameText) prior.translatedText.ifBlank { fresh.translatedText } else fresh.translatedText
                )
            }
        }
        return WebModeLayout(incoming.titleOffset, result)
    }

    @Synchronized
    fun persist(ctx: Context, url: String) {
        val k = key(url)
        val l = mem[k] ?: return
        try {
            val root = try { JSONObject(Prefs.get(ctx, KEY, "{}")) } catch (_: Exception) { JSONObject() }
            val data = root.optJSONObject("data") ?: JSONObject()
            val order = ArrayList<String>()
            root.optJSONArray("order")?.let { for (i in 0 until it.length()) order.add(it.getString(i)) }
            order.remove(k); order.add(k)
            data.put(k, l.toJson())
            while (order.size > MAX) data.remove(order.removeAt(0))
            Prefs.put(ctx, KEY, JSONObject().put("order", JSONArray(order)).put("data", data).toString())
        } catch (_: Exception) { }
    }
}

/**
 * Looks at the artwork around a recognised text block: the background colour to cover the old text
 * with, a text colour that was really used, and how far the empty bubble interior reaches beyond
 * the tight text box (so a longer translation has room instead of overflowing).
 */
internal object WmBubbleStyle {
    class Style(val l: Int, val t: Int, val r: Int, val b: Int, val bg: Int, val fg: Int, val fg2: Int, val soft: Boolean)

    private const val TOL = 38          // per-channel distance still counted as "the same background"
    private const val STEP = 2

    /** Axis-aligned box of a block inside a bitmap: [l, t, r, b]. */
    fun rectOf(b: OcrBlock, bw: Int, bh: Int): IntArray {
        val rad = Math.toRadians(b.angle.toDouble())
        val ex = (abs(Math.cos(rad)) * b.w + abs(Math.sin(rad)) * b.h) / 2.0
        val ey = (abs(Math.sin(rad)) * b.w + abs(Math.cos(rad)) * b.h) / 2.0
        val l = (b.cx - ex).toInt().coerceIn(0, bw - 1)
        val t = (b.cy - ey).toInt().coerceIn(0, bh - 1)
        val r = (b.cx + ex).toInt().coerceIn(l + 1, bw)
        val bt = (b.cy + ey).toInt().coerceIn(t + 1, bh)
        return intArrayOf(l, t, r, bt)
    }

    fun analyse(bmp: Bitmap, box: IntArray, others: List<IntArray>, expand: Boolean): Style {
        val bw = bmp.width
        val bh = bmp.height
        var l = box[0]; var t = box[1]; var r = box[2]; var b = box[3]
        var bg = ringMedian(bmp, l, t, r, b, 3)
        val soft = !ringFlat(bmp, l, t, r, b, bg)
        if (soft) {
            // Glow / shadow around the lettering tints the ring right next to it (that gave grey boxes on
            // black). Read the cover colour from a ring OUTSIDE the glow instead, if that ring is flat.
            val rp = ((b - t) * 0.22f).toInt().coerceIn(6, 26)
            val outer = ringMedian(bmp, l - rp, t - rp, r + rp, b + rp, 3)
            if (ringFlat(bmp, l - rp, t - rp, r + rp, b + rp, outer)) bg = outer
        }
        val mid = (t + b) / 2
        val fgTop = textColour(bmp, l, t, r, maxOf(t + 1, mid), bg)
        val fgBot = textColour(bmp, l, minOf(mid, b - 1), r, b, bg)
        val fgAll = textColour(bmp, l, t, r, b, bg)
        // Two clearly different halves = a gradient lettering: keep both ends, else one colour.
        val grad = colourDist(fgTop, fgBot) > 70 && contrast(fgTop, bg) >= MIN_CONTRAST && contrast(fgBot, bg) >= MIN_CONTRAST
        val fg = if (grad) fgTop else fgAll
        val fg2 = if (grad) fgBot else fgAll
        if (expand) {
            val buf = IntArray(maxOf(bw, bh) + 4)
            val maxX = maxOf(12, ((r - l) * 0.8f).toInt())
            val maxY = maxOf(12, ((b - t) * 0.8f).toInt())
            var gl = 0; var gr = 0; var gt = 0; var gb = 0
            var okL = true; var okR = true; var okT = true; var okB = true
            while (okL || okR || okT || okB) {
                if (okL) {
                    val n = l - STEP
                    if (gl >= maxX || n < 0 || hits(others, n, t, l, b) || !stripOk(bmp, n, t, l, b, bg, buf)) okL = false
                    else { l = n; gl += STEP }
                }
                if (okR) {
                    val n = r + STEP
                    if (gr >= maxX || n > bw || hits(others, r, t, n, b) || !stripOk(bmp, r, t, n, b, bg, buf)) okR = false
                    else { r = n; gr += STEP }
                }
                if (okT) {
                    val n = t - STEP
                    if (gt >= maxY || n < 0 || hits(others, l, n, r, t) || !stripOk(bmp, l, n, r, t, bg, buf)) okT = false
                    else { t = n; gt += STEP }
                }
                if (okB) {
                    val n = b + STEP
                    if (gb >= maxY || n > bh || hits(others, l, b, r, n) || !stripOk(bmp, l, b, r, n, bg, buf)) okB = false
                    else { b = n; gb += STEP }
                }
            }
            // Stay a little inside the bubble so its outline is never covered.
            if (gl > 6) l += 3
            if (gr > 6) r -= 3
            if (gt > 6) t += 3
            if (gb > 6) b -= 3
        }
        return Style(l, t, r, b, bg, fg, fg2, soft)
    }

    private fun hits(others: List<IntArray>, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        for (o in others) {
            if (x0 < o[2] + 2 && x1 > o[0] - 2 && y0 < o[3] + 2 && y1 > o[1] - 2) return true
        }
        return false
    }

    private fun near(c: Int, bg: Int): Boolean =
        abs(((c shr 16) and 255) - ((bg shr 16) and 255)) <= TOL &&
            abs(((c shr 8) and 255) - ((bg shr 8) and 255)) <= TOL &&
            abs((c and 255) - (bg and 255)) <= TOL

    /** True when (almost) every pixel of the strip still looks like the background. */
    private fun stripOk(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, bg: Int, buf: IntArray): Boolean {
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0 || w * h > buf.size) return false
        bmp.getPixels(buf, 0, w, x0, y0, w, h)
        var bad = 0
        val n = w * h
        for (i in 0 until n) if (!near(buf[i], bg)) bad++
        return bad * 100 <= n * 6
    }

    private fun median(v: IntArray, n: Int): Int {
        java.util.Arrays.sort(v, 0, n)
        return v[n / 2]
    }

    /** Median colour of a thin ring just outside the text box = the bubble's fill. */
    private fun ringMedian(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, pad: Int = 3): Int {
        val x0 = (l - pad).coerceAtLeast(0)
        val y0 = (t - pad).coerceAtLeast(0)
        val x1 = (r + pad).coerceAtMost(bmp.width - 1)
        val y1 = (b + pad).coerceAtMost(bmp.height - 1)
        val rs = IntArray(600); val gs = IntArray(600); val bs = IntArray(600)
        var n = 0
        val per = 2 * ((x1 - x0) + (y1 - y0)) + 4
        val step = maxOf(1, per / 240)
        fun add(x: Int, y: Int) {
            if (n >= rs.size) return
            val c = bmp.getPixel(x, y)
            rs[n] = (c shr 16) and 255; gs[n] = (c shr 8) and 255; bs[n] = c and 255; n++
        }
        var x = x0
        while (x <= x1) { add(x, y0); add(x, y1); x += step }
        var y = y0
        while (y <= y1) { add(x0, y); add(x1, y); y += step }
        if (n == 0) return 0xFFFFFFFF.toInt()
        return (0xFF shl 24) or (median(rs, n) shl 16) or (median(gs, n) shl 8) or median(bs, n)
    }

    private const val MIN_CONTRAST = 45   // the old threshold (90) threw away most coloured lettering for black/white

    private fun colourDist(a: Int, b: Int): Int =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))

    private fun contrast(a: Int, b: Int): Int = abs(lum(a) - lum(b))

    /** True when the ring around the text is one flat colour (a speech bubble), false on artwork. */
    private fun ringFlat(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, bg: Int): Boolean {
        val pad = 3
        val x0 = (l - pad).coerceAtLeast(0); val y0 = (t - pad).coerceAtLeast(0)
        val x1 = (r + pad).coerceAtMost(bmp.width - 1); val y1 = (b + pad).coerceAtMost(bmp.height - 1)
        var n = 0; var bad = 0
        val step = maxOf(1, ((x1 - x0) + (y1 - y0)) / 60)
        fun chk(x: Int, y: Int) { n++; if (!near(bmp.getPixel(x, y), bg)) bad++ }
        var x = x0
        while (x <= x1) { chk(x, y0); chk(x, y1); x += step }
        var y = y0
        while (y <= y1) { chk(x0, y); chk(x1, y); y += step }
        return n == 0 || bad * 100 <= n * 15
    }

    private fun lum(c: Int): Int = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    /** The colour the old text was drawn in: the average of the pixels farthest from the background. */
    private fun textColour(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, bg: Int): Int {
        val sx = maxOf(1, (r - l) / 40)
        val sy = maxOf(1, (b - t) / 40)
        val px = ArrayList<Long>()
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val c = bmp.getPixel(x, y)
                val d = abs(((c shr 16) and 255) - ((bg shr 16) and 255)) +
                    abs(((c shr 8) and 255) - ((bg shr 8) and 255)) + abs((c and 255) - (bg and 255))
                px.add((d.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL))
                x += sx
            }
            y += sy
        }
        val fallback = if (lum(bg) > 140) 0xFF111111.toInt() else 0xFFF5F5F5.toInt()
        if (px.isEmpty()) return fallback
        px.sortDescending()
        val take = maxOf(1, px.size / 10)
        var rr = 0L; var gg = 0L; var bb = 0L
        for (i in 0 until take) {
            val c = (px[i] and 0xFFFFFFFFL).toInt()
            rr += (c shr 16) and 255; gg += (c shr 8) and 255; bb += c and 255
        }
        val fg = (0xFF shl 24) or ((rr / take).toInt() shl 16) or ((gg / take).toInt() shl 8) or (bb / take).toInt()
        // Anti-aliasing and busy artwork can blur the estimate; if it is not clearly readable, use black/white.
        return if (abs(lum(fg) - lum(bg)) < MIN_CONTRAST) fallback else fg
    }
}
