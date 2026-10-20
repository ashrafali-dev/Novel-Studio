package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Routes PP-OCRv6 Small/Medium to scripts covered by the downloaded PP-OCRv6 pack.
 *
 * PP-OCRv6 officially supports Japanese, so Japanese must use the selected Small/Medium model.
 * Its published language list does not include Korean, so Korean uses ML Kit's Korean recognizer.
 * The old custom ONNX path rotated tall crops and ran one inference per detected box; that was both
 * unreliable for upright vertical manga lettering and painfully slow on long webtoon panels.
 * The standalone RapidOCR/legacy option remains separate for explicit testing.
 */
internal class PaddleProfileOcrEngine(
    context: Context,
    tier: String
) : OcrEngine {
    private val appContext = context.applicationContext
    private val paddleV6 = PaddleV6OcrEngine(appContext, tier)
    private val mlKitCjk = MlKitOcrEngine()
    private val lock = ReentrantReadWriteLock()
    @Volatile private var activeFamily: String = ""

    init {
        PaddleOcrModels.init(appContext)
    }

    private fun forLanguage(bitmap: Bitmap, script: OcrScript, call: (OcrEngine) -> OcrResult): OcrResult {
        val family = if (script == OcrScript.KOREAN) "mlkit-korean" else "paddle-v6"
        val readLock = lock.readLock()
        val writeLock = lock.writeLock()

        // Switch model families only when no inference is active; then downgrade to a read lock.
        writeLock.lock()
        try {
            if (activeFamily != family) {
                if (activeFamily == "paddle-v6") runCatching { paddleV6.close() }
                if (activeFamily == "mlkit-korean") runCatching { mlKitCjk.close() }
                activeFamily = family
            }
            readLock.lock()
        } finally {
            writeLock.unlock()
        }

        try {
            return if (family == "mlkit-korean") call(mlKitCjk) else call(paddleV6)
        } finally {
            readLock.unlock()
        }
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        forLanguage(bitmap, script) { it.recognize(bitmap, script) }

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult =
        forLanguage(bitmap, script) { it.recognizeFast(bitmap, script) }

    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult =
        forLanguage(bitmap, script) { it.recognizeFallback(bitmap, script) }

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        forLanguage(bitmap, script) { it.recognizeRegion(bitmap, script) }

    override fun close() {
        val writeLock = lock.writeLock()
        writeLock.lock()
        try {
            runCatching { paddleV6.close() }
            runCatching { mlKitCjk.close() }
            activeFamily = ""
        } finally {
            writeLock.unlock()
        }
    }
}
