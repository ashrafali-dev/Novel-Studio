package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for mapping a chatbot reply's [NNN] markers onto OCR bubbles.
 *
 * From the reading-panel report: Claude answered "[001]…[007]" plus "[008] B" / "[009] Q",
 * no bubble was painted, and the app claimed the chatbot had sent no text.
 */
class WebModeAlignTest {
    private val src = "ns-canvas:0,0,800x1200"

    private fun b(text: String, cx: Float) =
        WmBubble(src = src, cx = cx, cy = 0.3f, w = 0.2f, h = 0.08f, angle = 0f, font = 0.02f,
            bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), sourceText = text)

    private fun layout(n: Int, titleOffset: Int = 0) =
        WebModeLayout(titleOffset, (1..n).map { b("源テキスト$it", 0.1f * it) })

    @Test fun oneCharacterBodyIsNotATranslation() {
        // "[008] B" is a truncated/garbled reply line, not the translation of bubble 8.
        val values = WebModeLayout.align("[001] おはよう [008] B", layout(8))
        assertNotNull("the good marker must still place", values)
        assertEquals("おはよう", values!![0])
        assertNull("a single letter must never be painted", values[7])
    }

    @Test fun theReportedReplyNeverPaintsLetterBodies() {
        val reply = "[001] তোমার নাম কী? [002] আমি ভালো আছি [003] এখানে এসো [004] অপেক্ষা করো " +
            "[005] দাঁড়াও [006] শুনো [007] Hm? [008] B [009] Q"
        val values = WebModeLayout.align(reply, layout(9))!!
        assertEquals("তোমার নাম কী?", values[0])
        assertEquals("Hm?", values[6])
        assertNull("bubble 8 must stay blank", values[7])
        assertNull("bubble 9 must stay blank", values[8])
    }

    @Test fun partialReplyStillPlacesTheBubblesItCovers() {
        // 4 of 7 filled used to fail the 70% gate and discard the whole answer.
        val reply = "[001] এক [002] দুই [003] তিন [004] চার"
        val values = WebModeLayout.align(reply, layout(7))
        assertNotNull("a partial answer is better than nothing", values)
        assertEquals(listOf("এক", "দুই", "তিন", "চার"), values!!.take(4))
        assertNull(values[4])
    }

    @Test fun titleOffsetShiftsTheMarkersAndKeepsThemDistinct() {
        val values = WebModeLayout.align("[001] শিরোনাম [002] এক [003] দুই", layout(2, titleOffset = 1))!!
        assertEquals("এক", values[0])
        assertEquals("দুই", values[1])
    }

    @Test fun reversedOrRepeatedMarkersAreRejected() {
        assertNull(WebModeLayout.align("[002] দুই [001] এক", layout(2)))
        assertNull(WebModeLayout.align("[001] এক [001] আবার", layout(2)))
    }

    @Test fun markerBeyondTheLayoutIsRejected() {
        // A reply for a different (larger) page must never be squeezed onto this one.
        assertNull(WebModeLayout.align("[001] এক [005] পাঁচ", layout(2)))
    }

    @Test fun markerlessReplyIsRejected() {
        assertNull(WebModeLayout.align("কোনো মার্কার নেই", layout(3)))
    }

    @Test fun emptyOrTildeBodiesLeaveTheBubbleBlank() {
        val values = WebModeLayout.align("[001] এক [002] ~ [003] ～～", layout(3))!!
        assertEquals("এক", values[0])
        assertNull(values[1])
        assertNull(values[2])
    }

    @Test fun droppedTitleMarkerIsRejectedInsteadOfShifted() {
        // titleOffset=1 but the reply starts at [001] and lacks the last id: ambiguous, do not shift.
        assertNull(WebModeLayout.align("[001] এক [002] দুই", layout(3, titleOffset = 1)))
    }

    @Test fun allMarkersPlacedMeansNothingIsLost() {
        val values = WebModeLayout.align("[001] এক [002] দুই [003] তিন", layout(3))!!
        assertTrue(values.all { !it.isNullOrBlank() })
    }

    @Test fun plausibilityMatchesTheJsRule() {
        // Js.__plausible must stay in step with this: a fragment the poll calls "filled" becomes a
        // painted bubble, so both sides have to agree on what counts as a translation.
        for (one in listOf("B", "Q", "~", "～～", "", "  ", "...", "!!", "1", "ね", "네")) {
            assertFalse("\"$one\" is not a translation", WebModeLayout.isPlausibleTranslation(one))
        }
        for (real in listOf("Hm?", "তুমি", "おはよう", "AB", "12", "No", "네네", "你好")) {
            assertTrue("\"$real\" is a translation", WebModeLayout.isPlausibleTranslation(real))
        }
    }
}
