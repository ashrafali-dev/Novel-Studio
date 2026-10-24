package com.ashraf.novelstudio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug B: OCR of one panel must run once, even when read-ahead and the visible scan reach it together,
 * and OCR results keyed by a signed CDN URL must still be reused for the same picture.
 */
class PanelOcrCacheTest {

    private fun value(t: String) = PanelOcrCache.Value(t, emptyList())

    @Test fun visibleScanJoinsTheReadingTheReadAheadAlreadyStarted() {
        val cache = PanelOcrCache()
        val reads = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readAhead = Thread {
            cache.getOrJoin("k", 5_000L) { reads.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); value("text") }
        }.apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val visible = arrayOfNulls<PanelOcrCache.Value>(1)
        val visibleThread = Thread {
            visible[0] = cache.getOrJoin("k", 5_000L) { reads.incrementAndGet(); value("second read") }
        }.apply { start() }
        release.countDown()
        readAhead.join(5_000); visibleThread.join(5_000)

        assertEquals("the panel must be OCRed once", 1, reads.get())
        assertEquals("text", visible[0]?.text)
    }

    @Test fun readAheadJoinsTheReadingTheVisibleScanStarted() {
        val cache = PanelOcrCache()
        val reads = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val visible = Thread {
            cache.getOrJoin("k", 5_000L) { reads.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); value("v") }
        }.apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val ahead = arrayOfNulls<PanelOcrCache.Value>(1)
        val aheadThread = Thread { ahead[0] = cache.getOrJoin("k", 5_000L) { reads.incrementAndGet(); value("ahead") } }
            .apply { start() }
        release.countDown()
        visible.join(5_000); aheadThread.join(5_000)

        assertEquals(1, reads.get())
        assertEquals("v", ahead[0]?.text)
    }

    @Test fun aFinishedReadingIsReusedWithoutOcr() {
        val cache = PanelOcrCache()
        val reads = AtomicInteger()
        cache.getOrJoin("k", 1_000L) { reads.incrementAndGet(); value("once") }
        repeat(20) { assertEquals("once", cache.getOrJoin("k", 1_000L) { reads.incrementAndGet(); value("again") }?.text) }
        assertEquals(1, reads.get())
    }

    @Test fun anEmptyReadingIsNotCachedSoTheVisibleScanCanRetry() {
        val cache = PanelOcrCache()
        val reads = AtomicInteger()
        assertNull(cache.getOrJoin("k", 1_000L) { reads.incrementAndGet(); null })
        assertNull(cache.get("k"))
        // The visible scan gets its own attempt later.
        assertNotNull(cache.getOrJoin("k", 1_000L) { reads.incrementAndGet(); value("now it read") })
        assertEquals(2, reads.get())
    }

    @Test fun aFailedReadingDoesNotLeaveWaitersHanging() {
        val cache = PanelOcrCache()
        try {
            cache.getOrJoin("k", 1_000L) { throw IllegalStateException("engine unavailable") }
        } catch (_: IllegalStateException) {
        }
        assertFalse(cache.isInFlight("k"))
        assertNotNull(cache.getOrJoin("k", 1_000L) { value("after failure") })
    }

    @Test fun signedUrlsOfTheSamePictureShareOneReading() {
        val a = PanelOcrCache.key("https://cdn.site/panel.jpg?sign=aaa&exp=1", 800, 1200)
        val b = PanelOcrCache.key("https://cdn.site/panel.jpg?sign=bbb&exp=2", 800, 1200)
        assertEquals(a, b)
    }

    @Test fun differentPanelsOrGeometryAreNeverConflated() {
        val base = PanelOcrCache.key("https://cdn.site/panel.jpg?sign=aaa", 800, 1200)
        assertFalse(base == PanelOcrCache.key("https://cdn.site/other.jpg?sign=aaa", 800, 1200))
        assertFalse(base == PanelOcrCache.key("https://cdn.site/panel.jpg?sign=aaa", 800, 1201))
        assertFalse(base == PanelOcrCache.key("https://cdn2.site/panel.jpg?sign=aaa", 800, 1200))
    }

    @Test fun entryCountIsBounded() {
        val cache = PanelOcrCache(maxEntries = 4)
        repeat(50) { cache.put("k$it", value("t$it")) }
        assertNotNull(cache.get("k49"))
        assertNull(cache.get("k0"))
    }
}
