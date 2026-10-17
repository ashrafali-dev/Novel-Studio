package com.ashraf.novelstudio.japanesemanga

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ashraf.novelstudio.OcrBlock
import com.ashraf.novelstudio.OcrPost
import com.ashraf.novelstudio.OcrResult
import com.ashraf.novelstudio.OcrScript
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional multilingual PaddleOCR pack. Models/runtime are downloaded only after the user opts in,
 * into the same private root as the Japanese engine but under a separate "paddle/" subtree.
 * This separation means deleting either engine does not delete the other.
 */
internal class MultilingualPaddleModelManager(context: Context) {
    val root = File(context.applicationContext.filesDir, "japanese_manga_engine")
    private val base = File(root, "paddle")
    private val marker = File(base, "paddle_manifest.json")
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicReference<HttpURLConnection?>(null)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "multilingual-paddle-models").apply { isDaemon = true } }
    @Volatile private var busy = false

    data class Progress(val index: Int, val count: Int, val name: String, val read: Long, val total: Long)
    sealed class Result {
        object Success : Result()
        object Cancelled : Result()
        data class Failure(val message: String) : Result()
    }
    private data class Asset(val path: String, val url: String, val min: Long, val max: Long)

    fun isDownloading() = busy
    fun hasFiles(): Boolean = SPECS.any { it.path.startsWith("paddle/") && safeFile(it.path)?.exists() == true }
    fun packageBytes(): Long = SPECS.sumOf { safeFile(it.path)?.takeIf { file -> file.isFile }?.length() ?: 0L }

    @Synchronized fun isInstalled(): Boolean {
        if (!marker.isFile) return false
        return try {
            val meta = JSONObject(marker.readText())
            meta.optString("version") == VERSION && meta.optBoolean("ready") &&
                SPECS.all { spec -> safeFile(spec.path)?.let { it.isFile && it.length() in spec.min..spec.max } == true }
        } catch (_: Exception) { false }
    }

    fun cancel() {
        cancelled.set(true)
        active.getAndSet(null)?.disconnect()
    }

    fun shutdown() { cancel(); worker.shutdownNow() }

    fun download(onProgress: (Progress) -> Unit, onComplete: (Result) -> Unit) {
        synchronized(this) {
            if (busy) { onComplete(Result.Failure("ডাউনলোড ইতিমধ্যে চলছে")); return }
            busy = true
            cancelled.set(false)
        }
        worker.execute {
            var result: Result = Result.Success
            try {
                if (!base.exists() && !base.mkdirs()) error("OCR মডেল ফোল্ডার তৈরি করা যায়নি")
                marker.delete()
                SPECS.forEachIndexed { index, spec ->
                    checkCancel()
                    val target = safeFile(spec.path) ?: error("অবৈধ মডেল পাথ")
                    if (target.isFile && target.length() in spec.min..spec.max) {
                        onProgress(Progress(index + 1, SPECS.size, spec.path.removePrefix("paddle/"), target.length(), target.length()))
                    } else {
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("মডেল ফোল্ডার তৈরি করা যায়নি") }
                        target.delete()
                        val part = File(target.parentFile, target.name + ".part")
                        part.delete()
                        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20000
                            readTimeout = 60000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", "NovelStudio-MultilingualPaddleOCR/1")
                        }
                        active.set(connection)
                        try {
                            val code = connection.responseCode
                            if (code !in 200..299) error("HTTP $code · ${spec.path}")
                            val total = connection.contentLengthLong
                            if (total > 0 && total !in spec.min..spec.max) error("অপ্রত্যাশিত ফাইল সাইজ · ${spec.path}")
                            BufferedInputStream(connection.inputStream).use { input ->
                                FileOutputStream(part).use { output ->
                                    val buffer = ByteArray(32768)
                                    var readTotal = 0L
                                    while (true) {
                                        checkCancel()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        readTotal += n
                                        if (readTotal > spec.max) error("ফাইলের সাইজ বেশি · ${spec.path}")
                                        output.write(buffer, 0, n)
                                        onProgress(Progress(index + 1, SPECS.size, spec.path.removePrefix("paddle/"), readTotal, total))
                                    }
                                    if (total > 0 && readTotal != total) error("অসম্পূর্ণ ডাউনলোড · ${spec.path}")
                                    output.fd.sync()
                                }
                            }
                        } finally {
                            active.compareAndSet(connection, null)
                            connection.disconnect()
                        }
                        checkCancel()
                        if (part.length() !in spec.min..spec.max) error("ফাইল যাচাই ব্যর্থ · ${spec.path}")
                        if (!part.renameTo(target)) error("ফাইল সংরক্ষণ ব্যর্থ · ${spec.path}")
                    }
                }
                checkCancel()
                val files = JSONObject()
                SPECS.forEach { spec ->
                    val f = safeFile(spec.path) ?: error("অবৈধ ফাইল পাথ")
                    if (!f.isFile || f.length() !in spec.min..spec.max) error("ফাইল যাচাই ব্যর্থ · ${spec.path}")
                    files.put(spec.path, JSONObject().put("bytes", f.length()).put("sha256", sha256(f)))
                }
                val temporary = File(base, "paddle_manifest.part")
                temporary.writeText(JSONObject().put("ready", true).put("version", VERSION)
                    .put("license", "PaddleOCR models: Apache-2.0; ONNX Runtime Web: MIT")
                    .put("files", files).toString(2))
                if (!temporary.renameTo(marker)) error("ইনস্টলেশন মার্কার লেখা যায়নি")
                if (!isInstalled()) error("ইনস্টলেশনের শেষ যাচাই ব্যর্থ")
            } catch (_: CancelledException) {
                result = Result.Cancelled
                cleanupPartial()
            } catch (e: Exception) {
                result = if (cancelled.get()) Result.Cancelled else Result.Failure(e.message ?: "ডাউনলোড ব্যর্থ")
                cleanupPartial()
            } finally {
                active.getAndSet(null)?.disconnect()
                synchronized(this) { busy = false }
                onComplete(result)
            }
        }
    }

    @Synchronized fun deleteEngine(): Boolean {
        if (busy) return false
        marker.delete()
        SPECS.filter { it.path.startsWith("paddle/") }.forEach { spec ->
            safeFile(spec.path)?.let { file ->
                file.delete()
                File(file.parentFile, file.name + ".part").delete()
            }
        }
        File(base, "paddle_manifest.part").delete()
        // The WASM files are shared with the Japanese engine. Only remove them if its model pack
        // is not installed, so removing one optional pack cannot break the other.
        if (!File(root, "engine_manifest.json").isFile) {
            SPECS.filter { !it.path.startsWith("paddle/") }.forEach { spec ->
                safeFile(spec.path)?.let { file ->
                    file.delete()
                    File(file.parentFile, file.name + ".part").delete()
                }
            }
        }
        base.walkBottomUp().filter { it.isDirectory && it != base }.forEach { runCatching { it.delete() } }
        return !marker.exists() && SPECS.none { it.path.startsWith("paddle/") && safeFile(it.path)?.exists() == true }
    }

    fun resolve(path: String): File? = safeFile(path)

    private fun safeFile(path: String): File? {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") ||
            path.split("/").any { it == ".." }) return null
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, path).canonicalFile
        return candidate.takeIf { it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    private fun cleanupPartial() {
        SPECS.filter { it.path.startsWith("paddle/") }.forEach { spec ->
            safeFile(spec.path)?.let { File(it.parentFile, it.name + ".part").delete() }
        }
        File(base, "paddle_manifest.part").delete()
        marker.delete()
        if (!File(root, "engine_manifest.json").isFile) {
            SPECS.filter { !it.path.startsWith("paddle/") }.forEach { spec ->
                safeFile(spec.path)?.let { File(it.parentFile, it.name + ".part").delete() }
            }
        }
    }

    private fun checkCancel() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancelledException()
    }
    private class CancelledException : Exception()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val VERSION = "ppocrv6-multilingual-v1"
        private const val ORT = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.23.2/dist/"
        private const val HF = "https://huggingface.co/"
        private const val PADDLE_DICT = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/dict/"
        private val SPECS = listOf(
            Asset("ort.wasm.min.js", ORT + "ort.wasm.min.js", 10000, 500000),
            Asset("ort-wasm-simd-threaded.wasm", ORT + "ort-wasm-simd-threaded.wasm", 5000000, 40000000),
            Asset("ort-wasm-simd-threaded.mjs", ORT + "ort-wasm-simd-threaded.mjs", 5000, 500000),
            Asset("paddle/models/detection/ppocrv6_small_det.onnx", HF + "PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx", 8000000, 15000000),
            Asset("paddle/models/ocr/ppocrv6_small_rec.onnx", HF + "PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx", 18000000, 27000000),
            Asset("paddle/models/ocr/korean_ppocrv5_rec.onnx", HF + "PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx", 12000000, 18000000),
            Asset("paddle/dicts/ppocrv6_dict.txt", PADDLE_DICT + "ppocrv6_dict.txt", 60000, 150000),
            Asset("paddle/dicts/korean_dict.txt", PADDLE_DICT + "korean_dict.txt", 5000, 150000)
        )
    }
}

/**
 * Safe adapter used by RoutingOcrEngine. A missing/broken optional model returns null so ML Kit
 * continues to work without waiting or forcing the user to download models.
 */
internal object MultilingualPaddleOcr {
    @Volatile private var manager: MultilingualPaddleModelManager? = null
    @Volatile private var context: Context? = null

    @Synchronized fun init(context: Context) {
        val app = context.applicationContext
        if (this.context === app && manager != null) return
        this.context = app
        manager = MultilingualPaddleModelManager(app)
    }

    fun isAvailable(): Boolean = try { manager?.isInstalled() == true } catch (_: Exception) { false }

    fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult? {
        if (script !in setOf(OcrScript.LATIN, OcrScript.CHINESE, OcrScript.JAPANESE, OcrScript.KOREAN)) return null
        if (!isAvailable()) return null
        return try {
            val lang = when (script) {
                OcrScript.KOREAN -> "korean"
                OcrScript.CHINESE -> "chinese"
                OcrScript.LATIN -> "latin"
                // The general model is the same; the language tag selects manga-style right-to-left ordering.
                OcrScript.JAPANESE -> "chinese"
            }
            val output = JapaneseMangaRuntime.recognizePaddle(bitmap, lang)
            val regions = JSONObject(output.json).optJSONArray("regions") ?: return null
            val scale = if (output.scale > 0f) 1f / output.scale else 1f
            val blocks = ArrayList<OcrBlock>()
            for (i in 0 until regions.length()) {
                val region = regions.optJSONObject(i) ?: continue
                val text = region.optString("text").trim()
                if (text.isEmpty()) continue
                val x = region.optDouble("x", 0.0).toFloat() * scale
                val y = region.optDouble("y", 0.0).toFloat() * scale
                val w = region.optDouble("w", 0.0).toFloat() * scale
                val h = region.optDouble("h", 0.0).toFloat() * scale
                if (w < 4f || h < 4f) continue
                val block = OcrBlock(
                    text = text, top = y.toInt(), left = x.toInt(),
                    cx = x + w / 2f, cy = y + h / 2f, w = w, h = h,
                    angle = 0f, fontPx = minOf(w, h), vertical = false
                )
                if (!OcrPost.isNoise(block)) blocks.add(block)
            }
            OcrResult(OcrPost.mergeNeighbours(blocks))
        } catch (e: Exception) {
            Log.w("NovelStudioPaddle", "Optional Paddle OCR failed; falling back to ML Kit", e)
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
