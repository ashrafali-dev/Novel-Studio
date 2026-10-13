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

/** One recognised text block; [top]/[left] are pixel offsets inside the slice that was scanned. */
internal data class OcrBlock(val text: String, val top: Int, val left: Int)

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
                    if (t.isEmpty()) null else OcrBlock(t, b.boundingBox?.top ?: 0, b.boundingBox?.left ?: 0)
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

    @Synchronized
    override fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }
}
