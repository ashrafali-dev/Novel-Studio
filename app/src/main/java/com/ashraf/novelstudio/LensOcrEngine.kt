package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException

/**
 * Google Lens as an OCR engine. It ONLY reads the text in the picture: the request carries no
 * translate filter and nothing here knows about languages to translate into. Translation stays the
 * job of NovelStudio's chatbot; this class returns the same [OcrBlock]s every other engine returns.
 *
 * Transport: the (undocumented, unofficial) Chromium Lens upload endpoint, see [LensProtocol].
 * Because it is not a supported API this engine never trusts it blindly:
 *  - every failure (offline, DNS, reset, timeout, 4xx/5xx, unparseable or "no objects" reply) is thrown
 *    as [OcrUnavailableException]; [RoutingOcrEngine] then reads the page with ML Kit instead, and an
 *    unusable Lens reply is never reported as a successful scan;
 *  - a circuit breaker stops hammering a dead endpoint: after repeated transient failures Lens is
 *    skipped for a short while, after a rejected key / changed protocol for a long while, so scans
 *    do not stall on timeouts and the user is not spammed with errors.
 *
 * Network hardening (the two failures this was written against):
 *  - `UnknownHostException` (EAI_NODATA / "No address associated with hostname"): connectivity is
 *    checked first (fail fast when offline); the request is retried with backoff and ALTERNATES between
 *    a connection bound to the active [Network] (its own DNS) and the process default route, because a
 *    stale default network / VPN / Private-DNS edge case can fail one path while the other works.
 *  - `SocketException: Socket closed` / connection reset / EOF: stale keep-alive reuse and a half-closed
 *    pooled connection are the usual causes, so each request asks for `Connection: close`, streams a
 *    fixed-length body, never disconnects while reading, and is retried on a fresh connection. A real
 *    thread interrupt is NOT retried (it is a cancellation, not a network fault).
 */
internal class LensOcrEngine(context: Context) : OcrEngine {

    private val app: Context = context.applicationContext
    private val random = SecureRandom()

    @Volatile private var lastHttpStatus: Int = 0
    @Volatile private var lastHttpMeta: String = "no HTTP response yet"

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult = read(bitmap, script)
    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult = read(bitmap, script)
    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult = read(bitmap, script)

    /** Uploading the same picture again cannot produce a different answer, so there is no "retry view". */
    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult = OcrResult(emptyList())

    override fun close() { synchronized(cache) { cache.clear() } }

    // ---------------------------------------------------------------------------------------------

    private fun read(bitmap: Bitmap, script: OcrScript): OcrResult {
        if (bitmap.isRecycled) throw OcrUnavailableException("Google Lens: bitmap was recycled")
        if (bitmap.width < MIN_SIDE || bitmap.height < MIN_SIDE) return OcrResult(emptyList())

        val t0 = SystemClock.elapsedRealtime()
        val prepared = prepare(bitmap)
        val key = fnv64(prepared.jpeg) xor (prepared.w.toLong() shl 32) xor prepared.h.toLong()

        // recognizeCore() may ask for the same picture several times (other scripts, retry views).
        // Lens detects the language itself, so those would be identical uploads: serve them from memory.
        cached(key)?.let { return finish(it, bitmap, script) }

        Breaker.check()
        val payload = LensProtocol.buildObjectsRequest(
            jpeg = prepared.jpeg, width = prepared.w, height = prepared.h,
            language = languageHint(script), region = regionHint(), timeZone = zoneHint(),
            uuid = random.nextLong() and Long.MAX_VALUE,
            timeUsec = System.currentTimeMillis() * 1000L
        )

        Log.i(TAG, "request begin script=${script.name} source=${bitmap.width}x${bitmap.height} " +
            "upload=${prepared.w}x${prepared.h} jpegBytes=${prepared.jpeg.size} payloadBytes=${payload.size} " +
            "endpoint=${endpoint().substringAfter("://").substringBefore("/")}")
        val body = try {
            post(payload)
        } catch (e: OcrUnavailableException) {
            val diagnostic = "request failed; http=$lastHttpStatus; $lastHttpMeta; error=${e.message ?: e.javaClass.simpleName}"
            OcrEngineStatus.lastLensDiagnostic = diagnostic
            Log.e(TAG, diagnostic)
            throw e
        }

        val response = try {
            LensProtocol.parseResponse(body)
        } catch (e: LensProtocol.ProtocolException) {
            val diagnostic = "parse failed; http=$lastHttpStatus; $lastHttpMeta; replyBytes=${body.size}; " +
                "reason=${e.message ?: "unknown"}"
            OcrEngineStatus.lastLensDiagnostic = diagnostic
            Log.e(TAG, diagnostic)
            // 200 OK but not a Lens reply (captive portal, changed protocol, ...): do not trust, do not retry.
            Breaker.permanent("unreadable reply")
            throw OcrUnavailableException("Google Lens: unreadable reply (${e.message})", e)
        }
        val paragraphCount = response.paragraphs.size
        val lineCount = response.paragraphs.sumOf { it.lines.size }
        val nonEmptyLineCount = response.paragraphs.sumOf { p -> p.lines.count { it.text.isNotBlank() } }
        val textChars = response.paragraphs.sumOf { p -> p.lines.sumOf { it.text.length } }
        val lineBoxes = response.paragraphs.sumOf { p -> p.lines.count { it.box != null } }
        val paragraphBoxes = response.paragraphs.count { it.box != null }
        if (response.serverError != 0) {
            val diagnostic = "server error; http=$lastHttpStatus; $lastHttpMeta; replyBytes=${body.size}; " +
                "objects=${response.valid}; serverError=${response.serverError}; paragraphs=$paragraphCount lines=$lineCount"
            OcrEngineStatus.lastLensDiagnostic = diagnostic
            Log.w(TAG, diagnostic)
            Breaker.transient("server error ${response.serverError}")
            throw OcrUnavailableException("Google Lens: server error ${response.serverError}")
        }
        if (!response.valid) {
            val diagnostic = "missing objects_response; http=$lastHttpStatus; $lastHttpMeta; replyBytes=${body.size}; " +
                "objects=false; paragraphs=$paragraphCount lines=$lineCount"
            OcrEngineStatus.lastLensDiagnostic = diagnostic
            Log.w(TAG, diagnostic)
            Breaker.transient("reply without objects")
            throw OcrUnavailableException("Google Lens: reply without text layer")
        }

        val allBlocks = LensProtocol.toBlocks(response, bitmap.width, bitmap.height, prepared.w, prepared.h)
        val lines = allBlocks.filterNot { OcrPost.isNoise(it) }
            .sortedWith(compareBy({ it.top }, { it.left }))
        val diagnostic = "http=$lastHttpStatus; $lastHttpMeta; replyBytes=${body.size}; objects=${response.valid}; " +
            "shape=${response.shape}; paragraphs=$paragraphCount lines=$lineCount nonEmptyLines=$nonEmptyLineCount " +
            "textChars=$textChars lineBoxes=$lineBoxes paragraphBoxes=$paragraphBoxes " +
            "blocksBeforeNoise=${allBlocks.size} blocksAfterNoise=${lines.size} " +
            "script=${script.name} upload=${prepared.w}x${prepared.h}"
        OcrEngineStatus.lastLensDiagnostic = diagnostic
        if (lines.isEmpty()) Log.w(TAG, "NO_TEXT $diagnostic") else Log.i(TAG, "PARSED $diagnostic")
        Breaker.success()
        remember(key, lines)
        Log.i(TAG, "ok lines=${lines.size} sent=${prepared.w}x${prepared.h} bytes=${prepared.jpeg.size} " +
            "ms=${SystemClock.elapsedRealtime() - t0}")
        return finish(lines, bitmap, script)
    }

    /** Same post-processing the ML Kit engine applies, so the pipeline receives identical granularity. */
    private fun finish(lines: List<OcrBlock>, bitmap: Bitmap, script: OcrScript): OcrResult =
        OcrResult(if (script == OcrScript.LATIN) lines else OcrPost.mergeNeighbours(lines, bitmap))

    // ---- image preparation ---------------------------------------------------------------------

    private class Prepared(val jpeg: ByteArray, val w: Int, val h: Int)

    private fun prepare(src: Bitmap): Prepared {
        var work: Bitmap = src
        var owned: Bitmap? = null
        try {
            if (src.hasAlpha()) {
                // JPEG has no alpha; transparent pixels would turn black. A browser shows white behind them.
                val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
                owned = flat
                work = flat
            }
            val area = work.width.toLong() * work.height.toLong()
            if (area > MAX_AREA) {
                val k = Math.sqrt(MAX_AREA.toDouble() / area.toDouble())
                val sw = (work.width * k).toInt().coerceAtLeast(1)
                val sh = (work.height * k).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(work, sw, sh, true)
                if (scaled !== work) {
                    val old = owned
                    owned = scaled
                    work = scaled
                    if (old != null && old !== src && !old.isRecycled) old.recycle()
                }
            }
            var jpeg = encode(work, JPEG_QUALITY)
            if (jpeg.size > MAX_UPLOAD_BYTES) jpeg = encode(work, 60)
            if (jpeg.size > MAX_UPLOAD_BYTES) throw OcrUnavailableException("Google Lens: picture too large to upload")
            return Prepared(jpeg, work.width, work.height)
        } finally {
            val o = owned
            if (o != null && o !== src && !o.isRecycled) o.recycle()
        }
    }

    private fun encode(b: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(maxOf(32 * 1024, b.width * b.height / 8))
        if (!b.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw OcrUnavailableException("Google Lens: JPEG encode failed")
        return out.toByteArray()
    }

    private fun fnv64(data: ByteArray): Long {
        var h = -3750763034362895579L   // FNV-1a 64-bit offset basis (0xcbf29ce484222325)
        for (b in data) { h = h xor (b.toLong() and 0xFF); h *= 0x100000001b3L }
        return h
    }

    private fun languageHint(script: OcrScript): String = when (script) {
        OcrScript.LATIN -> "en"
        OcrScript.CHINESE -> "zh"
        OcrScript.JAPANESE -> "ja"
        OcrScript.KOREAN -> "ko"
    }

    private fun regionHint(): String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
    private fun zoneHint(): String = TimeZone.getDefault().id.takeIf { it.isNotBlank() } ?: "America/New_York"

    // ---- result cache --------------------------------------------------------------------------

    private class Cached(val at: Long, val lines: List<OcrBlock>)

    private val cache = object : LinkedHashMap<Long, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Cached>?): Boolean = size > CACHE_SIZE
    }

    private fun cached(key: Long): List<OcrBlock>? = synchronized(cache) {
        val c = cache[key] ?: return@synchronized null
        if (SystemClock.elapsedRealtime() - c.at > CACHE_TTL_MS) { cache.remove(key); null } else c.lines
    }

    private fun remember(key: Long, lines: List<OcrBlock>) {
        synchronized(cache) { cache[key] = Cached(SystemClock.elapsedRealtime(), lines) }
    }

    // ---- transport -----------------------------------------------------------------------------

    private class NetInfo(val online: Boolean, val network: Network?)

    /** Asks the OS first so "no connection" fails in microseconds instead of after a DNS timeout. */
    private fun netInfo(): NetInfo {
        val cm = try { app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager } catch (_: Throwable) { null }
            ?: return NetInfo(true, null)
        return try {
            val n = cm.activeNetwork ?: return NetInfo(false, null)
            val caps = cm.getNetworkCapabilities(n) ?: return NetInfo(false, null)
            NetInfo(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), n)
        } catch (_: SecurityException) {
            NetInfo(true, null)       // cannot ask: just try the request
        } catch (_: RuntimeException) {
            NetInfo(true, null)
        }
    }

    private fun endpoint(): String {
        val custom = Prefs.get(app, "lensEndpoint", "").trim()
        return if (custom.startsWith("https://")) custom else DEFAULT_ENDPOINT
    }

    private fun apiKey(): String = Prefs.get(app, "lensApiKey", "").trim().ifEmpty { DEFAULT_API_KEY }

    private class HttpStatus(val code: Int, val retryAfterMs: Long) : IOException("HTTP $code")

    /** One logical request: bounded retries inside one overall deadline. Throws [OcrUnavailableException] only. */
    private fun post(payload: ByteArray): ByteArray {
        lastHttpStatus = 0
        lastHttpMeta = "no HTTP response yet"
        val started = SystemClock.elapsedRealtime()
        var last: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) throw OcrUnavailableException("Google Lens: cancelled")
            val remaining = TOTAL_DEADLINE_MS - (SystemClock.elapsedRealtime() - started)
            if (remaining < MIN_ATTEMPT_MS) break
            val info = netInfo()
            if (!info.online) {
                // Not counted by the breaker: going offline is cheap to detect, and the moment the connection
                // returns Lens must work again instead of staying paused.
                throw OcrUnavailableException("Google Lens: no internet connection")
            }
            var waitMs = BACKOFF_MS shl attempt
            try {
                // Even attempts use a connection bound to the active network (its own DNS); odd ones the
                // process default route. If one path is broken the other usually is not.
                val body = postOnce(payload, info.network, bound = attempt % 2 == 0, budgetMs = remaining)
                return body
            } catch (e: HttpStatus) {
                last = e
                if (e.code !in RETRYABLE_STATUS) {
                    // 400/401/403/404...: wrong key, blocked or changed endpoint. Retrying cannot help.
                    Breaker.permanent("HTTP ${e.code}")
                    throw OcrUnavailableException("Google Lens: HTTP ${e.code} (endpoint or key rejected)", e)
                }
                if (e.retryAfterMs > 0) waitMs = maxOf(waitMs, e.retryAfterMs)
            } catch (e: SocketTimeoutException) {
                last = e                              // SocketTimeoutException IS an InterruptedIOException: keep first
            } catch (e: InterruptedIOException) {
                Thread.currentThread().interrupt()    // genuine cancellation: not a network fault, do not retry
                throw OcrUnavailableException("Google Lens: cancelled", e)
            } catch (e: SSLException) {
                if (isCertificateProblem(e)) {
                    Breaker.permanent("TLS certificate")
                    throw OcrUnavailableException("Google Lens: TLS certificate problem (${describe(e)})", e)
                }
                last = e
            } catch (e: IOException) {
                last = e    // UnknownHostException, ConnectException, SocketException ("Socket closed"), EOF, ...
            }
            val jitter = (random.nextInt(200)).toLong()
            val elapsed = SystemClock.elapsedRealtime() - started
            if (attempt == MAX_ATTEMPTS - 1 || elapsed + waitMs + jitter + MIN_ATTEMPT_MS > TOTAL_DEADLINE_MS) break
            try { Thread.sleep(waitMs + jitter) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw OcrUnavailableException("Google Lens: cancelled", e)
            }
        }
        val why = last?.let { describe(it) } ?: "timed out"
        Breaker.transient(why)
        throw OcrUnavailableException("Google Lens: network failure ($why)", last)
    }

    private fun postOnce(payload: ByteArray, network: Network?, bound: Boolean, budgetMs: Long): ByteArray {
        val url = URL(endpoint())
        if (!url.protocol.equals("https", ignoreCase = true)) throw IOException("refusing non-HTTPS endpoint")
        val raw = if (bound && network != null) network.openConnection(url) else url.openConnection()
        val c = raw as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.doInput = true
            c.useCaches = false
            c.instanceFollowRedirects = false          // a redirect of a protobuf POST means "not the real endpoint"
            c.connectTimeout = minOf(CONNECT_MS, budgetMs).toInt()
            c.readTimeout = minOf(READ_MS, budgetMs).toInt()
            c.setFixedLengthStreamingMode(payload.size)
            c.setRequestProperty("Content-Type", "application/x-protobuf")
            c.setRequestProperty("X-Goog-Api-Key", apiKey())
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Connection", "close")    // never reuse a pooled socket the peer may have closed
            // No Accept-Encoding header on purpose: then HttpURLConnection negotiates gzip AND decodes it itself.
            c.outputStream.use { it.write(payload); it.flush() }
            val code = c.responseCode
            lastHttpStatus = code
            lastHttpMeta = "contentType=${c.contentType ?: "unknown"} encoding=${c.contentEncoding ?: "none"} " +
                "headerLength=${c.getHeaderField("Content-Length") ?: "unknown"} requestBytes=${payload.size}"
            if (code !in 200..299) {
                Log.w(TAG, "HTTP_REJECTED status=$code $lastHttpMeta")
                val retryAfter = (c.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L).coerceIn(0L, 10L) * 1000L
                throw HttpStatus(code, retryAfter)
            }
            // Read to the end BEFORE disconnect(): disconnecting under a pending read is what produces
            // "Socket closed".
            val reply = c.inputStream.use { ins ->
                val out = ByteArrayOutputStream(16 * 1024)
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_RESPONSE_BYTES) throw IOException("Lens reply too large")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            Log.i(TAG, "HTTP_RESPONSE status=$code $lastHttpMeta replyBytes=${reply.size}")
            return reply
        } finally {
            try { c.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun isCertificateProblem(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 8) { if (t is CertificateException) return true; t = t.cause }
        return false
    }

    /** "UnknownHostException (GaiException: android_getaddrinfo failed: EAI_NODATA ...)" - no recognised text involved. */
    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.take(8).last()
        val head = e.javaClass.simpleName
        val tail = (root.message ?: e.message ?: "").replace(Regex("\\s+"), " ").trim().take(110)
        val rootName = if (root !== e) root.javaClass.simpleName + (if (tail.isNotEmpty()) ": " else "") else ""
        return head + (if (tail.isNotEmpty() || rootName.isNotEmpty()) " ($rootName$tail)" else "")
    }

    // ---- circuit breaker (process-wide, so recreating the engine does not forget a dead endpoint) ------

    private object Breaker {
        private var openUntil = 0L
        private var reason = ""
        private var transientFails = 0

        @Synchronized fun check() {
            // Stable text on purpose (no countdown): the router toasts each DISTINCT message only once.
            if (openUntil - SystemClock.elapsedRealtime() > 0) throw OcrUnavailableException("Google Lens: paused after $reason")
        }

        @Synchronized fun success() { transientFails = 0; openUntil = 0L; reason = "" }

        @Synchronized fun transient(why: String) {
            reason = why
            if (++transientFails >= TRANSIENT_TRIP) {
                transientFails = 0
                openUntil = SystemClock.elapsedRealtime() + TRANSIENT_PAUSE_MS
                Log.w(TAG, "pausing Lens ${TRANSIENT_PAUSE_MS / 1000}s: $why")
            }
        }

        @Synchronized fun permanent(why: String) {
            reason = why
            transientFails = 0
            openUntil = SystemClock.elapsedRealtime() + PERMANENT_PAUSE_MS
            Log.w(TAG, "pausing Lens ${PERMANENT_PAUSE_MS / 60000} min: $why")
        }
    }

    companion object {
        private const val TAG = "NovelStudioLens"

        /** Chromium's Lens overlay upload. Unofficial for third-party apps: may change or be blocked anytime. */
        const val DEFAULT_ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"

        /**
         * The public key embedded in Chromium's Lens overlay (identical in all four reference projects).
         * It is Google's, not ours; it can be rotated or revoked. Override with Prefs "lensApiKey".
         */
        private const val DEFAULT_API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val MIN_SIDE = 16
        private const val MAX_AREA = 3_000_000L
        private const val JPEG_QUALITY = 85
        private const val MAX_UPLOAD_BYTES = 3_500_000
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

        private const val CONNECT_MS = 7_000L
        private const val READ_MS = 15_000L
        private const val TOTAL_DEADLINE_MS = 22_000L
        private const val MIN_ATTEMPT_MS = 2_500L
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MS = 400L
        private val RETRYABLE_STATUS = setOf(429, 500, 502, 503, 504)

        private const val TRANSIENT_TRIP = 2
        private const val TRANSIENT_PAUSE_MS = 45_000L
        private const val PERMANENT_PAUSE_MS = 10 * 60_000L

        private const val CACHE_SIZE = 8
        private const val CACHE_TTL_MS = 3 * 60_000L
    }
}
