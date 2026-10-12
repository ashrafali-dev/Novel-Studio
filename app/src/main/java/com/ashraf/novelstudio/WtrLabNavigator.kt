package com.ashraf.novelstudio

import android.net.Uri

internal class WtrLabNavigator {
    fun isWtrLab(url: String?): Boolean {
        val host = runCatching { Uri.parse(url.orEmpty()).host?.lowercase().orEmpty() }.getOrDefault("")
        return host == "wtr-lab.com" || host.endsWith(".wtr-lab.com")
    }

    fun nextFromUrl(chapterUrl: String): String? = adjacent(chapterUrl, 1)
    fun prevFromUrl(chapterUrl: String): String? = adjacent(chapterUrl, -1)
    fun prefetchTarget(chapterUrl: String): String? = nextFromUrl(chapterUrl)

    private fun adjacent(url: String, delta: Long): String? = runCatching {
        val u = Uri.parse(url)
        val path = u.path.orEmpty()
        val m = Regex("^(.*?/chapter-)(\\d+)/?$", RegexOption.IGNORE_CASE).find(path)
            ?: return@runCatching null
        val n = m.groupValues[2].toLongOrNull() ?: return@runCatching null
        if (n + delta < 1) return@runCatching null
        u.buildUpon().path(m.groupValues[1] + (n + delta)).build().toString()
    }.getOrNull()
}
