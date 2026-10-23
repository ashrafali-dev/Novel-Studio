package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests of the recycled-canvas transition decision (no Android types). */
class WebModeLayoutTest {
    private val src = "ns-canvas:0,0,800x1200"
    private fun b(text: String, cx: Float, cy: Float) =
        WmBubble(src = src, cx = cx, cy = cy, w = 0.2f, h = 0.08f, angle = 0f, font = 0.02f,
            bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), sourceText = text)

    private val pageA = listOf(
        b("where are you going", 0.3f, 0.2f), b("I have to leave now", 0.7f, 0.4f), b("please wait for me", 0.4f, 0.7f))

    @Test fun sparseTotallyDifferentReadIsATransition() {
        val fresh = listOf(b("completely other words", 0.5f, 0.1f), b("nothing in common here", 0.2f, 0.9f))
        assertTrue(WebModeLayoutStore.canvasTransitionDetected(pageA, fresh))
    }

    @Test fun reReadOfTheSamePageIsNotATransition() {
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(pageA, pageA))
    }

    @Test fun sparseReReadOfSomeOfTheSameBubblesIsNotATransition() {
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(pageA, listOf(pageA[0], pageA[2])))
    }

    @Test fun noisyReReadStillExplainsItself() {
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(pageA, listOf(b("where are you goinq", 0.3f, 0.2f))))
    }

    @Test fun priorWithFewerThanThreeBubblesIsInconclusive() {
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(pageA.take(2), listOf(b("other words", 0.5f, 0.5f))))
    }

    @Test fun fullDifferentPageIsStillATransition() {
        val other = listOf(b("aaa bbb ccc", 0.1f, 0.1f), b("ddd eee fff", 0.5f, 0.5f), b("ggg hhh iii", 0.9f, 0.9f))
        assertTrue(WebModeLayoutStore.canvasTransitionDetected(pageA, other))
    }

    @Test fun curvedBubbleFillRejectsAsideWhoseCentreIsInsideRectangularBounds() {
        val w = 100; val h = 100
        val fill = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - 50) / 40f
            val dy = (y - 50) / 40f
            fill[y * w + x] = dx * dx + dy * dy <= 1f
        }
        // Both centres lie in [10,10..90,90], but the second box lies outside the oval's lower-right curve.
        val dialogue = intArrayOf(38, 35, 62, 50)
        val aside = intArrayOf(62, 62, 95, 95)
        val dialogueCoverage = WmBubbleStyle.rectBoundaryCoverage(dialogue, 0, 0, w, h, fill)
        val asideCoverage = WmBubbleStyle.rectBoundaryCoverage(aside, 0, 0, w, h, fill)
        assertTrue(dialogueCoverage >= 0.65f)
        assertTrue(asideCoverage < 0.65f)
    }

    @Test fun horizontalGradientAxisSurvivesLayoutSerialization() {
        val original = b("GRIT", 0.5f, 0.5f).copy(
            fg = 0xFFCC2200.toInt(), fg2 = 0xFFFFDD00.toInt(), gradientHorizontal = true
        )
        val decoded = WebModeLayout.fromJson(WebModeLayout(0, listOf(original)).toJson())
        assertTrue(decoded != null)
        assertTrue(decoded!!.bubbles.single().gradientHorizontal)
        assertEquals(original.fg, decoded.bubbles.single().fg)
        assertEquals(original.fg2, decoded.bubbles.single().fg2)
    }
}
