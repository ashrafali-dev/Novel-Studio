package com.ashraf.novelstudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for Bug 2: a sparse OCR read (1-2 bubbles) of a RECYCLED canvas must be
 * recognised as a page change when it matches nothing of a solid prior baseline, while re-reads
 * of the same page must never be treated as a transition.
 */
class CanvasTransitionTest {

    private fun bubble(src: String, cx: Float, cy: Float, text: String) = WmBubble(
        src = src, cx = cx, cy = cy, w = 0.2f, h = 0.05f,
        angle = 0f, font = 0.02f, bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(),
        sourceText = text
    )

    private val canvas = "ns-canvas:0,0,800x1200"
    private val priorPage = listOf(
        bubble(canvas, 0.5f, 0.2f, "WHERE ARE WE GOING"),
        bubble(canvas, 0.5f, 0.4f, "I DON'T KNOW YET"),
        bubble(canvas, 0.5f, 0.6f, "JUST KEEP WALKING")
    )

    @Test
    fun sparseFreshPageMatchingNothingIsATransition() {
        // Page B reuses the same canvas; only 2 bubbles were read and none match page A.
        val fresh = listOf(
            bubble(canvas, 0.5f, 0.3f, "STOP RIGHT THERE"),
            bubble(canvas, 0.5f, 0.5f, "WHO GOES THERE")
        )
        assertTrue(WebModeLayoutStore.canvasTransitionDetected(priorPage, fresh))
    }

    @Test
    fun singleFreshBubbleMatchingNothingIsATransition() {
        val fresh = listOf(bubble(canvas, 0.5f, 0.35f, "COMpletely different story"))
        assertTrue(WebModeLayoutStore.canvasTransitionDetected(priorPage, fresh))
    }

    @Test
    fun rescanOfSamePageIsNotATransition() {
        // A partial re-read still matches one existing bubble with overlap + same/contained text.
        val fresh = listOf(
            bubble(canvas, 0.5f, 0.2f, "WHERE ARE WE GOING"),
            bubble(canvas, 0.5f, 0.42f, "I DON'T KNOW YET NOW")
        )
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(priorPage, fresh))
    }

    @Test
    fun fullFreshPageIsLeftToSameCanvasContent() {
        // >= 3 fresh bubbles must NOT be decided by the sparse-page rule (sameCanvasContent owns it).
        val fresh = listOf(
            bubble(canvas, 0.5f, 0.2f, "NEW PAGE ONE"),
            bubble(canvas, 0.5f, 0.4f, "NEW PAGE TWO"),
            bubble(canvas, 0.5f, 0.6f, "NEW PAGE THREE")
        )
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(priorPage, fresh))
    }

    @Test
    fun noPriorBaselineIsNeverATransition() {
        val fresh = listOf(bubble(canvas, 0.5f, 0.3f, "ANYTHING"))
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(emptyList(), fresh))
    }

    @Test
    fun blankFreshReadIsNeverATransition() {
        val fresh = listOf(bubble(canvas, 0.5f, 0.3f, "   "), bubble(canvas, 0.5f, 0.4f, ""))
        assertFalse(WebModeLayoutStore.canvasTransitionDetected(priorPage, fresh))
    }
}
