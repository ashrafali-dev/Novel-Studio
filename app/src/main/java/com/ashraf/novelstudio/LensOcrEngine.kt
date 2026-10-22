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
 *  - `SocketException: Socket closed` / connection reset / EOF: stream a fixed-length body and
 *    fully drain successful responses so Android can reuse a healthy keep-alive connection, matching
 *    the pooled-client strategy in chrome-lens-py. Only partial/error responses are disconnected and retried.
 *    A real thread interrupt is NOT retried (it is a cancellation, not a network fault).
 */
internal class LensOcrEngine(context: Context) : OcrEngine {

    private val app: Context = context.applicationContext
    private val random = SecureRandom()

    // Concurrent foreground/read-ahead Lens requests must keep their diagnostics request-local.
    private val lastHttpStatus = ThreadLocal<Int>()
    private val lastHttpMeta = ThreadLocal<String>()

    private fun currentHttpStatus(): Int = lastHttpStatus.get() ?: 0
    private fun currentHttpMeta(): String = lastHttpMeta.get() ?: "no HTTP response yet"

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult = read(bitmap, script)
    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult = read(bitmap, script, fastMode = true)
    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult = read(bitmap, script)

    /**
     * The router can request a fallback pass after a partial read. Return the cached Lens result for
     * the same bitmap (read() hits its image cache) rather than reporting an artificial empty result,
     * which incorrectly changes the actual-engine status to ML Kit.
     */
    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult = read(bitmap, script)

    override fun close() { synchronized(cache) { cache.clear() } }

    // ---------------------------------------------------------------------------------------------

    private fun read(bitmap: Bitmap, script: OcrScript, fastMode: Boolean = false): OcrResult {
        if (bitmap.isRecycled) throw OcrUnavailableException("Google Lens: bitmap was recycled")
        if (bitmap.width < MIN_SIDE || bitmap.height < MIN_SIDE) return OcrResult(emptyList())

        val t0 = SystemClock.elapsedRealtime()
        val prepared = prepare(bitmap)
        val prepareMs = SystemClock.elapsedRealtime() - t0
        val keyStart = SystemClock.elapsedRealtime()
        val key = fnv64(prepared.jpeg) xor (prepared.w.toLong() shl 32) xor prepared.h.toLong()

        // Lens detects the image language itself. This cache is mainly for external callers that repeat one bitmap.
        val cachedLines = cached(key)
        val keyCacheMs = SystemClock.elapsedRealtime() - keyStart
        if (cachedLines != null) {
            val cachedLetters = cachedLines.sumOf { block -> block.text.count { ch -> ch.isLetterOrDigit() } }
            val result = finish(cachedLines, bitmap, script)
            val totalMs = SystemClock.elapsedRealtime() - t0
            val diagnostic = "LENS_CACHE_HIT imageId=${java.lang.Long.toHexString(key)} script=${script.name} " +
                "image=${bitmap.width}x${bitmap.height} cachedLines=${cachedLines.size} cachedLetters=$cachedLetters " +
                "timing=prepare:${prepareMs}ms,keyCache:${keyCacheMs}ms,post:${(totalMs - prepareMs - keyCacheMs).coerceAtLeast(0L)}ms,total:${totalMs}ms"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
            Log.i(TAG, diagnostic)
            Log.i(TAG, "TIMING imageId=${java.lang.Long.toHexString(key)} cacheHit=true prepareMs=$prepareMs keyCacheMs=$keyCacheMs totalMs=$totalMs")
            return result
        }

        Breaker.check()
        val payloadStart = SystemClock.elapsedRealtime()
        val payload = LensProtocol.buildObjectsRequest(
            jpeg = prepared.jpeg, width = prepared.w, height = prepared.h,
            language = languageHint(), region = regionHint(), timeZone = zoneHint(),
            uuid = random.nextLong() and Long.MAX_VALUE,
            timeUsec = System.currentTimeMillis() * 1000L,
        )
        val payloadBuildMs = SystemClock.elapsedRealtime() - payloadStart

        Log.i(TAG, "request begin imageId=${java.lang.Long.toHexString(key)} script=${script.name} source=${bitmap.width}x${bitmap.height} " +
            "upload=${prepared.w}x${prepared.h} jpegBytes=${prepared.jpeg.size} payloadBytes=${payload.size} " +
            "endpoint=${endpoint().substringAfter("://").substringBefore("/")}")
        var networkMs = 0L
        val networkStart = SystemClock.elapsedRealtime()
        val body = try {
            post(payload, fastMode).also { networkMs = SystemClock.elapsedRealtime() - networkStart }
        } catch (e: OcrUnavailableException) {
            networkMs = SystemClock.elapsedRealtime() - networkStart
            val diagnostic = "request failed; networkMs=$networkMs; totalMs=${SystemClock.elapsedRealtime() - t0}; http=${currentHttpStatus()}; ${currentHttpMeta()}; error=${e.message ?: e.javaClass.simpleName}"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
            Log.e(TAG, diagnostic)
            throw e
        }

        var parseMs = 0L
        val parseStart = SystemClock.elapsedRealtime()
        val response = try {
            LensProtocol.parseResponse(body).also { parseMs = SystemClock.elapsedRealtime() - parseStart }
        } catch (e: LensProtocol.ProtocolException) {
            parseMs = SystemClock.elapsedRealtime() - parseStart
            val diagnostic = "parse failed; parseMs=$parseMs; totalMs=${SystemClock.elapsedRealtime() - t0}; http=${currentHttpStatus()}; ${currentHttpMeta()}; replyBytes=${body.size}; " +
                "reason=${e.message ?: "unknown"}"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
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
            val diagnostic = "server error; http=${currentHttpStatus()}; ${currentHttpMeta()}; replyBytes=${body.size}; " +
                "objects=${response.valid}; serverError=${response.serverError}; paragraphs=$paragraphCount lines=$lineCount"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
            Log.w(TAG, diagnostic)
            Breaker.transient("server error ${response.serverError}")
            throw OcrUnavailableException("Google Lens: server error ${response.serverError}")
        }
        if (!response.valid) {
            val diagnostic = "missing objects_response; http=${currentHttpStatus()}; ${currentHttpMeta()}; replyBytes=${body.size}; " +
                "objects=false; paragraphs=$paragraphCount lines=$lineCount"
            OcrEngineStatus.setLensDiagnostic(diagnostic)
            Log.w(TAG, diagnostic)
            Breaker.transient("reply without objects")
            throw OcrUnavailableException("Google Lens: reply without text layer")
        }

        val mapStart = SystemClock.elapsedRealtime()
        val allBlocks = LensProtocol.toBlocks(response, bitmap.width, bitmap.height, prepared.w, prepared.h)
        val lines = allBlocks.filterNot { OcrPost.isNoise(it) }
            .sortedWith(compareBy({ it.top }, { it.left }))
        val mapMs = SystemClock.elapsedRealtime() - mapStart
        val totalMs = SystemClock.elapsedRealtime() - t0
        val timing = "timing=prepare:${prepareMs}ms,cache:${keyCacheMs}ms,payload:${payloadBuildMs}ms," +
            "http:${networkMs}ms,parse:${parseMs}ms,map:${mapMs}ms,total:${totalMs}ms"
        val diagnostic = "imageId=${java.lang.Long.toHexString(key)}; fastMode=$fastMode; http=${currentHttpStatus()}; ${currentHttpMeta()}; " +
            "replyBytes=${body.size}; objects=${response.valid}; " +
            "shape=${response.shape}; paragraphs=$paragraphCount lines=$lineCount nonEmptyLines=$nonEmptyLineCount " +
            "textChars=$textChars lineBoxes=$lineBoxes paragraphBoxes=$paragraphBoxes " +
            "blocksBeforeNoise=${allBlocks.size} blocksAfterNoise=${lines.size} " +
            "script=${script.name} upload=${prepared.w}x${prepared.h}; $timing"
        OcrEngineStatus.setLensDiagnostic(diagnostic)
        if (lines.isEmpty()) Log.w(TAG, "NO_TEXT $diagnostic") else Log.i(TAG, "PARSED $diagnostic")
        Breaker.success()
        remember(key, lines)
        Log.i(TAG, "ok lines=${lines.size} sent=${prepared.w}x${prepared.h} bytes=${prepared.jpeg.size} ms=$totalMs")
        Log.i(TAG, "TIMING imageId=${java.lang.Long.toHexString(key)} cacheHit=false prepareMs=$prepareMs keyCacheMs=$keyCacheMs " +
            "payloadBuildMs=$payloadBuildMs networkMs=$networkMs parseMs=$parseMs mapMs=$mapMs totalMs=$totalMs")
        val mergeStart = SystemClock.elapsedRealtime()
        val finished = finish(lines, bitmap, script)
        OcrDiag.log("LENS_MERGE", "mergeNeighbours ms=${SystemClock.elapsedRealtime() - mergeStart} blocksIn=${lines.size} blocksOut=${finished.blocks.size} " +
            "| whole read() total=${SystemClock.elapsedRealtime() - t0}ms of which network=${networkMs}ms")
        return finished
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
            // Match chrome-lens-py's proven Lens image preparation: only flatten images that
            // contain REAL transparency (ARGB_8888 / PixelCopy bitmaps often have alpha enabled but
            // every pixel is opaque). This avoids an unnecessary full-frame Canvas copy on every scan.
            val alpha = inspectTransparency(src)
            if (alpha.hasTransparency) {
                // Lens's server composites transparent uploads onto black. Pick the contrasting
                // background from actual opaque-pixel luminance so light text is not lost on white.
                val background = if (alpha.meanLuma < 128) Color.WHITE else Color.BLACK
                val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(background); drawBitmap(src, 0f, 0f, null) }
                owned = flat
                work = flat
            }
            val area = work.width.toLong() * work.height.toLong()
            if (area > MAX_AREA && (work.width > MAX_DIMENSION || work.height > MAX_DIMENSION)) {
                // Match Chromium Lens image preparation: retain native dimensions for ordinary
                // phone screenshots; only resize when the image exceeds the area AND 1600px bounds.
                val k = minOf(MAX_DIMENSION.toDouble() / work.width, MAX_DIMENSION.toDouble() / work.height)
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

    private class AlphaInfo(val hasTransparency: Boolean, val meanLuma: Int)

    /**
     * Port of chrome-lens-py's has_meaningful_alpha/_contrasting_background logic.
     * A full pixel scan is only needed when Bitmap reports alpha support. Rows are
     * chunked to avoid allocating a full-screen IntArray alongside the image bitmap.
     */
    private fun inspectTransparency(bitmap: Bitmap): AlphaInfo {
        if (!bitmap.hasAlpha()) return AlphaInfo(false, 255)
        val width = bitmap.width
        val chunkRows = minOf(16, bitmap.height)
        val pixels = IntArray(width * chunkRows)
        var transparent = false
        var luminanceSum = 0L
        var opaqueCount = 0L
        var top = 0
        while (top < bitmap.height) {
            val rows = minOf(chunkRows, bitmap.height - top)
            bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
            val count = width * rows
            for (i in 0 until count) {
                val pixel = pixels[i]
                val alpha = pixel ushr 24
                if (alpha < 255) transparent = true
                if (alpha > 128) {
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF
                    // Integer form of Rec. 601 luminance; exact scaling is not relevant to the 128 cutoff.
                    luminanceSum += (299L * red + 587L * green + 114L * blue) / 1000L
                    opaqueCount++
                }
            }
            top += rows
        }
        val mean = if (opaqueCount == 0L) 0 else (luminanceSum / opaqueCount).toInt()
        return AlphaInfo(transparent, mean)
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

    // The reference Lens client sends an empty OCR language hint so Lens detects text language itself.
    private fun languageHint(): String = ""

    private fun regionHint(): String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
    private fun zoneHint(): String = TimeZone.getDefault().id.takeIf { it.isNotBlank() } ?: "America/New_York"

    // ---- result cache --------------------------------------------------------------------------

    private class Cached(val at: Long, val lines: List<OcrBlock>)

    private val cache = object : LinkedHashMap<Long, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Cached>?): Boolean = size > CACHE_SIZE
    }

    private fun cached(key: Long): List<OcrBlock>? = synchronized(cache) {
        val c = cache[key] ?: return@synchronized null
        // A blank Lens response should not suppress another attempt for the full positive-cache TTL.
        // Keep a brief negative-cache window to avoid hammering Lens for every identical live frame,
        // then let a later scan retry the same image in case Lens's response was transiently incomplete.
        val ttl = if (c.lines.isEmpty()) EMPTY_CACHE_TTL_MS else CACHE_TTL_MS
        if (SystemClock.elapsedRealtime() - c.at > ttl) { cache.remove(key); null } else c.lines
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
    private fun post(payload: ByteArray, fastMode: Boolean): ByteArray {
        lastHttpStatus.set(0)
        lastHttpMeta.set("no HTTP response yet")
        val deadlineMs = if (fastMode) FAST_TOTAL_DEADLINE_MS else TOTAL_DEADLINE_MS
        val attemptLimit = if (fastMode) FAST_MAX_ATTEMPTS else MAX_ATTEMPTS
        val minimumAttemptMs = if (fastMode) FAST_MIN_ATTEMPT_MS else MIN_ATTEMPT_MS
        val started = SystemClock.elapsedRealtime()
        var last: Exception? = null
        for (attempt in 0 until attemptLimit) {
            if (Thread.currentThread().isInterrupted) throw OcrUnavailableException("Google Lens: cancelled")
            val remaining = deadlineMs - (SystemClock.elapsedRealtime() - started)
            if (remaining < minimumAttemptMs) break
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
            if (attempt == attemptLimit - 1 || elapsed + waitMs + jitter + minimumAttemptMs > deadlineMs) break
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
        var responseFullyRead = false
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
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Sec-Fetch-Mode", "no-cors")
            c.setRequestProperty("Sec-Fetch-Dest", "empty")
            // Repeated Webtoon scans should reuse a healthy TCP/TLS connection, like the owocr Lens client.
            c.setRequestProperty("Connection", "keep-alive")
            // No Accept-Encoding header on purpose: then HttpURLConnection negotiates gzip AND decodes it itself.
            c.outputStream.use { it.write(payload); it.flush() }
            val code = c.responseCode
            lastHttpStatus.set(code)
            lastHttpMeta.set("contentType=${c.contentType ?: "unknown"} encoding=${c.contentEncoding ?: "none"} " +
                "headerLength=${c.getHeaderField("Content-Length") ?: "unknown"} requestBytes=${payload.size}")
            if (code !in 200..299) {
                Log.w(TAG, "HTTP_REJECTED status=$code ${currentHttpMeta()}")
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
            responseFullyRead = true
            Log.i(TAG, "HTTP_RESPONSE status=$code ${currentHttpMeta()} replyBytes=${reply.size}")
            return reply
        } finally {
            // Preserve successful keep-alive sockets; discard partial/error responses before retrying.
            if (!responseFullyRead) try { c.disconnect() } catch (_: Throwable) {}
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

        @Synchronized fun status(): String {
            val left = openUntil - SystemClock.elapsedRealtime()
            return if (left > 0) "OPEN (Lens paused for ${left / 1000}s more) reason=$reason"
            else "closed (Lens allowed) transientFails=$transientFails lastReason=${reason.ifBlank { "-" }}"
        }

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

        /** For the OCR log: is the circuit breaker currently skipping Lens? */
        fun breakerStatus(): String = Breaker.status()

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
        private const val MAX_AREA = 1_500_000L
        private const val MAX_DIMENSION = 1600
        private const val JPEG_QUALITY = 40
        private const val MAX_UPLOAD_BYTES = 3_500_000
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

        // Realtime screenshot reading is latency-sensitive; a slow endpoint must not stall the reader.
        private const val FAST_TOTAL_DEADLINE_MS = 6_500L
        private const val FAST_MIN_ATTEMPT_MS = 1_000L
        private const val FAST_MAX_ATTEMPTS = 2
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
        private const val EMPTY_CACHE_TTL_MS = 1_000L
    }
}
