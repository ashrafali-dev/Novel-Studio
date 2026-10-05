package com.ashraf.novelstudio

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Tr(val id: Long, val novel: String, val number: String, val title: String, val url: String) {
    val sortKey: Double get() = number.toDoubleOrNull() ?: Double.MAX_VALUE
    fun label(): String = (if (number.isNotEmpty()) "Ch $number — " else "") + title
}

data class Bookmark(val name: String, val url: String, var lastUrl: String, var lastTitle: String)

data class HistoryEntry(val url: String, val title: String, val time: Long)

object Store {
    // ---------- offline library (saved translations) ----------
    private fun idx(c: Context) = File(c.filesDir, "translations.json")
    private fun dir(c: Context): File { val d = File(c.filesDir, "tr"); d.mkdirs(); return d }

    fun list(c: Context): List<Tr> {
        val f = idx(c)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Tr(o.getLong("id"), o.optString("novel", "Unknown"), o.optString("number"), o.optString("title"), o.optString("url"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun writeIdx(c: Context, items: List<Tr>) {
        val a = JSONArray()
        for (t in items) {
            a.put(JSONObject().put("id", t.id).put("novel", t.novel).put("number", t.number).put("title", t.title).put("url", t.url))
        }
        idx(c).writeText(a.toString())
    }

    fun novelNames(c: Context): List<String> =
        list(c).sortedByDescending { it.id }.map { it.novel }.distinct()

    fun chapters(c: Context, novel: String): List<Tr> =
        list(c).filter { it.novel == novel }.sortedWith(compareBy<Tr>({ it.sortKey }, { it.id }))

    fun save(c: Context, ch: Chapter?, text: String): Tr {
        val raw = (ch?.novel ?: "").ifBlank { "Unknown" }
        val novel = Prefs.get(c, "alias_$raw", raw)
        val number = ch?.number ?: ""
        val title = ch?.title ?: "অনুবাদ"
        val url = ch?.url ?: ""
        val items = list(c).toMutableList()
        val existing = items.firstOrNull {
            (url.isNotEmpty() && it.url == url) || (number.isNotEmpty() && it.novel == novel && it.number == number)
        }
        val id = existing?.id ?: System.currentTimeMillis()
        File(dir(c), "$id.txt").writeText(text)
        val tr = Tr(id, novel, number, title, url)
        if (existing == null) items.add(tr) else items[items.indexOf(existing)] = tr
        writeIdx(c, items)
        return tr
    }

    fun saveMapped(
        c: Context,
        ch: Chapter,
        text: String,
        segmentsJson: String,
        mapJson: String
    ): Tr {
        val tr = save(c, ch, text)
        val meta = JSONObject()
            .put("segments", segmentsJson)
            .put("map", mapJson)
            .put("version", 1)
        try { File(dir(c), "${tr.id}.map.json").writeText(meta.toString()) } catch (_: Exception) {}
        return tr
    }

    fun readMapped(c: Context, id: Long): JSONObject? =
        try {
            val f = File(dir(c), "${id}.map.json")
            if (!f.exists()) null else JSONObject(f.readText())
        } catch (_: Exception) { null }

    // saved translation for this chapter (matched by URL, or novel name + chapter number)
    fun find(c: Context, ch: Chapter): Tr? {
        val raw = ch.novel.ifBlank { "Unknown" }
        val novel = Prefs.get(c, "alias_$raw", raw)
        return list(c).firstOrNull {
            (ch.url.isNotEmpty() && it.url == ch.url) || (ch.number.isNotEmpty() && it.novel == novel && it.number == ch.number)
        }
    }

    fun read(c: Context, id: Long): String = try { File(dir(c), "$id.txt").readText() } catch (e: Exception) { "" }

    fun delete(c: Context, id: Long) {
        File(dir(c), "$id.txt").delete()
        writeIdx(c, list(c).filter { it.id != id })
    }

    fun deleteNovel(c: Context, novel: String) {
        for (t in list(c)) if (t.novel == novel) File(dir(c), "${t.id}.txt").delete()
        writeIdx(c, list(c).filter { it.novel != novel })
    }

    fun rename(c: Context, old: String, new: String) {
        if (new.isBlank() || new == old) return
        writeIdx(c, list(c).map { if (it.novel == old) it.copy(novel = new) else it })
        Prefs.put(c, "alias_$old", new)   // future chapters saved under the old detected name go to the new name
    }

    fun export(c: Context, novel: String?): String {
        val names = if (novel != null) listOf(novel) else novelNames(c)
        return names.joinToString("\n\n########################\n\n") { n ->
            "📖 $n\n\n" + chapters(c, n).joinToString("\n\n==========\n\n") { it.label() + "\n\n" + read(c, it.id) }
        }
    }

    // ---------- browser history ----------
    fun history(c: Context): List<HistoryEntry> {
        val raw = Prefs.get(c, "browserHistory")
        if (raw.isEmpty()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                HistoryEntry(o.optString("url"), o.optString("title"), o.optLong("time"))
            }.sortedByDescending { it.time }
        } catch (_: Exception) { emptyList() }
    }

    fun addHistory(c: Context, url: String, title: String) {
        if (!url.startsWith("http")) return
        val host = Uri.parse(url).host ?: return
        if (host.isBlank()) return
        val old = history(c).filter { it.url != url }.take(99).toMutableList()
        old.add(0, HistoryEntry(url, title.ifBlank { host }, System.currentTimeMillis()))
        val a = JSONArray()
        old.forEach { h ->
            a.put(JSONObject().put("url", h.url).put("title", h.title).put("time", h.time))
        }
        Prefs.put(c, "browserHistory", a.toString())
    }

    fun clearHistory(c: Context) {
        Prefs.put(c, "browserHistory", "[]")
    }

    // ---------- bookmarks of novel sites ----------
    fun bookmarks(c: Context): MutableList<Bookmark> {
        val s = Prefs.get(c, "lib")
        if (s.isEmpty()) return mutableListOf()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Bookmark(o.getString("name"), o.getString("url"), o.optString("lastUrl"), o.optString("lastTitle"))
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun saveBookmarks(c: Context, l: List<Bookmark>) {
        val a = JSONArray()
        for (n in l) a.put(JSONObject().put("name", n.name).put("url", n.url).put("lastUrl", n.lastUrl).put("lastTitle", n.lastTitle))
        Prefs.put(c, "lib", a.toString())
    }

    fun addBookmark(c: Context, n: Bookmark) {
        val l = bookmarks(c)
        l.removeAll { it.url == n.url }
        l.add(0, n)
        saveBookmarks(c, l)
    }

    fun removeBookmark(c: Context, i: Int) {
        val l = bookmarks(c)
        if (i in l.indices) { l.removeAt(i); saveBookmarks(c, l) }
    }

    fun touchBookmark(c: Context, ch: Chapter) {
        val host = AdBlock.root(Uri.parse(ch.url).host ?: return)
        val l = bookmarks(c)
        val n = l.firstOrNull { AdBlock.root(Uri.parse(it.url).host ?: "") == host } ?: return
        n.lastUrl = ch.url
        n.lastTitle = ch.title
        saveBookmarks(c, l)
    }
}
