package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ashraf.novelstudio.japanesemanga.JapaneseMangaRuntime
import org.json.JSONObject

/**
 * Adapts the manga-trained detector/recognizer to [OcrEngine] and ensembles it with ML Kit.
 * Both recognizers use the same screenshot coordinate space; [OcrLineFusion] reconciles overlapping
 * readings and lets the second recognizer contribute lines the comic detector missed.
 */
internal class JapaneseMangaOcrEngine : OcrEngine {
    // A two-engine ensemble: the Manga-trained detector/recognizer is best for Japanese/Chinese art,
    // while ML Kit can recover ordinary or faint lines the comic detector misses. Korean uses the same
    // comic detector with its Hangul recognizer plus the Korean ML Kit model as a second opinion.
    private val supplementary = MlKitOcrEngine()

    private fun run(bitmap: Bitmap, script: OcrScript, fast: Boolean, region: Boolean = false): OcrResult {
        val out = JapaneseMangaRuntime.recognize(bitmap, script.name)
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
                vertical = r.optBoolean("vertical", false),
                confidence = r.optDouble("recConfidence", 0.0).toFloat().coerceIn(0f, 1f)
            )
            if (!OcrPost.isNoise(block)) blocks.add(block)
        }

        // Do not stop at the first useful output: reconcile both recognizers so missed text regions
        // can be recovered and overlapping readings can be resolved by geometry/text agreement.
        val second = when {
            region -> supplementary.recognizeRegion(bitmap, script)
            fast -> supplementary.recognizeFast(bitmap, script)
            else -> supplementary.recognize(bitmap, script)
        }
        OcrLineFusion.addAll(blocks, second.blocks)
        val verticalVotes = blocks.count { it.vertical }
        val verticalReading = script != OcrScript.LATIN && blocks.isNotEmpty() &&
            verticalVotes > blocks.size * 0.45
        val ordered = if (verticalReading) {
            blocks.sortedWith(compareByDescending<OcrBlock> { it.cx }.thenBy { it.cy })
        } else {
            blocks.sortedWith(compareBy<OcrBlock> { it.cy }.thenBy { it.cx })
        }
        return OcrResult(ordered)
    }

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        run(bitmap, script, fast = false)

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult =
        run(bitmap, script, fast = true)

    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult =
        run(bitmap, script, fast = false)

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult =
        run(bitmap, script, fast = false, region = true)

    override fun close() {
        supplementary.close()
    }
}
/**
 * The engine the scan pipeline actually uses. Japanese, Chinese and Korean pages use the specialized
 * comic detector with the appropriate recognizer when installed; other scripts or model failures use ML Kit,
 * so real-time scanning keeps working for all languages exactly as before.
 */
/** Short, user-visible name of the engine that produced the LAST OCR result (shown in the activity chip). */
internal object OcrEngineStatus {
    @Volatile var label: String = ""
    /** Last Paddle failure that made the router fall back to ML Kit (shown in the OCR models dialog). */
    @Volatile var lastError: String = ""
    @Volatile private var lastToast: String = ""

    @Volatile private var lastCopied: String = ""

    /** Copies the error to the clipboard once per distinct message, so it can be pasted into a bug report. */
    fun copyOnce(context: Context?, message: String) {
        if (context == null || message == lastCopied) return
        lastCopied = message
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("NovelStudio OCR error", message))
        } catch (_: Throwable) {}
    }

    /** Shows a long toast once per distinct message (so a failing scan loop does not spam the screen). */
    fun toastOnce(context: Context?, message: String) {
        if (context == null || message == lastToast) return
        lastToast = message
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        }
    }

    fun describe(selected: String, used: String, fellBack: Boolean): String = when {
        used.startsWith("mlkit(comic-model-unavailable") -> "ML Kit (Comic OCR model unavailable)"
        used.startsWith("mlkit(comic-script-unsupported") -> "ML Kit (Comic OCR supports Japanese/Chinese/Korean)"
        used.startsWith("mlkit(paddle-model-unavailable") -> "ML Kit (Paddle model unavailable)"
        used.startsWith("mlkit(legacy-script-unsupported") -> "ML Kit (legacy OCR doesn't support this script)"
        used.startsWith("mlkit(korean-by-design") -> "ML Kit (no Korean Paddle pack)"
        used == "paddle_korean" -> "Paddle Korean v5"
        used == "mlkit(empty-primary)" && selected == "manga" -> "ML Kit (Comic OCR found no text)"
        used == "mlkit(empty-primary)" -> "ML Kit (Paddle found no text)"
        used == "mlkit(oom)" && selected == "manga" -> "ML Kit (Comic OCR: low RAM)"
        used == "mlkit(oom)" -> "ML Kit (Paddle: low RAM)"
        used == "mlkit(link-error)" && selected == "manga" -> "ML Kit (Comic OCR runtime unavailable)"
        used == "mlkit(link-error)" -> "ML Kit (Paddle native lib missing)"
        used.startsWith("mlkit(error:") && selected == "manga" -> "ML Kit (Comic OCR error: " + used.removePrefix("mlkit(error:").removeSuffix(")") + ")"
        used.startsWith("mlkit(error:") -> "ML Kit (Paddle error: " + used.removePrefix("mlkit(error:").removeSuffix(")") + ")"
        used.startsWith("mlkit") && fellBack && selected == "manga" -> "ML Kit (Comic OCR fallback)"
        used.startsWith("mlkit") && fellBack && selected.startsWith("paddle") -> "ML Kit (Paddle fallback)"
        used.startsWith("mlkit") && fellBack -> "ML Kit (fallback)"
        used.startsWith("mlkit") -> "ML Kit"
        used == "paddle_small" -> "Paddle Small"
        used == "paddle_heavy" -> "Paddle Medium"
        used == "manga" -> "Multilingual Comic OCR"
        used == "rapid_onnx" -> "RapidOCR"
        else -> ""
    }
}

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
        // Initialize the installed comic-model manifest on every app process start; the Activity may
        // have been closed in a previous process, so relying on it to initialize the runtime breaks
        // automatic OCR after restarting NovelStudio.
        JapaneseMangaRuntime.init(context)
        val requested = Prefs.get(context, "ocrEngine", "mlkit")
        // Only record the new choice. The old engine is released by active() INSIDE OcrGate, i.e. never while an
        // inference is running (closing here, outside the gate, could free sessions under a running scan; and
        // waiting for the gate here while holding this monitor would deadlock with active()).
        if (requested != selectedMode) selectedMode = requested
    }

    private fun active(script: OcrScript): OcrEngine {
        val mode = selectedMode
        synchronized(this) {
            if (activeCustom != null && activeMode != mode) {
                try { activeCustom?.close() } catch (_: Exception) {}
                activeCustom = null
                activeMode = ""
            }
        }
        if (mode == "mlkit") return mlKit
        // The custom legacy ONNX recognizer is not reliable for upright vertical CJK lettering and
        // runs each detected box separately. Keep Korean/Japanese real-time scans on ML Kit instead.
        if (mode == "rapid_onnx" && (script == OcrScript.KOREAN || script == OcrScript.JAPANESE)) return mlKit
        if (mode == "manga") {
            val supported = script == OcrScript.JAPANESE ||
                script == OcrScript.CHINESE || script == OcrScript.KOREAN
            return if (supported && JapaneseMangaRuntime.isAvailable()) manga else mlKit
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
                    "paddle_small" -> PaddleProfileOcrEngine(context, "small")
                    "paddle_heavy" -> PaddleProfileOcrEngine(context, "heavy")
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
        val waited = OcrGate.Waited()
        val t0 = android.os.SystemClock.elapsedRealtime()
        var used = "?"
        var fellBack = false
        try {
            return OcrGate.run(waited) {
                val r = routeInner(bitmap, script, run) { engine, fb -> used = engine; fellBack = fb }
                r
            }
        } finally {
            OcrEngineStatus.label = OcrEngineStatus.describe(selectedMode, used, fellBack)
            // Diagnostic line: ids/timings only, never the recognised text.
            Log.i("NovelStudioOCR", "requestedEngine=$selectedMode language=${script.name} " +
                "activeEngine=$used fallbackUsed=$fellBack " +
                "job=${Thread.currentThread().name} gateWaitMs=${waited.ms} " +
                "totalMs=${android.os.SystemClock.elapsedRealtime() - t0} " +
                "image=${bitmap.width}x${bitmap.height}")
        }
    }

    private fun routeInner(
        bitmap: Bitmap, script: OcrScript, run: (OcrEngine) -> OcrResult,
        report: (String, Boolean) -> Unit
    ): OcrResult {
        val chosen = try {
            active(script)
        } catch (e: Exception) {
            Log.w("NovelStudioOCR", "Could not select ${selectedMode}: ${e.message}")
            mlKit
        } catch (_: OutOfMemoryError) {
            mlKit
        }
        if (chosen === mlKit) {
            val reason = when {
                selectedMode == "manga" && script !in setOf(OcrScript.JAPANESE, OcrScript.CHINESE, OcrScript.KOREAN) ->
                    "mlkit(comic-script-unsupported)"
                selectedMode == "manga" && !JapaneseMangaRuntime.isAvailable() ->
                    "mlkit(comic-model-unavailable)"
                selectedMode == "paddle_small" || selectedMode == "paddle_heavy" ->
                    "mlkit(paddle-model-unavailable)"
                selectedMode == "rapid_onnx" && script != OcrScript.LATIN ->
                    "mlkit(legacy-script-unsupported)"
                else -> "mlkit"
            }
            report(reason, selectedMode != "mlkit")
            return run(mlKit)
        }

        return try {
            val primary = run(chosen)
            if (useful(primary)) {
                // Label what REALLY read the page: Korean is Paddle only with the Korean pack, otherwise ML Kit.
                when ((chosen as? PaddleProfileOcrEngine)?.familyFor(script)) {
                    "mlkit-korean" -> report("mlkit(korean-by-design under $selectedMode)", true)
                    "paddle-ko" -> report("paddle_korean", false)
                    else -> report(selectedMode, false)
                }
                primary
            }
            else { report("mlkit(empty-primary)", true); run(mlKit) }
        } catch (e: Exception) {
            // "detection: OrtException – <first 70 chars of ONNX Runtime's message>" (no recognised text involved).
            val root = e.cause ?: e
            val stage = Regex("stage '([^']+)'").find(e.message ?: "")?.groupValues?.get(1)
            val detail = (root.message ?: "").replace(Regex("\\s+"), " ").trim().take(150)
            val why = (if (stage != null) "$stage: " else "") + root.javaClass.simpleName +
                (if (detail.isNotEmpty()) " – $detail" else "")
            val full = (if (stage != null) "$stage: " else "") + root.javaClass.simpleName + " – " +
                (root.message ?: "").replace(Regex("\\s+"), " ").trim().take(220)
            OcrEngineStatus.lastError = "$selectedMode ${script.name}: $full"
            val engineName = if (selectedMode == "manga") "Comic OCR" else "Paddle"
            OcrEngineStatus.toastOnce(appContext, "$engineName error → ML Kit\n$full")
            OcrEngineStatus.copyOnce(appContext, "NovelStudio $engineName error: $full")
            Log.w("NovelStudioOCR", "${selectedMode} failed ($why); falling back to ML Kit: ${e.message}", e)
            report("mlkit(error:$why)", true)
            run(mlKit)
        } catch (e: OutOfMemoryError) {
            Log.w("NovelStudioOCR", "${selectedMode} ran out of memory; falling back to ML Kit")
            report("mlkit(oom)", true)
            run(mlKit)
        } catch (e: LinkageError) {
            Log.w("NovelStudioOCR", "${selectedMode} native runtime unavailable; falling back to ML Kit: ${e.message}")
            report("mlkit(link-error)", true)
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

    /** Never frees an engine while it is reading: the release runs on its own thread, behind OcrGate. */
    override fun close() {
        Thread({
            try { OcrGate.run { closeNow() } } catch (_: Throwable) {}
        }, "ns-ocr-close").apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun closeNow() {
        try { activeCustom?.close() } catch (_: Exception) {}
        activeCustom = null
        activeMode = ""
        mlKit.close()
        manga.close()
    }
}
