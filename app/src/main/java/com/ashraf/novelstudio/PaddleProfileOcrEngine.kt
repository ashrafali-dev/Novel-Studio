package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

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
    @Volatile private var activeFamily: String = ""
    private var lastPaddleUseMs = 0L

    private companion object { const val PADDLE_IDLE_RELEASE_MS = 120_000L }

    init {
        PaddleOcrModels.init(appContext)
    }

    private fun forLanguage(bitmap: Bitmap, script: OcrScript, call: (OcrEngine) -> OcrResult): OcrResult {
        // Korean is deliberately NOT read by the downloaded PP-OCRv6 pack (see class comment). Say so in the log
        // every time, so "Korean is slow/wrong with the Paddle model" can be traced to the engine that really ran.
        val family = if (script == OcrScript.KOREAN) "mlkit-korean" else "paddle-v6"
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            // Every call arrives through OcrGate (RoutingOcrEngine.route), so no inference can be running here and
            // releasing is safe. The old code released the Paddle sessions on EVERY switch to Korean, so alternating
            // Korean/Japanese (Auto language, fast fallback scripts) reloaded up to ~140 MB each time. Now they are
            // released only after PADDLE_IDLE_RELEASE_MS without any Paddle use (RAM is freed during long Korean reads).
            val now = System.nanoTime() / 1_000_000L
            if (family == "paddle-v6") {
                lastPaddleUseMs = now
            } else if (lastPaddleUseMs != 0L && now - lastPaddleUseMs > PADDLE_IDLE_RELEASE_MS) {
                runCatching { paddleV6.close() }
                lastPaddleUseMs = 0L
                Log.i("NovelStudioOCR", "paddleProfile released idle Paddle sessions")
            }
            return if (family == "mlkit-korean") call(mlKitCjk) else call(paddleV6)
        } finally {
            if (activeFamily != family) {
                Log.i("NovelStudioOCR", "paddleProfile language=${script.name} activeFamily=$family " +
                    "(previous=${activeFamily.ifEmpty { "none" }})")
                activeFamily = family
            }
            Log.i("NovelStudioOCR", "paddleProfile language=${script.name} activeFamily=$family " +
                "ms=${android.os.SystemClock.elapsedRealtime() - t0}")
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
        runCatching { paddleV6.close() }
        runCatching { mlKitCjk.close() }
        activeFamily = ""
    }
}
