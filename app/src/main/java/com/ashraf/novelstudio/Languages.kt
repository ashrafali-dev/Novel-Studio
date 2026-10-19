package com.ashraf.novelstudio

import android.content.Context

/**
 * "Translate from X to Y" setting. The translation prompts are generated from it, so a user who picks
 * Hindi gets a prompt that asks for Hindi. Bengali with auto-detected source keeps the original
 * hand-written Bengali prompts untouched. A prompt the user edited by hand is never overwritten.
 */
object Langs {
    const val AUTO = "Auto-detect"
    const val DEFAULT_TARGET = "Bengali"

    val SOURCES = listOf(
        AUTO, "Chinese", "Japanese", "Korean", "English", "Thai", "Vietnamese", "Indonesian", "Spanish", "Russian"
    )
    val TARGETS = listOf(
        "Bengali", "English", "Hindi", "Urdu", "Arabic", "Indonesian", "Malay", "Spanish", "Portuguese",
        "French", "German", "Russian", "Turkish", "Vietnamese", "Thai", "Filipino"
    )

    fun source(c: Context): String = Prefs.get(c, "srcLang", AUTO).let { if (it in SOURCES) it else AUTO }
    fun target(c: Context): String = Prefs.get(c, "tgtLang", DEFAULT_TARGET).let { if (it in TARGETS) it else DEFAULT_TARGET }
    fun setupDone(c: Context): Boolean = Prefs.bool(c, "langSetupDone")

    fun save(c: Context, src: String, tgt: String) {
        Prefs.put(c, "srcLang", src)
        Prefs.put(c, "tgtLang", tgt)
        Prefs.putBool(c, "langSetupDone", true)
        applyRuntime(c)
    }

    /** Pushes the choice into code that has no Context (the sound-effect safety net is Bengali-only). */
    fun applyRuntime(c: Context) {
        WmSfx.bengali = target(c) == DEFAULT_TARGET
    }

    /** True when a hand-edited translation prompt is saved (the language choice will not replace it). */
    fun hasCustomPrompt(c: Context): Boolean {
        val p = Prefs.get(c, "prompt", "")
        val r = Prefs.get(c, "realtimePrompt", "")
        return (p.isNotBlank() && p != Prefs.DEFAULT_PROMPT) || (r.isNotBlank() && r != Prefs.DEFAULT_REALTIME_PROMPT)
    }

    private fun classic(c: Context) = source(c) == AUTO && target(c) == DEFAULT_TARGET

    private fun fromPart(c: Context): String {
        val s = source(c)
        return if (s == AUTO) " (detect the source language automatically)" else " written in $s"
    }

    fun novelPrompt(c: Context): String {
        if (classic(c)) return Prefs.DEFAULT_PROMPT
        val tgt = target(c)
        return """You are an expert novel translator. I will send you chapters of web novels${fromPart(c)}. Translate every chapter into natural, fluent $tgt.

Rules:
- Do not skip anything and do not summarize. Translate the whole chapter.
- Keep character names and special terms (skills, ranks, place names) identical in every chapter.
- Keep the tone of the dialogue and the narration the same as the original.
- Reply with the translation only, with no extra comments.

From now on, translate whatever I send."""
    }

    fun realtimePrompt(c: Context): String {
        if (classic(c)) return Prefs.DEFAULT_REALTIME_PROMPT
        val tgt = target(c)
        return """You are an expert webtoon, manhwa and comic translator. Translate every piece of text read from the images${fromPart(c)} into clear, natural $tgt.

Rules:
- Keep each marker such as [001], [002] exactly once, in exactly the same order.
- Do not skip any text: dialogue, narration, system windows and sound effects all count.
- Keep the emotion, each character's voice and the meaning of the scene. Keep translations short and natural so they fit speech bubbles.
- Do not rewrite the original text and do not add summaries or explanations.
- Do not output your own thoughts, reasoning, status lines, headings, disclaimers or any extra commentary.
- Return only the translation with the markers, in $tgt."""
    }

    /**
     * The three lines of the comic-page rules that name the target language. For Bengali this is exactly
     * the original wording (including the Bengali sound-effect examples).
     */
    fun sfxRule(c: Context): String {
        val tgt = target(c)
        if (tgt == DEFAULT_TARGET) return """sound effect. Translate EVERY marker into Bengali script, including sound effects and exclamations
(BOOM -> ধুম!, HAH -> হাঃ, GRR -> গরররর!). If a word has no Bengali equivalent, write its sound in
Bengali letters. Never leave a marker in English letters, never skip a marker, and never put two"""
        return """sound effect. Translate EVERY marker into $tgt, including sound effects and exclamations:
write each sound the way $tgt readers expect to see it. If a word has no $tgt equivalent, write its
sound in $tgt letters. Never leave a marker in its original language, never skip a marker, and never put two"""
    }
}
