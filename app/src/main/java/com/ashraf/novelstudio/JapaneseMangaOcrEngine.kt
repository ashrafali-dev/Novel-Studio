package com.ashraf.novelstudio

import android.graphics.Bitmap
import android.util.Log
import com.ashraf.novelstudio.japanesemanga.JapaneseMangaRuntime
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Uses the dedicated Japanese recognizer only to re-read text crops already found by the app's
 * normal OCR. The source block's geometry, reading order, angle and position are never replaced.
 */
internal class JapaneseMangaOcrEngine {
    /**
     * Re-recognise a small list of existing text boxes. The model is deliberately not asked to
     * detect text or speech balloons here: ML Kit and the regular Web Mode pipeline own detection,
     * grouping, coordinates, text-removal geometry and Bengali overlay placement.
     */
    fun refine(bitmap: Bitmap, base: OcrResult, maxRegions: Int): OcrResult {
        if (base.blocks.isEmpty() || !JapaneseMangaRuntime.isAvailable()) return base
        return try {
            val payload = JSONArray()
            for ((index, block) in base.blocks.withIndex()) {
                if (payload.length() >= maxRegions) break
                if (block.w < 6f || block.h < 6f || abs(OcrPost.usableTilt(block.angle)) >= 8f) continue

                // Give the recognizer a little margin so thin Japanese strokes aren't cut off.
                val pad = (block.fontPx * 0.12f).toInt().coerceIn(2, 9)
                val left = floor(block.cx - block.w / 2f).toInt() - pad
                val top = floor(block.cy - block.h / 2f).toInt() - pad
                val right = ceil(block.cx + block.w / 2f).toInt() + pad
                val bottom = ceil(block.cy + block.h / 2f).toInt() + pad
                val x = left.coerceIn(0, bitmap.width - 1)
                val y = top.coerceIn(0, bitmap.height - 1)
                val r = right.coerceIn(x + 1, bitmap.width)
                val b = bottom.coerceIn(y + 1, bitmap.height)
                if (r - x < 6 || b - y < 6) continue

                payload.put(
                    JSONObject()
                        .put("index", index)
                        .put("x", x)
                        .put("y", y)
                        .put("w", r - x)
                        .put("h", b - y)
                        .put("vertical", block.vertical || block.h > block.w * 1.25f)
                )
            }
            if (payload.length() == 0) return base

            val root = JSONObject(JapaneseMangaRuntime.recognizeRegions(bitmap, payload.toString()).json)
            val results = root.optJSONArray("results") ?: return base
            val replacements = HashMap<Int, String>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val index = item.optInt("index", -1)
                if (index !in base.blocks.indices) continue
                val candidate = item.optString("text").replace(Regex("\\s+"), " ").trim()
                val original = base.blocks[index].text.trim()
                if (acceptableReplacement(original, candidate)) replacements[index] = candidate
            }
            if (replacements.isEmpty()) return base

            // Keep every original box, order, size and style exactly as detected by the app.
            OcrResult(base.blocks.mapIndexed { index, block ->
                replacements[index]?.let { block.copy(text = it) } ?: block
            })
        } catch (e: Exception) {
            Log.w("NovelStudioManga", "Text-only manga recognition failed; preserving default OCR", e)
            base
        } catch (_: OutOfMemoryError) {
            base
        }
    }

    private fun acceptableReplacement(original: String, candidate: String): Boolean {
        if (candidate.isBlank() || candidate.length > 1200 || '\uFFFD' in candidate) return false
        // This model is for Japanese manga text, not English-only lettering or page watermarks.
        if (!containsJapaneseScript(candidate)) return false
        val oldMeaningful = original.count { it.isLetterOrDigit() }
        val newMeaningful = candidate.count { it.isLetterOrDigit() }
        if (oldMeaningful >= 4 && newMeaningful < max(1, oldMeaningful / 3)) return false
        if (oldMeaningful >= 4 && newMeaningful > oldMeaningful * 3 + 5) return false
        return true
    }

    private fun containsJapaneseScript(text: String): Boolean = text.any { ch ->
        ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' ||
            ch in '\u31F0'..'\u31FF' || ch in '\u3400'..'\u4DBF' ||
            ch in '\u4E00'..'\u9FFF' || ch in '\uF900'..'\uFAFF' ||
            ch in '\uFF66'..'\uFF9D'
    }
}

/**
 * Keep the normal on-device detector/layout pipeline in charge. When Japanese is selected or
 * confidently found by the existing recognizer, the optional PP-OCRv6 model only refines text
 * inside the already-detected boxes. If it is missing, slow, invalid or out of memory, the original
 * ML Kit result survives unchanged.
 */
internal class RoutingOcrEngine(
    private val general: OcrEngine = MlKitOcrEngine(),
    private val manga: JapaneseMangaOcrEngine = JapaneseMangaOcrEngine()
) : OcrEngine {
    private fun hasJapaneseEvidence(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' || it in '\uFF66'..'\uFF9D' }) return true
        val han = text.count { it in '\u3400'..'\u4DBF' || it in '\u4E00'..'\u9FFF' || it in '\uF900'..'\uFAFF' }
        val letters = text.count { it.isLetter() }
        return han >= 2 && letters > 0 && han * 100 >= letters * 55
    }

    private fun route(
        bitmap: Bitmap,
        script: OcrScript,
        fast: Boolean,
        region: Boolean = false
    ): OcrResult {
        val available = JapaneseMangaRuntime.isAvailable()
        val limit = if (fast) FAST_REFINEMENT_LIMIT else FULL_REFINEMENT_LIMIT
        if (script == OcrScript.JAPANESE && available) {
            val base = when {
                region -> general.recognizeRegion(bitmap, OcrScript.JAPANESE)
                fast -> general.recognizeFast(bitmap, OcrScript.JAPANESE)
                else -> general.recognize(bitmap, OcrScript.JAPANESE)
            }
            return manga.refine(bitmap, base, limit)
        }

        // Unknown/auto-detected panels may initially be classified as Latin. Only perform the
        // extra Japanese probe when the user has installed the optional model; other panels pay no cost.
        if (script == OcrScript.LATIN && available) {
            val probe = try {
                general.recognizeFast(bitmap, OcrScript.JAPANESE)
            } catch (e: Exception) {
                Log.d("NovelStudioManga", "Japanese script probe skipped: " + e.message)
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            if (probe != null && hasJapaneseEvidence(probe.text)) {
                return manga.refine(bitmap, probe, limit)
            }
        }

        return when {
            region -> general.recognizeRegion(bitmap, script)
            fast -> general.recognizeFast(bitmap, script)
            else -> general.recognize(bitmap, script)
        }
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script, fast = false)

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script, fast = true)

    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult =
        general.recognizeFallback(bitmap, script)

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        route(bitmap, script, fast = true, region = true)

    override fun close() {
        general.close()
    }

    companion object {
        // Bound sequential WASM inference on mid-range phones; default OCR text/boxes are never lost.
        private const val FAST_REFINEMENT_LIMIT = 24
        private const val FULL_REFINEMENT_LIMIT = 64
    }
}
