package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "the chatbot did not send any text" state used to fire the moment one read came back as UI
 * chrome only, even though the answer was on screen (the DOM tracker had missed its nodes).
 *
 * The re-read is bounded, so a page that genuinely has no answer still fails promptly.
 */
class ReplyRereadTest {

    @Test fun chromeOnlyReadIsRetriedBeforeFailing() {
        // Three re-reads are allowed, the fourth empty read gives up.
        assertTrue(ReplyHandoff.shouldReRead(meaningful = false, attempts = 0))
        assertTrue(ReplyHandoff.shouldReRead(meaningful = false, attempts = 1))
        assertTrue(ReplyHandoff.shouldReRead(meaningful = false, attempts = 2))
        assertFalse(ReplyHandoff.shouldReRead(meaningful = false, attempts = 3))
    }

    @Test fun aMeaningfulReadIsNeverRetried() {
        assertFalse(ReplyHandoff.shouldReRead(meaningful = true, attempts = 0))
    }

    @Test fun theBoundIsSmallEnoughToStillSurfaceARealFailure() {
        assertTrue("a long wait would hide a real problem", ReplyHandoff.MAX_RE_READS <= 5)
    }

    @Test fun attemptsBeyondTheBoundStayTerminal() {
        for (a in 3..50) assertFalse("attempt $a", ReplyHandoff.shouldReRead(meaningful = false, attempts = a))
    }

    @Test fun theBoundIsExactlyThreeReads() {
        assertEquals(3, ReplyHandoff.MAX_RE_READS)
    }
}
