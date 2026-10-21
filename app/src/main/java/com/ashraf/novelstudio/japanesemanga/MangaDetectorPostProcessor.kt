package com.ashraf.novelstudio.japanesemanga

import android.content.Context
import android.graphics.PointF
import android.util.Base64
import com.paddle.ocr.postprocess.DBPostProcessor
import com.paddle.ocr.util.OpenCVUtils
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs PaddleOCR's native DBPostProcess on PP-OCRv6 Manga's ONNX probability map.
 *
 * The detector itself remains ONNX Runtime Web, but its output is passed to the same OpenCV contour,
 * polygon scoring, unclip and min-area-rectangle implementation used by NovelStudio's PaddleOCR
 * pipeline. This avoids an approximate connected-component postprocessor that can split glyphs or
 * merge adjacent lines incorrectly.
 */
internal object MangaDetectorPostProcessor {
    private const val MAX_MAP_PIXELS = 1_000_000

    fun process(context: Context, payload: String): String {
        return try {
            val input = JSONObject(payload)
            val mapH = input.getInt("mh")
            val mapW = input.getInt("mw")
            val resizedW = input.getInt("rw")
            val resizedH = input.getInt("rh")
            val imageW = input.getInt("W")
            val imageH = input.getInt("H")
            require(mapH > 0 && mapW > 0 && mapH.toLong() * mapW <= MAX_MAP_PIXELS) {
                "Invalid detector map dimensions: ${mapH}x${mapW}"
            }
            require(resizedW > 0 && resizedH > 0 && imageW > 0 && imageH > 0) {
                "Invalid detector image dimensions"
            }
            if (!OpenCVUtils.init(context.applicationContext)) {
                throw IllegalStateException("OpenCV native runtime could not initialize")
            }

            val bytes = Base64.decode(input.getString("mapB64"), Base64.NO_WRAP)
            val n = mapH * mapW
            require(bytes.size == n * 4) { "Detector map byte length mismatch: ${bytes.size}, expected ${n * 4}" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val probability = FloatArray(n)
            buffer.get(probability)

            val boxes = DBPostProcessor.process(
                pred = probability,
                predShape = longArrayOf(1L, 1L, mapH.toLong(), mapW.toLong()),
                thresh = 0.15f,
                boxThresh = 0.25f,
                unclipRatio = 1.4f,
                maxCandidates = 5000,
                useDilation = false,
                scoreMode = "slow",
                boxType = "quad",
                // DBPostProcessor maps detector-map pixels back to the resized detector input. We then
                // scale those points to the original source bitmap below.
                originalH = resizedH,
                originalW = resizedW,
                unclipContour = true,
                // The upstream Manga detector has no 5-pixel post-unclip cutoff; keep small/fine text.
                minSizeAfterUnclip = 3f
            )

            val out = JSONArray()
            val sx = imageW.toFloat() / resizedW.toFloat()
            val sy = imageH.toFloat() / resizedH.toFloat()
            for (box in boxes) {
                val points = JSONArray()
                val mapped = ArrayList<PointF>(4)
                var minX = Float.POSITIVE_INFINITY
                var minY = Float.POSITIVE_INFINITY
                var maxX = Float.NEGATIVE_INFINITY
                var maxY = Float.NEGATIVE_INFINITY
                var cx = 0f
                var cy = 0f
                for (point: PointF in box.points) {
                    val x = (point.x * sx).coerceIn(0f, imageW.toFloat())
                    val y = (point.y * sy).coerceIn(0f, imageH.toFloat())
                    points.put(JSONArray().put(x.toDouble()).put(y.toDouble()))
                    mapped.add(PointF(x, y))
                    cx += x
                    cy += y
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
                val w = maxX - minX
                val h = maxY - minY
                if (w < 4f || h < 4f || mapped.size != 4) continue
                // Keep the same quadrilateral geometry convention as the existing native Paddle adapter:
                // axis-aligned bounds are for clipping, while oriented edge sizes/angle drive OCR overlays.
                val p0 = mapped[0]
                val p1 = mapped[1]
                val p2 = mapped[2]
                val orientedW = kotlin.math.hypot((p1.x - p0.x).toDouble(), (p1.y - p0.y).toDouble()).toFloat()
                val orientedH = kotlin.math.hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble()).toFloat()
                val angle = Math.toDegrees(kotlin.math.atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble())).toFloat()
                out.put(
                    JSONObject()
                        .put("x", minX.toDouble())
                        .put("y", minY.toDouble())
                        .put("w", w.toDouble())
                        .put("h", h.toDouble())
                        .put("cx", (cx / mapped.size).toDouble())
                        .put("cy", (cy / mapped.size).toDouble())
                        .put("orientedW", orientedW.toDouble())
                        .put("orientedH", orientedH.toDouble())
                        .put("angle", angle.toDouble())
                        .put("points", points)
                )
            }
            JSONObject()
                .put("boxes", out)
                .put("postprocessor", "PaddleOCR DBPostProcess/OpenCV")
                .toString()
        } catch (e: Throwable) {
            JSONObject()
                .put("error", (e.message ?: e.javaClass.simpleName).take(300))
                .put("postprocessor", "PaddleOCR DBPostProcess/OpenCV")
                .toString()
        }
    }
}
