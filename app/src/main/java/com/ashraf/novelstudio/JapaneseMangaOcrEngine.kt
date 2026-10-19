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
        val root = JSONObject(out.json)
        val grouped = root.optJSONArray("bubbles")?.takeIf { it.length() > 0 }
        val regions = grouped ?: root.optJSONArray("regions") ?: return OcrResult(emptyList())
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
                // A grouped speech balloon is a horizontal translation surface even when its source text was vertical.
                vertical = grouped == null && r.optBoolean("vertical", false)
            )
            val shortJapaneseColumn = grouped != null && block.h > block.w * 6f && text.length <= 4 &&
                text.any { ch -> ch in '\u3040'..'\u30FF' || ch in '\u3400'..'\u9FFF' || ch in '\uFF66'..'\uFF9D' }
            if (!OcrPost.isNoise(block) || shortJapaneseColumn) blocks.add(block)
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

    private fun hasJapaneseEvidence(text: String): Boolean {
        if (text.isBlank()) return false
        val kana = text.count { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' || it in '\uFF66'..'\uFF9D' }
        if (kana > 0) return true
        // Some short manga balloons contain only kanji. Accept a mostly-CJK Japanese-recognizer
        // result as a hint too; model errors still fall back to the Japanese ML Kit output.
        val han = text.count { it in '\u3400'..'\u4DBF' || it in '\u4E00'..'\u9FFF' || it in '\uF900'..'\uFAFF' }
        val letters = text.count { it.isLetter() }
        return han >= 2 && letters > 0 && han * 100 >= letters * 55
    }

    private fun viaManga(bitmap: Bitmap, script: OcrScript): OcrResult? {
        if (!wantsManga(script)) return null
        return try {
            manga.recognize(bitmap, script).takeIf { it.blocks.isNotEmpty() }
        } catch (e: Exception) {
            Log.w("NovelStudioManga", "Manga model failed, using ML Kit: " + e.message)
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * On auto-detected/unknown readers, ML Kit's Japanese recognizer is a quick script probe.
     * Only a real Japanese/CJK signal activates the dedicated model; ordinary English/Korean
     * webtoons keep their normal OCR path. This works even when the site URL has no manga hint.
     */
    private fun autoDetectedManga(bitmap: Bitmap, script: OcrScript): OcrResult? {
        if (script != OcrScript.LATIN || !JapaneseMangaRuntime.isAvailable()) return null
        return try {
            val probe = general.recognizeFast(bitmap, OcrScript.JAPANESE)
            if (!hasJapaneseEvidence(probe.text)) null
            else viaManga(bitmap, OcrScript.JAPANESE) ?: probe
        } catch (e: Exception) {
            Log.d("NovelStudioManga", "Japanese auto-detection skipped: " + e.message)
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        viaManga(bitmap, script)
            ?: autoDetectedManga(bitmap, script)
            ?: general.recognize(bitmap, script)

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult =
        viaManga(bitmap, script)
            ?: autoDetectedManga(bitmap, script)
            ?: general.recognizeFast(bitmap, script)

    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult =
        general.recognizeFallback(bitmap, script)

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        viaManga(bitmap, script)
            ?: autoDetectedManga(bitmap, script)
            ?: general.recognizeRegion(bitmap, script)

    override fun close() {
        general.close()
        manga.close()
    }
}
