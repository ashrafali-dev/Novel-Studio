package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min

class WebtoonOcr(private val context: Context) {
    companion object {
        const val MODEL_VERSION = "mlkit-text-v2-1"
        private const val TILE_HEIGHT = 1800
        private const val MAX_INPUT_WIDTH = 1800
    }

    data class PrepareProgress(val stage: String, val percent: Int)

    private var latin: TextRecognizer? = null
    private var chinese: TextRecognizer? = null
    private var japanese: TextRecognizer? = null
    private var korean: TextRecognizer? = null

    fun prepare(onProgress: ((PrepareProgress) -> Unit)? = null) {
        close()
        onProgress?.invoke(PrepareProgress("Latin OCR চালু হচ্ছে", 20))
        latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        onProgress?.invoke(PrepareProgress("Chinese OCR প্রস্তুত", 40))
        chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        onProgress?.invoke(PrepareProgress("Japanese OCR প্রস্তুত", 60))
        japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        onProgress?.invoke(PrepareProgress("Korean OCR প্রস্তুত", 80))
        korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        onProgress?.invoke(PrepareProgress("OCR engine প্রস্তুত", 100))
    }

    fun recognize(bytes: ByteArray): WebtoonOcrRunResult {
        require(bytes.isNotEmpty()) { "OCR image is empty" }
        val latinRecognizer = latin ?: error("OCR engine is not prepared")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val fullW = bounds.outWidth
        val fullH = bounds.outHeight
        require(fullW > 0 && fullH > 0) { "image dimensions unavailable" }

        val decoder = BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes), false)
            ?: error("image decoder unavailable")

        val all = ArrayList<WebtoonOcrResult>()
        var y = 0
        try {
            while (y < fullH) {
                val tileH = min(TILE_HEIGHT, fullH - y)
                val tile = decoder.decodeRegion(
                    Rect(0, y, fullW, y + tileH),
                    BitmapFactory.Options()
                )
                if (tile == null) {
                    y += tileH
                    continue
                }

                try {
                    val scale = if (tile.width > MAX_INPUT_WIDTH) {
                        MAX_INPUT_WIDTH.toFloat() / tile.width.toFloat()
                    } else {
                        1f
                    }

                    val input = if (scale < 1f) {
                        Bitmap.createScaledBitmap(
                            tile,
                            (tile.width * scale).toInt().coerceAtLeast(1),
                            (tile.height * scale).toInt().coerceAtLeast(1),
                            true
                        )
                    } else {
                        tile
                    }

                    try {
                        val primary = runRecognizer(latinRecognizer, input, y, scale)
                        all.addAll(primary)

                        // Latin is cheap and catches English dialogue. If it did not
                        // produce convincing CJK text, try the CJK recognizers too.
                        val primaryText = primary.joinToString("\n") { it.text }
                        if (!hasCjk(primaryText) || primaryText.length < 3) {
                            for (recognizer in listOfNotNull(chinese, japanese, korean)) {
                                runCatching {
                                    all.addAll(runRecognizer(recognizer, input, y, scale))
                                }
                            }
                        }
                    } finally {
                        if (input !== tile) input.recycle()
                    }
                } finally {
                    tile.recycle()
                }

                if (y + tileH >= fullH) break
                y += TILE_HEIGHT
            }
        } finally {
            decoder.recycle()
        }

        return WebtoonOcrRunResult(dedupe(all))
    }

    private fun runRecognizer(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        tileTop: Int,
        scale: Float
    ): List<WebtoonOcrResult> {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        val out = ArrayList<WebtoonOcrResult>()

        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.replace(Regex("\\s+"), " ").trim()
                val box = line.boundingBox ?: continue
                if (text.isBlank() || !text.any { it.isLetterOrDigit() }) continue

                val x0 = box.left / scale
                val y0 = tileTop + box.top / scale
                val x1 = box.right / scale
                val y1 = tileTop + box.bottom / scale
                if (x1 - x0 < 6f || y1 - y0 < 6f) continue

                out += WebtoonOcrResult(
                    text = text,
                    confidence = 0.95f,
                    points = listOf(
                        PointF(x0, y0),
                        PointF(x1, y0),
                        PointF(x1, y1),
                        PointF(x0, y1)
                    )
                )
            }
        }
        return out
    }

    private fun hasCjk(text: String): Boolean = text.any {
        it in '\u3400'..'\u4DBF' ||
        it in '\u4E00'..'\u9FFF' ||
        it in '\u3040'..'\u30FF' ||
        it in '\uAC00'..'\uD7AF'
    }

    private fun dedupe(items: List<WebtoonOcrResult>): List<WebtoonOcrResult> {
        val out = ArrayList<WebtoonOcrResult>()
        for (item in items) {
            val duplicate = out.any { kept ->
                kept.text.equals(item.text, ignoreCase = true) &&
                    iou(kept, item) >= 0.55f
            }
            if (!duplicate) out += item
        }
        return out.sortedWith(
            compareBy<WebtoonOcrResult> { it.points.minOf { p -> p.y } }
                .thenBy { it.points.minOf { p -> p.x } }
        )
    }

    private fun iou(a: WebtoonOcrResult, b: WebtoonOcrResult): Float {
        fun bounds(r: WebtoonOcrResult): RectF {
            val xs = r.points.map { it.x }
            val ys = r.points.map { it.y }
            return RectF(
                xs.minOrNull() ?: 0f,
                ys.minOrNull() ?: 0f,
                xs.maxOrNull() ?: 0f,
                ys.maxOrNull() ?: 0f
            )
        }

        val aa = bounds(a)
        val bb = bounds(b)
        val ix = max(0f, min(aa.right, bb.right) - max(aa.left, bb.left))
        val iy = max(0f, min(aa.bottom, bb.bottom) - max(aa.top, bb.top))
        val inter = ix * iy
        val areaA = max(0f, aa.width()) * max(0f, aa.height())
        val areaB = max(0f, bb.width()) * max(0f, bb.height())
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }

    fun close() {
        listOf(latin, chinese, japanese, korean).forEach {
            runCatching { it?.close() }
        }
        latin = null
        chinese = null
        japanese = null
        korean = null
    }
}
