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
    /** False once the user navigated away / started another action: the job stops and reports nothing useful. */
    val isValid: () -> Boolean,
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

        private fun fail(e: WebModeError, msg: String = e.label) {
            diag.error = e
            Log.w(TAG, diag.summary())
            onMain { req.onDone(WebModeOutcome.Failure(e, msg, diag)) }
        }

        fun begin() {
            ensureBridge(wv)
            progress("Discovering chapter panels", 1)
            tick(0, -1, 0)
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
            wv.evaluateJavascript(wrap(LIB + JS_COLLECT)) { raw ->
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
            val text = WebModeLogic.assemble(title, texts.map { it.orEmpty() })
            // Where every paragraph of `text` sat on its image, in the same order, so the translation
            // can be put back into the original bubbles (see WebModeWriter.bubbleScript).
            val allBubbles = ArrayList<WmBubble>()
            for (l in layouts) if (l != null) allBubbles.addAll(l)
            WebModeLayoutStore.put(meta.url, WebModeLayout(if (title.trim().isEmpty()) 0 else 1, allBubbles))
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
                val js = "(function(id,u){try{fetch(u,{credentials:'include'}).then(function(r){" +
                    "if(!r.ok)throw new Error('http '+r.status);return r.blob();}).then(function(b){" +
                    "var fr=new FileReader();fr.onload=function(){NsWmBridge.ok(id,String(fr.result));};" +
                    "fr.onerror=function(){NsWmBridge.fail(id,'reader');};fr.readAsDataURL(b);})" +
                    ".catch(function(e){NsWmBridge.fail(id,String(e));});}catch(e){NsWmBridge.fail(id,String(e));}" +
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
                try { readSlice(bmp, st, 0, sample, w, h, src, texts, bubbles) } finally { bmp.recycle() }
            } else {
                @Suppress("DEPRECATION")
                val region = (try { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) } catch (_: Exception) { null })
                    ?: throw DecodeException()
                try {
                    for ((y0, y1) in WebModeLogic.slices(h)) {
                        if (!alive()) break
                        val bmp = region.decodeRegion(Rect(0, y0, w, y1), opts) ?: throw DecodeException()
                        try {
                            if (bmp.width >= 32 && bmp.height >= 32) readSlice(bmp, st, y0, sample, w, h, src, texts, bubbles)
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
            src: String, texts: MutableList<String>, out: MutableList<WmBubble>
        ) {
            val blocks = recognize(bmp, st).blocks.filter { it.text.isNotBlank() }
            if (blocks.isEmpty()) return
            val rects = blocks.map { WmBubbleStyle.rectOf(it, bmp.width, bmp.height) }
            val k = sample.toFloat()
            for ((i, b) in blocks.withIndex()) {
                val rotated = Math.abs(b.angle) >= 3f
                val others = rects.filterIndexed { j, _ -> j != i }
                val s = WmBubbleStyle.analyse(bmp, rects[i], others, expand = !rotated)
                val cx: Float; val cy: Float; val bw: Float; val bh: Float
                if (rotated) {
                    // Keep the text's own rotated box (a little roomier); it is rotated again when drawn.
                    cx = b.cx; cy = b.cy; bw = b.w * 1.1f + 6f; bh = b.h * 1.1f + 6f
                } else {
                    cx = (s.l + s.r) / 2f; cy = (s.t + s.b) / 2f; bw = (s.r - s.l) + 4f; bh = (s.b - s.t) + 4f
                }
                texts.add(b.text.replace(NEWLINES, " ").trim())
                out.add(WmBubble(
                    src = src,
                    cx = cx * k / fullW, cy = (y0 + cy * k) / fullH,
                    w = bw * k / fullW, h = bh * k / fullH,
                    angle = if (rotated) b.angle else 0f,
                    font = b.fontPx * k / fullW,
                    bg = s.bg, fg = s.fg
                ))
            }
        }

        private fun recognize(bmp: Bitmap, st: ScriptState): OcrResult {
            val first = engine.recognize(bmp, st.current)
            if (first.text.length >= 2) { st.locked = true; return first }
            if (!st.locked && st.fallbackBudget > 0) {
                st.fallbackBudget--
                for (s in WebModeLogic.fallbackScripts(st.current)) {
                    val r = engine.recognize(bmp, s)
                    if (r.text.length >= 2) { st.current = s; st.locked = true; return r }
                }
            }
            return first
        }
    }

    private class ScriptState(var current: OcrScript) { var locked = false; var fallbackBudget = 3 }
    private class DecodeException : Exception()
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
var BAD=/avatar|icon|logo|banner|sprite|emoji|profile|badge|pixel|tracking|advert|\/ads?\/|[_\-.]ads?[_\-.]|thumb|btn|button|spinner/i;
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
    if(BAD.test((im.className&&im.className.baseVal!==undefined?im.className.baseVal:im.className||'')+' '+(im.id||'')+' '+(im.alt||'')+' '+src))continue;
    if(im.closest&&im.closest('header,footer,nav,aside,[class*=comment],[class*=recommend],[class*=related],[class*=banner],[id*=comment]'))continue;
    seen[src]=1;
    var top=sc?(r.top-sr.top+sc.scrollTop):(r.top+wy);
    out.push({s:src,t:Math.round(top),l:Math.round(r.left),w:Math.round(r.width),h:Math.round(r.height),d:(im.complete&&nw>0)?1:0});
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

        private const val JS_RESTORE = """
var sc=scroller();
if(sc&&window.__nsWmS!==undefined)sc.scrollTop=window.__nsWmS;
else if(window.__nsWmY!==undefined)window.scrollTo(0,window.__nsWmY);
try{delete window.__nsWmY;delete window.__nsWmS;}catch(e){}
return '1';
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
