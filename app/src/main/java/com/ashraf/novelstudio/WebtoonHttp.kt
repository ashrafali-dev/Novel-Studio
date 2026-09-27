package com.ashraf.novelstudio

import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object WebtoonHttp {
    private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun loadImage(src: String, referer: String, cookie: String? = null): ByteArray {
        val s = src.trim()
        if (s.startsWith("data:image/", ignoreCase = true)) return decodeDataUrl(s)
        require(s.startsWith("http://") || s.startsWith("https://")) { "Unsupported image URL" }

        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val conn = (URL(s).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("User-Agent", UA)
                    if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                    setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val len = conn.contentLengthLong
                    if (len > MAX_IMAGE_BYTES) throw IllegalStateException("Image too large")
                    BufferedInputStream(conn.inputStream).use { input ->
                        val out = ByteArrayOutputStream(if (len > 0 && len <= MAX_IMAGE_BYTES) len.toInt() else 64 * 1024)
                        val buf = ByteArray(32 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMAGE_BYTES) throw IllegalStateException("Image too large")
                            out.write(buf, 0, n)
                        }
                        return out.toByteArray()
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                last = t
                if (attempt < 2) Thread.sleep((250L * (attempt + 1)))
            }
        }
        throw last ?: IllegalStateException("Image download failed")
    }

    private fun decodeDataUrl(value: String): ByteArray {
        val comma = value.indexOf(',')
        require(comma > 0) { "Invalid data URL" }
        val meta = value.substring(0, comma)
        val payload = value.substring(comma + 1)
        return if (meta.contains(";base64", ignoreCase = true)) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
        }
    }
}
