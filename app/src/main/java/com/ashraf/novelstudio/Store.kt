package com.ashraf.novelstudio

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Tr(val id: Long, val title: String, val url: String)
data class Novel(val name: String, val url: String, var lastUrl: String, var lastTitle: String)

object Store {
    // ---------- saved translations ----------
    private fun idx(c: Context) = File(c.filesDir, "translations.json")
    private fun dir(c: Context): File { val d = File(c.filesDir, "tr"); d.mkdirs(); return d }

    fun list(c: Context): List<Tr> {
        val f = idx(c)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Tr(o.getLong("id"), o.getString("title"), o.optString("url"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun writeIdx(c: Context, items: List<Tr>) {
        val a = JSONArray()
        for (t in items) a.put(JSONObject().put("id", t.id).put("title", t.title).put("url", t.url))
        idx(c).writeText(a.toString())
    }

    fun save(c: Context, title: String, url: String, text: String) {
        val items = list(c).toMutableList()
        val existing = items.firstOrNull { url.isNotEmpty() && it.url == url }
        val id = existing?.id ?: System.currentTimeMillis()
        File(dir(c), "$id.txt").writeText(text)
        if (existing == null) { items.add(Tr(id, title, url)); writeIdx(c, items) }
    }

    fun read(c: Context, id: Long): String = try { File(dir(c), "$id.txt").readText() } catch (e: Exception) { "" }

    fun delete(c: Context, id: Long) {
        File(dir(c), "$id.txt").delete()
        writeIdx(c, list(c).filter { it.id != id })
    }

    fun exportAll(c: Context): String =
        list(c).joinToString("\n\n==========\n\n") { it.title + "\n\n" + read(c, it.id) }

    // ---------- novel library ----------
    fun novels(c: Context): MutableList<Novel> {
        val s = Prefs.get(c, "lib")
        if (s.isEmpty()) return mutableListOf()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Novel(o.getString("name"), o.getString("url"), o.optString("lastUrl"), o.optString("lastTitle"))
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun saveNovels(c: Context, l: List<Novel>) {
        val a = JSONArray()
        for (n in l) a.put(JSONObject().put("name", n.name).put("url", n.url).put("lastUrl", n.lastUrl).put("lastTitle", n.lastTitle))
        Prefs.put(c, "lib", a.toString())
    }

    fun addNovel(c: Context, n: Novel) {
        val l = novels(c)
        l.removeAll { it.url == n.url }
        l.add(0, n)
        saveNovels(c, l)
    }

    fun removeNovel(c: Context, i: Int) {
        val l = novels(c)
        if (i in l.indices) { l.removeAt(i); saveNovels(c, l) }
    }

    // remember the last chapter read for the saved novel on the same site
    fun touchNovel(c: Context, ch: Chapter) {
        val host = AdBlock.root(Uri.parse(ch.url).host ?: return)
        val l = novels(c)
        val n = l.firstOrNull { AdBlock.root(Uri.parse(it.url).host ?: "") == host } ?: return
        n.lastUrl = ch.url
        n.lastTitle = ch.title
        saveNovels(c, l)
    }
}
