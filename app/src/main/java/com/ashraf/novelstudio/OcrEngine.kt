package com.ashraf.novelstudio

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutionException

/** Scripts Web Mode can ask an engine to read. */
internal enum class OcrScript { LATIN, CHINESE, JAPANESE, KOREAN }

/**
 * One recognised text block. All numbers are pixels inside the slice that was scanned.
 * [top]/[left] keep the reading order; the rest describes where the text really sat, so a
 * translation can be put back on the same spot: [cx]/[cy] centre, [w]/[h] size of the (unrotated)
 * text box, [angle] rotation in degrees (clockwise), [fontPx] estimated glyph size, [vertical] for
 * top-to-bottom columns (common in manga).
 */
internal data class OcrBlock(
    val text: String,
    val top: Int,
    val left: Int,
    val cx: Float = left.toFloat(),
    val cy: Float = top.toFloat(),
    val w: Float = 0f,
    val h: Float = 0f,
    val angle: Float = 0f,
    val fontPx: Float = 0f,
    val vertical: Boolean = false
)

internal class OcrResult(val blocks: List<OcrBlock>) {
    val text: String get() = blocks.joinToString("\n\n") { it.text.trim() }.trim()
}

/** Failure reported by an engine (model missing, init failure, ...). */
internal class OcrUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Geometry helpers shared by the engine and Web Mode (pure Kotlin, no Android types). */
internal object OcrPost {
    /**
     * ML Kit sometimes reports stylised or skewed text as turned by ~180 degrees, and the translation
     * was then drawn upside down. Text is never meant to be read upside down, so fold the angle into
     * (-90, 90]. Anything steeper than [MAX_TILT] after that is treated as "not tilted".
     */
    const val MAX_TILT = 50f

    fun foldAngle(a: Float): Float {
        var x = a % 360f
        if (x > 180f) x -= 360f
        if (x <= -180f) x += 360f
        if (x > 90f) x -= 180f
        if (x <= -90f) x += 180f
        return x
    }

    fun usableTilt(a: Float): Float {
        val f = foldAngle(a)
        return if (Math.abs(f) <= MAX_TILT) f else 0f
    }

    /** Axis-aligned overlap area of two centre/size boxes. */
    private fun inter(a: OcrBlock, b: OcrBlock): Float {
        val x = Math.min(a.cx + a.w / 2, b.cx + b.w / 2) - Math.max(a.cx - a.w / 2, b.cx - b.w / 2)
        val y = Math.min(a.cy + a.h / 2, b.cy + b.h / 2) - Math.max(a.cy - a.h / 2, b.cy - b.h / 2)
        return if (x > 0 && y > 0) x * y else 0f
    }

    /** Share of the SMALLER box that lies inside the other one. */
    fun overlapOfSmaller(a: OcrBlock, b: OcrBlock): Float {
        val small = Math.min(a.w * a.h, b.w * b.h)
        return if (small <= 0f) 0f else inter(a, b) / small
    }

    /** Grayscale with strongly stretched contrast: makes glowing / outlined lettering readable for OCR. */
    fun boostContrast(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val cm = android.graphics.ColorMatrix()
        cm.setSaturation(0f)
        val k = 2.2f
        val o = -128f * (k - 1f)
        cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, o,
            0f, k, 0f, 0f, o,
            0f, 0f, k, 0f, o,
            0f, 0f, 0f, 1f, 0f)))
        val p = android.graphics.Paint()
        p.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    /**
     * Keeps only the BRIGHT lettering (white text with a glow / dark outline on black) as black on
     * white. The glow haze that confuses the recogniser disappears and the letters come out clean.
     */
    fun brightMask(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val l = (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
            px[i] = if (l >= 205) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Scan-site watermarks ("asurascans.com", "www.xyz.net") are not dialogue and must never be translated. */
    private val SITE = Regex(
        "(?i)(?:https?://|www\\s?\\.)|\\b[a-z0-9][a-z0-9-]{2,}\\s?\\.(?:com|net|org|io|xyz|site|info|app)\\b"
    )

    /** Drops blocks that are clearly recogniser noise: no letters, mostly symbols, or hair-thin streaks. */
    fun isNoise(b: OcrBlock): Boolean {
        val t = b.text.trim()
        if (t.isEmpty()) return true
        if (SITE.containsMatchIn(t)) return true
        val letters = t.count { it.isLetter() }
        if (letters == 0) return true
        if (t.length >= 3 && letters * 100 < t.length * 45) return true
        if (b.h < 9f || b.w < 9f) return true
        if (b.h > b.w * 6f && t.length <= 4) return true    // vertical smear of a few "letters"
        return false
    }

    fun scaled(b: OcrBlock, k: Float): OcrBlock = OcrBlock(
        b.text, (b.top * k).toInt(), (b.left * k).toInt(),
        b.cx * k, b.cy * k, b.w * k, b.h * k, b.angle, b.fontPx * k, b.vertical
    )

    /**
     * ML Kit often cuts ONE speech bubble into several blocks ("WHY IS THIS ROPE SO" / "HARD TO
     * UNTIE?"). The chatbot then got fragments, translated some and left the rest in English.
     * Blocks that sit right under each other, overlap horizontally and have the same text size and
     * tilt are one sentence: join them into one block.
     */
    fun mergeNeighbours(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size < 2) return blocks
        val work = ArrayList(blocks.sortedWith(compareBy({ it.cy }, { it.cx })))
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in 0 until work.size) {
                for (j in i + 1 until work.size) {
                    val a = work[i]; val b = work[j]
                    if (!joinable(a, b)) continue
                    work[i] = join(a, b)
                    work.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
        return work.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun joinable(a: OcrBlock, b: OcrBlock): Boolean {
        if (a.vertical || b.vertical) return false
        if (Math.abs(a.angle) >= 6f || Math.abs(b.angle) >= 6f) return false   // clearly tilted text stays as it is
        val fa = if (a.fontPx > 0f) a.fontPx else a.h
        val fb = if (b.fontPx > 0f) b.fontPx else b.h
        if (fa <= 0f || fb <= 0f) return false
        val ratio = fa / fb
        if (ratio < 0.45f || ratio > 2.2f) return false
        val f = Math.max(fa, fb)
        val top = if (a.cy <= b.cy) a else b
        val bot = if (top === a) b else a
        val gap = (bot.cy - bot.h / 2) - (top.cy + top.h / 2)
        if (gap > f * 1.7f || gap < -f * 0.6f) return false
        val ox = Math.min(a.cx + a.w / 2, b.cx + b.w / 2) - Math.max(a.cx - a.w / 2, b.cx - b.w / 2)
        val narrow = Math.min(a.w, b.w)
        if (narrow <= 0f || ox < narrow * 0.3f) return false
        // centred lines of one bubble share a middle; a side-by-side caption does not
        if (Math.abs(a.cx - b.cx) > Math.max(a.w, b.w) * 0.7f) return false
        return true
    }

    private fun join(a: OcrBlock, b: OcrBlock): OcrBlock {
        val top = if (a.cy <= b.cy) a else b
        val bot = if (top === a) b else a
        val l = Math.min(a.cx - a.w / 2, b.cx - b.w / 2)
        val r = Math.max(a.cx + a.w / 2, b.cx + b.w / 2)
        val t = Math.min(a.cy - a.h / 2, b.cy - b.h / 2)
        val bt = Math.max(a.cy + a.h / 2, b.cy + b.h / 2)
        val text = top.text.trim() + " " + bot.text.trim()
        return OcrBlock(
            text, t.toInt(), l.toInt(),
            (l + r) / 2f, (t + bt) / 2f, r - l, bt - t, 0f,
            Math.max(a.fontPx, b.fontPx), false
        )
    }
}

/**
 * The only OCR surface the rest of the app sees. Swap the implementation in
 * [WebModeExtractor.defaultEngine] to change engines; nothing else depends on ML Kit.
 */
internal interface OcrEngine {
    /** Blocking. Call from a worker thread, never the main thread. */
    fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult
    /** Fast single-pass OCR for screen scanning; callers may request a bounded fallback when sparse. */
    fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult = recognize(bitmap, script)
    /**
     * One bounded retry for sparse/failed OCR. Implementations may upscale and boost contrast.
     * Empty default keeps alternate/test engines from accidentally repeating expensive work.
     */
    fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult = OcrResult(emptyList())
    /** A small crop that holds ONE speech bubble: cheaper than [recognize] (few variants). */
    fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult = recognize(bitmap, script)
    fun close()
}

/**
 * On-device ML Kit engine. Latin, Chinese (Simplified + Traditional), Japanese and Korean each use
 * their own bundled model (com.google.mlkit:text-recognition[-chinese|-japanese|-korean]).
 * Recognisers are created lazily, so a script that is never needed never loads its model.
 */
internal class MlKitOcrEngine : OcrEngine {
    private val recognizers = HashMap<OcrScript, TextRecognizer>()

    @Synchronized
    private fun recognizer(script: OcrScript): TextRecognizer = recognizers.getOrPut(script) {
        when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
    }

    // Normalize spacing and punctuation for comparing partial OCR readings from different passes.
    private fun comparableOcrText(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult {
        // Real-time mode must return quickly: one on-device pass over the original bitmap.
        // Full multi-view/contrast recognition remains available through recognize() for chapter scans
        // and through the bounded fallback when this first pass is genuinely sparse.
        try {
            val result = Tasks.await(
                recognizer(script).process(InputImage.fromBitmap(bitmap, 0))
            )
            return OcrResult(OcrPost.mergeNeighbours(toBlocks(result).filterNot { OcrPost.isNoise(it) }))
        } catch (e: ExecutionException) {
            val c = e.cause
            if (c is MlKitException) throw OcrUnavailableException("ML Kit ${script.name}: code ${c.errorCode}", c)
            throw OcrUnavailableException("ML Kit ${script.name} failed: ${c?.javaClass?.simpleName}", c)
        } catch (e: MlKitException) {
            throw OcrUnavailableException("ML Kit ${script.name}: code ${e.errorCode}", e)
        }
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult {
        try {
            val rec = recognizer(script)
            // Stylised sound effects are what ML Kit misses most: huge, outlined, glowing or
            // gradient lettering. Look at the picture in several ways AT THE SAME TIME (ML Kit runs
            // the tasks in parallel, so this costs little more than one pass) and keep every block
            // that an earlier look did not already find:
            //   1x original, 1/2 size, 1/2 size with boosted contrast (glow/outline), 1/4 size (giant letters)
            val mine = ArrayList<Bitmap>()                       // bitmaps we created and must recycle
            val tasks = ArrayList<com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text>>()
            val scale = ArrayList<Float>()
            try {
                tasks.add(rec.process(InputImage.fromBitmap(bitmap, 0))); scale.add(1f)
                if (bitmap.width >= 240 && bitmap.height >= 240) {
                    try {
                        val half = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                        mine.add(half)
                        tasks.add(rec.process(InputImage.fromBitmap(half, 0))); scale.add(2f)
                        val boosted = OcrPost.boostContrast(half)
                        mine.add(boosted)
                        tasks.add(rec.process(InputImage.fromBitmap(boosted, 0))); scale.add(2f)
                        val bright = OcrPost.brightMask(bitmap)
                        mine.add(bright)
                        tasks.add(rec.process(InputImage.fromBitmap(bright, 0))); scale.add(1f)
                        if (bitmap.width >= 480 && bitmap.height >= 480) {
                            val quarter = Bitmap.createScaledBitmap(bitmap, bitmap.width / 4, bitmap.height / 4, true)
                            mine.add(quarter)
                            tasks.add(rec.process(InputImage.fromBitmap(quarter, 0))); scale.add(4f)
                        }
                    } catch (_: OutOfMemoryError) { }
                    // Extra looks for the full (non-real-time) scan. The prefetch already hides the
                    // waiting time, so quality matters more than speed here. Each one is optional:
                    // an out-of-memory error only skips that look.
                    val area = bitmap.width.toLong() * bitmap.height.toLong()
                    // 2x enlarged: small / thin lettering that the 1x pass reads as noise.
                    if (maxOf(bitmap.width, bitmap.height) <= 1400 && area * 4L <= 6_000_000L) {
                        try {
                            val up = Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
                            mine.add(up)
                            tasks.add(rec.process(InputImage.fromBitmap(up, 0))); scale.add(0.5f)
                        } catch (_: OutOfMemoryError) { }
                    }
                    // Full-size contrast stretch: faint text on tinted / grey bubbles (the 1/2-size
                    // boosted look above can lose thin strokes).
                    if (area <= 3_200_000L) {
                        try {
                            val boost1 = OcrPost.boostContrast(bitmap)
                            mine.add(boost1)
                            tasks.add(rec.process(InputImage.fromBitmap(boost1, 0))); scale.add(1f)
                        } catch (_: OutOfMemoryError) { }
                    }
                }
                val all = ArrayList<OcrBlock>()
                for ((i, t) in tasks.withIndex()) {
                    val blocks = try { toBlocks(Tasks.await(t)) }
                        catch (e: ExecutionException) { if (i == 0) throw e else emptyList() }
                    for (b in blocks) {
                        val big = if (scale[i] == 1f) b else OcrPost.scaled(b, scale[i])
                        val candidateText = comparableOcrText(big.text)
                        if (i > 0 && candidateText.length < 2) continue

                        if (i > 0) {
                            val overlaps = all.indices.filter {
                                OcrPost.overlapOfSmaller(all[it], big) > 0.25f
                            }
                            if (overlaps.isNotEmpty()) {
                                // A later scale/contrast pass can read more of a line than the
                                // original pass. Do not blindly discard it as a duplicate: keep it
                                // when its text extends an earlier reading at the same position.
                                val upgrade = overlaps.firstOrNull { index ->
                                    val priorText = comparableOcrText(all[index].text)
                                    priorText.length >= 3 &&
                                        candidateText.length > priorText.length + maxOf(2, priorText.length / 10) &&
                                        candidateText.contains(priorText)
                                }
                                if (upgrade != null) {
                                    all[upgrade] = big
                                    // If the fuller result also contains another overlapping
                                    // fragment, remove that fragment so it is not translated twice.
                                    val redundant = overlaps.filter { index ->
                                        index != upgrade && comparableOcrText(all[index].text).let { priorText ->
                                            priorText.length >= 3 && candidateText.contains(priorText)
                                        }
                                    }.sortedDescending()
                                    for (index in redundant) all.removeAt(index)
                                }
                                // Keep the best existing candidate for this position unless the
                                // new one demonstrably contains more of the same text.
                                continue
                            }
                        }
                        all.add(big)
                    }
                }
                val merged = OcrPost.mergeNeighbours(all.filterNot { OcrPost.isNoise(it) })
                return OcrResult(merged)
            } finally {
                for (m in mine) if (m !== bitmap) m.recycle()
            }
        } catch (e: ExecutionException) {
            val c = e.cause
            if (c is MlKitException) throw OcrUnavailableException("ML Kit ${script.name}: code ${c.errorCode}", c)
            throw OcrUnavailableException("ML Kit ${script.name} failed: ${c?.javaClass?.simpleName}", c)
        } catch (e: MlKitException) {
            throw OcrUnavailableException("ML Kit ${script.name}: code ${e.errorCode}", e)
        }
    }

    /**
     * Retry only when the caller detects a sparse first reading. Upscale within a bounded pixel
     * budget and run normal + contrast-boosted inputs in parallel, then merge distinct text boxes.
     */
    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult {
        val maxDim = maxOf(bitmap.width, bitmap.height).toFloat()
        val area = bitmap.width.toDouble() * bitmap.height.toDouble()
        val factor = minOf(
            2f,
            2400f / maxDim,
            kotlin.math.sqrt(5_000_000.0 / area).toFloat()
        ).coerceAtLeast(1f)

        var enlarged: Bitmap? = null
        var boosted: Bitmap? = null
        try {
            val base = if (factor > 1.01f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * factor).toInt().coerceAtLeast(1),
                    (bitmap.height * factor).toInt().coerceAtLeast(1),
                    true
                ).also { enlarged = it }
            } else bitmap
            boosted = OcrPost.boostContrast(base)
            val rec = recognizer(script)
            val tasks = listOf(
                rec.process(InputImage.fromBitmap(base, 0)),
                rec.process(InputImage.fromBitmap(boosted, 0))
            )
            val all = ArrayList<OcrBlock>()
            for (task in tasks) {
                val raw = try { toBlocks(Tasks.await(task)) }
                    catch (_: ExecutionException) { emptyList() }
                for (found in raw) {
                    val candidate = OcrPost.scaled(found, 1f / factor)
                    if (OcrPost.isNoise(candidate)) continue
                    val key = comparableOcrText(candidate.text)
                    if (key.isEmpty()) continue
                    val overlaps = all.indices.filter {
                        OcrPost.overlapOfSmaller(all[it], candidate) > 0.25f
                    }
                    if (overlaps.isEmpty()) {
                        all.add(candidate)
                    } else {
                        val upgrade = overlaps.firstOrNull { index ->
                            val prior = comparableOcrText(all[index].text)
                            prior.length >= 3 &&
                                key.length > prior.length + maxOf(2, prior.length / 10) &&
                                key.contains(prior)
                        }
                        if (upgrade != null) all[upgrade] = candidate
                    }
                }
            }
            return OcrResult(OcrPost.mergeNeighbours(all.filterNot { OcrPost.isNoise(it) }))
        } catch (_: OutOfMemoryError) {
            return OcrResult(emptyList())
        } catch (e: ExecutionException) {
            val c = e.cause
            throw OcrUnavailableException("ML Kit ${script.name} retry failed: ${c?.javaClass?.simpleName}", c)
        } catch (e: MlKitException) {
            throw OcrUnavailableException("ML Kit ${script.name} retry: code ${e.errorCode}", e)
        } finally {
            boosted?.let { if (it !== bitmap) it.recycle() }
            enlarged?.let { if (it !== bitmap && it !== boosted) it.recycle() }
        }
    }

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult {
        try {
            val rec = recognizer(script)
            val mine = ArrayList<Bitmap>()
            val tasks = ArrayList<com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text>>()
            val scale = ArrayList<Float>()
            try {
                tasks.add(rec.process(InputImage.fromBitmap(bitmap, 0))); scale.add(1f)
                if (bitmap.height < 220 && bitmap.width >= 16 && bitmap.height >= 16) {
                    try {
                        val up = Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
                        mine.add(up)
                        tasks.add(rec.process(InputImage.fromBitmap(up, 0))); scale.add(0.5f)
                    } catch (_: OutOfMemoryError) { }
                }
                var best: List<OcrBlock> = emptyList()
                var bestLen = -1
                for ((i, t) in tasks.withIndex()) {
                    val blocks = try { toBlocks(Tasks.await(t)) }
                        catch (e: ExecutionException) { if (i == 0) throw e else emptyList() }
                    val scaledBlocks = if (scale[i] == 1f) blocks else blocks.map { OcrPost.scaled(it, scale[i]) }
                    val merged = OcrPost.mergeNeighbours(scaledBlocks.filterNot { OcrPost.isNoise(it) })
                    val len = merged.sumOf { it.text.length }
                    if (len > bestLen) { best = merged; bestLen = len }
                }
                return OcrResult(best)
            } finally {
                for (m in mine) if (m !== bitmap) m.recycle()
            }
        } catch (e: ExecutionException) {
            val c = e.cause
            throw OcrUnavailableException("ML Kit ${script.name} failed: ${c?.javaClass?.simpleName}", c)
        } catch (e: MlKitException) {
            throw OcrUnavailableException("ML Kit ${script.name}: code ${e.errorCode}", e)
        }
    }

    /**
     * ML Kit's own "blocks" split one sentence at random on glowing / stylised lettering. Take its
     * LINES instead and let [OcrPost.mergeNeighbours] rebuild the sentences with one consistent rule.
     * Vertical text and clearly tilted blocks (sound effects) stay whole.
     */
    private fun toBlocks(vt: com.google.mlkit.vision.text.Text): List<OcrBlock> {
        val out = ArrayList<OcrBlock>()
        for (b in vt.textBlocks) {
            val t = b.text.trim()
            if (t.isEmpty()) continue
            val whole = toBlock(t, b)
            if (whole.vertical || b.lines.size < 2 || Math.abs(whole.angle) >= 6f) { out.add(whole); continue }
            for (l in b.lines) {
                val lt = l.text.trim()
                if (lt.isEmpty()) continue
                val g = geo(l.cornerPoints, l.boundingBox) ?: continue
                val box = l.boundingBox
                out.add(OcrBlock(lt, box?.top ?: 0, box?.left ?: 0, g.cx, g.cy, g.w, g.h, g.angle, g.h * 0.9f, false))
            }
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private class Geo(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float)

    /** Centre, size and rotation from the 4 corner points (clockwise from top-left), else the bounding box. */
    private fun geo(corners: Array<android.graphics.Point>?, box: android.graphics.Rect?): Geo? {
        if (corners != null && corners.size == 4) {
            val cx = corners.sumOf { it.x.toDouble() }.toFloat() / 4f
            val cy = corners.sumOf { it.y.toDouble() }.toFloat() / 4f
            val w = Math.hypot((corners[1].x - corners[0].x).toDouble(), (corners[1].y - corners[0].y).toDouble()).toFloat()
            val h = Math.hypot((corners[2].x - corners[1].x).toDouble(), (corners[2].y - corners[1].y).toDouble()).toFloat()
            val a = Math.toDegrees(Math.atan2((corners[1].y - corners[0].y).toDouble(), (corners[1].x - corners[0].x).toDouble())).toFloat()
            return Geo(cx, cy, w, h, OcrPost.foldAngle(a))
        }
        if (box != null) return Geo(box.exactCenterX(), box.exactCenterY(), box.width().toFloat(), box.height().toFloat(), 0f)
        return null
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return s[s.size / 2]
    }

    private fun toBlock(t: String, b: com.google.mlkit.vision.text.Text.TextBlock): OcrBlock {
        val box = b.boundingBox
        val top = box?.top ?: 0
        val left = box?.left ?: 0
        val g = geo(b.cornerPoints, box) ?: return OcrBlock(t, top, left)
        // Glyph size: a horizontal line is as tall as the glyphs, a vertical column as wide.
        val tall = ArrayList<Float>()
        val wide = ArrayList<Float>()
        var verticalVotes = 0
        for (l in b.lines) {
            val lg = geo(l.cornerPoints, l.boundingBox) ?: continue
            if (lg.h > lg.w * 1.6f) { verticalVotes++; wide.add(lg.w) } else tall.add(lg.h)
        }
        val vertical = verticalVotes > 0 && verticalVotes * 2 >= b.lines.size
        val font = if (vertical) median(wide) else median(tall)
        val px = if (font > 0f) font * 0.9f else minOf(g.w, g.h) * 0.5f
        return OcrBlock(t, top, left, g.cx, g.cy, g.w, g.h, g.angle, px, vertical)
    }

    @Synchronized
    override fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }
}
