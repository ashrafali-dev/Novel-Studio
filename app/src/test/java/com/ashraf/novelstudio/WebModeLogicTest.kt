package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebModeLogicTest {
    @Test
    fun realtimeFallbackPrefersMangaScriptsForGenericLatinGuess() {
        assertEquals(
            listOf(OcrScript.JAPANESE, OcrScript.KOREAN, OcrScript.CHINESE),
            WebModeLogic.realtimeFallbackScripts(OcrScript.LATIN)
        )
    }

    @Test
    fun realtimeFallbackIncludesEveryOtherScriptExactlyOnce() {
        for (script in OcrScript.values()) {
            val fallbacks = WebModeLogic.realtimeFallbackScripts(script)
            assertEquals(OcrScript.values().size - 1, fallbacks.size)
            assertFalse("Fallback must not retry the same script: $script", fallbacks.contains(script))
            assertEquals(OcrScript.values().size - 1, fallbacks.toSet().size)
        }
    }

    @Test
    fun naverNavigationTilesAreSiteChromeNotComicPanels() {
        // From the live log: these were OCRed, translated and injected into the reply.
        assert(WebModeLogic.isSiteChrome(
            "https://m.comic.naver.com/webtoon/detail?titleId=850928&no=25&week=thu", 367, 156, "m.comic.naver.com"))
        assert(WebModeLogic.isSiteChrome(
            "https://m.comic.naver.com/webtoon/detail?titleId=850928&no=25", 255, 89, "m.comic.naver.com"))
    }

    @Test
    fun theRealChapterStripIsKept() {
        assertFalse(WebModeLogic.isSiteChrome(
            "https://image-comic.pstatic.net/mobilewebimg/850928/25/bd2f9.jpg", 720, 1529, "m.comic.naver.com"))
        assertFalse(WebModeLogic.isSiteChrome(
            "https://image-comic.pstatic.net/mobilewebimg/850928/25/bd2f9.jpg", 420, 975, "m.comic.naver.com"))
    }

    @Test
    fun adBannersAndLogosFromAnyHostAreChrome() {
        assert(WebModeLogic.isSiteChrome("https://cdn.example.com/ads/banner-300x250.png", 300, 250, "example.com"))
        assert(WebModeLogic.isSiteChrome("https://example.com/logo.png", 120, 120, "example.com"))
    }

    @Test
    fun canvasPanelsAreNeverTreatedAsChrome() {
        assertFalse(WebModeLogic.isSiteChrome("data:image/png;base64,AAAA", 800, 1200, "m.comic.naver.com"))
    }
}
