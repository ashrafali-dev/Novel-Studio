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

/**
 * The only OCR surface the rest of the app sees. Swap the implementation in
 * [WebModeExtractor.defaultEngine] to change engines; nothing else depends on ML Kit.
 */
internal interface OcrEngine {
    /** Blocking. Call from a worker thread, never the main thread. */
    fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult
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

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult {
        try {
            val vt = Tasks.await(recognizer(script).process(InputImage.fromBitmap(bitmap, 0)))
            val blocks = vt.textBlocks
                .mapNotNull { b ->
                    val t = b.text.trim()
                    if (t.isEmpty()) null else toBlock(t, b)
                }
                .sortedWith(compareBy({ it.top }, { it.left }))
            return OcrResult(blocks)
        } catch (e: ExecutionException) {
            val c = e.cause
            if (c is MlKitException) throw OcrUnavailableException("ML Kit ${script.name}: code ${c.errorCode}", c)
            throw OcrUnavailableException("ML Kit ${script.name} failed: ${c?.javaClass?.simpleName}", c)
        } catch (e: MlKitException) {
            throw OcrUnavailableException("ML Kit ${script.name}: code ${e.errorCode}", e)
        }
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
            return Geo(cx, cy, w, h, a)
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
