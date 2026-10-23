package com.ashraf.novelstudio

/**
 * Structural checks on a downloaded recognition `inference.yml`, using the SAME anchors the vendored
 * runtime reads (`com.paddle.ocr.model.ModelConfig`: a top-level `PostProcess:` line, then
 * `character_dict:` nested one level deeper, then one `- 'char'` entry per class).
 *
 * A file that only *contains* those strings can still be wrong (an HTML error page with a copied
 * snippet, a detector yml, a truncated dictionary). Here the dictionary indentation is followed for
 * real, so a file whose entries the runtime would not read is rejected at download time with a clear
 * message instead of turning into an empty OCR result later.
 *
 * Pure Kotlin: runs on the JVM in unit tests without Android or the native runtime.
 */
internal object PaddleConfigValidator {

    /** Minimum number of dictionary entries; the smallest real PP-OCR dictionary has ~6 000. */
    const val MIN_DICTIONARY_ENTRIES = 100

    class Layout(val postProcessIndent: Int, val dictionaryIndent: Int, val entries: Int)

    /**
     * Mirrors ModelConfig.extractCharacterDict line by line. Returns null when the layout the runtime
     * needs is missing or yields fewer than [MIN_DICTIONARY_ENTRIES] entries.
     */
    fun inspect(content: String): Layout? {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
        val postProcessLine = lines.indexOfFirst { it.trim() == "PostProcess:" }
        if (postProcessLine < 0) return null
        val postProcessIndent = leadingSpaces(lines[postProcessLine])

        var dictionaryLine = -1
        var i = postProcessLine + 1
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) { i++; continue }
            val indent = leadingSpaces(lines[i])
            if (indent <= postProcessIndent) break
            if (trimmed == "character_dict:") { dictionaryLine = i; break }
            i++
        }
        if (dictionaryLine < 0) return null
        val dictionaryIndent = leadingSpaces(lines[dictionaryLine])

        var entries = 0
        var j = dictionaryLine + 1
        while (j < lines.size) {
            val line = lines[j]
            val indent = leadingSpaces(line)
            val contentPart = line.substring(indent)
            val trimmed = contentPart.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) { j++; continue }
            if (!contentPart.startsWith("-")) {
                if (indent <= dictionaryIndent) break
                j++
                continue
            }
            entries++
            j++
        }
        if (entries < MIN_DICTIONARY_ENTRIES) return null
        return Layout(postProcessIndent, dictionaryIndent, entries)
    }

    fun isValid(content: String): Boolean = inspect(content) != null

    private fun leadingSpaces(line: String): Int =
        line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
}
