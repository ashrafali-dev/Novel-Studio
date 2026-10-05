package com.ashraf.novelstudio

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Best-effort local site learning cache.
 *
 * This keeps the useful DOM/CSS/JS metadata for a site so the extractor has a
 * local record of how the page looked when it was learned. It never uploads
 * the snapshot anywhere.
 */
object SiteSnapshotStore {
    private const val DIR = "site_snapshots"
    private const val MAX_BYTES = 450_000

    private fun hostOf(url: String): String {
        val raw = url.substringAfter("://", "")
        return raw.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':').removePrefix("www.").lowercase()
    }

    private fun file(c: Context, url: String): File? {
        val h = hostOf(url)
        if (h.isBlank()) return null
        val d = File(c.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return File(d, "$h.json")
    }

    fun save(c: Context, url: String, snapshot: String) {
        val f = file(c, url) ?: return
        val safe = if (snapshot.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            snapshot
        } else {
            try {
                val o = JSONObject(snapshot)
                o.put("truncated", true)
                o.put("contentHtml", o.optString("contentHtml", "").take(180_000))
                o.put("cssText", o.optString("cssText", "").take(140_000))
                o.put("scriptsInline", o.optString("scriptsInline", "").take(60_000))
                o.toString()
            } catch (_: Exception) {
                snapshot.take(MAX_BYTES)
            }
        }
        try { f.writeText(safe) } catch (_: Exception) {}
    }

    fun load(c: Context, url: String): String {
        val f = file(c, url) ?: return ""
        return try { f.readText() } catch (_: Exception) { "" }
    }
}
