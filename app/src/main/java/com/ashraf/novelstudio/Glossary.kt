package com.ashraf.novelstudio

import android.content.Context
import java.text.Normalizer
import java.util.Locale

object Glossary {
    private data class Entry(val aliases: List<String>, val translation: String)
    private var cachedRaw = ""
    private var cachedEntries: List<Entry> = emptyList()

    @Synchronized
    private fun entries(c: Context): List<Entry> {
        val raw = GlossaryStore.raw(c)
        if (raw == cachedRaw) return cachedEntries
        val map = LinkedHashMap<String, Pair<String, String>>()
        for (line0 in raw.lineSequence()) {
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val pos = line.indexOf("=>").let { if (it >= 0) it else line.indexOf('=') }
            if (pos <= 0) continue
            val left = line.substring(0, pos).trim()
            val right = line.substring(if (line.startsWith("=>", pos)) pos + 2 else pos + 1).trim()
            if (left.isEmpty() || right.isEmpty()) continue
            val aliases = left.split('|').map { it.trim() }
                .filter { it.isNotEmpty() }.distinctBy { key(it) }
            for (alias in aliases) map[key(alias)] = alias to right
        }
        cachedEntries = map.values.groupBy { it.second }.values
            .map { group -> Entry(group.map { it.first }, group.first().second) }
        cachedRaw = raw
        return cachedEntries
    }

    private fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

    private fun boundaryRequired(s: String): Boolean =
        s.any { it.isLetterOrDigit() && it.code < 0x2E80 }

    fun matches(c: Context, chapter: String): List<Pair<String, String>> {
        if (chapter.isBlank()) return emptyList()
        data class Hit(val start: Int, val end: Int, val surface: String, val translation: String)
        val hits = ArrayList<Hit>()
        // Cheap pre-filter: most glossary terms are not in a given chapter, so only
        // build a Regex for the ones whose text really occurs (plain indexOf is fast).
        val lc = chapter.lowercase(Locale.ROOT)
        for (entry in entries(c)) {
            for (alias in entry.aliases) {
                if (alias.isBlank()) continue
                if (!lc.contains(alias.lowercase(Locale.ROOT))) continue
                val escaped = Regex.escape(alias)
                val pattern = if (boundaryRequired(alias)) {
                    "(?<![\\p{L}\\p{N}])($escaped)(?![\\p{L}\\p{N}])"
                } else "($escaped)"
                val rx = try { Regex(pattern, setOf(RegexOption.IGNORE_CASE)) } catch (_: Exception) { continue }
                for (m in rx.findAll(chapter)) {
                    hits += Hit(m.range.first, m.range.last + 1, m.value, entry.translation)
                }
            }
        }
        hits.sortWith(compareBy<Hit>({ it.start }, { -(it.end - it.start) }))
        val selected = ArrayList<Hit>()
        for (h in hits) {
            if (selected.any { h.start < it.end && it.start < h.end }) continue
            selected += h
        }
        val seen = LinkedHashSet<String>()
        return selected.mapNotNull {
            val k = key(it.surface)
            if (seen.add(k)) it.surface to it.translation else null
        }
    }

    fun block(c: Context, chapter: String): String {
        val m = matches(c, chapter)
        if (m.isEmpty()) return ""
        return "[Glossary — use these translations consistently]\n" +
            m.joinToString("\n") { "${it.first} => ${it.second}" }
    }

    fun block(chapter: String): String = ""

    val count: Int
        get() = cachedEntries.sumOf { it.aliases.size }

    fun read(c: Context): String = GlossaryStore.raw(c)

    fun write(c: Context, text: String) {
        GlossaryStore.save(c, text)
        cachedRaw = ""
        cachedEntries = emptyList()
        entries(c)
    }

    fun append(c: Context, text: String) {
        val add = text.trim()
        if (add.isEmpty()) return
        val old = GlossaryStore.raw(c).trim()
        write(c, if (old.isEmpty()) add else "$old\n$add")
    }

    fun reload(c: Context) {
        cachedRaw = ""
        cachedEntries = emptyList()
        entries(c)
    }
}
