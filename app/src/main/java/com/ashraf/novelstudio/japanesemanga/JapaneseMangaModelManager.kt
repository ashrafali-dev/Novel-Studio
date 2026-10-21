package com.ashraf.novelstudio.japanesemanga

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns only optional manga-engine files under app-private storage. */
internal class JapaneseMangaModelManager(context: Context) {
    val root = File(context.applicationContext.filesDir, "japanese_manga_engine")
    private val marker = File(root, "engine_manifest.json")
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicReference<HttpURLConnection?>(null)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "japanese-manga-models").apply { isDaemon = true } }
    @Volatile private var busy = false
    @Volatile private var installedCacheCheckedAt = 0L
    @Volatile private var installedCacheValue = false

    data class Progress(val index: Int, val count: Int, val name: String, val read: Long, val total: Long)
    sealed class Result {
        object Success : Result()
        object Cancelled : Result()
        data class Failure(val message: String) : Result()
    }
    private data class Asset(val path: String, val url: String, val min: Long, val max: Long, val sha256: String? = null)

    fun isDownloading() = busy
    fun hasFiles(): Boolean = root.exists() && (root.list()?.isNotEmpty() == true)
    fun packageBytes(): Long = SPECS.sumOf { safeFile(it.path)?.takeIf { file -> file.isFile }?.length() ?: 0L }

    @Synchronized fun isInstalled(): Boolean {
        val now = SystemClock.elapsedRealtime()
        // Hash the downloaded bundle once per process lifetime (or after explicit install/delete invalidation).
        // Routing queries this frequently during realtime OCR; repeated multi-file hashing causes stalls and heat.
        if (installedCacheCheckedAt > 0L) return installedCacheValue
        installedCacheValue = try {
            if (!marker.isFile) false else {
                val meta = JSONObject(marker.readText())
                val installedFiles = meta.optJSONObject("files")
                installedFiles != null &&
                    meta.optString("version") == VERSION && meta.optBoolean("ready") &&
                    SPECS.all { s ->
                        val file = safeFile(s.path)
                        val entry = installedFiles.optJSONObject(s.path)
                        file?.let {
                            val savedHash = entry?.optString("sha256").orEmpty()
                            it.isFile && it.length() in s.min..s.max &&
                                entry != null && entry.optLong("bytes", -1L) == it.length() &&
                                savedHash.matches(Regex("[0-9a-fA-F]{64}")) &&
                                sha256(it).equals(savedHash, ignoreCase = true) &&
                                (s.sha256 == null || savedHash.equals(s.sha256, ignoreCase = true))
                        } == true
                    }
            }
        } catch (_: Exception) { false }
        installedCacheCheckedAt = now
        return installedCacheValue
    }

    internal fun invalidateInstalledCache() {
        installedCacheValue = false
        installedCacheCheckedAt = 0L
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
                if (!root.exists() && !root.mkdirs()) error("ইঞ্জিন ফোল্ডার তৈরি করা যায়নি")
                marker.delete()
                invalidateInstalledCache()
                // Reinstall is a clean transaction. A previous model-pack version may contain a
                // same-named but incompatible dictionary; never reuse files just because their size fits.
                SPECS.forEach { spec ->
                    val oldTarget = safeFile(spec.path) ?: error("অবৈধ ফাইল পাথ")
                    if (oldTarget.exists() && !oldTarget.delete()) error("পুরোনো মডেল ফাইল মুছতে পারিনি · " + spec.path)
                    File(oldTarget.parentFile, oldTarget.name + ".part").delete()
                }
                SPECS.forEachIndexed { i, spec ->
                    checkCancel()
                    val target = safeFile(spec.path) ?: error("অবৈধ ফাইল পাথ")
                    if (target.isFile && validAsset(target, spec)) {
                        onProgress(Progress(i + 1, SPECS.size, spec.path, target.length(), target.length()))
                    } else {
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("ফোল্ডার তৈরি করা যায়নি") }
                        target.delete()
                        val part = File(target.parentFile, target.name + ".part")
                        part.delete()
                        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20000; readTimeout = 45000; instanceFollowRedirects = true
                            setRequestProperty("User-Agent", "NovelStudio-JapaneseMangaEngine/1")
                        }
                        active.set(connection)
                        try {
                            val code = connection.responseCode
                            if (code !in 200..299) error("HTTP " + code + " · " + spec.path)
                            val total = connection.contentLengthLong
                            if (total > 0 && total !in spec.min..spec.max) error("অপ্রত্যাশিত ফাইল সাইজ · " + spec.path)
                            BufferedInputStream(connection.inputStream).use { input ->
                                FileOutputStream(part).use { output ->
                                    val buffer = ByteArray(32768)
                                    var readTotal = 0L
                                    while (true) {
                                        checkCancel()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        readTotal += n
                                        if (readTotal > spec.max) error("ফাইলের সাইজ বেশি · " + spec.path)
                                        output.write(buffer, 0, n)
                                        onProgress(Progress(i + 1, SPECS.size, spec.path, readTotal, total))
                                    }
                                    if (total > 0 && readTotal != total) error("অসম্পূর্ণ ডাউনলোড · " + spec.path)
                                }
                            }
                        } finally {
                            active.compareAndSet(connection, null)
                            connection.disconnect()
                        }
                        checkCancel()
                        if (!validAsset(part, spec)) error("মডেল/ডিকশনারি যাচাই ব্যর্থ · " + spec.path)
                        if (!part.renameTo(target)) error("ফাইল সংরক্ষণ ব্যর্থ · " + spec.path)
                    }
                }
                checkCancel()
                val files = JSONObject()
                SPECS.forEach { spec ->
                    val f = safeFile(spec.path) ?: error("অবৈধ ফাইল পাথ")
                    if (!f.isFile || !validAsset(f, spec)) error("মডেল/ডিকশনারি যাচাই ব্যর্থ · " + spec.path)
                    files.put(spec.path, JSONObject().put("bytes", f.length()).put("sha256", sha256(f)))
                }
                val temporary = File(root, "engine_manifest.part")
                temporary.writeText(JSONObject().put("ready", true).put("version", VERSION)
                    .put("runtime", ORT_VERSION).put("license", "PP-OCRv6_manga (Japanese/Chinese): Apache-2.0; PP-OCRv5 Korean recognizer: Apache-2.0; ONNX Runtime Web: MIT")
                    .put("files", files).toString(2))
                if (!temporary.renameTo(marker)) error("ইনস্টলেশন মার্কার লেখা যায়নি")
                // A UI status query may have cached "not installed" while assets were downloading.
                // Force a fresh hash/manifest verification after the ready marker is committed.
                invalidateInstalledCache()
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
        invalidateInstalledCache()
        return !root.exists() || root.deleteRecursively()
    }

    fun resolve(path: String): File? = safeFile(path)

    private fun safeFile(path: String): File? {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") ||
            path.split("/").any { it == ".." }) return null
        val base = root.canonicalFile
        val candidate = File(base, path).canonicalFile
        return candidate.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun cleanupPartial() {
        invalidateInstalledCache()
        SPECS.forEach { s -> safeFile(s.path)?.let { File(it.parentFile, it.name + ".part").delete() } }
        File(root, "engine_manifest.part").delete()
        marker.delete()
    }

    private fun checkCancel() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancelledException() }
    private class CancelledException : Exception()

    private fun validAsset(file: File, asset: Asset): Boolean {
        if (!file.isFile || file.length() !in asset.min..asset.max) return false
        val expected = asset.sha256 ?: return true
        return runCatching { sha256(file).equals(expected, ignoreCase = true) }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val VERSION = "pp-ocrv6-multilingual-comic-v7"
        const val ORT_VERSION = "1.23.2"
        private const val HF = "https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/ba1d479e8a61a20e8318c9758c73fbbbd290b98d/"
        private const val ORT = "https://unpkg.com/onnxruntime-web@1.23.2/dist/"
        private val SPECS = listOf(
            Asset("ort.wasm.min.js", ORT + "ort.wasm.min.js", 10000, 200000),
            Asset("ort-wasm-simd.wasm", ORT + "ort-wasm-simd.wasm", 5000000, 40000000),
            Asset("ort-wasm-simd.mjs", ORT + "ort-wasm-simd.mjs", 5000, 200000),
            Asset("det/manga_det_v0.2.onnx", HF + "det/manga_det_v0.2.onnx", 500000, 8000000),
            Asset("rec/manga_rec_v0.2.onnx", HF + "rec/manga_rec_v0.2.onnx", 10000000, 30000000),
            Asset("ppocrv6_dict.txt", HF + "ppocrv6_dict.txt", 10000, 1000000),
            // Korean uses the comic detector with a script-specific recognition model and dictionary.
            Asset("rec/korean_PP-OCRv5_mobile_rec.onnx", "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/5c6f574b8e2230adf4287b33e736d71b9fabd28e/inference.onnx", 1000000, 40000000),
            Asset("korean_dict.txt", "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/ppocr/utils/dict/ppocrv5_korean_dict.txt", 10000, 2000000)
        )
    }
}