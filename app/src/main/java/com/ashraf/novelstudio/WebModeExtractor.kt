package com.ashraf.novelstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class WebModeError(val label: String) {
    NO_IMAGES("No chapter images found on this page"),
    IMAGES_NOT_LOADED("Chapter images were found but could not be loaded"),
    DECODE_FAILED("Chapter images could not be decoded"),
    OCR_EMPTY("OCR found no text in the chapter images"),
    OCR_MODEL_UNAVAILABLE("The OCR model is unavailable"),
    CHATBOT_SUBMIT_FAILED("Chatbot submission failed"),
    CHATBOT_INCOMPLETE("Chatbot response incomplete"),
    NAVIGATION_INVALIDATED("Chapter navigation invalidated the job"),
    TIMEOUT("Web Mode timed out")
}

/** Internal counters for one Web Mode job. Never holds cookies, keys or page text. */
internal class WebModeDiagnostics(val jobId: Int, val host: String) {
    @Volatile var discovered = 0
    @Volatile var duplicates = 0
    @Volatile var downloaded = 0
    @Volatile var decoded = 0
    @Volatile var ocrOk = 0
    @Volatile var ocrEmpty = 0
    @Volatile var failed = 0
    @Volatile var chars = 0
    @Volatile var cancelled = false
    @Volatile var timedOut = false
    @Volatile var error: WebModeError? = null
    fun summary(): String =
        "job=$jobId host=$host discovered=$discovered dup=$duplicates downloaded=$downloaded decoded=$decoded " +
            "ocrOk=$ocrOk ocrEmpty=$ocrEmpty failed=$failed chars=$chars cancelled=$cancelled " +
            "timedOut=$timedOut error=${error?.name ?: "none"}"
}

internal sealed class WebModeOutcome {
    class Success(val chapter: Chapter, val diag: WebModeDiagnostics, val failedPanels: Int) : WebModeOutcome()
    class Failure(val error: WebModeError, val message: String, val diag: WebModeDiagnostics) : WebModeOutcome()
}

internal class WebModeRequest(
    val webView: WebView,
    /** When true, OCR only images intersecting the current viewport and never scrolls the reader. */
    val visibleOnly: Boolean = false,
    /** False once the user navigated away / started another action: the job stops and reports nothing useful. */
    val isValid: () -> Boolean,
    /** Force extract: do not download images; OCR what the WebView actually shows while it is scrolled. */
    val force: Boolean = false,
    val onProgress: (stage: String, pct: Int) -> Unit,
    val onDone: (WebModeOutcome) -> Unit
)

/**
 * OCR-based chapter extraction for image chapters (Webtoon-style readers).
 * Reads the images of the page that is ALREADY loaded in [WebModeRequest.webView], so the real
 * session, lazy loaders and cookies are used. Produces an ordinary [Chapter]; the existing
 * chatbot pipeline takes it from there.
 */
internal class WebModeExtractor(
    private val handler: Handler,
    private val userAgent: String,
    private val engine: OcrEngine = MlKitOcrEngine()
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ns-webmode") }
    private val jobIds = AtomicInteger(0)
    private val bridge = PageBridge()
    private val bridged = java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>())

    /** Returns a handle; call [Handle.cancel] to stop the job. Must be called on the main thread. */
    fun start(req: WebModeRequest): Handle {
        val job = Job(req, jobIds.incrementAndGet())
        job.begin()
        return job
    }

    interface Handle { fun cancel() }

    fun shutdown() {
        worker.shutdownNow()
        engine.close()
    }

    // ---------------------------------------------------------------- page bridge (blob:/data:/CORS fetches)
    internal class Pending { val latch = CountDownLatch(1); @Volatile var data: String? = null; @Volatile var err: String? = null }

    internal inner class PageBridge {
        val pending = ConcurrentHashMap<String, Pending>()

        @JavascriptInterface
        fun ok(id: String, dataUrl: String) {
            pending[id]?.let { it.data = dataUrl; it.latch.countDown() }
        }

        @JavascriptInterface
        fun fail(id: String, msg: String) {
            pending[id]?.let { it.err = msg.take(120); it.latch.countDown() }
        }
    }

    private fun ensureBridge(wv: WebView) {
        if (bridged.add(wv)) wv.addJavascriptInterface(bridge, "NsWmBridge")
    }

    // ---------------------------------------------------------------- job
    private inner class Job(val req: WebModeRequest, val id: Int) : Handle {
        private val cancelled = AtomicBoolean(false)
        private val startedAt = SystemClock.elapsedRealtime()
        private val wv = req.webView
        val diag = WebModeDiagnostics(id, hostOf(wv.url.orEmpty()))

        private fun alive() = !cancelled.get() && req.isValid()
        private fun elapsed() = SystemClock.elapsedRealtime() - startedAt
        private fun onMain(block: () -> Unit) { handler.post(block) }

        override fun cancel() { cancelled.set(true); diag.cancelled = true }

        private fun progress(stage: String, pct: Int) = onMain {
            if (alive()) req.onProgress(stage, pct.coerceIn(0, 99))
        }

        @Volatile private var fellBackToForce = false

        private fun fail(e: WebModeError, msg: String = e.label) {
            // Live (real-time) OCR always tries the normal way first. Only when the images cannot be
            // downloaded/decoded does Force Extract (screen OCR) take over, once.
            if (req.force && req.visibleOnly && !fellBackToForce &&
                (e == WebModeError.IMAGES_NOT_LOADED || e == WebModeError.DECODE_FAILED)) {
                fellBackToForce = true
                diag.error = null
                worker.execute { runCatching { processForce() }.onFailure {
                    fail(WebModeError.DECODE_FAILED, "Force extract crashed: ${it.javaClass.simpleName}")
                } }
                return
            }
            diag.error = e
            Log.w(TAG, diag.summary())
            onMain { req.onDone(WebModeOutcome.Failure(e, msg, diag)) }
        }

        fun begin() {
            ensureBridge(wv)
            progress("Discovering chapter panels", 1)
            if (req.force && !req.visibleOnly) {
                worker.execute { runCatching { processForce() }.onFailure {
                    Log.w(TAG, "force job $id crashed: ${it.javaClass.simpleName}")
                    fail(WebModeError.DECODE_FAILED, "Force extract crashed: ${it.javaClass.simpleName}")
                } }
                return
            }
            if (req.visibleOnly) finishDiscovery() else tick(0, -1, 0)
        }

        // ---- phase 1: scroll the real page so lazy loaders fire, until the image set stops changing
        private fun tick(n: Int, lastCount: Int, stable: Int) {
            if (!alive()) { restoreScroll(); fail(WebModeError.NAVIGATION_INVALIDATED); return }
            if (elapsed() > DISCOVERY_MAX_MS || n > MAX_TICKS) { finishDiscovery(); return }
            wv.evaluateJavascript(wrap(LIB + JS_STEP)) { raw ->
                if (!alive()) { restoreScroll(); fail(WebModeError.NAVIGATION_INVALIDATED); return@evaluateJavascript }
                val o = parseJson(raw)
                if (o == null) {
                    handler.postDelayed({ tick(n + 1, lastCount, 0) }, 500L)
                    return@evaluateJavascript
                }
                val count = o.optInt("n", 0)
                val pending = o.optInt("p", 0)
                val bottom = o.optBoolean("bottom", false)
                val ready = o.optString("ready") == "complete"
                var st = if (bottom && ready && count == lastCount) stable + 1 else 0
                // A reader that has not drawn anything yet gets a longer grace period.
                if (count == 0 && elapsed() < EMPTY_GRACE_MS) st = 0
                progress("Discovering chapter panels", 1 + minOf(8, n / 4))
                if (st >= 3 && (pending == 0 || st >= 8)) finishDiscovery()
                else handler.postDelayed({ tick(n + 1, count, st) }, if (bottom) 500L else 350L)
            }
        }

        private fun restoreScroll() {
            try { wv.evaluateJavascript(wrap(LIB + JS_RESTORE), null) } catch (_: Exception) {}
        }

        private fun finishDiscovery() {
            val collectScript = if (req.visibleOnly) JS_COLLECT_VISIBLE else JS_COLLECT
            wv.evaluateJavascript(wrap(LIB + collectScript)) { raw ->
                restoreScroll()
                if (!alive()) { fail(WebModeError.NAVIGATION_INVALIDATED); return@evaluateJavascript }
                val o = parseJson(raw)
                if (o == null) { fail(WebModeError.NO_IMAGES); return@evaluateJavascript }
                val arr = o.optJSONArray("imgs") ?: JSONArray()
                val refs = ArrayList<PanelRef>(arr.length())
                for (i in 0 until minOf(arr.length(), MAX_PANELS)) {
                    val p = arr.optJSONObject(i) ?: continue
                    val s = p.optString("s")
                    if (s.isNotBlank()) refs.add(PanelRef(s, p.optInt("t"), p.optInt("l"), p.optInt("w"), p.optInt("h")))
                }
                val panels = WebModeLogic.dedupeAndOrder(refs)
                diag.discovered = panels.size
                diag.duplicates = refs.size - panels.size
                if (panels.isEmpty()) { fail(WebModeError.NO_IMAGES); return@evaluateJavascript }
                val meta = PageMeta(
                    url = o.optString("url", wv.url.orEmpty()),
                    lang = o.optString("lang"),
                    title = o.optString("title").trim().take(200),
                    novel = o.optString("novel").trim().take(120),
                    next = o.optString("next").takeIf { it.startsWith("http") },
                    prev = o.optString("prev").takeIf { it.startsWith("http") }
                )
                worker.execute { runCatching { process(panels, meta) }.onFailure {
                    Log.w(TAG, "job $id crashed: ${it.javaClass.simpleName}")
                    fail(WebModeError.DECODE_FAILED, "Web Mode crashed: ${it.javaClass.simpleName}")
                } }
            }
        }

        // ---- phase 2 (worker thread): fetch -> decode -> OCR, one panel at a time (bounded memory)
        private fun process(panels: List<PanelRef>, meta: PageMeta) {
            val total = panels.size
            val texts = arrayOfNulls<String>(total)
            val layouts = arrayOfNulls<List<WmBubble>>(total)
            val seen = HashSet<String>()
            val script = ScriptState(WebModeLogic.pickScript(meta.lang, meta.url))
            var fetchFailed = 0
            var decodeFailed = 0
            for ((i, p) in panels.withIndex()) {
                if (!alive()) { onMain { req.onDone(WebModeOutcome.Failure(WebModeError.NAVIGATION_INVALIDATED, WebModeError.NAVIGATION_INVALIDATED.label, diag)) }; return }
                if (elapsed() > JOB_MAX_MS) { diag.timedOut = true; fail(WebModeError.TIMEOUT); return }
                progress("Reading panel ${i + 1}/$total", 10 + i * 89 / total)
                val bytes = fetchWithRetry(p, meta.url)
                if (bytes == null) { fetchFailed++; diag.failed++; continue }
                if (!seen.add(md5(bytes))) { diag.duplicates++; continue }
                diag.downloaded++
                try {
                    val po = ocrBytes(bytes, script, p.src)
                    diag.decoded++
                    if (po == null) continue                      // too small to be a panel: ignored, not an error
                    val t = po.text
                    if (t.isBlank()) diag.ocrEmpty++ else { diag.ocrOk++; diag.chars += t.length }
                    texts[i] = t
                    layouts[i] = po.bubbles
                } catch (e: OcrUnavailableException) {
                    fail(WebModeError.OCR_MODEL_UNAVAILABLE, e.message ?: WebModeError.OCR_MODEL_UNAVAILABLE.label)
                    return
                } catch (e: DecodeException) {
                    decodeFailed++; diag.failed++
                } catch (e: OutOfMemoryError) {
                    decodeFailed++; diag.failed++
                }
            }
            if (!alive()) { onMain { req.onDone(WebModeOutcome.Failure(WebModeError.NAVIGATION_INVALIDATED, WebModeError.NAVIGATION_INVALIDATED.label, diag)) }; return }
            progress("Collecting recognized text", 99)
            when {
                diag.downloaded == 0 -> { fail(WebModeError.IMAGES_NOT_LOADED, "Found ${diag.discovered} images but none could be downloaded"); return }
                diag.decoded == 0 -> { fail(WebModeError.DECODE_FAILED, "Downloaded ${diag.downloaded} images but none could be decoded"); return }
                diag.chars == 0 -> { fail(WebModeError.OCR_EMPTY, "Scanned ${diag.decoded} images; OCR found no text"); return }
                WebModeLogic.tooManyFailures(total, diag.failed) -> {
                    fail(WebModeError.IMAGES_NOT_LOADED, "${diag.failed} of $total panels failed; the chapter would be incomplete")
                    return
                }
            }
            val title = meta.title.ifBlank { hostOf(meta.url) }
            val panelTexts = texts.map { it.orEmpty() }
            // In live mode the title is metadata, not an OCR paragraph. Leaving it in the
            // source stream shifts every [NNN] marker and makes the first bubble unmatchable.
            val text = if (req.visibleOnly) WebModeLogic.assemble("", panelTexts)
                else WebModeLogic.assemble(title, panelTexts)
            // Keep every OCR block's exact source text beside its geometry. The main thread can then
            // deduplicate bubbles across overlapping viewports and map each reply to its own box.
            val allBubbles = ArrayList<WmBubble>()
            for (l in layouts) if (l != null) allBubbles.addAll(l)
            val titleOffset = if (req.visibleOnly || title.isBlank()) 0 else 1
            WebModeLayoutStore.put(meta.url, WebModeLayout(titleOffset, allBubbles))
            val ch = Chapter(
                title = title,
                text = text,
                next = meta.next,
                prev = meta.prev,
                url = meta.url,
                novel = meta.novel.ifBlank { hostOf(meta.url).removePrefix("www.") },
                number = WebModeLogic.episodeNumber(title, meta.url),
                contentSel = ""
            )
            Log.i(TAG, diag.summary())
            val failedPanels = diag.failed
            onMain { req.onDone(WebModeOutcome.Success(ch, diag, failedPanels)) }
        }

        // ---- FORCE mode: no image is downloaded. The page is scrolled from the top and the WebView's own
        // pixels (exactly what the user sees) are OCR'd per image. Works for blob:/encrypted/CORS/hot-link images.
        private fun <T> evalWait(js: String, parse: (String?) -> T): T {
            val latch = CountDownLatch(1)
            var res: String? = null
            handler.post {
                try { wv.evaluateJavascript(js) { r -> res = r; latch.countDown() } }
                catch (_: Exception) { latch.countDown() }
            }
            latch.await(20, TimeUnit.SECONDS)
            return parse(res)
        }

        private fun isBlank(b: Bitmap): Boolean {
            val first = b.getPixel(b.width / 2, b.height / 2)
            var differs = 0
            for (yi in 1..12) for (xi in 1..8) {
                if (b.getPixel(b.width * xi / 9, b.height * yi / 13) != first) differs++
            }
            return differs < 3
        }

        /** Second way to take the picture (API 26+): copy the WebView's rectangle from the window surface. */
        private fun captureByPixelCopy(): Bitmap? {
            if (android.os.Build.VERSION.SDK_INT < 26) return null
            var act: android.app.Activity? = null
            var c: android.content.Context? = wv.context
            while (c is android.content.ContextWrapper) { if (c is android.app.Activity) { act = c; break }; c = c.baseContext }
            val win = act?.window ?: return null
            val latch = CountDownLatch(1)
            var out: Bitmap? = null
            handler.post {
                try {
                    val loc = IntArray(2); wv.getLocationInWindow(loc)
                    if (wv.width > 0 && wv.height > 0) {
                        val b = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                        android.view.PixelCopy.request(win, Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height), b,
                            { code -> if (code == android.view.PixelCopy.SUCCESS) out = b else b.recycle(); latch.countDown() }, handler)
                    } else latch.countDown()
                } catch (_: Throwable) { latch.countDown() }
            }
            latch.await(10, TimeUnit.SECONDS)
            return out
        }

        private fun captureView(): Bitmap? {
            val a = captureByDraw()
            if (a != null && !isBlank(a)) return a
            a?.recycle()
            forceBlank++
            val b = captureByPixelCopy()
            if (b != null && !isBlank(b)) return b
            b?.recycle()
            return null
        }

        private fun captureByDraw(): Bitmap? {
            val latch = CountDownLatch(1)
            var out: Bitmap? = null
            handler.post {
                try {
                    val w = wv.width; val h = wv.height
                    if (w > 0 && h > 0) {
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(android.graphics.Color.WHITE)
                        wv.draw(android.graphics.Canvas(b))
                        out = b
                    }
                } catch (_: Throwable) {
                } finally { latch.countDown() }
            }
            latch.await(20, TimeUnit.SECONDS)
            return out
        }

        @Volatile private var forceBlank = 0
        @Volatile private var forceShots = 0

        private fun forceFail(e: WebModeError, msg: String = e.label) {
            evalWait(wrap(LIB + JS_RESTORE)) { null }
            evalWait(wrap(JS_FORCE_SHOW)) { null }
            fail(e, msg)
        }

        private fun processForce() {
            val mo = evalWait(wrap(LIB + JS_COLLECT)) { parseJson(it) }
            if (mo == null) { fail(WebModeError.NO_IMAGES); return }
            val meta = PageMeta(
                url = mo.optString("url", wv.url.orEmpty()),
                lang = mo.optString("lang"),
                title = mo.optString("title").trim().take(200),
                novel = mo.optString("novel").trim().take(120),
                next = mo.optString("next").takeIf { it.startsWith("http") },
                prev = mo.optString("prev").takeIf { it.startsWith("http") }
            )
            val script = ScriptState(WebModeLogic.pickScript(meta.lang, meta.url))
            val accs = LinkedHashMap<String, ForceAcc>()
            // the old translation overlay must not be in the picture
            evalWait(wrap(JS_FORCE_HIDE)) { null }
            if (!req.visibleOnly) evalWait(wrap(LIB + JS_FORCE_TOP)) { null }
            var stable = 0
            var steps = 0
            while (true) {
                if (!alive()) { forceFail(WebModeError.NAVIGATION_INVALIDATED); return }
                if (elapsed() > JOB_MAX_MS) { diag.timedOut = true; forceFail(WebModeError.TIMEOUT); return }
                if (steps > MAX_TICKS) break
                try { Thread.sleep(if (steps == 0) 700L else 450L) } catch (_: InterruptedException) { return }
                val info = evalWait(wrap(LIB + JS_FORCE_INFO)) { parseJson(it) }
                val bmp = captureView()
                forceShots++
                if (info != null && bmp != null) {
                    try { forceOcr(bmp, info, script, accs) }
                    catch (e: OcrUnavailableException) {
                        forceFail(WebModeError.OCR_MODEL_UNAVAILABLE, e.message ?: WebModeError.OCR_MODEL_UNAVAILABLE.label); return
                    } finally { bmp.recycle() }
                } else bmp?.recycle()
                progress("Reading screen ${steps + 1} (${accs.size} images)", 5 + minOf(90, steps * 3))
                if (req.visibleOnly) break
                val bottom = evalWait(wrap(LIB + JS_FORCE_SCROLL)) { parseJson(it)?.optBoolean("bottom", true) ?: true }
                stable = if (bottom) stable + 1 else 0
                if (stable >= 2) break
                steps++
            }
            evalWait(wrap(LIB + JS_RESTORE)) { null }
            evalWait(wrap(JS_FORCE_SHOW)) { null }
            if (!alive()) { fail(WebModeError.NAVIGATION_INVALIDATED); return }

            val panels = accs.values.sortedBy { it.top }
            diag.discovered = accs.size
            diag.downloaded = accs.size
            diag.decoded = accs.size
            val panelTexts = panels.map { it.texts.joinToString("\n\n") }
            diag.chars = panelTexts.sumOf { it.length }
            diag.ocrOk = panelTexts.count { it.isNotBlank() }
            diag.ocrEmpty = panelTexts.size - diag.ocrOk
            when {
                forceBlank > 0 && accs.isEmpty() && forceShots == forceBlank -> {
                    fail(WebModeError.DECODE_FAILED, "Force extract: every screen capture came back blank"); return }
                accs.isEmpty() -> { fail(WebModeError.NO_IMAGES, "Force extract: no loaded image on screen (screens=$forceShots)"); return }
                diag.chars == 0 -> { fail(WebModeError.OCR_EMPTY, "Force extract: ${accs.size} images, screens=$forceShots, blank=$forceBlank; OCR found no text"); return }
            }
            val title = meta.title.ifBlank { hostOf(meta.url) }
            val text = if (req.visibleOnly) WebModeLogic.assemble("", panelTexts) else WebModeLogic.assemble(title, panelTexts)
            val allBubbles = ArrayList<WmBubble>()
            for (p in panels) allBubbles.addAll(p.bubbles)
            val titleOffset = if (req.visibleOnly || title.isBlank()) 0 else 1
            WebModeLayoutStore.put(meta.url, WebModeLayout(titleOffset, allBubbles))
            val ch = Chapter(
                title = title, text = text, next = meta.next, prev = meta.prev, url = meta.url,
                novel = meta.novel.ifBlank { hostOf(meta.url).removePrefix("www.") },
                number = WebModeLogic.episodeNumber(title, meta.url), contentSel = ""
            )
            Log.i(TAG, "force " + diag.summary())
            onMain { req.onDone(WebModeOutcome.Success(ch, diag, 0)) }
        }

        /** OCR the on-screen part of every loaded image in one screenshot of the WebView. */
        private fun forceOcr(bmp: Bitmap, info: JSONObject, st: ScriptState, accs: MutableMap<String, ForceAcc>) {
            val vw = info.optDouble("vw", 0.0).toFloat()
            if (vw <= 0f) return
            val scale = bmp.width / vw                      // device pixels per CSS pixel
            val arr = info.optJSONArray("imgs") ?: return
            for (i in 0 until arr.length()) {
                if (!alive()) return
                val p = arr.optJSONObject(i) ?: continue
                val src = p.optString("s")
                if (src.isBlank()) continue
                val l = p.optDouble("vl").toFloat() * scale
                val t = p.optDouble("vt").toFloat() * scale
                val fw = p.optDouble("fw").toFloat() * scale
                val fh = p.optDouble("fh").toFloat() * scale
                if (fw < 40f || fh < 40f) continue
                val x0 = Math.max(0, Math.round(l)); val y0 = Math.max(0, Math.round(t))
                val x1 = Math.min(bmp.width, Math.round(l + fw)); val y1 = Math.min(bmp.height, Math.round(t + fh))
                if (x1 - x0 < 40 || y1 - y0 < 40) continue
                var crop: Bitmap? = null
                try {
                    crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
                    val acc = accs.getOrPut(src) { ForceAcc() }
                    acc.top = p.optInt("t")
                    val offX = Math.max(0f, x0 - l).toInt()
                    val offY = Math.max(0f, y0 - t).toInt()
                    val first = (y0 - t) <= 3f
                    val last = (t + fh) <= bmp.height + 3f
                    readSlice(crop, st, offY, 1, fw.toInt().coerceAtLeast(1), fh.toInt().coerceAtLeast(1), src,
                        acc.texts, acc.bubbles, first, last, offX)
                } catch (e: OcrUnavailableException) { throw e
                } catch (_: Exception) {
                } catch (_: OutOfMemoryError) {
                } finally { if (crop != null && crop !== bmp) crop.recycle() }
            }
        }

        // ---- fetching: HTTP with the WebView's cookies; blob:/data: and refused requests go through the page itself
        private fun fetchWithRetry(p: PanelRef, pageUrl: String): ByteArray? {
            for (attempt in 1..3) {
                if (!alive()) return null
                try {
                    val u = p.src
                    return when {
                        u.startsWith("data:", true) -> decodeDataUrl(u)
                        u.startsWith("blob:", true) -> fetchViaPage(u)
                        else -> try { httpGet(u, pageUrl) } catch (e: IOException) { fetchViaPage(u) }
                    }
                } catch (_: Exception) {
                    if (attempt < 3) try { Thread.sleep(600L * attempt) } catch (_: InterruptedException) { return null }
                }
            }
            return null
        }

        private fun httpGet(url: String, referer: String): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000
                c.readTimeout = 25000
                c.instanceFollowRedirects = true
                // A CDN may reuse a panel URL while replacing its bytes in a live reader.
                // Disable HTTP caching so same-URL OCR rechecks see current pixels.
                c.useCaches = false
                c.setRequestProperty("Cache-Control", "no-cache")
                c.setRequestProperty("Pragma", "no-cache")
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
                val code = c.responseCode
                if (code !in 200..299) throw IOException("http $code")
                if (c.contentLengthLong > MAX_BYTES) throw IOException("too large")
                return c.inputStream.use { ins ->
                    val out = java.io.ByteArrayOutputStream(maxOf(32 * 1024, c.contentLength))
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("too large")
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } finally {
                c.disconnect()
            }
        }

        private fun decodeDataUrl(u: String): ByteArray {
            val comma = u.indexOf(',')
            if (comma < 0) throw IOException("bad data url")
            val head = u.substring(0, comma)
            val body = u.substring(comma + 1)
            return if (head.contains(";base64", true)) Base64.decode(body, Base64.DEFAULT)
            else java.net.URLDecoder.decode(body, "UTF-8").toByteArray(Charsets.ISO_8859_1)
        }

        /** fetch() inside the page: works for blob: URLs and for same-origin / CORS-enabled images. */
        private fun fetchViaPage(u: String): ByteArray {
            val rid = UUID.randomUUID().toString()
            val pending = Pending()
            bridge.pending[rid] = pending
            try {
                val js = "(function(id,u){" +
                    "function viaCanvas(){try{var im=null,L=document.images;" +
                    "for(var i=0;i<L.length;i++){if((L[i].currentSrc||L[i].src)===u){im=L[i];break;}}" +
                    "if(!im||!im.naturalWidth)throw new Error('no loaded img');" +
                    "var c=document.createElement('canvas');c.width=im.naturalWidth;c.height=im.naturalHeight;" +
                    "c.getContext('2d').drawImage(im,0,0);" +
                    "NsWmBridge.ok(id,c.toDataURL('image/jpeg',0.92));}catch(e){NsWmBridge.fail(id,String(e));}}" +
                    "try{fetch(u,{credentials:'include'}).then(function(r){" +
                    "if(!r.ok)throw new Error('http '+r.status);return r.blob();}).then(function(b){" +
                    "var fr=new FileReader();fr.onload=function(){NsWmBridge.ok(id,String(fr.result));};" +
                    "fr.onerror=function(){viaCanvas();};fr.readAsDataURL(b);})" +
                    ".catch(function(e){viaCanvas();});}catch(e){viaCanvas();}" +
                    "return 'started';})(" + JSONObject.quote(rid) + "," + JSONObject.quote(u) + ")"
                onMain { wv.evaluateJavascript(js, null) }
                if (!pending.latch.await(25, TimeUnit.SECONDS)) throw IOException("page fetch timeout")
                val d = pending.data ?: throw IOException(pending.err ?: "page fetch failed")
                val bytes = decodeDataUrl(d)
                if (bytes.size > MAX_BYTES) throw IOException("too large")
                return bytes
            } finally {
                bridge.pending.remove(rid)
            }
        }

        // ---- decode + OCR. Returns null for images too small to be a panel; no bubbles for a panel without text.
        private fun ocrBytes(bytes: ByteArray, st: ScriptState, src: String): PanelOcr? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) throw DecodeException()
            if (w < MIN_W || h < MIN_H) return null
            val sample = when { w > 3200 -> 4; w > 1600 -> 2; else -> 1 }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val texts = ArrayList<String>()
            val bubbles = ArrayList<WmBubble>()
            if (h <= WebModeLogic.SLICE_H) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw DecodeException()
                try { readSlice(bmp, st, 0, sample, w, h, src, texts, bubbles, true, true) } finally { bmp.recycle() }
            } else {
                @Suppress("DEPRECATION")
                val region = (try { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) } catch (_: Exception) { null })
                    ?: throw DecodeException()
                try {
                    val sl = WebModeLogic.slices(h)
                    for ((idx, yy) in sl.withIndex()) {
                        val (y0, y1) = yy
                        if (!alive()) break
                        val bmp = region.decodeRegion(Rect(0, y0, w, y1), opts) ?: throw DecodeException()
                        try {
                            if (bmp.width >= 32 && bmp.height >= 32)
                                readSlice(bmp, st, y0, sample, w, h, src, texts, bubbles, idx == 0, idx == sl.size - 1)
                        } finally { bmp.recycle() }
                    }
                } finally { region.recycle() }
            }
            return PanelOcr(texts.joinToString("\n\n"), bubbles)
        }

        /**
         * OCR one slice and describe every block: its text (one line = one paragraph for the chatbot)
         * and where it sat on the full image. [texts] and [out] stay index-aligned.
         */
        private fun readSlice(
            bmp: Bitmap, st: ScriptState, y0: Int, sample: Int, fullW: Int, fullH: Int,
            src: String, texts: MutableList<String>, out: MutableList<WmBubble>,
            firstSlice: Boolean, lastSlice: Boolean, x0: Int = 0
        ) {
            // Slices overlap. A block touching a cut edge is only part of its text; the neighbouring
            // slice sees it whole, so it is dropped here instead of being translated in halves.
            val edge = 4f
            val blocks = recognize(bmp, st).blocks.filter { b ->
                if (b.text.isBlank()) return@filter false
                val top = b.cy - b.h / 2f
                val bot = b.cy + b.h / 2f
                !((!firstSlice && top <= edge) || (!lastSlice && bot >= bmp.height - edge))
            }
            if (blocks.isEmpty()) return
            val rects = blocks.map { WmBubbleStyle.rectOf(it, bmp.width, bmp.height) }
            val k = sample.toFloat()

            // Bubbles that still hold lettering no block accounts for (a line the OCR missed): read the
            // whole inside of the bubble again as one clean picture, and use that fuller text.
            val refinedText = arrayOfNulls<String>(blocks.size)
            val refinedBox = arrayOfNulls<IntArray>(blocks.size)
            val consumed = BooleanArray(blocks.size)
            for (i in blocks.indices) {
                if (consumed[i]) continue
                val b = blocks[i]
                if (b.vertical || Math.abs(OcrPost.usableTilt(b.angle)) >= 6f) continue
                val inter: IntArray = (try { WmBubbleStyle.bubbleInterior(bmp, rects[i], rects) } catch (_: Exception) { null }) ?: continue

                // A connected/overlapping pair of speech balloons can produce one large interior.
                // Never OCR that whole region as a single paragraph when it already contains another
                // recognised text block: this can combine two speakers/balloons and then paint one
                // oversized white cover across both. Retry only isolated text regions; other blocks
                // retain their own text and geometry.
                val hasOtherTextBlock = blocks.indices.any { j ->
                    if (j == i) false else {
                        val cxj = (rects[j][0] + rects[j][2]) / 2
                        val cyj = (rects[j][1] + rects[j][3]) / 2
                        cxj >= inter[0] && cxj < inter[2] && cyj >= inter[1] && cyj < inter[3]
                    }
                }
                if (hasOtherTextBlock) continue

                val cw = inter[2] - inter[0]; val ch = inter[3] - inter[1]
                if (cw < 12 || ch < 12) continue
                var crop: Bitmap? = null
                try {
                    crop = Bitmap.createBitmap(bmp, inter[0], inter[1], cw, ch)
                    val res = engine.recognizeRegion(crop, st.current)
                    val joined = res.blocks.sortedWith(compareBy({ it.top }, { it.left }))
                        .joinToString(" ") { it.text.replace(NEWLINES, " ").trim() }.trim()
                    val own = b.text.replace(NEWLINES, " ").trim()
                    val ownKey = WebModeLayout.normalizeOcrText(own)
                    val joinedKey = WebModeLayout.normalizeOcrText(joined)
                    // Accept a crop retry only when it preserves the already-read text and adds
                    // meaningful content. A fixed +4 character threshold discarded shorter missed
                    // words/lines; a containment check avoids replacing a good read with OCR noise.
                    if (ownKey.length >= 3 &&
                        joinedKey.contains(ownKey) &&
                        joinedKey.length > ownKey.length + maxOf(2, (ownKey.length * 0.08f).toInt())) {
                        refinedText[i] = joined
                        refinedBox[i] = inter
                        // other blocks sitting inside this bubble are now part of the fuller text
                        for (j in blocks.indices) {
                            if (j == i) continue
                            val cxj = (rects[j][0] + rects[j][2]) / 2; val cyj = (rects[j][1] + rects[j][3]) / 2
                            if (cxj in inter[0] until inter[2] && cyj in inter[1] until inter[3]) consumed[j] = true
                        }
                    }
                } catch (_: Exception) {
                } catch (_: OutOfMemoryError) {
                } finally {
                    if (crop != null && crop !== bmp) crop.recycle()
                }
            }

            for ((i, b) in blocks.withIndex()) {
                if (consumed[i]) continue
                val tilt = OcrPost.usableTilt(b.angle)          // never upside down, never sideways
                val rotated = Math.abs(tilt) >= 6f
                val others = rects.filterIndexed { j, _ -> j != i }
                val s = WmBubbleStyle.analyse(bmp, rects[i], others, expand = !rotated)
                var cx: Float; var cy: Float; var bw: Float; var bh: Float
                // Wipe the old lettering COMPLETELY: OCR boxes are tight, but outlines, glow, shadows and
                // descenders reach past them. Pad by a share of the line height (more on artwork).
                val lineH = (if (b.fontPx > 0f) b.fontPx else minOf(b.w, b.h)).coerceAtLeast(8f)
                val pad = lineH * (if (s.soft) 0.34f else 0.2f) + 3f
                if (rotated) {
                    cx = b.cx; cy = b.cy; bw = b.w * 1.1f + pad * 2f; bh = b.h * 1.1f + pad * 2f
                } else {
                    // flat bubbles already grew to their interior (kept inside the outline); only pad the text-sized ones
                    val grown = (s.r - s.l) > (rects[i][2] - rects[i][0]) + 8 || (s.b - s.t) > (rects[i][3] - rects[i][1]) + 8
                    val p = if (grown) 2f else pad
                    cx = (s.l + s.r) / 2f; cy = (s.t + s.b) / 2f; bw = (s.r - s.l) + p * 2f; bh = (s.b - s.t) + p * 2f
                }
                val text = (refinedText[i] ?: b.text).replace(NEWLINES, " ").trim()
                val rb = refinedBox[i]
                if (rb != null) {
                    // the bubble's whole inside (kept a little off its outline)
                    cx = (rb[0] + rb[2]) / 2f; cy = (rb[1] + rb[3]) / 2f
                    bw = (rb[2] - rb[0]) - 4f; bh = (rb[3] - rb[1]) - 4f
                }
                val nb = WmBubble(
                    src = src,
                    cx = (x0 + cx * k) / fullW, cy = (y0 + cy * k) / fullH,
                    w = bw * k / fullW, h = bh * k / fullH,
                    angle = if (rotated) tilt else 0f,
                    font = b.fontPx * k / fullW,
                    bg = s.bg, fg = s.fg, fg2 = s.fg2, soft = s.soft,
                    sourceText = text
                )
                // Same text seen by two overlapping slices: keep the bigger box once.
                var dup = -1
                for (j in out.indices) {
                    if (out[j].src == src && sameSpot(out[j], nb)) { dup = j; break }
                }
                if (dup >= 0) {
                    if (nb.w * nb.h > out[dup].w * out[dup].h * 1.15f) { out[dup] = nb; texts[dup] = text }
                    continue
                }
                texts.add(text)
                out.add(nb)
            }
        }

        private fun sameSpot(a: WmBubble, b: WmBubble): Boolean {
            val x = Math.min(a.cx + a.w / 2, b.cx + b.w / 2) - Math.max(a.cx - a.w / 2, b.cx - b.w / 2)
            val y = Math.min(a.cy + a.h / 2, b.cy + b.h / 2) - Math.max(a.cy - a.h / 2, b.cy - b.h / 2)
            if (x <= 0f || y <= 0f) return false
            val small = Math.min(a.w * a.h, b.w * b.h)
            return small > 0f && x * y / small > 0.5f
        }

        private fun recognize(bmp: Bitmap, st: ScriptState): OcrResult {
            val first = engine.recognize(bmp, st.current)
            // A good OCR result stays on the fast path. Retry only if the first pass found no text,
            // or returned just one/two tiny fragments where a full panel is likely still unread.
            val result = if (needsOcrRetry(bmp, first)) {
                val retry = try { engine.recognizeFallback(bmp, st.current) } catch (_: Exception) {
                    OcrResult(emptyList())
                }
                combineOcrResults(first, retry)
            } else first

            if (result.text.length >= 2) { st.locked = true; return result }
            if (!st.locked && st.fallbackBudget > 0) {
                st.fallbackBudget--
                for (s in WebModeLogic.fallbackScripts(st.current)) {
                    val r = engine.recognize(bmp, s)
                    if (r.text.length >= 2) { st.current = s; st.locked = true; return r }
                }
            }
            return result
        }

        private fun needsOcrRetry(bmp: Bitmap, result: OcrResult): Boolean {
            if (result.blocks.isEmpty()) return true
            val letters = result.text.count { it.isLetterOrDigit() }
            if (letters >= 24 || result.blocks.size > 2) return false
            val covered = result.blocks.sumOf { (it.w * it.h).toDouble().coerceAtLeast(0.0) }
            val coverage = covered / (bmp.width.toDouble() * bmp.height.toDouble()).coerceAtLeast(1.0)
            return coverage < 0.12
        }

        private fun combineOcrResults(first: OcrResult, retry: OcrResult): OcrResult {
            if (retry.blocks.isEmpty()) return first
            val all = first.blocks.toMutableList()
            for (candidate in retry.blocks.sortedWith(compareBy({ it.top }, { it.left }))) {
                val key = candidate.text.lowercase().filter { it.isLetterOrDigit() }
                if (key.isEmpty()) continue
                val overlaps = all.indices.filter {
                    OcrPost.overlapOfSmaller(all[it], candidate) > 0.25f
                }
                if (overlaps.isEmpty()) {
                    val duplicateText = all.any { prior ->
                        val priorKey = prior.text.lowercase().filter { it.isLetterOrDigit() }
                        priorKey.isNotEmpty() && (priorKey.contains(key) || key.contains(priorKey)) &&
                            kotlin.math.abs(prior.cx - candidate.cx) < maxOf(prior.w, candidate.w) * 0.25f &&
                            kotlin.math.abs(prior.cy - candidate.cy) < maxOf(prior.h, candidate.h) * 0.6f
                    }
                    if (!duplicateText) all.add(candidate)
                } else {
                    val upgrade = overlaps.firstOrNull { index ->
                        val priorKey = all[index].text.lowercase().filter { it.isLetterOrDigit() }
                        priorKey.length >= 3 &&
                            key.length > priorKey.length + maxOf(2, priorKey.length / 10) &&
                            key.contains(priorKey)
                    }
                    if (upgrade != null) all[upgrade] = candidate
                }
            }
            return OcrResult(OcrPost.mergeNeighbours(all.filterNot { OcrPost.isNoise(it) }))
        }
    }

    private class ScriptState(var current: OcrScript) { var locked = false; var fallbackBudget = 3 }
    private class DecodeException : Exception()
    private class ForceAcc { var top = 0; val texts = ArrayList<String>(); val bubbles = ArrayList<WmBubble>() }
    private class PanelOcr(val text: String, val bubbles: List<WmBubble>)
    private class PageMeta(val url: String, val lang: String, val title: String, val novel: String, val next: String?, val prev: String?)

    companion object {
        private const val TAG = "NsWebMode"
        private val NEWLINES = Regex("\\s*\\n\\s*")
        private const val MAX_PANELS = 400
        private const val MAX_TICKS = 400
        private const val MAX_BYTES = 25 * 1024 * 1024
        private const val DISCOVERY_MAX_MS = 120_000L
        private const val EMPTY_GRACE_MS = 15_000L
        private const val JOB_MAX_MS = 12 * 60_000L
        private const val MIN_W = 120
        private const val MIN_H = 60

        private fun hostOf(u: String): String =
            u.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':')

        private fun md5(b: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) } + ":" + b.size

        private fun parseJson(raw: String?): JSONObject? = try {
            if (raw == null || raw == "null") null
            else JSONObject(JSONArray("[$raw]").getString(0))
        } catch (_: Exception) { null }

        private fun wrap(body: String) = "(function(){try{$body}catch(e){return JSON.stringify({err:String(e)});}})()"

        // ---------------- JavaScript run inside the real page (no dollar signs: this is a Kotlin string)
        private const val LIB = """
var BAD=/avatar|icon|logo|banner|sprite|emoji|profile|badge|pixel|tracking|advert|\/ads?\/|[_\-.]ads?[_\-.]|spinner/i;
var PH=/placeholder|blank\.|spacer|loading|lazy[-_.]?(img|load)|1x1|transparen/i;
var LAZY=['data-src','data-original','data-lazy-src','data-lazy','data-url','data-echo','data-actualsrc','data-orig-src','data-pc-src'];
var de=document.scrollingElement||document.documentElement;
function scroller(){
  if((de.scrollHeight||0)>(window.innerHeight||0)+80)return null;
  var best=null,bs=0,els=document.querySelectorAll('div,main,section,article');
  for(var i=0;i<els.length&&i<3000;i++){var e=els[i];var d=e.scrollHeight-e.clientHeight;
    if(d>200&&e.scrollHeight>bs){var o=getComputedStyle(e).overflowY;
      if(o==='auto'||o==='scroll'||o==='overlay'){bs=e.scrollHeight;best=e;}}}
  return best;}
function cands(sc){
  var out=[],seen={},imgs=document.images,wy=window.pageYOffset||de.scrollTop||0,sr=sc?sc.getBoundingClientRect():null;
  for(var i=0;i<imgs.length;i++){
    var im=imgs[i],src=im.currentSrc||im.src||'';
    var ph=!src||/^data:image\/(gif|svg)/i.test(src)||PH.test(src);
    if(ph){
      for(var a=0;a<LAZY.length;a++){var v=im.getAttribute(LAZY[a]);if(v&&v.length>4){src=v;ph=false;break;}}
      if(ph){var ss=im.getAttribute('data-srcset')||im.getAttribute('srcset');
        if(ss){src=ss.split(',')[0].trim().split(/\s+/)[0];ph=!src;}}
    }
    if(!src||ph)continue;
    try{src=new URL(src,document.baseURI).href;}catch(e){continue;}
    if(seen[src])continue;
    var r=im.getBoundingClientRect(),nw=im.naturalWidth||0,nh=im.naturalHeight||0;
    if(!((r.width>=180&&r.height>=100)||(nw>=400&&nh>=250)))continue;
    var meta=(im.className&&im.className.baseVal!==undefined?im.className.baseVal:im.className||'')+' '+(im.id||'')+' '+(im.alt||'');
    if(BAD.test(meta+' '+src))continue;
    if(im.closest&&im.closest('header,footer,nav,aside,[class*=comment],[class*=recommend],[class*=related],[class*=banner],[id*=comment]'))continue;
    seen[src]=1;
    var top=sc?(r.top-sr.top+sc.scrollTop):(r.top+wy);
    out.push({s:src,t:Math.round(top),l:Math.round(r.left),w:Math.round(r.width),h:Math.round(r.height),d:(im.complete&&nw>0)?1:0,fw:r.width,fh:r.height,vl:r.left,vt:r.top,vb:r.bottom});
  }
  // Some readers draw comic panels as CSS background images instead of <img>.
  // Treat only large elements as panels to avoid tiny UI artwork.
  var els=document.querySelectorAll('*'),limit=Math.min(els.length,5000);
  for(var j=0;j<limit;j++){
    var el=els[j],bg='';
    try{bg=getComputedStyle(el).backgroundImage||'';}catch(ignore){}
    if(!bg||bg==='none')continue;
    var r=el.getBoundingClientRect();
    if(r.width<180||r.height<100)continue;
    var meta=(el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className||'')+' '+(el.id||'')+' '+(el.getAttribute('aria-label')||'');
    if(BAD.test(meta))continue;
    if(el.closest&&el.closest('header,footer,nav,aside,[class*=comment],[class*=recommend],[class*=related],[class*=banner],[id*=comment]'))continue;
    var rx=/url\((?:"|')?([^"')]+)(?:"|')?\)/g,m;
    while((m=rx.exec(bg))!==null){
      var s=(m[1]||'').trim();
      if(!s||/^data:image\/(gif|svg)/i.test(s))continue;
      try{s=new URL(s,document.baseURI).href;}catch(ignore){continue;}
      if(seen[s]||BAD.test(s))continue;
      seen[s]=1;
      var top=sc?(r.top-sr.top+sc.scrollTop):(r.top+wy);
      out.push({s:s,t:Math.round(top),l:Math.round(r.left),w:Math.round(r.width),h:Math.round(r.height),d:1,fw:r.width,fh:r.height,vl:r.left,vt:r.top,vb:r.bottom});
    }
  }
  return out;}
"""

        private const val JS_STEP = """
var sc=scroller(),vh=window.innerHeight||800,before,after,hh,bottom;
if(sc){
  if(window.__nsWmS===undefined)window.__nsWmS=sc.scrollTop;
  before=sc.scrollTop;vh=sc.clientHeight;sc.scrollTop=before+Math.round(vh*0.85);after=sc.scrollTop;hh=sc.scrollHeight;
}else{
  before=window.pageYOffset||de.scrollTop||0;
  if(window.__nsWmY===undefined)window.__nsWmY=before;
  window.scrollBy(0,Math.round(vh*0.85));after=window.pageYOffset||de.scrollTop||0;
  hh=Math.max(de.scrollHeight||0,document.body?document.body.scrollHeight:0);
}
bottom=(after+vh>=hh-4)||after===before;
var c=cands(sc),p=0;for(var i=0;i<c.length;i++){if(!c[i].d)p++;}
return JSON.stringify({n:c.length,p:p,bottom:bottom,ready:document.readyState});
"""

        private const val JS_FORCE_INFO = """
var sc=scroller(),c=cands(sc),vw=window.innerWidth||de.clientWidth||0,vh=window.innerHeight||0,o=[];
for(var i=0;i<c.length;i++){var p=c[i];
  if(p.d&&p.vb>0&&p.vt<vh&&(p.vl+p.fw)>0&&p.vl<vw)o.push(p);}
return JSON.stringify({vw:vw,vh:vh,imgs:o});
"""

        private const val JS_FORCE_SCROLL = """
var sc=scroller(),vh=window.innerHeight||800,before,after,hh,bottom;
if(sc){
  if(window.__nsWmS===undefined)window.__nsWmS=sc.scrollTop;
  before=sc.scrollTop;vh=sc.clientHeight;sc.scrollTop=before+Math.round(vh*0.55);after=sc.scrollTop;hh=sc.scrollHeight;
}else{
  before=window.pageYOffset||de.scrollTop||0;
  if(window.__nsWmY===undefined)window.__nsWmY=before;
  window.scrollBy(0,Math.round(vh*0.55));after=window.pageYOffset||de.scrollTop||0;
  hh=Math.max(de.scrollHeight||0,document.body?document.body.scrollHeight:0);
}
bottom=(after+vh>=hh-4)||after===before;
return JSON.stringify({bottom:bottom});
"""

        private const val JS_FORCE_TOP = """
var sc=scroller();
if(sc){if(window.__nsWmS===undefined)window.__nsWmS=sc.scrollTop;sc.scrollTop=0;}
else{if(window.__nsWmY===undefined)window.__nsWmY=window.pageYOffset||de.scrollTop||0;window.scrollTo(0,0);}
return '1';
"""

        private const val JS_FORCE_HIDE = """
var e=document.getElementById('__ns_wm_overlay');if(e)e.style.visibility='hidden';return '1';
"""

        private const val JS_FORCE_SHOW = """
var e=document.getElementById('__ns_wm_overlay');if(e)e.style.visibility='';return '1';
"""

        private const val JS_RESTORE = """
var sc=scroller();
if(sc&&window.__nsWmS!==undefined)sc.scrollTop=window.__nsWmS;
else if(window.__nsWmY!==undefined)window.scrollTo(0,window.__nsWmY);
try{delete window.__nsWmY;delete window.__nsWmS;}catch(e){}
return '1';
"""

        private const val JS_COLLECT_VISIBLE = """
var sc=scroller(),c=cands(sc);
var top=sc?(sc.scrollTop||0):(window.pageYOffset||de.scrollTop||0);
var height=sc?(sc.clientHeight||window.innerHeight||800):(window.innerHeight||800);
var bottom=top+height;
var vpH=window.innerHeight||800;c=c.filter(function(p){return p.vb>0&&p.vt<vpH;});
function q(s){var e=document.querySelector(s);return e?(e.textContent||'').trim():'';}
function link(kind){
  var re=kind==='next'?/next|\\u4e0b\\u4e00|\\ub2e4\\uc74c|\\u6b21\\u306e|\\u6b21\\u3078|\\u6b21\\u7ae0/i:/prev(?!iew)|\\u4e0a\\u4e00|\\uc774\\uc804|\\u524d\\u306e|\\u524d\\u3078|\\u524d\\u7ae0/i;
  var cur=location.href.split('#')[0],cand=[];
  var el=document.querySelector('link[rel='+kind+'],a[rel='+kind+']');
  if(el&&el.href)cand.push(el.href);
  var as=document.querySelectorAll('a[href]');
  for(var i=0;i<as.length;i++){var a=as[i],cls=(a.className&&a.className.baseVal!==undefined?a.className.baseVal:a.className||'')+' '+(a.id||'')+' '+(a.getAttribute('aria-label')||'')+' '+(a.title||''),t=(a.textContent||'').trim();if(/disabled/i.test(cls)||a.getAttribute('aria-disabled')==='true')continue;if((t.length<=30&&re.test(t))||re.test(cls))cand.push(a.href);}
  var root=function(h){return h.split('.').slice(-2).join('.');};
  for(var j=0;j<cand.length;j++){var h=cand[j];if(h&&/^https?:/i.test(h)&&h.split('#')[0]!==cur){try{if(root(new URL(h).hostname)===root(location.hostname))return h;}catch(e){}}}
  return '';}
var og=document.querySelector('meta[property="og:title"]');
var title=q('.subj_episode')||q('h1')||(og&&og.content)||document.title||'';
var novel=q('.detail_header .subj')||q('a.subj')||q('.series-title')||q('.comic-title')||'';
return JSON.stringify({lang:document.documentElement.lang||'',url:location.href,title:title,novel:novel,next:link('next'),prev:link('prev'),imgs:c.slice(0,600)});
"""

        private const val JS_COLLECT = """
var sc=scroller(),c=cands(sc);
function q(s){var e=document.querySelector(s);return e?(e.textContent||'').trim():'';}
function link(kind){
  var re=kind==='next'?/next|\u4e0b\u4e00|\ub2e4\uc74c|\u6b21\u306e|\u6b21\u3078|\u6b21\u7ae0/i:/prev(?!iew)|\u4e0a\u4e00|\uc774\uc804|\u524d\u306e|\u524d\u3078|\u524d\u7ae0/i;
  var cur=location.href.split('#')[0],cand=[];
  var el=document.querySelector('link[rel='+kind+'],a[rel='+kind+']');
  if(el&&el.href)cand.push(el.href);
  var as=document.querySelectorAll('a[href]');
  for(var i=0;i<as.length;i++){var a=as[i];
    var cls=(a.className&&a.className.baseVal!==undefined?a.className.baseVal:a.className||'')+' '+(a.id||'')+' '+(a.getAttribute('aria-label')||'')+' '+(a.title||'');
    var t=(a.textContent||'').trim();
    if(/disabled/i.test(cls)||a.getAttribute('aria-disabled')==='true')continue;
    if((t.length<=30&&re.test(t))||re.test(cls))cand.push(a.href);}
  var root=function(h){return h.split('.').slice(-2).join('.');};
  for(var j=0;j<cand.length;j++){var h=cand[j];
    if(h&&/^https?:/i.test(h)&&h.split('#')[0]!==cur){try{if(root(new URL(h).hostname)===root(location.hostname))return h;}catch(e){}}}
  return '';}
var og=document.querySelector('meta[property="og:title"]');
var title=q('.subj_episode')||q('h1')||(og&&og.content)||document.title||'';
var novel=q('.detail_header .subj')||q('a.subj')||q('.series-title')||q('.comic-title')||'';
return JSON.stringify({lang:document.documentElement.lang||'',url:location.href,title:title,novel:novel,next:link('next'),prev:link('prev'),imgs:c.slice(0,600)});
"""
    }
}
