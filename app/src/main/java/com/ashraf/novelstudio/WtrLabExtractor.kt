package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

internal class WtrLabExtractor {
    fun extract(webView: WebView, attempt: Int = 0, callback: (Chapter?) -> Unit) {
        val url = webView.url.orEmpty()
        if (url.isBlank()) { callback(null); return }

        val chapterNo = Regex("/chapter-(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)
        val titleSel = chapterNo?.let { "#chapter-$it > span" }.orEmpty()

        webView.evaluateJavascript(Js.fastExtract(".chapter-body", titleSel, "", "")) { raw ->
            val payload = decode(raw)
            val parsed = runCatching { JSONObject(payload) }.getOrNull()
            val body = parsed?.optString("text", "")?.trim().orEmpty()
            val count = parsed?.optInt("count", 0) ?: 0
            val ok = parsed?.optBoolean("ok", false) == true && body.length >= 40 && count > 0
            if (!ok) {
                if (attempt < 8) webView.postDelayed({ extract(webView, attempt + 1, callback) }, 150L)
                else callback(null)
                return@evaluateJavascript
            }

            val title = parsed?.optString("title", "")?.trim().orEmpty()
            val navigator = WtrLabNavigator()
            val next = parsed?.optJSONObject("next")?.optString("href", "")?.takeIf { it.isNotBlank() }
                ?: navigator.nextFromUrl(url)
            val prev = parsed?.optJSONObject("prev")?.optString("href", "")?.takeIf { it.isNotBlank() }
                ?: navigator.prevFromUrl(url)
            val number = Regex("(?:chapter|chap|ch)\\.?\\s*[-#:.]?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
                .find(title)?.groupValues?.getOrNull(1) ?: chapterNo.orEmpty()

            val chapter = Chapter(
                title.ifBlank { "Chapter $number" },
                body,
                next,
                prev,
                url,
                parsed?.optString("novel", "")?.trim().orEmpty().ifBlank { "wtr-lab.com" },
                number,
                parsed?.optString("contentSel", ".chapter-body").orEmpty().ifBlank { ".chapter-body" },
                parsed?.optString("titleSel", titleSel).orEmpty(),
                parsed?.optJSONObject("next")?.optString("selector", "").orEmpty(),
                parsed?.optJSONObject("prev")?.optString("selector", "").orEmpty()
            )
            SiteProfiles.remember(webView.context, chapter)
            callback(chapter)
        }
    }

    private fun decode(raw: String?): String = try {
        if (raw == null || raw == "null") "" else JSONArray("[$raw]").getString(0)
    } catch (_: Exception) { "" }
}
