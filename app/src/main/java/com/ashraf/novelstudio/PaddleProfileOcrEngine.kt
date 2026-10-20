package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Language-aware profile adapter. PP-OCRv6 Small/Medium's published standard recognition dictionaries
 * are English/Chinese oriented. For Korean and Japanese, keep the actual language-specific PP-OCRv4
 * recognition packs already supported by NovelStudio, using ML Kit as the final fallback in RoutingOcrEngine.
 *
 * The read/write lock lets calls of the same language proceed concurrently where the underlying engine
 * permits it, while model-family switches wait until current OCR calls finish before releasing sessions.
 */
internal class PaddleProfileOcrEngine(
    context: Context,
    tier: String
) : OcrEngine {
    private val appContext = context.applicationContext
    private val paddleV6 = PaddleV6OcrEngine(appContext, tier)
    private val legacyLanguage = OnnxOcrEngine()
    private val lock = ReentrantReadWriteLock()
    @Volatile private var activeFamily: String = ""

    init {
        OcrModels.init(appContext)
        PaddleOcrModels.init(appContext)
    }

    private fun forLanguage(bitmap: Bitmap, script: OcrScript, call: (OcrEngine) -> OcrResult): OcrResult {
        val family = if (script == OcrScript.KOREAN || script == OcrScript.JAPANESE) "legacy-cjk" else "paddle-v6"
        val readLock = lock.readLock()
        val writeLock = lock.writeLock()
        // Acquire the write lock to switch model families; then downgrade to a read lock before releasing
        // it, so another language cannot close this engine in the middle of an inference call.
        writeLock.lock()
        try {
            if (activeFamily != family) {
                if (activeFamily == "paddle-v6") runCatching { paddleV6.close() }
                if (activeFamily == "legacy-cjk") runCatching { legacyLanguage.close() }
                activeFamily = family
            }
            readLock.lock()
        } finally {
            writeLock.unlock()
        }

        try {
            if (family == "legacy-cjk") {
                val pack = OcrModels.PACKS.firstOrNull { it.script == script }
                if (pack == null || !OcrModels.isInstalled(pack)) return OcrResult(emptyList())
                return call(legacyLanguage)
            }
            return call(paddleV6)
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
            runCatching { legacyLanguage.close() }
            activeFamily = ""
        } finally {
            writeLock.unlock()
        }
    }
}
