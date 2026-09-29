package com.ashraf.novelstudio

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/**
 * User glossary.
 * Format per line:
 *   source | variant | 原文 => বাংলা
 * or:
 *   me = আমাকে
 *
 * The raw text is kept exactly as pasted by the user. Matching is performed
 * only when a chapter is sent to the chatbot, and only matched terms are
 * added to the prompt.
 */
object GlossaryStore {
    private const val PREF = "glossary"
    private const val KEY = "raw"

    data class Entry(val aliases: List<String>, val translation: String)

    fun raw(c: Context): String =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun save(c: Context, text: String) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, text).apply()
    }

    fun entries(c: Context): List<Entry> = parse(raw(c))

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (line0 in text.lineSequence()) {
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val pos = line.indexOf("=>").let { if (it >= 0) it else line.indexOf('=') }
            if (pos <= 0) continue

            val left = line.substring(0, pos).trim()
            val right = line.substring(if (line.startsWith("=>", pos)) pos + 2 else pos + 1).trim()
            if (left.isEmpty() || right.isEmpty()) continue

            val aliases = left.split('|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { key(it) }
            if (aliases.isNotEmpty()) out += Entry(aliases, right)
        }
        return out
    }

    /** Returns only glossary entries whose aliases actually occur in the chapter. */
    fun contextFor(c: Context, chapter: String): String {
        if (chapter.isBlank()) return ""
        prepare(c)
        val all = cachedEntries
        if (all.isEmpty()) return ""

        val matches = LinkedHashMap<String, String>()
        // Split into modest regexes so a 2000+ term glossary does not create
        // one enormous regex. Longest aliases are checked first.
        for (chunk in all.chunked(120)) {
            val aliasEntries = chunk.flatMap { e -> e.aliases.map { alias -> alias to e } }
                .sortedByDescending { it.first.length }
            val aliasMap = aliasEntries.associate { it.first.let { alias -> key(alias) } to it.second }
            val parts = aliasEntries.map { (alias, entry) ->
                val a = Regex.escape(alias)
                val pattern = if (needsBoundary(alias)) {
                    "(?<![\\p{L}\\p{N}])($a)(?![\\p{L}\\p{N}])"
                } else "($a)"
                pattern to entry
            }
            if (parts.isEmpty()) continue

            val rx = try {
                Regex(parts.joinToString("|") { it.first }, setOf(RegexOption.IGNORE_CASE))
            } catch (_: Exception) {
                continue
            }
            for (m in rx.findAll(chapter)) {
                val hit = m.value
                val normalized = key(hit)
                val entry = aliasMap[normalized] ?: continue
                matches.putIfAbsent(normalized, entry.translation)
            }
        }

        if (matches.isEmpty()) return ""
        return "\n\n[Glossary — use these translations consistently]\n" +
            matches.entries.joinToString("\n") { "${it.key} => ${it.value}" }
    }

    // Parsing the same 2000+ lines for every chapter is unnecessary. This
    // lightweight process cache is invalidated automatically when the pasted raw text changes.
    private var cachedRaw = ""
    private var cachedEntries: List<Entry> = emptyList()

    private fun prepare(c: Context) {
        val r = raw(c)
        if (r != cachedRaw) {
            cachedRaw = r
            cachedEntries = parse(r)
        }
    }

    private fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

    private fun needsBoundary(s: String): Boolean =
        s.any { it.isLetterOrDigit() && it.code < 0x2E80 }
}
