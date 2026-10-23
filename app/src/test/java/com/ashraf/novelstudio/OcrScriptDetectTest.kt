package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for script detection and the balloon safe-area geometry (no Android types). */
class OcrScriptDetectTest {
    private fun block(text: String) = OcrBlock(text = text, top = 0, left = 0)

    @Test fun hangulWinsOverStrayLatinInTheSameBubble() {
        // The exact failure from the log: a Korean bubble whose first read was "SO이L0."
        assertEquals(OcrScript.KOREAN, OcrScriptDetect.pick(listOf(block("SO이L0."), block("자!오늘 저녁은 해물찜을 맛있게 해보자!!"))))
    }

    @Test fun kanaMeansJapaneseEvenWithHan() {
        assertEquals(OcrScript.JAPANESE, OcrScriptDetect.pick(listOf(block("ここで買い物するの？"))))
    }

    @Test fun hanOnlyIsChinese() {
        assertEquals(OcrScript.CHINESE, OcrScriptDetect.pick(listOf(block("今天晚上的海鲜真好吃"))))
    }

    @Test fun oneLatinWordIsNotALanguage() {
        assertNull(OcrScriptDetect.pick(listOf(block("SO"))))
    }

    @Test fun englishPanelIsLatin() {
        assertEquals(OcrScript.LATIN, OcrScriptDetect.pick(listOf(block("I have to leave now"))))
    }

    @Test fun garbageReadDoesNotContradictAValidKoreanPanel() {
        assertFalse(OcrScriptDetect.contradicts(listOf(block("자!오늘 저녁")), OcrScript.KOREAN))
    }
}

class BubbleGeometryTest {
    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        floatArrayOf(l, t, r, t, r, b, l, b)

    @Test fun fullRectangleKeepsTheWholeBox() {
        val f = BubbleGeometry.frame(rect(0.1f, 0.1f, 0.5f, 0.3f))!!
        val safe = BubbleGeometry.safeTextRect(f, 0.1f, 0.1f, 0.5f, 0.3f)!!
        assertTrue(safe[2] - safe[0] > 0.9f * 0.4f)
        assertTrue(safe[3] - safe[1] > 0.9f * 0.2f)
    }

    @Test fun ovalOutlineShrinksToItsWidestInside() {
        // A 40x20 oval: an axis-aligned rectangle inside it is smaller than the bounding box.
        val cx = 0.3f; val cy = 0.2f; val rx = 0.1f; val ry = 0.05f
        val pts = ArrayList<Pair<Float, Float>>()
        for (i in 0 until 48) {
            val a = i * 2 * Math.PI / 48
            pts.add((cx + rx * Math.cos(a)).toFloat() to (cy + ry * Math.sin(a)).toFloat())
        }
        val poly = FloatArray(96) { i -> if (i % 2 == 0) pts[i / 2].first else pts[i / 2].second }
        val f = BubbleGeometry.frame(poly)!!
        val safe = BubbleGeometry.safeTextRect(f, cx - rx, cy - ry, cx + rx, cy + ry)!!
        val wr = (safe[2] - safe[0]) / (2 * rx)
        val hr = (safe[3] - safe[1]) / (2 * ry)
        assertTrue("width ratio $wr", wr in 0.5f..0.8f)
        assertTrue("height ratio $hr", hr in 0.5f..0.8f)
    }

    @Test fun tinySafeAreaIsRejected() {
        // Long thin triangle: the inscribed rectangle is a sliver, the caller must keep its rectangle.
        val poly = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.1f, 0.95f)
        val f = BubbleGeometry.frame(poly)!!
        assertNull(BubbleGeometry.safeTextRect(f, 0.1f, 0.1f, 0.9f, 0.95f))
    }

    @Test fun largestRectOnASolidGridIsTheGrid() {
        val filled = BooleanArray(20 * 10) { true }
        val r = BubbleGeometry.largestInsideRect(filled, 20, 10)
        assertNotNull(r)
        assertEquals(0, r!![0]); assertEquals(0, r[1]); assertEquals(20, r[2]); assertEquals(10, r[3])
    }
}
