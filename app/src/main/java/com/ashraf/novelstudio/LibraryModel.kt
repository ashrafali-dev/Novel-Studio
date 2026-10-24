package com.ashraf.novelstudio

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Read-only view model behind the Library and the home screen's library strip.
 *
 * It does NOT invent content. Extensions here are the app's real source handling — built-in site
 * adapters ([SiteRegistry]) plus hosts the app has actually learned selectors for
 * ([SiteProfiles], written only after a successful extraction) — and built-in starting points. Update
 * rows are computed from chapters that really exist in the offline translation [Store] together with
 * the app's own read/fetch history (see [MainActivity.notifyLibraryChanged] / [recordFetched]).
 */
object LibraryModel {

    data class Ext(
        val id: String,              // stable key persisted in Prefs
        val name: String,
        val host: String?,
        val url: String?,            // built-in start page, if any
        val langs: List<String>,
        val kind: String,            // "Built-in adapter" / "Learned on this device" / "Built-in"
        val builtIn: Boolean,
        val known: Boolean,          // the app can use it without learning (adapter or built-in)
        val learnedAt: Long
    )

    data class Shelf(
        val name: String,
        val chapters: Int,
        val sourceHost: String,
        val lastSavedAt: Long,       // id of the newest translation = save time
        val chapterLabels: List<String>
    ) { val isComic: Boolean get() = chapterLabels.firstOrNull { it.isNotBlank() }?.let { isComicNumber(it) } ?: false }

    data class Update(
        val novel: String,
        val sourceHost: String,
        val chapters: Int,
        val newCount: Int,           // chapters added after the last one this app recorded for the book
        val lastChapter: String,
        val at: Long,
        val isComic: Boolean
    )

    private const val K_EXT = "libExtensions"
    private const val K_READ = "libRead"        // novel -> chapter id last opened
    private const val K_SEEN = "libSeen"        // novel -> {count, at} baseline at the last Library visit

    // ------------------------------------------------------------------ sources / extensions

    fun sources(c: Context): List<Ext> {
        val out = LinkedHashMap<String, Ext>()
        fun add(e: Ext) { out.putIfAbsent(e.id.ifEmpty { e.host ?: e.name }, e) }

        // 1) Built-in site adapters (real extraction code in SiteRegistry).
        for (a in builtInAdapters) add(Ext(a.id, a.name, a.host, a.url, a.langs, "Built-in adapter", true, true, 0L))

        // 2) Default starting points (the same list the home screen offers) that are not learned yet.
        for ((name, url) in defaultSites) {
            val host = hostOf(url) ?: continue
            add(Ext("host:" + host, name, host, url, listOf("WebView"), "Available", false, false, 0L))
        }

        // 3) Hosts the app really learned on this device.
        val profiles = try { JSONObject(Prefs.get(c, "siteProfiles", "{}")) } catch (_: Exception) { JSONObject() }
        val hosts = profiles.keys().asSequence().toList()
        for (host in hosts) {
            val o = profiles.optJSONObject(host) ?: continue
            val n = listOf("content", "next", "title", "prev").count { o.optString(it).isNotBlank() }
            if (n == 0) continue
            val langs = if (o.optBoolean("learned")) listOf("WebView", "Learned") else listOf("WebView")
            add(Ext("host:" + host, prettyName(host), host, "https://" + host + "/", langs, "Learned on this device", false, true, 0L))
        }
        return out.values.toList()
    }

    /** Hosts that were learned but have been turned off in [disabled]. */
    fun enabled(c: Context, e: Ext): Boolean = e.builtIn || !disabled(c).contains(e.id)

    fun disabled(c: Context): Set<String> =
        Prefs.get(c, "libExtDisabled", "").split('\n').filter { it.isNotBlank() }.toSet()

    fun setEnabled(c: Context, id: String, on: Boolean) {
        val cur = disabled(c).toMutableSet()
        if (on) cur.remove(id) else cur.add(id)
        Prefs.put(c, "libExtDisabled", cur.joinToString("\n"))
    }

    // ------------------------------------------------------------------ shelves + updates

    fun shelves(c: Context): List<Shelf> {
        val items = Store.list(c)
        return items.groupBy { it.novel }.map { (name, list) ->
            val sorted = list.sortedWith(compareBy<Tr>({ it.sortKey }, { it.id }))
            Shelf(
                name = name,
                chapters = list.size,
                sourceHost = hostOf(list.maxByOrNull { it.id }?.url.orEmpty()).orEmpty(),
                lastSavedAt = list.maxOf { it.id },
                chapterLabels = sorted.map { it.label() }
            )
        }.sortedByDescending { it.lastSavedAt }
    }

    /**
     * "Updated" rows for books that have a real offline copy: chapters saved AFTER the chapter this app
     * last opened for that book, compared with the count the user last saw in the Library. No baseline ->
     * honest derived signal (everything since the last opened chapter counts as new).
     */
    fun updates(c: Context, markSeen: Boolean = false): List<Update> {
        val read = json(c, K_READ)
        val seen = json(c, K_SEEN)
        val rows = shelves(c).mapNotNull { s ->
            if (s.chapters <= 0) return@mapNotNull null
            val all = Store.chapters(c, s.name)
            val lastReadId = read.optLong(s.name, -1L)
            val baselineCount = seen.optInt(s.name + "_count", -1)
            val newCount = when {
                baselineCount >= 0 -> (s.chapters - baselineCount).coerceAtLeast(0)
                else -> if (lastReadId > 0) all.count { it.id > lastReadId } else s.chapters
            }
            Update(
                novel = s.name,
                sourceHost = s.sourceHost,
                chapters = s.chapters,
                newCount = newCount,
                lastChapter = all.lastOrNull()?.label().orEmpty(),
                at = s.lastSavedAt,
                isComic = s.isComic
            )
        }.filter { it.chapters > 0 }
        if (markSeen) {
            val o = json(c, K_SEEN)
            for (r in rows) o.put(r.novel + "_count", r.chapters)
            Prefs.put(c, K_SEEN, o.toString())
        }
        return rows.sortedWith(compareByDescending<Update> { it.newCount > 0 }.thenByDescending { it.at })
    }

    /** Called when the user really opens a saved chapter. */
    fun recordRead(c: Context, novel: String, chapterId: Long) {
        val o = json(c, K_READ)
        o.put(novel, chapterId)
        Prefs.put(c, K_READ, o.toString())
    }

    /** Chapters a Shelf can offer: newest first, with the ids needed to open them. */
    fun shelfChapters(c: Context, novel: String): List<Tr> =
        Store.chapters(c, novel).sortedWith(compareByDescending<Tr> { it.sortKey }.thenByDescending { it.id })

    fun readRaw(c: Context, id: Long): String = Store.read(c, id)

    // ------------------------------------------------------------------ helpers

    private data class BuiltIn(
        val id: String, val name: String, val host: String, val url: String, val langs: List<String>
    )

    private val builtInAdapters = listOf(
        BuiltIn("adapter:webnovel", "WebNovel", "webnovel.com", "https://www.webnovel.com/", listOf("WebView", "chapters", "next")),
        BuiltIn("adapter:wtrlab", "WTR-LAB", "wtr-lab.com", "https://wtr-lab.com/", listOf("WebView", "next", "auto-next")),
        BuiltIn("adapter:qidian", "Qidian", "qidian.com", "https://www.qidian.com/", listOf("WebView", "chapters")),
        BuiltIn("adapter:novel543", "Novel543", "novel543.com", "https://www.novel543.com/", listOf("WebView", "chapters")),
        BuiltIn("builtin:webtoons", "Webtoons", "webtoons.com", "https://www.webtoons.com/", listOf("WebView", "images")),
        BuiltIn("builtin:tapas", "Tapas", "tapas.io", "https://tapas.io/", listOf("WebView", "images")),
        BuiltIn("builtin:mangadex", "MangaDex", "mangadex.org", "https://mangadex.org/", listOf("WebView", "images")),
        BuiltIn("builtin:novelbin", "NovelBin", "", "https://novelbin.com/", listOf("WebView"))
    )

    private val defaultSites = listOf(
        "BoxNovel" to "https://boxnovel.com/",
        "AsianNovel" to "https://www.asianovel.com/",
        "NovelFull" to "https://novelfull.net/",
        "LightNovelPub" to "https://lightnovelpub.com/",
        "NovelNext" to "https://novelnext.com/",
        "ReadNovelFull" to "https://readnovelfull.com/"
    )

    private fun json(c: Context, key: String): JSONObject =
        try { JSONObject(Prefs.get(c, key, "{}")) } catch (_: Exception) { JSONObject() }

    fun hostOf(url: String): String? = try {
        Uri.parse(url).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.ifBlank { null }
    } catch (_: Exception) { null }

    fun prettyName(host: String): String =
        host.removePrefix("www.").removePrefix("m.").substringBefore('.').replaceFirstChar { it.uppercase() }

    private fun isComicNumber(label: String): Boolean = label.contains(Regex("#\\d+"))

    /** Label shown for a shelf row (source + chapter count). */
    fun shelfMeta(s: Shelf): String {
        val src = s.sourceHost.ifBlank { "" }
        val kind = if (s.isComic) "comic" else "novel"
        return listOfNotNull(s.chapters.toString() + " ch", kind, src.ifBlank { null }).joinToString(" · ")
    }
}
