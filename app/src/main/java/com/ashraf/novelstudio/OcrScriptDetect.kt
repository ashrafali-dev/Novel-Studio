package com.ashraf.novelstudio

/**
 * Which script a panel is really written in.
 *
 * The URL/language guess ([WebModeLogic.pickScript]) is only a *starting* guess: m.comic.naver.com
 * serves Japanese/Korean/Chinese titles under the same host, and a site that once learned the wrong
 * script kept every later panel locked to it. The router then asked Paddle for JAPANESE on Hangul
 * text, Paddle returned nothing, and the ML Kit fallback was asked for JAPANESE too - so both
 * engines were blind on the same panel (log: "paddle_heavy returned no usable text for JAPANESE;
 * using ML Kit fallback" while the text was Korean).
 *
 * [pick] counts letters only: every OCR engine returns approximations, but the *writing system*
 * survives even a bad read. Counting is deliberately cheap and allocation-free-ish, so it can run on
 * the output of the first pass instead of costing another OCR call.
 */
internal object OcrScriptDetect {
    private const val MIN_HAN = 3
    private const val MIN_KANA = 2
    private const val MIN_HANGUL = 2
    private const val MIN_LATIN = 8

    private fun hangul(c: Char) = c in '\uAC00'..'\uD7A3' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F'
    private fun kana(c: Char) = c in '\u3040'..'\u30FF' || c in '\u31F0'..'\u31FF' || c == '\u30FC'
    private fun han(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF'
    private fun latin(c: Char) = c in 'a'..'z' || c in 'A'..'Z' ||
        c in '\u00C0'..'\u024F' || c in '\u0980'..'\u09FF'   // Bengali/Latin-ext: the target language, not the source

    /**
     * Best guess for [blocks], or null when the read is too sparse to decide (one stray glyph is not
     * evidence for a language: the first label of a Korean bubble is often a single Latin "SO").
     * Kana wins over Han (Japanese always carries kana; Chinese never does), Hangul is checked first.
     */
    fun pick(blocks: List<OcrBlock>): OcrScript? {
        var h = 0; var k = 0; var c = 0; var l = 0
        for (b in blocks) {
            for (ch in b.text) {
                when {
                    hangul(ch) -> h++
                    kana(ch) -> k++
                    han(ch) -> c++
                    latin(ch) -> l++
                }
            }
        }
        return when {
            h >= MIN_HANGUL && h * 3 >= (c + k) -> OcrScript.KOREAN
            k >= MIN_KANA && k * 4 >= c -> OcrScript.JAPANESE
            c >= MIN_HAN && (c + k) > l -> OcrScript.CHINESE
            l >= MIN_LATIN -> OcrScript.LATIN
            h > 0 && h >= c + k -> OcrScript.KOREAN
            k > 0 && k * 2 >= c -> OcrScript.JAPANESE
            c >= 2 -> OcrScript.CHINESE
            else -> null
        }
    }

    /**
     * True when a read with [want] produced mostly letters of a *different* writing system, i.e. the
     * engine recognised shapes but under the wrong dictionary. Used to throw away garbage like
     * "SO\uC774L0." before it reaches the translator.
     */
    fun contradicts(blocks: List<OcrBlock>, want: OcrScript): Boolean {
        val got = pick(blocks) ?: return false
        if (got == want) return false
        // A Latin read of CJK art (or the reverse) is a real mismatch; Han<->Kana is not reliable.
        val cjk = setOf(OcrScript.CHINESE, OcrScript.JAPANESE, OcrScript.KOREAN)
        return (want in cjk) != (got in cjk) || want == OcrScript.KOREAN || got == OcrScript.KOREAN
    }

    /** The first script in [order] that differs from [wrong] and from the blocked set. */
    fun alternate(wrong: OcrScript, tried: Set<OcrScript>): OcrScript? =
        (WebModeLogic.realtimeFallbackScripts(wrong).filter { it !in tried }).firstOrNull()
}
