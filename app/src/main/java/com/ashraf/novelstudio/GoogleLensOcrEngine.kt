package com.ashraf.novelstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Experimental, unofficial Google Lens OCR client.
 *
 * Calls the reverse-engineered Chromium Lens endpoint used by chrome-lens-ocr,
 * not a documented public OCR API. Images are uploaded to Google. This needs internet
 * and may stop working if the endpoint or its anti-abuse requirements change.
 * RoutingOcrEngine falls back to ML Kit when any request fails.
 */
internal class GoogleLensOcrEngine : OcrEngine {
    @Volatile private var cookieHeader = ""

    override fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult {
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) return OcrResult(emptyList())
        val uploaded = shrinkForUpload(bitmap)
        try {
            val out = ByteArrayOutputStream()
            if (!uploaded.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("Could not encode image for Google Lens")
            }
            val response = postLens(out.toByteArray(), uploaded.width, uploaded.height)
            val blocks = parseTextBlocks(response, uploaded.width, uploaded.height, bitmap.width, bitmap.height)
            Log.i(TAG, "Google Lens OCR: image=${bitmap.width}x${bitmap.height}, returned=${blocks.size} blocks")
            return OcrResult(blocks.sortedWith(compareBy<OcrBlock> { it.top }.thenBy { it.left }))
        } finally {
            if (uploaded !== bitmap && !uploaded.isRecycled) uploaded.recycle()
        }
    }

    override fun recognizeFast(bitmap: Bitmap, script: OcrScript): OcrResult = recognize(bitmap, script)

    // Prevent the generic sparse-result retry from making a second paid/remote-style request.
    override fun recognizeFallback(bitmap: Bitmap, script: OcrScript): OcrResult = OcrResult(emptyList())

    override fun recognizeRegion(bitmap: Bitmap, script: OcrScript): OcrResult = recognize(bitmap, script)

    override fun close() {
        cookieHeader = ""
    }

    private fun shrinkForUpload(src: Bitmap): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= MAX_SIDE) return src
        val scale = MAX_SIDE.toFloat() / maxSide.toFloat()
        return Bitmap.createScaledBitmap(
            src,
            maxOf(1, (src.width * scale).toInt()),
            maxOf(1, (src.height * scale).toInt()),
            true
        )
    }

    private fun postLens(png: ByteArray, width: Int, height: Int): ByteArray {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/x-protobuf")
            setRequestProperty("X-Goog-Api-Key", LENS_API_KEY)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Origin", "https://lens.google.com")
            setRequestProperty("Referer", "https://lens.google.com/")
            cookieHeader.takeIf(String::isNotBlank)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            connection.outputStream.use { it.write(buildRequest(png, width, height)) }
            val status = connection.responseCode
            updateCookies(connection)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) throw IOException("Google Lens endpoint returned HTTP $status")
            if (body.isEmpty()) throw IOException("Google Lens returned an empty response")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun updateCookies(connection: HttpURLConnection) {
        val newCookies = connection.headerFields.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value.orEmpty() }
            .mapNotNull { it.substringBefore(';').trim().takeIf(String::isNotBlank) }
        if (newCookies.isNotEmpty()) {
            val values = LinkedHashMap<String, String>()
            cookieHeader.split(';').map { it.trim() }.filter { it.contains('=') }.forEach {
                values[it.substringBefore('=')] = it
            }
            newCookies.forEach { values[it.substringBefore('=')] = it }
            cookieHeader = values.values.joinToString("; ")
        }
    }

    private fun buildRequest(png: ByteArray, width: Int, height: Int): ByteArray {
        val requestId = ProtoWriter().apply {
            varint(1, System.currentTimeMillis())
            varint(2, 1)
            varint(3, 1)
        }.bytes()

        val locale = ProtoWriter().apply {
            string(1, "en")
            string(2, "US")
            string(3, "America/New_York")
        }.bytes()
        val filter = ProtoWriter().apply { varint(1, AUTO_FILTER) }.bytes()
        val filters = ProtoWriter().apply { message(1, filter) }.bytes()
        val clientContext = ProtoWriter().apply {
            varint(1, PLATFORM_WEB)
            varint(2, SURFACE_CHROMIUM)
            message(4, locale)
            message(17, filters)
        }.bytes()
        val requestContext = ProtoWriter().apply {
            message(3, requestId)
            message(4, clientContext)
        }.bytes()

        val imagePayload = ProtoWriter().apply { byteString(1, png) }.bytes()
        val imageMetadata = ProtoWriter().apply {
            varint(1, width.toLong())
            varint(2, height.toLong())
        }.bytes()
        val imageData = ProtoWriter().apply {
            message(1, imagePayload)
            message(3, imageMetadata)
        }.bytes()
        val objectsRequest = ProtoWriter().apply {
            message(1, requestContext)
            message(3, imageData)
        }.bytes()
        return ProtoWriter().apply { message(1, objectsRequest) }.bytes()
    }

    private fun parseTextBlocks(
        response: ByteArray,
        sentWidth: Int,
        sentHeight: Int,
        originalWidth: Int,
        originalHeight: Int
    ): List<OcrBlock> {
        val serverResponse = ProtoReader(response).fields()
        val objectsResponseBytes = serverResponse.firstOrNull { it.number == 2 }?.bytes ?: return emptyList()
        val objectsResponse = ProtoReader(objectsResponseBytes).fields()
        val textBytes = objectsResponse.firstOrNull { it.number == 3 }?.bytes ?: return emptyList()
        val textFields = ProtoReader(textBytes).fields()
        val layoutBytes = textFields.firstOrNull { it.number == 1 }?.bytes ?: return emptyList()
        val layout = ProtoReader(layoutBytes).fields()
        val out = ArrayList<OcrBlock>()

        for (paragraphField in layout.filter { it.number == 1 && it.bytes != null }) {
            val paragraph = ProtoReader(paragraphField.bytes!!).fields()
            for (lineField in paragraph.filter { it.number == 2 && it.bytes != null }) {
                val line = ProtoReader(lineField.bytes!!).fields()
                val words = line.filter { it.number == 1 && it.bytes != null }.mapNotNull { wordField ->
                    val wordFields = ProtoReader(wordField.bytes!!).fields()
                    val text = wordFields.firstOrNull { it.number == 2 }?.string.orEmpty()
                    val separator = wordFields.firstOrNull { it.number == 3 }?.string
                    if (text.isBlank()) null else Word(text, separator)
                }
                val lineText = buildString {
                    words.forEachIndexed { index, word ->
                        append(word.text)
                        val separator = word.separator
                        if (!separator.isNullOrEmpty()) append(separator)
                        else if (index < words.lastIndex) append(' ')
                    }
                }.replace(Regex("\\s+"), " ").trim()
                if (lineText.isBlank()) continue

                val geometryBytes = line.firstOrNull { it.number == 2 && it.bytes != null }?.bytes
                val box = geometryBytes?.let(::parseGeometryBox)
                val mapped = box?.let { mapBox(it, sentWidth, sentHeight, originalWidth, originalHeight) }
                    ?: Box(originalWidth / 2f, originalHeight / 2f, originalWidth.toFloat(),
                        maxOf(8f, originalHeight / 12f), 0f)

                val block = OcrBlock(
                    text = lineText,
                    top = (mapped.cy - mapped.h / 2f).toInt().coerceAtLeast(0),
                    left = (mapped.cx - mapped.w / 2f).toInt().coerceAtLeast(0),
                    cx = mapped.cx,
                    cy = mapped.cy,
                    w = mapped.w.coerceAtLeast(2f),
                    h = mapped.h.coerceAtLeast(4f),
                    angle = OcrPost.foldAngle(mapped.angle),
                    fontPx = mapped.h,
                    vertical = mapped.h > mapped.w * 1.35f,
                    confidence = 0.75f
                )
                if (!OcrPost.isNoise(block)) out.add(block)
            }
        }
        return out
    }

    private data class Word(val text: String, val separator: String?)
    private data class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float)

    private fun parseGeometryBox(geometryBytes: ByteArray): Box? {
        val geometry = ProtoReader(geometryBytes).fields()
        val boxBytes = geometry.firstOrNull { it.number == 1 && it.bytes != null }?.bytes ?: return null
        val fields = ProtoReader(boxBytes).fields()
        fun floatField(id: Int): Float? = fields.firstOrNull { it.number == id }?.float
        val cx = floatField(1) ?: return null
        val cy = floatField(2) ?: return null
        val width = floatField(3) ?: return null
        val height = floatField(4) ?: return null
        val rotation = floatField(5) ?: 0f
        return Box(cx, cy, width, height, rotation)
    }

    private fun mapBox(box: Box, sentWidth: Int, sentHeight: Int, originalWidth: Int, originalHeight: Int): Box {
        // Lens usually returns normalized geometry; tolerate pixel coordinates if the protocol changes.
        val normalized = abs(box.cx) <= 1.5f && abs(box.cy) <= 1.5f &&
            abs(box.w) <= 1.5f && abs(box.h) <= 1.5f
        val cx = if (normalized) box.cx * sentWidth else box.cx
        val cy = if (normalized) box.cy * sentHeight else box.cy
        val width = if (normalized) box.w * sentWidth else box.w
        val height = if (normalized) box.h * sentHeight else box.h
        return Box(
            cx = cx * originalWidth.toFloat() / sentWidth.toFloat(),
            cy = cy * originalHeight.toFloat() / sentHeight.toFloat(),
            w = width * originalWidth.toFloat() / sentWidth.toFloat(),
            h = height * originalHeight.toFloat() / sentHeight.toFloat(),
            angle = box.angle
        )
    }

    private data class ProtoField(
        val number: Int,
        val wireType: Int,
        val value: Long = 0L,
        val bytes: ByteArray? = null,
        val float: Float? = null,
        val string: String? = null
    )

    private class ProtoReader(private val input: ByteArray) {
        private var cursor = 0

        fun fields(): List<ProtoField> {
            val out = ArrayList<ProtoField>()
            while (cursor < input.size) {
                val tag = readVarint().toInt()
                if (tag == 0) break
                val number = tag ushr 3
                val wire = tag and 7
                if (number <= 0) throw IOException("Invalid protobuf field tag")
                when (wire) {
                    0 -> out.add(ProtoField(number, wire, value = readVarint()))
                    1 -> { requireRemaining(8); cursor += 8 }
                    2 -> {
                        val length = readVarint()
                        if (length < 0 || length > input.size - cursor) throw IOException("Invalid protobuf length")
                        val value = input.copyOfRange(cursor, cursor + length.toInt())
                        cursor += length.toInt()
                        out.add(ProtoField(number, wire, bytes = value, string = String(value, Charsets.UTF_8)))
                    }
                    5 -> {
                        requireRemaining(4)
                        val value = ByteBuffer.wrap(input, cursor, 4).order(ByteOrder.LITTLE_ENDIAN).float
                        cursor += 4
                        out.add(ProtoField(number, wire, float = value))
                    }
                    else -> throw IOException("Unsupported protobuf wire type $wire")
                }
            }
            return out
        }

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (cursor >= input.size) throw IOException("Unexpected end of protobuf")
                val byte = input[cursor++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if ((byte and 0x80) == 0) return result
                shift += 7
            }
            throw IOException("Malformed protobuf varint")
        }

        private fun requireRemaining(count: Int) {
            if (input.size - cursor < count) throw IOException("Unexpected end of protobuf")
        }
    }

    private class ProtoWriter {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long) {
            rawVarint((field shl 3).toLong())
            rawVarint(value)
        }

        fun string(field: Int, value: String) = byteString(field, value.toByteArray(Charsets.UTF_8))
        fun message(field: Int, value: ByteArray) = byteString(field, value)

        fun byteString(field: Int, value: ByteArray) {
            rawVarint(((field shl 3) or 2).toLong())
            rawVarint(value.size.toLong())
            out.write(value)
        }

        fun bytes(): ByteArray = out.toByteArray()

        private fun rawVarint(input: Long) {
            var value = input
            while ((value and -128L) != 0L) {
                out.write((value.toInt() and 0x7F) or 0x80)
                value = value ushr 7
            }
            out.write(value.toInt())
        }
    }

    private companion object {
        const val TAG = "NovelStudioLensOCR"
        const val MAX_SIDE = 1200
        const val ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"
        // Published in the upstream reverse-engineered client; this is not a user credential.
        const val LENS_API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val PLATFORM_WEB = 3L
        const val SURFACE_CHROMIUM = 4L
        const val AUTO_FILTER = 7L
    }
}
