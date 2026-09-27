package com.ashraf.novelstudio

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class WebtoonActivity : Activity() {
    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val states = ConcurrentHashMap<String, ImageState>()
    private val hashes = ConcurrentHashMap<String, String>()
    private val regions = ConcurrentHashMap<String, List<WebtoonTextRegion>>()
    private val visuals = ConcurrentHashMap<String, Pair<String, String>>()
    private val blobCallbacks = HashMap<String, (ByteArray?) -> Unit>()
    private val queue = ArrayDeque<WebtoonTranslationBatch>()
    private val attempts = HashMap<String, Int>()

    private lateinit var webView: WebView
    private lateinit var chatWebView: WebView
    private lateinit var urlBar: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var ocr: WebtoonOcr
    private lateinit var ocrCache: WebtoonCache
    private lateinit var translationCache: WebtoonCache

    private var auto = true
    private var ocrReady = false
    private var destroyed = false
    private var translating = false
    private var translationToken = 0
    private var scanScheduled = false

    private val scanLoop = object : Runnable {
        override fun run() {
            if (destroyed || !auto) return
            scanNow()
            handler.postDelayed(this, 1300L)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        CookieManager.getInstance().setAcceptCookie(true)

        ocr = WebtoonOcr(this)
        ocrCache = WebtoonCache(this, "webtoon_ocr_cache.json", 700)
        translationCache = WebtoonCache(this, "webtoon_translation_cache.json", 1400)

        buildUi()
        auto = Prefs.bool(this, "webtoonAuto", true)

        val startUrl = intent.getStringExtra("url").orEmpty().ifBlank {
            Prefs.get(this, "lastNovelUrl", "https://duckduckgo.com/")
        }
        urlBar.setText(startUrl)
        webView.loadUrl(startUrl)
        chatWebView.loadUrl(Prefs.get(this, "botUrl", "https://chatgpt.com/"))

        handler.postDelayed({ prepareOcr() }, 250L)
        if (auto) handler.postDelayed(scanLoop, 900L)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(wc, dp(46)).also {
                it.setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener { action() }
        }

    private fun buildUi() {
        webView = WebView(this)
        chatWebView = WebView(this)
        setupWebView(webView)
        setupWebView(chatWebView)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, icon: Bitmap?) {
                if (!urlBar.hasFocus() && !url.isNullOrBlank()) urlBar.setText(url)
                clearOverlay()
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                if (!url.isNullOrBlank()) {
                    urlBar.setText(url)
                    Prefs.put(this@WebtoonActivity, "lastNovelUrl", url)
                }
                v?.evaluateJavascript(WebtoonJs.install(), null)
                v?.evaluateJavascript(WebtoonJs.scan()) { scanRaw(it) }
                requestScanSoon()
            }

            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val u = r?.url?.toString() ?: return false
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }
        }
        webView.webChromeClient = WebChromeClient()

        chatWebView.webViewClient = WebViewClient()
        chatWebView.webChromeClient = WebChromeClient()
        chatWebView.alpha = 0f
        chatWebView.addJavascriptInterface(Bridge(), "NSWT")

        urlBar = EditText(this).apply {
            hint = "Webtoon link…"
            setSingleLine(true)
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x99FFFFFF.toInt())
            setBackgroundColor(0xFF25252B.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            setOnEditorActionListener { _, _, _ -> loadUrlFromBar(); true }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF17171B.toInt())
            addView(urlBar, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(btn("Go") { loadUrlFromBar() }, LinearLayout.LayoutParams(wc, dp(44)))
            addView(btn("⟳") { webView.reload() }, LinearLayout.LayoutParams(wc, dp(44)))
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        status = TextView(this).apply {
            text = "Webtoon প্রস্তুত হচ্ছে…"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(9), dp(3), dp(9), dp(3))
            setBackgroundColor(0xCC000000.toInt())
        }

        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(progress, LinearLayout.LayoutParams(mp, dp(3)))
            addView(status, LinearLayout.LayoutParams(mp, wc))
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF17171B.toInt())
            addView(btn("🖼 Scan") { scanNow() })
            addView(btn("⚡ Auto") { toggleAuto() })
            addView(btn("🧪 Demo") {
                webView.loadUrl("https://raw.githubusercontent.com/ashrafali-dev/Novel-Studio/main/webtoon_test.html")
            })
            addView(btn("🧹 Clear") { clearOverlay() })
            addView(btn("✕") { finish() })
        }

        val layers = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(mp, mp))
            addView(chatWebView, FrameLayout.LayoutParams(dp(2), dp(2), Gravity.END or Gravity.BOTTOM))
            addView(statusBox, FrameLayout.LayoutParams(mp, wc, Gravity.TOP))
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF111114.toInt())
                addView(top, LinearLayout.LayoutParams(mp, wc))
                addView(layers, LinearLayout.LayoutParams(mp, 0, 1f))
                addView(bottom, LinearLayout.LayoutParams(mp, wc))
            }
        )
    }

    private fun setupWebView(v: WebView) {
        val s = v.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        v.setBackgroundColor(0xFF111114.toInt())
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
    }

    private fun loadUrlFromBar() {
        val raw = urlBar.text.toString().trim()
        if (raw.isBlank()) return
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            !raw.contains(' ') && raw.contains('.') -> "https://" + raw
            else -> "https://duckduckgo.com/?q=" + Uri.encode(raw)
        }
        webView.loadUrl(url)
    }

    private fun prepareOcr() {
        setStatus("OCR engine প্রস্তুত করছি…", 5)
        executor.submit {
            try {
                ocr.prepare { p ->
                    handler.post {
                        if (!destroyed) {
                            progress.progress = p.percent
                            status.text = "🧠 " + p.stage + "  " + p.percent + "%"
                        }
                    }
                }
                handler.post {
                    if (!destroyed) {
                        ocrReady = true
                        setStatus("✅ OCR ready — পড়তে থাকো, পিছনে অনুবাদ হবে", 100)
                        scanNow()
                    }
                }
            } catch (t: Throwable) {
                handler.post {
                    if (!destroyed) {
                        setStatus("❌ OCR প্রস্তুত হয়নি: " + (t.message ?: "unknown error"), 0)
                    }
                }
            }
        }
    }

    private fun requestScanSoon() {
        if (scanScheduled || destroyed) return
        scanScheduled = true
        handler.postDelayed({
            scanScheduled = false
            if (!destroyed) scanNow()
        }, 450L)
    }

    private fun scanNow() {
        if (destroyed) return
        webView.evaluateJavascript(WebtoonJs.install(), null)
        webView.evaluateJavascript(WebtoonJs.scan()) { scanRaw(it) }
    }

    private fun scanRaw(raw: String?) {
        if (destroyed || raw.isNullOrBlank()) return
        val json = decodeJs(raw)
        if (json.isBlank()) return
        runCatching {
            val a = JSONArray(json)
            val list = ArrayList<WebtoonImageRef>(a.length())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list += WebtoonImageRef(
                    id = o.optString("id"),
                    src = o.optString("src"),
                    top = o.optDouble("top", 0.0).toFloat(),
                    left = o.optDouble("left", 0.0).toFloat(),
                    width = o.optDouble("width", 0.0).toFloat(),
                    height = o.optDouble("height", 0.0).toFloat(),
                    naturalWidth = o.optInt("nw", 0),
                    naturalHeight = o.optInt("nh", 0),
                    visible = o.optBoolean("visible", false),
                    distance = o.optDouble("distance", 0.0).toFloat()
                )
            }
            list.sortWith(
                compareByDescending<WebtoonImageRef> { it.visible }
                    .thenBy { it.distance }
                    .thenBy { it.top }
            )
            for (ref in list.distinctBy { it.id }.take(7)) {
                if (ocrReady) processImage(ref)
            }
        }
    }

    private fun processImage(ref: WebtoonImageRef) {
        val old = states[ref.id]
        if (old != null) {
            when (old.state) {
                WebtoonImageState.LOADING,
                WebtoonImageState.OCR,
                WebtoonImageState.WAITING_AI -> return
                WebtoonImageState.READY -> {
                    renderReady(ref.id)
                    return
                }
                WebtoonImageState.FAILED -> {
                    if (System.currentTimeMillis() - old.lastAttempt < 10_000L) return
                }
                WebtoonImageState.NEW -> {}
            }
        }

        states[ref.id] = ImageState(WebtoonImageState.LOADING, System.currentTimeMillis())
        loadBytes(ref) { bytes ->
            if (bytes == null || bytes.isEmpty()) {
                states[ref.id] = ImageState(WebtoonImageState.FAILED, System.currentTimeMillis())
                setStatus("⚠️ panel image পাওয়া যায়নি", 0)
                return@loadBytes
            }

            executor.submit {
                try {
                    // Lazy-loaded Webtoon images often report naturalWidth/naturalHeight = 0
                    // even though the downloaded bytes contain the full panel. OCR coordinates
                    // must use the real bitmap dimensions, not the placeholder DOM dimensions.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val actualRef = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        ref.copy(naturalWidth = bounds.outWidth, naturalHeight = bounds.outHeight)
                    } else {
                        ref
                    }

                    val imageHash = WebtoonHash.sha256(bytes)
                    val key = WebtoonHash.key(WebtoonOcr.MODEL_VERSION, imageHash)
                    val cache = ocrCache.get(key)
                    val detected = if (!cache.isNullOrBlank()) {
                        readRegions(cache)
                    } else {
                        val result = ocr.recognize(bytes)
                        val found = buildRegions(actualRef, bytes, result.results)
                        saveRegions(key, found)
                        found
                    }

                    hashes[ref.id] = imageHash
                    regions[ref.id] = detected

                    handler.post {
                        if (!destroyed) {
                            states[ref.id] =
                                ImageState(WebtoonImageState.WAITING_AI, System.currentTimeMillis())
                            if (detected.isEmpty()) {
                                states[ref.id] =
                                    ImageState(WebtoonImageState.READY, System.currentTimeMillis())
                                setStatus("🫥 এই panel-এ পাঠযোগ্য text নেই — skip", 100)
                            } else {
                                enqueueTranslations(ref.id)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    handler.post {
                        if (!destroyed) {
                            states[ref.id] =
                                ImageState(WebtoonImageState.FAILED, System.currentTimeMillis())
                            setStatus("❌ OCR failed: " + (t.message ?: "unknown"), 0)
                        }
                    }
                }
            }
        }
    }

    private fun loadBytes(ref: WebtoonImageRef, cb: (ByteArray?) -> Unit) {
        fun finish(bytes: ByteArray?) {
            handler.post { if (!destroyed) cb(bytes) }
        }

        val src = ref.src.trim()
        val pageUrl = webView.url.orEmpty()
        when {
            src.startsWith("data:image/", true) -> executor.submit {
                finish(runCatching { WebtoonHttp.loadImage(src, pageUrl) }.getOrNull())
            }
            src.startsWith("blob:", true) -> {
                blobCallbacks[ref.id] = cb
                webView.evaluateJavascript(WebtoonJs.requestImageData(ref.id), null)
            }
            else -> {
                val absolute = runCatching { URI(pageUrl).resolve(src).toString() }.getOrNull() ?: src
                val cookie = runCatching { CookieManager.getInstance().getCookie(absolute) }.getOrNull()
                executor.submit {
                    finish(runCatching {
                        WebtoonHttp.loadImage(absolute, pageUrl, cookie)
                    }.getOrNull())
                }
            }
        }
    }

    private fun enqueueTranslations(imageId: String) {
        val all = regions[imageId].orEmpty()
        if (all.isEmpty()) return
        val hash = hashes[imageId].orEmpty()
        val missing = all.filter {
            translationCache.get(translationKey(hash, it.text)) == null
        }

        if (missing.isEmpty()) {
            states[imageId] = ImageState(WebtoonImageState.READY, System.currentTimeMillis())
            renderReady(imageId)
            return
        }

        for (b in WebtoonBatcher.makeBatches(missing)) queue.addLast(b)
        pumpTranslations()
    }

    private fun translationKey(imageHash: String, text: String): String =
        WebtoonHash.key("bn-v1", imageHash, text.trim().replace(Regex("\\s+"), " "))

    private fun pumpTranslations() {
        if (destroyed || translating || queue.isEmpty()) {
            updateTranslationStatus()
            return
        }
        translating = true
        val batch = queue.removeFirst()
        val key = batch.imageId + "|" + batch.items.joinToString(",") { it.id }
        val attempt = attempts[key] ?: 0
        attempts[key] = attempt + 1
        waitChatIdle(translationToken, batch, buildPrompt(batch), attempt, 0)
        updateTranslationStatus()
    }

    private fun buildPrompt(batch: WebtoonTranslationBatch): String {
        val ids = batch.items.joinToString(", ") { it.id }
        return Prefs.prompt(this) +
            "\n\n---\nWEBTOON BATCH TRANSLATION\n" +
            "Translate every source region into natural, fluent literary Bengali. " +
            "Preserve personality, emotion, humor, urgency and scene context.\n" +
            "Do not add explanations or headings. Keep every ID exactly.\n" +
            "Return exactly one line entry per ID in this form:\n" +
            "[ID] Bengali translation\n" +
            "IDs required: " + ids + "\n\nSOURCE:\n" +
            WebtoonBatcher.buildPayload(batch)
    }

    private fun waitChatIdle(
        token: Int,
        batch: WebtoonTranslationBatch,
        prompt: String,
        attempt: Int,
        tries: Int
    ) {
        if (destroyed || token != translationToken) return
        chatWebView.evaluateJavascript(Js.readLen()) { raw ->
            if (destroyed || token != translationToken) return@evaluateJavascript
            val p = decodeJs(raw).split("|")
            val streaming = p.getOrNull(1) == "1"
            if (streaming && tries < 8) {
                handler.postDelayed(
                    { waitChatIdle(token, batch, prompt, attempt, tries + 1) },
                    700L
                )
            } else {
                sendBatch(token, batch, prompt, attempt)
            }
        }
    }

    private fun sendBatch(
        token: Int,
        batch: WebtoonTranslationBatch,
        prompt: String,
        attempt: Int
    ) {
        chatWebView.evaluateJavascript(Js.send(prompt, true)) { raw ->
            if (destroyed || token != translationToken) return@evaluateJavascript
            val result = decodeJs(raw)
            if (!result.startsWith("ok:")) {
                retryBatch(batch, attempt, "চ্যাটবট বক্স পাওয়া যায়নি")
                return@evaluateJavascript
            }
            val p = result.substring(3).split(":")
            val beforeCount = p.getOrNull(0)?.toIntOrNull() ?: 0
            val beforeLen = p.getOrNull(1)?.toIntOrNull() ?: 0
            handler.postDelayed({
                pollBatch(token, batch, attempt, beforeCount, beforeLen, 0, 0)
            }, 1400L)
        }
    }

    private fun pollBatch(
        token: Int,
        batch: WebtoonTranslationBatch,
        attempt: Int,
        beforeCount: Int,
        beforeLen: Int,
        stable: Int,
        elapsed: Int
    ) {
        if (destroyed || token != translationToken) return
        chatWebView.evaluateJavascript(Js.readLen()) { raw ->
            if (destroyed || token != translationToken) return@evaluateJavascript
            val p = decodeJs(raw).split("|")
            val count = p.getOrNull(0)?.toIntOrNull() ?: 0
            val streaming = p.getOrNull(1) == "1"
            val len = p.getOrNull(2)?.toIntOrNull() ?: 0
            val got = count > beforeCount || (count == beforeCount && len > beforeLen)
            val stableNow =
                if (got && len == beforeLen) stable + 1 else if (got) 0 else stable

            when {
                got && !streaming && stableNow >= 2 -> finishBatch(batch, attempt)
                elapsed >= 70 -> retryBatch(batch, attempt, "AI উত্তর আসতে দেরি হচ্ছে")
                else -> handler.postDelayed({
                    pollBatch(
                        token, batch, attempt, beforeCount, beforeLen,
                        stableNow, elapsed + 1
                    )
                }, 1000L)
            }
        }
    }

    private fun finishBatch(batch: WebtoonTranslationBatch, attempt: Int) {
        chatWebView.evaluateJavascript(Js.readText()) { raw ->
            if (destroyed) return@evaluateJavascript
            val parsed = parseTranslations(decodeJs(raw))
            val missing = ArrayList<WebtoonTextRegion>()

            for (region in batch.items) {
                val tr = parsed[region.id].orEmpty().trim()
                if (tr.isNotEmpty()) {
                    val hash = hashes[region.imageId].orEmpty()
                    translationCache.put(translationKey(hash, region.text), tr)
                    renderReady(region.imageId)
                } else {
                    missing += region
                }
            }

            translating = false
            if (missing.isNotEmpty() && attempt < 2) {
                queue.addFirst(
                    WebtoonTranslationBatch(
                        imageId = batch.imageId,
                        items = missing,
                        estimatedChars = missing.sumOf { it.text.length }
                    )
                )
            } else if (missing.isNotEmpty()) {
                states[batch.imageId] =
                    ImageState(WebtoonImageState.FAILED, System.currentTimeMillis())
                setStatus("⚠️ কিছু dialogue অনুবাদ পাওয়া যায়নি — আবার Scan করো", 0)
            }
            pumpTranslations()
        }
    }

    private fun parseTranslations(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        val clean = text
            .replace("\u0060\u0060\u0060text", "")
            .replace("\u0060\u0060\u0060", "")
            .trim()
        val re =
            Regex("""(?ms)^\s*\[([^\]]+)]\s*(.*?)(?=^\s*\[[^\]]+\]\s*|\z)""")
        for (m in re.findAll(clean)) {
            val id = m.groupValues[1].trim()
            val value = m.groupValues[2].trim()
            if (id.isNotEmpty() && value.isNotEmpty()) out[id] = value
        }
        if (out.isNotEmpty()) return out

        runCatching {
            val o = JSONObject(clean)
            for (k in o.keys()) {
                val v = o.optString(k, "").trim()
                if (v.isNotEmpty()) out[k.removePrefix("[").removeSuffix("]")] = v
            }
        }
        return out
    }

    private fun retryBatch(
        batch: WebtoonTranslationBatch,
        attempt: Int,
        reason: String
    ) {
        translating = false
        if (attempt < 2) {
            queue.addFirst(batch)
            setStatus("↻ " + reason + " — আবার চেষ্টা করছি", 5)
        } else {
            states[batch.imageId] =
                ImageState(WebtoonImageState.FAILED, System.currentTimeMillis())
            setStatus("❌ " + reason, 0)
        }
        pumpTranslations()
    }

    private fun renderReady(imageId: String) {
        if (destroyed) return
        val list = regions[imageId].orEmpty()
        if (list.isEmpty()) return
        val hash = hashes[imageId].orEmpty()
        val out = JSONArray()
        var complete = true

        for (r in list) {
            val tr = translationCache.get(translationKey(hash, r.text))
            if (tr.isNullOrBlank()) {
                complete = false
                continue
            }
            val visual = visuals[r.id] ?: Pair("#f7f7f7", "#111111")
            out.put(
                JSONObject()
                    .put("id", r.id)
                    .put("x", r.x).put("y", r.y)
                    .put("width", r.width).put("height", r.height)
                    .put("text", tr)
                    .put("background", visual.first)
                    .put("foreground", visual.second)
            )
        }

        if (out.length() > 0) {
            webView.evaluateJavascript(
                WebtoonJs.render(imageId, out.toString()),
                null
            )
        }
        if (complete) {
            states[imageId] =
                ImageState(WebtoonImageState.READY, System.currentTimeMillis())
        }
        updateTranslationStatus()
    }

    private fun readRegions(json: String): List<WebtoonTextRegion> {
        val out = ArrayList<WebtoonTextRegion>()
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = o.optString("id")
            out += WebtoonTextRegion(
                id = id,
                imageId = o.optString("imageId"),
                text = o.optString("text"),
                x = o.optDouble("x").toFloat(),
                y = o.optDouble("y").toFloat(),
                width = o.optDouble("width").toFloat(),
                height = o.optDouble("height").toFloat()
            )
            visuals[id] = Pair(
                o.optString("background", "#f7f7f7"),
                o.optString("foreground", "#111111")
            )
        }
        return out
    }

    private fun saveRegions(key: String, list: List<WebtoonTextRegion>) {
        val a = JSONArray()
        for (r in list) {
            val v = visuals[r.id] ?: Pair("#f7f7f7", "#111111")
            a.put(
                JSONObject()
                    .put("id", r.id).put("imageId", r.imageId)
                    .put("text", r.text)
                    .put("x", r.x).put("y", r.y)
                    .put("width", r.width).put("height", r.height)
                    .put("background", v.first).put("foreground", v.second)
            )
        }
        ocrCache.put(key, a.toString())
    }

    private fun buildRegions(
        ref: WebtoonImageRef,
        bytes: ByteArray,
        ocrResults: List<WebtoonOcrResult>
    ): List<WebtoonTextRegion> {
        val raw = ArrayList<WebtoonTextRegion>()
        for (item in ocrResults) {
            val text = item.text.replace(Regex("\\s+"), " ").trim()
            if (text.isBlank() || text.length > 180) continue
            if (item.confidence < 0.30f) continue
            if (!text.any { it.isLetterOrDigit() }) continue

            val xs = item.points.map { it.x }
            val ys = item.points.map { it.y }
            val x0 = xs.minOrNull()?.coerceIn(0f, ref.naturalWidth.toFloat()) ?: continue
            val y0 = ys.minOrNull()?.coerceIn(0f, ref.naturalHeight.toFloat()) ?: continue
            val x1 = xs.maxOrNull()?.coerceIn(0f, ref.naturalWidth.toFloat()) ?: continue
            val y1 = ys.maxOrNull()?.coerceIn(0f, ref.naturalHeight.toFloat()) ?: continue
            val w = x1 - x0
            val h = y1 - y0
            if (w < 8f || h < 8f) continue

            val id = "wt_" + WebtoonHash.key(
                ref.id,
                String.format(Locale.US, "%.0f", x0),
                String.format(Locale.US, "%.0f", y0),
                text
            ).take(12)

            raw += WebtoonTextRegion(id, ref.id, text, x0, y0, w, h)
        }

        raw.sortWith(compareBy<WebtoonTextRegion> { it.y }.thenBy { it.x })
        val merged = ArrayList<WebtoonTextRegion>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null) {
                val overlap = max(
                    0f,
                    min(last.x + last.width, r.x + r.width) - max(last.x, r.x)
                )
                val ratio = overlap / min(last.width, r.width).coerceAtLeast(1f)
                val gap = r.y - (last.y + last.height)
                if (ratio >= 0.24f && gap <= max(last.height, r.height) * 0.85f) {
                    val nx = min(last.x, r.x)
                    val ny = min(last.y, r.y)
                    val rx = max(last.x + last.width, r.x + r.width)
                    val by = max(last.y + last.height, r.y + r.height)
                    val mergedText = last.text + "\n" + r.text
                    merged[merged.lastIndex] = last.copy(
                        id = "wt_" + WebtoonHash.key(
                            ref.id,
                            String.format(Locale.US, "%.0f", nx),
                            String.format(Locale.US, "%.0f", ny),
                            mergedText
                        ).take(12),
                        text = mergedText,
                        x = nx,
                        y = ny,
                        width = rx - nx,
                        height = by - ny
                    )
                    continue
                }
            }
            merged += r
        }

        for (r in merged) {
            visuals[r.id] =
                sampleBackground(bytes, r, ref.naturalWidth, ref.naturalHeight)
        }
        return merged
    }

    private fun sampleBackground(
        bytes: ByteArray,
        r: WebtoonTextRegion,
        naturalWidth: Int,
        naturalHeight: Int
    ): Pair<String, String> = runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        val sample = max(1, max(b.outWidth, b.outHeight) / 1200)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return@runCatching Pair("#f7f7f7", "#111111")

        val x0 = ((r.x / naturalWidth.coerceAtLeast(1)) * bmp.width)
            .toInt().coerceIn(0, bmp.width - 1)
        val y0 = ((r.y / naturalHeight.coerceAtLeast(1)) * bmp.height)
            .toInt().coerceIn(0, bmp.height - 1)
        val x1 = (((r.x + r.width) / naturalWidth.coerceAtLeast(1)) * bmp.width)
            .toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = (((r.y + r.height) / naturalHeight.coerceAtLeast(1)) * bmp.height)
            .toInt().coerceIn(y0 + 1, bmp.height)

        var rs = 0L
        var gs = 0L
        var bs = 0L
        var n = 0
        val sx = max(1, (x1 - x0) / 8)
        val sy = max(1, (y1 - y0) / 8)

        fun samplePixel(x: Int, y: Int) {
            val c = bmp.getPixel(x, y)
            rs += android.graphics.Color.red(c)
            gs += android.graphics.Color.green(c)
            bs += android.graphics.Color.blue(c)
            n++
        }

        for (x in x0 until x1 step sx) {
            samplePixel(x, y0)
            samplePixel(x, max(y0, y1 - 1))
        }
        for (y in y0 until y1 step sy) {
            samplePixel(x0, y)
            samplePixel(max(x0, x1 - 1), y)
        }
        bmp.recycle()

        if (n == 0) return@runCatching Pair("#f7f7f7", "#111111")
        val r8 = (rs / n).toInt().coerceIn(0, 255)
        val g8 = (gs / n).toInt().coerceIn(0, 255)
        val b8 = (bs / n).toInt().coerceIn(0, 255)
        val lum = 0.2126 * r8 + 0.7152 * g8 + 0.0722 * b8
        val bg = String.format(Locale.US, "#%02x%02x%02x", r8, g8, b8)
        Pair(bg, if (lum > 145) "#111111" else "#ffffff")
    }.getOrDefault(Pair("#f7f7f7", "#111111"))

    private fun updateTranslationStatus() {
        val queued = queue.sumOf { it.items.size }
        val ready = states.values.count { it.state == WebtoonImageState.READY }
        status.text = "🖼 ready:" + ready +
            "  queue:" + queued +
            if (translating) " • AI batch চলছে" else ""
    }

    private fun setStatus(s: String, pct: Int) {
        status.text = s
        progress.progress = pct.coerceIn(0, 100)
    }

    private fun toggleAuto() {
        auto = !auto
        Prefs.putBool(this, "webtoonAuto", auto)
        handler.removeCallbacks(scanLoop)
        if (auto) handler.post(scanLoop)
        setStatus(if (auto) "⚡ Auto scan ON" else "⏸ Auto scan OFF", 100)
    }

    private fun clearOverlay() {
        webView.evaluateJavascript(WebtoonJs.clear(), null)
    }

    private fun decodeJs(raw: String?): String = try {
        if (raw.isNullOrBlank() || raw == "null") "" else JSONArray("[" + raw + "]").getString(0)
    } catch (_: Throwable) {
        ""
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        destroyed = true
        translationToken++
        handler.removeCallbacksAndMessages(null)
        blobCallbacks.clear()
        queue.clear()
        executor.shutdownNow()
        runCatching { ocr.close() }
        runCatching { webView.destroy() }
        runCatching { chatWebView.destroy() }
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun imageDataReady(id: String, dataUrl: String) {
            handler.post {
                val cb = blobCallbacks.remove(id) ?: return@post
                if (!dataUrl.startsWith("data:image/", true)) {
                    cb(null)
                    return@post
                }
                executor.submit {
                    val bytes = runCatching {
                        WebtoonHttp.loadImage(dataUrl, webView.url.orEmpty())
                    }.getOrNull()
                    handler.post { if (!destroyed) cb(bytes) }
                }
            }
        }
    }

    private data class ImageState(
        val state: WebtoonImageState,
        val lastAttempt: Long
    )
}
