package com.ashraf.novelstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

data class GlossaryEntry(
    val source: String,
    val variants: List<String>,
    val translation: String
)

object GlossaryStore {
    private const val PREF = "ns_glossary"

    private fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

    fun list(c: Context): List<GlossaryEntry> {
        val raw = Prefs.get(c, PREF)
        if (raw.isBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val source = o.optString("source").trim()
                val translation = o.optString("translation").trim()
                val variants = mutableListOf<String>()
                val va = o.optJSONArray("variants")
                if (va != null) for (j in 0 until va.length()) {
                    va.optString(j).trim().takeIf { it.isNotEmpty() }?.let(variants::add)
                }
                if (source.isEmpty() || translation.isEmpty()) null
                else GlossaryEntry(source, variants.distinct(), translation)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(c: Context, entries: List<GlossaryEntry>) {
        val a = JSONArray()
        entries.filter { it.source.isNotBlank() && it.translation.isNotBlank() }
            .forEach { e ->
                a.put(
                    JSONObject()
                        .put("source", e.source.trim())
                        .put("variants", JSONArray(e.variants.filter { it.isNotBlank() }))
                        .put("translation", e.translation.trim())
                )
            }
        Prefs.put(c, PREF, a.toString())
    }

    fun add(c: Context, source: String, variantsText: String, translation: String) {
        val src = source.trim()
        val tr = translation.trim()
        if (src.isEmpty() || tr.isEmpty()) return

        val variants = variantsText.split("|", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(src, true) }
            .distinct()

        val old = list(c).toMutableList()
        val idx = old.indexOfFirst { key(it.source) == key(src) }
        val entry = GlossaryEntry(src, variants, tr)
        if (idx >= 0) old[idx] = entry else old.add(entry)
        save(c, old)
    }

    fun remove(c: Context, index: Int) {
        val old = list(c).toMutableList()
        if (index in old.indices) {
            old.removeAt(index)
            save(c, old)
        }
    }

    /*
     * Returns only glossary entries actually present in this chapter.
     * Longest matches win, so "Young Master" is checked before "Master".
     * The returned text is intentionally small: only matched terms are sent
     * to the translator, leaving the existing chapter/translation pipeline
     * unchanged.
     */
    fun matches(c: Context, chapter: String): List<GlossaryEntry> {
        if (chapter.isBlank()) return emptyList()
        val normalized = Normalizer.normalize(chapter, Normalizer.Form.NFKC)
        return list(c).filter { e ->
            val terms = listOf(e.source) + e.variants
            terms.any { term ->
                if (term.isBlank()) return@any false
                val t = Normalizer.normalize(term, Normalizer.Form.NFKC)
                val re = if (t.all { it.isLetterOrDigit() || it.isWhitespace() || it in "'-_." }) {
                    Regex("(?i)(?<![A-Za-z0-9])${Regex.escape(t)}(?![A-Za-z0-9])")
                } else {
                    Regex("(?i)${Regex.escape(t)}")
                }
                re.containsMatchIn(normalized)
            }
        }
    }

    fun formatMatches(c: Context, chapter: String): String {
        return matches(c, chapter).joinToString("\n") { e ->
            "${e.source} => ${e.translation}" +
                (if (e.variants.isNotEmpty()) " [${e.variants.joinToString(" | ")}]" else "")
        }
    }
}
