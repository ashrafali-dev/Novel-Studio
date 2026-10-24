package com.ashraf.novelstudio

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Google Lens failure-reporting rules. These cover the confusing diagnostics
 * users reported ("http=200 paragraphs=0 lines=0", "http=0 cancelled") without touching the network.
 */
class LensAnalysisTest {

    // ---- upload geometry -------------------------------------------------------------------------

    @Test fun ordinaryScreenshotIsNeverResized() {
        assertEquals(1080, LensAnalysis.preparedSize(1080, 2400).w)
        assertEquals(2400, LensAnalysis.preparedSize(1080, 2400).h)
        // 2400x2400 is over MAX_AREA (5.76 MP), so it is resized; see oversizedAreaIsResized...
        assertTrue(LensAnalysis.preparedSize(2400, 2400).w.toLong() * LensAnalysis.preparedSize(2400, 2400).h <= LensAnalysis.MAX_AREA)
        assertEquals(1600, LensAnalysis.preparedSize(1200, 1600).h)
    }

    @Test fun oversizedAreaIsResizedEvenWhenSidesAreBelowTheMaxDimension() {
        // 2400x2400 = 5.76 MP: the old rule kept it native (both sides <= 2400) and could exceed the
        // 3.5 MB JPEG body limit -> "Google Lens: picture too large to upload".
        val p = LensAnalysis.preparedSize(2400, 2400)
        assertTrue("area must fit the upload limit", p.w.toLong() * p.h.toLong() <= LensAnalysis.MAX_AREA)
    }

    @Test fun longStripIsCappedOnItsLongestSide() {
        val p = LensAnalysis.preparedSize(1080, 20000)
        assertEquals(LensAnalysis.MAX_DIMENSION, p.h)
        assertTrue(p.w.toLong() * p.h.toLong() <= LensAnalysis.MAX_AREA)
        assertTrue(p.w > 0)
    }

    @Test fun squarePanelsKeepTheirAspectRatio() {
        val p = LensAnalysis.preparedSize(4000, 4000)
        assertEquals(p.w, p.h)
        assertTrue(p.w.toLong() * p.h.toLong() <= LensAnalysis.MAX_AREA)
    }

    // ---- 200 replies -----------------------------------------------------------------------------

    @Test fun replyWithoutObjectsResponseIsSilentNotAnEmptyImage() {
        val v = LensAnalysis.classify(parsed = true, valid = false, textBlocks = 0, paragraphs = 0, lines = 0)
        assertEquals(LensAnalysis.Outcome.SILENT, v.outcome)
        assertFalse("a silent reply must never be cached as 'this image has no text'",
            LensAnalysis.isCacheable(v.outcome))
    }

    @Test fun validReplyWithoutTextIsALegitimateEmptyImage() {
        val v = LensAnalysis.classify(parsed = true, valid = true, textBlocks = 0, paragraphs = 0, lines = 0)
        assertEquals(LensAnalysis.Outcome.EMPTY_IMAGE, v.outcome)
        assertTrue(LensAnalysis.isCacheable(v.outcome))
    }

    @Test fun textReplyIsText() {
        val v = LensAnalysis.classify(parsed = true, valid = true, textBlocks = 3, paragraphs = 2, lines = 4)
        assertEquals(LensAnalysis.Outcome.TEXT, v.outcome)
        assertTrue(LensAnalysis.isCacheable(v.outcome))
    }

    @Test fun unparseableBodyIsNotALensReply() {
        val v = LensAnalysis.classify(parsed = false, valid = false, textBlocks = 0, paragraphs = 0, lines = 0)
        assertEquals(LensAnalysis.Outcome.NOT_A_LENS_REPLY, v.outcome)
        assertFalse(LensAnalysis.isCacheable(v.outcome))
    }

    // ---- 200-without-text cause ------------------------------------------------------------------

    private class Answer(status: Int, meta: String) : LensTransport.Answer(status, meta)

    @Test fun successfulAnswerExplainsAReplyWithoutTextLayer() {
        val answer = Answer(200, "contentType=text/plain")
        val wrapped = RuntimeException("wrapped", answer)
        assertSame(answer, LensTransport.answerCause(wrapped))
    }

    @Test fun clientAndServerErrorsAreNotReportedAsASuccessfulAnswer() {
        assertNull(LensTransport.answerCause(Answer(403, "HTTP 403")))
        assertNull(LensTransport.answerCause(Answer(503, "HTTP 503")))
    }

    @Test fun pureTransportFailureHasNoAnswer() {
        assertNull(LensTransport.answerCause(UnknownHostException("No address associated with hostname")))
    }

    @Test fun cancellationIsRecognisedSeparatelyFromNetworkFaults() {
        assertTrue(LensTransport.isCancellation(InterruptedIOException("cancelled")))
        // SocketTimeoutException extends InterruptedIOException, so a read timeout also lands here; the
        // engine decides before calling this (the timeout branch keeps its own message).
        assertTrue(LensTransport.isCancellation(SocketTimeoutException("timeout")))
        assertFalse(LensTransport.isCancellation(UnknownHostException("dns")))
    }

    @Test fun describedFailureNamesTheRootCause() {
        val text = LensTransport.describe(UnknownHostException("No address associated with hostname"))
        assertTrue(text, text.contains("UnknownHostException"))
    }
}
