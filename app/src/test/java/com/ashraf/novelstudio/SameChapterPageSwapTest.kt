package com.ashraf.novelstudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for Bug 5: the realtime ahead-translation cache must survive in-chapter
 * page swaps (readers rewriting the trailing page/lang segment) and must still be dropped on
 * a real chapter navigation. Pins WebModeLogic.sameChapterPageSwap, which drives that decision.
 */
class SameChapterPageSwapTest {

    private fun swap(a: String, b: String) = WebModeLogic.sameChapterPageSwap(a, b)

    @Test
    fun trailingPageNumberChangeIsSameChapter() {
        assertTrue(
            swap(
                "https://mangadex.org/chapter/abc-123-def/1",
                "https://mangadex.org/chapter/abc-123-def/8"
            )
        )
    }

    @Test
    fun trailingLangAndPageChangeIsSameChapter() {
        assertTrue(
            swap(
                "https://mangadex.org/chapter/abc-123-def/en/1",
                "https://mangadex.org/chapter/abc-123-def/en/2"
            )
        )
    }

    @Test
    fun differentChapterIsNotSameChapter() {
        assertFalse(
            swap(
                "https://mangadex.org/chapter/abc-123-def/1",
                "https://mangadex.org/chapter/zzz-999-yyy/1"
            )
        )
    }

    @Test
    fun differentHostIsNotSameChapter() {
        assertFalse(
            swap(
                "https://mangadex.org/chapter/abc-123-def/1",
                "https://evil.example.org/chapter/abc-123-def/2"
            )
        )
    }

    @Test
    fun identicalUrlIsNotASwap() {
        assertFalse(
            swap(
                "https://mangadex.org/chapter/abc-123-def/1",
                "https://mangadex.org/chapter/abc-123-def/1"
            )
        )
    }

    @Test
    fun queryOnlyChangeIsNotASwap() {
        assertFalse(
            swap(
                "https://mangadex.org/chapter/abc-123-def/1",
                "https://mangadex.org/chapter/abc-123-def/1?x=1"
            )
        )
    }

    @Test
    fun wholeSiteIsNotASwap() {
        assertFalse(
            swap(
                "https://mangadex.org/chapter/abc-123-def/1",
                "https://mangadex.org/title/abc-123-def"
            )
        )
    }

    @Test
    fun nonHttpIsNeverASwap() {
        assertFalse(swap("about:blank", "about:blank2"))
    }
}
