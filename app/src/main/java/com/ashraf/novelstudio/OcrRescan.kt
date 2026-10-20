package com.ashraf.novelstudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Second look for text the first OCR pass left unread.
 *
 * ML Kit's detector sometimes skips a line (glowing / outlined / stylised lettering) while it reads
 * the lines around it. The old retry only fired when the first pass found almost nothing, so a panel
 * with 3 good blocks and 1 missing line was never looked at again.
 *
 * Here the picture itself is checked: strong-edge pixels that sit OUTSIDE every block ML Kit already
 * returned are grouped into wide, line-shaped regions. Each region is cut out, enlarged, and read
 * again (plain, bright-mask and contrast-stretched looks). Anything new that does not overlap an
 * existing block is added, and the blocks are merged again so a continuation line joins its sentence.
 *
 * Pure addition: if anything fails, the caller keeps the original result.
 */
internal object OcrRescan {
    private const val TAG = "NSOcrRescan"

    // Tuned on a real webtoon screenshot (glowing serif caption between two bubbles).
    private const val GRID_W = 400        // analysis width in pixels
    private const val EDGE_T = 70         // gradient strength that counts as an edge
    private const val GAP = 8             // horizontal gap (grid px) bridged between letters
    private const val MIN_W = 28          // region must be at least this wide (grid px)
    private const val MIN_H = 7
    private const val MAX_H = 70
    private const val MIN_ASPECT = 2.2f   // text lines are wide, not square blobs
    private const val DENS_LO = 0.08f     // share of edge pixels in the region's box
    private const val DENS_HI = 0.55f

    internal class Region(val l: Int, val t: Int, val r: Int, val b: Int)

    private fun luma(p: Int): Int =
        (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10

    private fun letters(s: String): Int = s.count { it.isLetterOrDigit() }

    /** Line-shaped ink regions (original pixel coordinates) that no block in [covered] explains. */
    fun findUncovered(bmp: Bitmap, covered: List<OcrBlock>, maxRegions: Int): List<Region> {
        val bw = bmp.width
        val bh = bmp.height
        if (bw < 48 || bh < 24 || maxRegions <= 0) return emptyList()
        val s = if (bw > GRID_W) GRID_W.toFloat() / bw else 1f
        val gw = Math.max(8, (bw * s).toInt())
        val gh = Math.max(8, (bh * s).toInt())
        val sx = gw.toFloat() / bw
        val sy = gh.toFloat() / bh

        val small = if (gw == bw && gh == bh) bmp else Bitmap.createScaledBitmap(bmp, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        if (small !== bmp) small.recycle()
        val lum = IntArray(gw * gh)
        for (i in px.indices) lum[i] = luma(px[i])

        // 1) strong edges
        val edge = BooleanArray(gw * gh)
        for (y in 1 until gh - 1) {
            val row = y * gw
            for (x in 1 until gw - 1) {
                val i = row + x
                val d = Math.abs(lum[i + 1] - lum[i - 1]) + Math.abs(lum[i + gw] - lum[i - gw])
                if (d > EDGE_T) edge[i] = true
            }
        }

        // 2) blank out everything the first pass already explained (with a little margin for glow)
        for (b in covered) {
            val a = Math.toRadians(Math.abs(b.angle).toDouble())
            val c = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            val hx = (b.w * c + b.h * sn) / 2f
            val hy = (b.w * sn + b.h * c) / 2f
            val font = if (b.fontPx > 0f) b.fontPx else b.h
            val pad = ((font * sy * 0.3f).toInt()).coerceIn(3, 6)
            val x0 = (((b.cx - hx) * sx).toInt() - pad).coerceIn(0, gw)
            val x1 = (((b.cx + hx) * sx).toInt() + pad).coerceIn(0, gw)
            val y0 = (((b.cy - hy) * sy).toInt() - pad).coerceIn(0, gh)
            val y1 = (((b.cy + hy) * sy).toInt() + pad).coerceIn(0, gh)
            for (yy in y0 until y1) {
                val row = yy * gw
                for (xx in x0 until x1) edge[row + xx] = false
            }
        }

        // 3) bridge the gaps between letters sideways, then thicken by one row
        val closed = BooleanArray(gw * gh)
        for (y in 0 until gh) {
            val row = y * gw
            var filledTo = -1
            for (x in 0 until gw) {
                if (!edge[row + x]) continue
                val end = Math.min(gw - 1, x + GAP)
                var xx = Math.max(x - GAP, filledTo + 1)
                while (xx <= end) { closed[row + xx] = true; xx++ }
                if (end > filledTo) filledTo = end
            }
        }
        val grown = BooleanArray(gw * gh)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val i = y * gw + x
                if (closed[i] || (y > 0 && closed[i - gw]) || (y < gh - 1 && closed[i + gw])) grown[i] = true
            }
        }

        // 4) connected components -> line-shaped candidates
        class Cand(val score: Float, val l: Int, val t: Int, val r: Int, val b: Int)
        val cands = ArrayList<Cand>()
        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        for (start in 0 until gw * gh) {
            if (!grown[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = gw; var maxX = -1; var minY = gh; var maxY = -1
            while (sp > 0) {
                val cur = stack[--sp]
                val cy = cur / gw
                val cx = cur - cy * gw
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                if (cx > 0 && grown[cur - 1] && !seen[cur - 1]) { seen[cur - 1] = true; stack[sp++] = cur - 1 }
                if (cx < gw - 1 && grown[cur + 1] && !seen[cur + 1]) { seen[cur + 1] = true; stack[sp++] = cur + 1 }
                if (cy > 0 && grown[cur - gw] && !seen[cur - gw]) { seen[cur - gw] = true; stack[sp++] = cur - gw }
                if (cy < gh - 1 && grown[cur + gw] && !seen[cur + gw]) { seen[cur + gw] = true; stack[sp++] = cur + gw }
            }
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            if (w < MIN_W || h < MIN_H || h > MAX_H) continue
            if (w.toFloat() / h < MIN_ASPECT) continue
            var e = 0
            for (yy in minY..maxY) {
                val row = yy * gw
                for (xx in minX..maxX) if (edge[row + xx]) e++
            }
            val dens = e.toFloat() / (w * h)
            if (dens < DENS_LO || dens > DENS_HI) continue
            val l = (minX / sx).toInt()
            val r = ((maxX + 1) / sx).toInt()
            val t = (minY / sy).toInt()
            val b = ((maxY + 1) / sy).toInt()
            cands.add(Cand(w * h * dens, l, t, r, b))
        }
        cands.sortByDescending { it.score }

        val out = ArrayList<Region>()
        for (c in cands) {
            if (out.size >= maxRegions) break
            val hPx = c.b - c.t
            val padX = Math.max(6, (hPx * 0.25f).toInt())
            val padY = Math.max(6, (hPx * 0.35f).toInt())
            val l = Math.max(0, c.l - padX)
            val t = Math.max(0, c.t - padY)
            val r = Math.min(bw, c.r + padX)
            val b = Math.min(bh, c.b + padY)
            if (r - l >= 12 && b - t >= 8) out.add(Region(l, t, r, b))
        }
        return out
    }

    /** Reads one region: enlarged, then bright-mask and (full scans) contrast-stretched; best look wins. */
    private fun readRegion(
        engine: OcrEngine, bmp: Bitmap, reg: Region, script: OcrScript, fastMode: Boolean
    ): List<OcrBlock> {
        val cw = reg.r - reg.l
        val ch = reg.b - reg.t
        if (cw < 12 || ch < 8) return emptyList()
        val crop = Bitmap.createBitmap(bmp, reg.l, reg.t, cw, ch)
        val made = ArrayList<Bitmap>()
        try {
            var f = Math.min(3f, Math.max(1f, 96f / ch))
            val longest = Math.max(cw, ch) * f
            if (longest > 2400f) f = Math.max(1f, f * 2400f / longest)
            val base: Bitmap = if (f > 1.05f) {
                val up = Bitmap.createScaledBitmap(crop, (cw * f).toInt(), (ch * f).toInt(), true)
                made.add(up)
                up
            } else crop
            val looks = ArrayList<Bitmap>()
            looks.add(base)
            val mask = OcrPost.brightMask(base)
            made.add(mask)
            looks.add(mask)
            if (!fastMode) {
                val boosted = OcrPost.boostContrast(base)
                made.add(boosted)
                looks.add(boosted)
            }
            var best: List<OcrBlock> = emptyList()
            var bestLen = 0
            for (look in looks) {
                val res = if (fastMode) engine.recognizeFast(look, script) else engine.recognizeRegion(look, script)
                val len = res.blocks.sumOf { letters(it.text) }
                if (len > bestLen) { best = res.blocks; bestLen = len }
            }
            val sc = base.width.toFloat() / cw
            return best.map { b ->
                val s = OcrPost.scaled(b, 1f / sc)
                s.copy(top = s.top + reg.t, left = s.left + reg.l, cx = s.cx + reg.l, cy = s.cy + reg.t)
            }
        } finally {
            for (m in made) m.recycle()
            if (crop !== bmp) crop.recycle()
        }
    }

    /**
     * [base] is the normal OCR result for [bmp]. Returns [base] plus any lines found by the second
     * look, or [base] itself when nothing new turned up.
     */
    fun augment(
        ctx: Context, engine: OcrEngine, bmp: Bitmap, base: OcrResult,
        script: OcrScript, fastMode: Boolean
    ): OcrResult {
        val debug = try { Prefs.bool(ctx, "ocrDebug", false) } catch (_: Exception) { false }
        val regions: List<Region> = try {
            findUncovered(bmp, base.blocks, if (fastMode) 2 else 5)
        } catch (_: Exception) {
            emptyList()
        } catch (_: OutOfMemoryError) {
            emptyList()
        }

        val found = ArrayList<OcrBlock>()
        val empty = ArrayList<Region>()
        for (reg in regions) {
            val got: List<OcrBlock> = try {
                readRegion(engine, bmp, reg, script, fastMode)
            } catch (_: Exception) {
                emptyList()
            } catch (_: OutOfMemoryError) {
                emptyList()
            }
            var added = 0
            for (nb in got) {
                if (OcrPost.isNoise(nb)) continue
                if (letters(nb.text) < 2) continue
                val clash = base.blocks.any { OcrPost.overlapOfSmaller(it, nb) > 0.25f } ||
                    found.any { OcrPost.overlapOfSmaller(it, nb) > 0.25f }
                if (clash) continue
                found.add(nb)
                added++
            }
            if (added == 0) empty.add(reg)
        }
        if (regions.isNotEmpty()) {
            Log.i(TAG, "regions=${regions.size} recovered=${found.size} script=$script fast=$fastMode")
        }
        if (debug) {
            try { saveDebug(ctx, bmp, base.blocks, found, empty) } catch (_: Exception) {} catch (_: OutOfMemoryError) {}
        }
        if (found.isEmpty()) return base
        return OcrResult(OcrPost.mergeNeighbours(base.blocks + found, bmp))
    }

    // ------------------------------------------------------------------ debug images

    private var savedCount = 0

    /**
     * Green = blocks from the normal pass, orange = lines recovered by the rescan,
     * red = regions that were re-read but gave nothing. Saved to Pictures/NovelStudioOCR (max 30 per run).
     */
    @Synchronized
    private fun saveDebug(
        ctx: Context, bmp: Bitmap, base: List<OcrBlock>, found: List<OcrBlock>, empty: List<Region>
    ) {
        if (savedCount >= 30) return
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return
        try {
            val canvas = Canvas(copy)
            val p = Paint()
            p.style = Paint.Style.STROKE
            p.strokeWidth = Math.max(2f, copy.width / 300f)
            fun box(b: OcrBlock, color: Int) {
                p.color = color
                canvas.drawRect(b.cx - b.w / 2f, b.cy - b.h / 2f, b.cx + b.w / 2f, b.cy + b.h / 2f, p)
            }
            for (b in base) box(b, Color.GREEN)
            for (b in found) box(b, Color.rgb(255, 140, 0))
            p.color = Color.RED
            for (r in empty) canvas.drawRect(r.l.toFloat(), r.t.toFloat(), r.r.toFloat(), r.b.toFloat(), p)
            write(ctx, copy)
            savedCount++
        } finally {
            copy.recycle()
        }
    }

    private fun write(ctx: Context, bm: Bitmap) {
        val name = "ocr_" + System.currentTimeMillis() + "_" + savedCount + ".jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NovelStudioOCR")
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { bm.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } else {
            val dir = ctx.getExternalFilesDir("ocr_debug") ?: return
            dir.mkdirs()
            java.io.FileOutputStream(java.io.File(dir, name)).use { bm.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
    }
}
