package com.ashraf.novelstudio

/**
 * Pure decisions about one Google Lens reply and about upload image preparation. Kept free of Android
 * types so the rules that decide "success / silence / failure" and the upload geometry are covered by
 * plain JVM tests (no network, no bitmap required).
 */
internal object LensAnalysis {

    /** Sanity limits for a 3.5 MB JPEG upload; prepared images may never exceed them. */
    const val MAX_AREA = 4_000_000L
    const val MAX_DIMENSION = 2400
    const val MIN_SIDE_FOR_UPLOAD = 16

    /** Upload geometry for [w] x [h]; the result is always within the JPEG/uploader limits. */
    class Prepared(val w: Int, val h: Int)

    /**
     * Ordinary phone screenshots stay at native size (Chromium Lens behaviour). Only resize when the
     * picture is BOTH larger than [MAX_AREA] pixels AND longer than [MAX_DIMENSION] on a side — and then
     * to a size that satisfies the area limit too. The previous rule only checked both maxima, so a
     * 2400x2400 screenshot could be re-encoded with no resize and still exceed the 3.5 MB body limit.
     */
    fun preparedSize(w: Int, h: Int): Prepared {
        require(w > 0 && h > 0) { "empty image" }
        if (w.toLong() * h.toLong() <= MAX_AREA && w <= MAX_DIMENSION && h <= MAX_DIMENSION) return Prepared(w, h)
        val byArea = Math.sqrt(MAX_AREA.toDouble() / (w.toDouble() * h.toDouble()))
        val byDim = MAX_DIMENSION.toDouble() / Math.max(w, h)
        val k = Math.min(1.0, Math.min(byArea, byDim))
        return Prepared((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
    }

    /**
     * A 200 answer without a text layer. The distinction matters: a reply that carried an
     * objects_response but no paragraphs is Lens telling us the picture has no text (a legitimate empty
     * result), while a 200 body without objects_response is Lens not answering for this upload — it says
     * nothing about the picture and must not be remembered as "this image has no text".
     */
    enum class Outcome { TEXT, EMPTY_IMAGE, SILENT, NOT_A_LENS_REPLY }

    class Verdict(val outcome: Outcome, val textBlocks: Int, val paragraphs: Int, val lines: Int)

    fun classify(parsed: Boolean, valid: Boolean, textBlocks: Int, paragraphs: Int, lines: Int): Verdict {
        val outcome = when {
            !parsed -> Outcome.NOT_A_LENS_REPLY
            !valid -> Outcome.SILENT
            textBlocks > 0 -> Outcome.TEXT
            else -> Outcome.EMPTY_IMAGE
        }
        return Verdict(outcome, textBlocks, paragraphs, lines)
    }

    /**
     * Whether an outcome may be stored so a repeated scan of the same bitmap does not upload again.
     * SILENT is never cached (it was a transport/protocol hiccup, not a statement about the picture);
     * EMPTY_IMAGE is cached briefly by the caller, TEXT for the full TTL.
     */
    fun isCacheable(outcome: Outcome): Boolean = outcome == Outcome.TEXT || outcome == Outcome.EMPTY_IMAGE
}
