package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ashraf.novelstudio.japanesemanga.JapaneseMangaRuntime
import org.json.JSONObject

/**
 * Adapter between the downloaded manga model (see [JapaneseMangaRuntime]) and the app's [OcrEngine]
 * surface. Every region the model returns is one speech-bubble-sized block whose text is already in
 * Japanese reading order (vertical columns right-to-left), so nothing downstream has to re-order it.
 */
internal class JapaneseMangaOcrEngine : OcrEngine {
    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult {
        val out = JapaneseMangaRuntime.recognize(bitmap)
        val regions = JSONObject(out.json).optJSONArray("regions") ?: return OcrResult(emptyList())
        val k = if (out.scale > 0f) 1f / out.scale else 1f
        val blocks = ArrayList<OcrBlock>()
        for (i in 0 until regions.length()) {
            val r = regions.optJSONObject(i) ?: continue
            val text = r.optString("text").trim()
            if (text.isEmpty()) continue
            val x = r.optDouble("x", 0.0).toFloat() * k
            val y = r.optDouble("y", 0.0).toFloat() * k
            val w = r.optDouble("w", 0.0).toFloat() * k
            val h = r.optDouble("h", 0.0).toFloat() * k
            if (w < 4f || h < 4f) continue
            val font = r.optDouble("font", 0.0).toFloat() * k
            val block = OcrBlock(
                text = text,
                top = y.toInt(), left = x.toInt(),
                cx = x + w / 2f, cy = y + h / 2f,
                w = w, h = h, angle = 0f,
                fontPx = if (font > 0f) font else minOf(w, h),
                vertical = r.optBoolean("vertical", false)
            )
            if (!OcrPost.isNoise(block)) blocks.add(block)
        }
        return OcrResult(blocks)
    }

    override fun close() {}
}

/**
 * The engine the scan pipeline actually uses. Japanese pages go to the dedicated manga model when it
 * has been downloaded; everything else (and any manga-model failure or empty result) stays on ML Kit,
 * so real-time scanning keeps working for all languages exactly as before.
 */
internal class RoutingOcrEngine : OcrEngine {
    @Volatile private var appContext: Context? = null
    private val mlKit: OcrEngine = MlKitOcrEngine()
    private val manga: OcrEngine = JapaneseMangaOcrEngine()
    @Volatile private var selectedMode: String = "mlkit"
    private var activeMode: String = ""
    private var activeCustom: OcrEngine? = null

    /** Called by WebMode before the first OCR pass so the chosen engine follows the Settings value. */
    @Synchronized
    fun configure(context: Context) {
        appContext = context.applicationContext
        OcrModels.init(context)
        PaddleOcrModels.init(context)
        val requested = Prefs.get(context, "ocrEngine", "mlkit")
        if (requested != selectedMode) {
            try { activeCustom?.close() } catch (_: Exception) {}
            activeCustom = null
            activeMode = ""
            selectedMode = requested
        }
    }

    private fun active(script: OcrScript): OcrEngine {
        val mode = selectedMode
        if (mode == "mlkit") return mlKit
        if (mode == "manga") {
            return if (script == OcrScript.JAPANESE && JapaneseMangaRuntime.isAvailable()) manga else mlKit
        }
        if (mode == "paddle_small" || mode == "paddle_heavy") {
            val tier = if (mode == "paddle_small") "small" else "heavy"
            val modelPack = PaddleOcrModels.pack(tier)
            if (modelPack == null || !PaddleOcrModels.isInstalled(modelPack)) {
                // Do not attempt a doomed native model load on every scan. If a downloaded model
                // was deleted, release its session on the next OCR request to free the RAM.
                synchronized(this) {
                    if (activeMode == mode) {
                        try { activeCustom?.close() } catch (_: Exception) {}
                        activeCustom = null
                        activeMode = ""
                    }
                }
                return mlKit
            }
        }

        synchronized(this) {
            if (activeCustom == null || activeMode != mode) {
                try { activeCustom?.close() } catch (_: Exception) {}
                val context = appContext
                    ?: throw OcrUnavailableException("OCR engine has not been initialized")
                activeCustom = when (mode) {
                    "paddle_small" -> PaddleV6OcrEngine(context, "small")
                    "paddle_heavy" -> PaddleV6OcrEngine(context, "heavy")
                    "rapid_onnx" -> FallbackOcrEngine(OnnxOcrEngine(), MlKitOcrEngine())
                    else -> mlKit
                }
                activeMode = mode
            }
            return activeCustom ?: mlKit
        }
    }

    private fun useful(result: OcrResult): Boolean =
        result.blocks.any { it.text.count { ch -> ch.isLetterOrDigit() } >= 2 }

    private fun route(bitmap: Bitmap, script: OcrScript, run: (OcrEngine) -> OcrResult): OcrResult {
        val chosen = try {
            active(script)
        } catch (e: Exception) {
            Log.w("NovelStudioOCR", "Could not select ${selectedMode}: ${e.message}")
            mlKit
        } catch (_: OutOfMemoryError) {
            mlKit
        }
        if (chosen === mlKit) return run(mlKit)

        return try {
            val primary = run(chosen)
            if (useful(primary)) primary else run(mlKit)
        } catch (e: Exception) {
            Log.w("NovelStudioOCR", "${selectedMode} failed; falling back to ML Kit: ${e.message}")
            run(mlKit)
        } catch (e: OutOfMemoryError) {
            Log.w("NovelStudioOCR", "${selectedMode} ran out of memory; falling back to ML Kit")
            run(mlKit)
        } catch (e: LinkageError) {
            Log.w("NovelStudioOCR", "${selectedMode} native runtime unavailable; falling back to ML Kit: ${e.message}")
            run(mlKit)
        }
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script) { it.recognize(bitmap, script) }

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script) { it.recognizeFast(bitmap, script) }

    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script) { it.recognizeFallback(bitmap, script) }

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script) { it.recognizeRegion(bitmap, script) }

    @Synchronized
    override fun close() {
        try { activeCustom?.close() } catch (_: Exception) {}
        activeCustom = null
        activeMode = ""
        mlKit.close()
        manga.close()
    }
}
