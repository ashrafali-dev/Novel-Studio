package com.ashraf.novelstudio

import java.net.URLDecoder
import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bug D: the completion poll already holds the reply; finishJob may use it directly only when that
 * cannot change the outcome, otherwise it must fall back to reading the page (null here).
 */
class ReplyHandoffTest {

    @Test fun completedPollWithTextIsHandedOver() {
        assertEquals("Bonjour", ReplyHandoff.usablePollText("Bonjour", gotReply = true, completed = true))
    }

    @Test fun aBareMarkerReplyIsHandedOverWithoutJudgingIt() {
        // Correctness is decided by finishJob's existing marker checks; the handoff must not reinterpret.
        assertEquals("[001]", ReplyHandoff.usablePollText("[001]", gotReply = true, completed = true))
    }

    @Test fun notCompletedNotGotOrBlankFallsBackToReadingThePage() {
        assertNull(ReplyHandoff.usablePollText("text", gotReply = true, completed = false))
        assertNull(ReplyHandoff.usablePollText("text", gotReply = false, completed = true))
        assertNull(ReplyHandoff.usablePollText("   ", gotReply = true, completed = true))
        assertNull(ReplyHandoff.usablePollText(null, gotReply = true, completed = true))
    }

    @Test fun theWireFieldSurvivesSeparatorsAndUnicode() {
        val reply = "[001] a | b\n[002] こんにちは & 100%"
        val encoded = URLEncoder.encode(reply, "UTF-8")
        assertFalse("the wire field must not contain a separator", encoded.contains('|'))
        val wire = "3|0|40|2|1|ok|1|1|0|" + encoded
        // Same extraction as pollReplyText: readLen answers with 10 fields; the reply is the last.
        val pollText = wire.substringAfter('|', "").split('|', limit = 9).getOrNull(8) ?: ""
        assertEquals(reply, URLDecoder.decode(pollText, "UTF-8"))
    }
}
