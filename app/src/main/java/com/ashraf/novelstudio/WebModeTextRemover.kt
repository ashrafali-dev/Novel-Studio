package com.ashraf.novelstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative, pixel-level remover for OCR-confirmed lettering on flat/near-flat backgrounds.
 *
 * Design rules borrowed as concepts (not copied implementation) from BubbleBlaster and PanelCleaner:
 * use OCR geometry, make a per-pixel mask instead of painting an entire text rectangle, and skip a
 * region when its foreground/background or connected-component shape is ambiguous. Complex art is
 * deliberately left for the existing overlay instead of being damaged by a guessed fill.
 *
 * This is a lightweight first-stage cleaner, not a neural inpainting model. It is intentionally
 * bounded for phones and returns false when the image is too large or the mask is unsafe.
 */
internal object WebModeTextRemover {
    private const val MAX_WIDTH = 4096
    private const val MAX_HEIGHT = 4096
    private const val MAX_PIXELS = 3_000_000L
    private const val MAX_BYTES = 18 * 1024 * 1024

    fun cleanAndCache(
        context: Context,
        sourceUrl: String,
        encoded: ByteArray,
        bubbles: List<WmBubble>
    ): Boolean {
        if (!Prefs.bool(context, "wmTextRemoval", false)) return false
        if (sourceUrl.isBlank() || encoded.size !in 1..MAX_BYTES || bubbles.isEmpty()) return false
        if (isAnimatedOrUnsupported(encoded)) return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width !in 1..MAX_WIDTH || height !in 1..MAX_HEIGHT ||
            width.toLong() * height.toLong() > MAX_PIXELS) return false

        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return false
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888 && decoded.isMutable) decoded
            else decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
                ?: run { decoded.recycle(); return false }

        try {
            val count = width * height
            val original = IntArray(count)
            bitmap.getPixels(original, 0, width, 0, 0, width, height)
            val output = original.copyOf()
            var totalRemoved = 0
            val boundedBubbles = bubbles.asSequence()
                .filter { it.sourceText.isNotBlank() && !it.soft && it.eraseW > 0f && it.eraseH > 0f }
                .take(500)
                .toList()

            for (bubble in boundedBubbles) {
                // Erase only the OCR-confirmed source glyph region. The translation box may
                // intentionally span the full speech bubble to fit longer Bengali text.
                val cx = bubble.eraseCx * width
                val cy = bubble.eraseCy * height
                val bw = bubble.eraseW * width
                val bh = bubble.eraseH * height
                if (!cx.isFinite() || !cy.isFinite() || !bw.isFinite() || !bh.isFinite()) continue
                val x0 = (cx - bw / 2f).toInt().coerceIn(0, width - 1)
                val y0 = (cy - bh / 2f).toInt().coerceIn(0, height - 1)
                val x1 = ceil(cx + bw / 2f).toInt().coerceIn(x0 + 1, width)
                val y1 = ceil(cy + bh / 2f).toInt().coerceIn(y0 + 1, height)
                val rw = x1 - x0
                val rh = y1 - y0
                val regionSize = rw.toLong() * rh.toLong()
                if (rw < 4 || rh < 4 || regionSize > 1_250_000L) continue

                val bg = opaque(bubble.bg)
                val fg1 = opaque(bubble.fg)
                val fg2 = opaque(bubble.fg2)
                if (distance(bg, fg1) < 52 && distance(bg, fg2) < 52) continue

                val fontPx = (bubble.font * width).takeIf { it.isFinite() && it > 0f }
                    ?.coerceIn(3f, 140f) ?: max(3f, min(rw, rh).toFloat() * 0.65f)
                // Dense/bold CJK glyphs (and vertical columns) often touch, so a "component" can be several
                // glyphs long. Allow that, and protect bubble outlines by the border-contact rule below.
                val maxComponentW = max(8, (fontPx * 6.5f).toInt())
                val maxComponentH = max(8, (fontPx * 6.5f).toInt())
                val maxComponentPixels = max(18, (fontPx * fontPx * 9f + fontPx * 10f).toInt())

                val regionCount = regionSize.toInt()
                val candidate = ByteArray(regionCount)
                val seen = ByteArray(regionCount)
                val selected = ByteArray(regionCount)
                for (ry in 0 until rh) {
                    val src = (y0 + ry) * width + x0
                    val row = ry * rw
                    for (rx in 0 until rw) {
                        val p = original[src + rx]
                        val fromBg = distance(p, bg)
                        if (fromBg < 46) continue
                        val fromFg = min(distance(p, fg1), distance(p, fg2))
                        // Include anti-aliased letter edges, but require a real contrast from the bubble fill.
                        if (fromFg <= max(82f, fromBg * 1.08f)) candidate[row + rx] = 1
                    }
                }

                val queue = IntArray(regionCount.coerceAtMost(1_250_000))
                var selectedCount = 0
                for (start in 0 until regionCount) {
                    if (candidate[start].toInt() == 0 || seen[start].toInt() != 0) continue
                    var head = 0
                    var tail = 0
                    queue[tail++] = start
                    seen[start] = 1
                    var minX = rw; var minY = rh; var maxX = -1; var maxY = -1
                    var componentPixels = 0
                    while (head < tail) {
                        val at = queue[head++]
                        val ry = at / rw
                        val rx = at - ry * rw
                        minX = min(minX, rx); maxX = max(maxX, rx)
                        minY = min(minY, ry); maxY = max(maxY, ry)
                        componentPixels++
                        for (dy in -1..1) for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = rx + dx; val ny = ry + dy
                            if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                            val ni = ny * rw + nx
                            if (candidate[ni].toInt() != 0 && seen[ni].toInt() == 0 && tail < queue.size) {
                                seen[ni] = 1
                                queue[tail++] = ni
                            }
                        }
                    }
                    val cw = maxX - minX + 1
                    val ch = maxY - minY + 1
                    // A big shape that reaches two sides of the region is the bubble outline, not lettering.
                    val touches = (if (minX == 0) 1 else 0) + (if (maxX == rw - 1) 1 else 0) +
                        (if (minY == 0) 1 else 0) + (if (maxY == rh - 1) 1 else 0)
                    if (touches >= 2 && (cw > fontPx * 2.2f || ch > fontPx * 2.2f)) continue
                    // Large lines, bubble contours and art strokes are ambiguous: keep them.
                    if (componentPixels < max(3, (fontPx * 0.06f).toInt()) ||
                        cw > maxComponentW || ch > maxComponentH ||
                        componentPixels > maxComponentPixels) continue
                    for (i in 0 until tail) {
                        val at = queue[i]
                        selected[at] = 1
                    }
                    selectedCount += componentPixels
                }

                // If the inferred mask covers too much of a region, it is probably art rather than text.
                // Tight OCR boxes around dense CJK glyphs can legitimately contain more ink than Latin boxes.
                // The connected-component/size checks above remain the stronger art-protection filters.
                if (selectedCount < 4 || selectedCount > regionCount * 0.5f) continue

                // Grow the mask a little: letter edges (anti-aliasing, JPEG ringing, faint halo) are lighter than
                // the core pixels, fall outside the strict candidate test, and used to stay as a ghost of the old
                // text behind the translation. Only pixels that differ from the paper colour are taken.
                val grow = (fontPx * 0.07f).toInt().coerceIn(1, 3)
                val mask = selected.copyOf()
                for (ry in 0 until rh) {
                    for (rx in 0 until rw) {
                        if (selected[ry * rw + rx].toInt() == 0) continue
                        for (dy in -grow..grow) {
                            val ny = ry + dy
                            if (ny < 0 || ny >= rh) continue
                            for (dx in -grow..grow) {
                                val nx = rx + dx
                                if (nx < 0 || nx >= rw) continue
                                val ni = ny * rw + nx
                                if (mask[ni].toInt() == 0 &&
                                    distance(original[(y0 + ny) * width + x0 + nx], bg) >= 10f) mask[ni] = 1
                            }
                        }
                    }
                }

                // Fill only selected glyph pixels from the nearest unmasked pixels. On flat speech-bubble
                // backgrounds this retains the exact local paper/colour instead of repainting a big box.
                for (ry in 0 until rh) {
                    for (rx in 0 until rw) {
                        val at = ry * rw + rx
                        if (mask[at].toInt() == 0) continue
                        var sampleN = 0
                        var sumR = 0; var sumG = 0; var sumB = 0
                        var radius = 1
                        while (radius <= 5 && sampleN < 6) {
                            for (dy in -radius..radius) {
                                for (dx in -radius..radius) {
                                    if (max(abs(dx), abs(dy)) != radius) continue
                                    val nx = rx + dx; val ny = ry + dy
                                    if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                                    val ni = ny * rw + nx
                                    if (mask[ni].toInt() != 0) continue
                                    val p = original[(y0 + ny) * width + x0 + nx]
                                    if (distance(p, bg) > 72f) continue
                                    sumR += red(p); sumG += green(p); sumB += blue(p); sampleN++
                                }
                            }
                            radius++
                        }
                        // Average only nearby unmasked pixels with similar background colour; avoids
                        // repainting the whole line or allocating arrays for every single glyph pixel.
                        val fill = if (sampleN >= 3) {
                            (sumR / sampleN shl 16) or (sumG / sampleN shl 8) or (sumB / sampleN)
                        } else bg and 0x00FFFFFF
                        output[(y0 + ry) * width + x0 + rx] = 0xFF000000.toInt() or fill
                    }
                }
                totalRemoved += selectedCount
            }
            if (totalRemoved < 8) return false

            bitmap.setPixels(output, 0, width, 0, 0, width, height)
            val bytesOut = ByteArrayOutputStream()
            // API 30+ supports lossless WebP. Older Android versions use lossless PNG instead
            // of re-encoding the whole comic page with lossy WebP.
            val modern = Build.VERSION.SDK_INT >= 30
            val format = if (modern) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
            if (!bitmap.compress(format, 100, bytesOut)) return false
            val cleaned = bytesOut.toByteArray()
            if (cleaned.isEmpty() || cleaned.size > MAX_BYTES) return false
            return WebModeTextRemovalCache.store(context, sourceUrl, cleaned,
                if (modern) "image/webp" else "image/png")
        } catch (_: OutOfMemoryError) {
            return false
        } catch (_: Exception) {
            return false
        } finally {
            bitmap.recycle()
        }
    }

    private fun isAnimatedOrUnsupported(bytes: ByteArray): Boolean {
        if (bytes.size >= 6) {
            val header = String(bytes, 0, 6, Charsets.US_ASCII)
            if (header == "GIF87a" || header == "GIF89a") return true
        }
        if (bytes.size >= 16 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") {
            val marker = String(bytes, 12, min(4, bytes.size - 12), Charsets.US_ASCII)
            if (marker == "ANIM" || marker == "ANMF") return true
        }
        return false
    }

    private fun opaque(color: Int): Int = color or (0xFF000000.toInt())
    private fun red(color: Int) = (color ushr 16) and 0xFF
    private fun green(color: Int) = (color ushr 8) and 0xFF
    private fun blue(color: Int) = color and 0xFF

    private fun distance(a: Int, b: Int): Float {
        return (abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))) / 3f
    }

}
