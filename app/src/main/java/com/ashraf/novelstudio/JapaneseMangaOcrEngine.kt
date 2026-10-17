package com.ashraf.novelstudio

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
internal class RoutingOcrEngine(
    private val general: OcrEngine = MlKitOcrEngine(),
    private val manga: OcrEngine = JapaneseMangaOcrEngine()
) : OcrEngine {

    private fun wantsManga(script: OcrScript) =
        script == OcrScript.JAPANESE && JapaneseMangaRuntime.isAvailable()

    private fun viaManga(bitmap: Bitmap, script: OcrScript): OcrResult? {
        if (!wantsManga(script)) return null
        return try {
            manga.recognize(bitmap, script).takeIf { it.blocks.isNotEmpty() }
        } catch (e: Exception) {
            Log.w("NovelStudioManga", "Manga model failed, trying optional OCR fallback: " + e.message)
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun viaPaddle(bitmap: Bitmap, script: OcrScript): OcrResult? =
        MultilingualPaddleOcr.recognize(bitmap, script)?.takeIf { it.blocks.isNotEmpty() }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        viaManga(bitmap, script) ?: viaPaddle(bitmap, script) ?: general.recognize(bitmap, script)

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult =
        viaManga(bitmap, script) ?: viaPaddle(bitmap, script) ?: general.recognizeFast(bitmap, script)

    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult =
        general.recognizeFallback(bitmap, script)

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        viaManga(bitmap, script) ?: viaPaddle(bitmap, script) ?: general.recognizeRegion(bitmap, script)

    override fun close() {
        general.close()
        manga.close()
    }
}
