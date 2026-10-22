package com.ashraf.novelstudio

import java.io.ByteArrayOutputStream

/**
 * Wire format of the Google Lens "overlay" upload that Chromium itself uses
 * (POST https://lensfrontend-pa.googleapis.com/v1/crupload, body = protobuf).
 *
 * IMPORTANT: this is NOT a documented or supported public API. Google can change or block it at any
 * time. Everything here is therefore written defensively: the parser rejects anything that does not
 * look like the expected message instead of guessing, so a changed protocol is reported as a failure
 * (and the app falls back to another OCR engine) rather than shown as an "empty but successful" scan.
 *
 * Only the handful of fields needed for text recognition are encoded/decoded, by hand, so no protobuf
 * library has to be added to the app. Field numbers were read from the message descriptors shipped
 * with the reference projects (chrome-lens-py, owocr, chrome-lens-ocr) and cross-checked against each
 * other; none of their source code is copied. Pure Kotlin, no Android types: unit-testable on the JVM.
 *
 * Request  = ServerRequest{ 1: objects_request{ 1: request_context{ 3: request_id, 4: client_context },
 *                                               3: image_data{ 1: payload{1: bytes}, 3: metadata{1: w, 2: h} } } }
 * Response = ServerResponse{ 1: error{1: type}, 2: objects_response{ 3: text{ 1: text_layout{ 1: paragraphs } } } }
 */
internal object LensProtocol {

    /** Thrown for a body that is not a valid/expected Lens response. */
    class ProtocolException(message: String) : Exception(message)

    // ---- enum values (lens_overlay_*.proto) -----------------------------------------------------
    private const val PLATFORM_WEB = 3
    private const val SURFACE_CHROMIUM = 4
    private const val RENDERING_ENV_LENS_OVERLAY = 14
    private const val FILTER_AUTO = 7
    private const val COORD_IMAGE = 2
    private const val WRITING_TOP_TO_BOTTOM = 2

    // =============================================================================================
    // Encoding
    // =============================================================================================

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }

        private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

        fun uint(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun int(field: Int, v: Int) { tag(field, 0); varint(v.toLong()) }
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun message(field: Int, body: Writer.() -> Unit) {
            val inner = Writer()
            inner.body()
            bytes(field, inner.toByteArray())
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /**
     * Full-image OCR request. [language]/[region]/[timeZone] only describe the caller's locale; Lens
     * detects the language of the pixels itself and this request asks for NO translation (no
     * TRANSLATE filter is ever sent - translating is the chatbot's job).
     */
    fun buildObjectsRequest(
        jpeg: ByteArray, width: Int, height: Int,
        language: String, region: String, timeZone: String,
        uuid: Long, timeUsec: Long, analyticsId: ByteArray
    ): ByteArray {
        require(width > 0 && height > 0 && jpeg.isNotEmpty()) { "empty image" }
        val root = Writer()
        root.message(1) {                                   // objects_request
            message(1) {                                    // request_context
                message(3) {                                // request_id
                    uint(1, uuid)                           //   uuid
                    int(2, 1)                               //   sequence_id
                    int(3, 1)                               //   image_sequence_id
                    if (analyticsId.isNotEmpty()) bytes(4, analyticsId)
                    uint(7, timeUsec)                       //   time_usec
                }
                message(4) {                                // client_context
                    int(1, PLATFORM_WEB)
                    int(2, SURFACE_CHROMIUM)
                    message(4) {                            //   locale_context
                        string(1, language)
                        string(2, region)
                        string(3, timeZone)
                    }
                    message(17) {                           //   client_filters
                        message(1) { int(1, FILTER_AUTO) }  //     one filter: AUTO_FILTER (never TRANSLATE)
                    }
                    message(20) {                           //   rendering_context
                        int(2, RENDERING_ENV_LENS_OVERLAY)
                    }
                }
            }
            message(3) {                                    // image_data
                message(1) { bytes(1, jpeg) }               //   payload.image_bytes
                message(3) { int(1, width); int(2, height) }//   image_metadata
            }
        }
        return root.toByteArray()
    }

    // =============================================================================================
    // Decoding
    // =============================================================================================

    private class Reader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (pos >= end || shift > 63) throw ProtocolException("truncated varint")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        /** Next field header as (fieldNumber shl 3 | wireType). */
        fun tag(): Int {
            val t = varint()
            if (t <= 0 || t > Int.MAX_VALUE) throw ProtocolException("bad tag")
            return t.toInt()
        }

        fun fixed32(): Int {
            if (pos + 4 > end) throw ProtocolException("truncated fixed32")
            val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun float(): Float = java.lang.Float.intBitsToFloat(fixed32())

        /** Length-delimited payload as a sub-reader over the same buffer (no copy). */
        fun sub(): Reader {
            val len = varint()
            if (len < 0 || len > (end - pos).toLong()) throw ProtocolException("bad length")
            val r = Reader(buf, pos, pos + len.toInt())
            pos += len.toInt()
            return r
        }

        fun string(): String {
            val len = varint()
            if (len < 0 || len > (end - pos).toLong()) throw ProtocolException("bad length")
            val s = String(buf, pos, len.toInt(), Charsets.UTF_8)
            pos += len.toInt()
            return s
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> { if (pos + 8 > end) throw ProtocolException("truncated fixed64"); pos += 8 }
                2 -> { val len = varint(); if (len < 0 || len > (end - pos).toLong()) throw ProtocolException("bad length"); pos += len.toInt() }
                5 -> { if (pos + 4 > end) throw ProtocolException("truncated fixed32"); pos += 4 }
                else -> throw ProtocolException("unsupported wire type $wire")
            }
        }
    }

    /** A box exactly as Lens reports it. [normalized] = fractions of the uploaded image, else pixels of it. */
    class Box(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val rotationRad: Float, val normalized: Boolean
    )

    class Line(val text: String, val box: Box?)
    class Paragraph(val lines: List<Line>, val vertical: Boolean, val box: Box?)

    /**
     * [valid] is true only when the body really was a ServerResponse that carried an objects_response.
     * [serverError] is the server-reported error type (0 = none).
     */
    class Response(val paragraphs: List<Paragraph>, val valid: Boolean, val serverError: Int)

    fun parseResponse(body: ByteArray): Response {
        if (body.isEmpty()) throw ProtocolException("empty response body")
        val root = Reader(body, 0, body.size)
        var sawObjects = false
        var serverError = 0
        val paragraphs = ArrayList<Paragraph>()
        while (root.hasMore()) {
            val t = root.tag()
            val field = t ushr 3
            val wire = t and 7
            when {
                field == 1 && wire == 2 -> {                       // error
                    val e = root.sub()
                    while (e.hasMore()) {
                        val et = e.tag()
                        if (et ushr 3 == 1 && et and 7 == 0) serverError = e.varint().toInt() else e.skip(et and 7)
                    }
                }
                field == 2 && wire == 2 -> {                       // objects_response
                    sawObjects = true
                    readObjectsResponse(root.sub(), paragraphs)
                }
                else -> root.skip(wire)
            }
        }
        return Response(paragraphs, sawObjects, serverError)
    }

    private fun readObjectsResponse(r: Reader, out: MutableList<Paragraph>) {
        while (r.hasMore()) {
            val t = r.tag()
            if (t ushr 3 == 3 && t and 7 == 2) readText(r.sub(), out) else r.skip(t and 7)   // 3: text
        }
    }

    private fun readText(r: Reader, out: MutableList<Paragraph>) {
        while (r.hasMore()) {
            val t = r.tag()
            if (t ushr 3 == 1 && t and 7 == 2) readTextLayout(r.sub(), out) else r.skip(t and 7) // 1: text_layout
        }
    }

    private fun readTextLayout(r: Reader, out: MutableList<Paragraph>) {
        while (r.hasMore()) {
            val t = r.tag()
            if (t ushr 3 == 1 && t and 7 == 2) out.add(readParagraph(r.sub())) else r.skip(t and 7) // 1: paragraphs
        }
    }

    private fun readParagraph(r: Reader): Paragraph {
        val lines = ArrayList<Line>()
        var box: Box? = null
        var vertical = false
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            when {
                field == 2 && wire == 2 -> lines.add(readLine(r.sub()))
                field == 3 && wire == 2 -> box = readGeometry(r.sub())
                field == 4 && wire == 0 -> vertical = r.varint().toInt() == WRITING_TOP_TO_BOTTOM
                else -> r.skip(wire)
            }
        }
        return Paragraph(lines, vertical, box)
    }

    private fun readLine(r: Reader): Line {
        val sb = StringBuilder()
        var box: Box? = null
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            when {
                field == 1 && wire == 2 -> readWord(r.sub(), sb)
                field == 2 && wire == 2 -> box = readGeometry(r.sub())
                else -> r.skip(wire)
            }
        }
        return Line(sb.toString().trim(), box)
    }

    /** Appends plain_text + text_separator of one word. */
    private fun readWord(r: Reader, sb: StringBuilder) {
        var text = ""
        var sep = ""
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            when {
                field == 2 && wire == 2 -> text = r.string()
                field == 3 && wire == 2 -> sep = r.string()
                else -> r.skip(wire)
            }
        }
        sb.append(text).append(sep)
    }

    /** Geometry{ 1: bounding_box = CenterRotatedBox }. */
    private fun readGeometry(r: Reader): Box? {
        var box: Box? = null
        while (r.hasMore()) {
            val t = r.tag()
            if (t ushr 3 == 1 && t and 7 == 2) box = readBox(r.sub()) else r.skip(t and 7)
        }
        return box
    }

    private fun readBox(r: Reader): Box {
        var cx = 0f; var cy = 0f; var w = 0f; var h = 0f; var rot = 0f
        var coord = 0
        while (r.hasMore()) {
            val t = r.tag()
            val field = t ushr 3
            val wire = t and 7
            when {
                wire == 5 && field == 1 -> cx = r.float()
                wire == 5 && field == 2 -> cy = r.float()
                wire == 5 && field == 3 -> w = r.float()
                wire == 5 && field == 4 -> h = r.float()
                wire == 5 && field == 5 -> rot = r.float()
                wire == 0 && field == 6 -> coord = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        return Box(cx, cy, w, h, rot, normalized = coord != COORD_IMAGE)
    }

    // =============================================================================================
    // Mapping to the app's OcrBlock format
    // =============================================================================================

    /**
     * Converts a parsed response into the blocks the rest of NovelStudio already consumes, in pixels of
     * the ORIGINAL bitmap ([origW] x [origH]); [sentW] x [sentH] is what was actually uploaded.
     *
     * Granularity matches the ML Kit engine so downstream grouping behaves the same: horizontal text is
     * one block per LINE (the pipeline re-joins lines into bubbles itself), vertical text is one block
     * per paragraph with its columns in the order Lens reported them.
     */
    fun toBlocks(resp: Response, origW: Int, origH: Int, sentW: Int, sentH: Int): List<OcrBlock> {
        val out = ArrayList<OcrBlock>()
        for (p in resp.paragraphs) {
            if (p.vertical) {
                val text = p.lines.map { it.text }.filter { it.isNotEmpty() }.joinToString(" ")
                if (text.isEmpty()) continue
                val g = pixels(p.box ?: unionOf(p.lines), origW, origH, sentW, sentH) ?: continue
                out.add(block(text, g, vertical = true))
            } else {
                for (l in p.lines) {
                    if (l.text.isEmpty()) continue
                    val g = pixels(l.box ?: p.box, origW, origH, sentW, sentH) ?: continue
                    // Some servers describe an upright column as a very tall, unrotated box.
                    out.add(block(l.text, g, vertical = g.h > g.w * 1.6f && Math.abs(g.angle) < 6f))
                }
            }
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private class Px(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float)

    private fun unionOf(lines: List<Line>): Box? {
        val boxes = lines.mapNotNull { it.box }
        if (boxes.isEmpty()) return null
        val l = boxes.minOf { it.cx - it.w / 2f }
        val r = boxes.maxOf { it.cx + it.w / 2f }
        val t = boxes.minOf { it.cy - it.h / 2f }
        val b = boxes.maxOf { it.cy + it.h / 2f }
        return Box((l + r) / 2f, (t + b) / 2f, r - l, b - t, 0f, boxes[0].normalized)
    }

    private fun pixels(b: Box?, origW: Int, origH: Int, sentW: Int, sentH: Int): Px? {
        if (b == null) return null
        val kx = if (b.normalized) origW.toFloat() else origW.toFloat() / sentW.coerceAtLeast(1)
        val ky = if (b.normalized) origH.toFloat() else origH.toFloat() / sentH.coerceAtLeast(1)
        val px = Px(b.cx * kx, b.cy * ky, b.w * kx, b.h * ky,
            // Assumption (not verifiable offline): positive = clockwise in image space, as for ML Kit.
            OcrPost.foldAngle(Math.toDegrees(b.rotationRad.toDouble()).toFloat()))
        if (!px.cx.isFinite() || !px.cy.isFinite() || !px.w.isFinite() || !px.h.isFinite()) return null
        if (px.w <= 0f || px.h <= 0f) return null
        return px
    }

    private fun block(text: String, g: Px, vertical: Boolean): OcrBlock {
        val glyph = (if (vertical) g.w else g.h) * 0.9f
        return OcrBlock(
            text = text,
            top = (g.cy - g.h / 2f).toInt(), left = (g.cx - g.w / 2f).toInt(),
            cx = g.cx, cy = g.cy, w = g.w, h = g.h, angle = g.angle,
            fontPx = glyph, vertical = vertical,
            confidence = 0f   // Lens reports none; 0 = "unknown" for OcrLineFusion
        )
    }
}
