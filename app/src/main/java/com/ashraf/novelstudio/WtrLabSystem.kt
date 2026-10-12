package com.ashraf.novelstudio

import android.webkit.WebView

internal class WtrLabSystem(private val context: android.content.Context) : SiteAdapter {
    private val navigator = WtrLabNavigator()
    private val extractor = WtrLabExtractor()
    private val writer = WtrLabWriter()

    override val id: String = "wtr-lab"
    override val capabilities = SiteCapabilities(extraction = true, nextChapter = true, previousChapter = true, translationInsert = true, htmlPreserve = true, autoNext = true, chapterNumberDetection = true)
    override fun matches(url: String?): Boolean = navigator.isWtrLab(url)
    override fun extract(webView: WebView, attempt: Int, callback: (Chapter?) -> Unit) = extractor.extract(webView, attempt, callback)
    override fun catalogUrl(chapterUrl: String): String? = null
    override fun catalogPickScript(direction: String, title: String, sourceUrl: String): String = ""
    override fun prefetchTarget(chapterUrl: String): String? = navigator.prefetchTarget(chapterUrl)
    override fun writeTranslation(webView: WebView, chapter: Chapter, translatedText: String, fontFile: String, fontSizePx: Int, pageKey: String, callback: (Boolean) -> Unit) = writer.write(webView, chapter, translatedText, fontFile, fontSizePx, pageKey, callback)
}
