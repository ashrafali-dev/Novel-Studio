package com.ashraf.novelstudio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug B regression: the same panel must not be downloaded (or OCRed) twice just because read-ahead and
 * the visible scan both look at it, and a cancelled read-ahead must keep the bytes it already fetched.
 */
class PanelStoreTest {

    private fun bytes(n: Int) = ByteArray(16) { (n + it).toByte() }

    @Test fun aDownloadedPanelIsNeverDownloadedAgain() {
        val store = PanelStore()
        val fetch = AtomicInteger()
        val load: () -> ByteArray? = { fetch.incrementAndGet(); bytes(1) }

        val first = store.loadOrJoin("https://cdn/x.jpg?v=1", 1_000L, load)
        val second = store.loadOrJoin("https://cdn/x.jpg?v=1", 1_000L, load)

        assertArrayEquals(bytes(1), first)
        assertArrayEquals(bytes(1), second)
        assertEquals("one network fetch for two lookups", 1, fetch.get())
    }

    @Test fun aConcurrentSecondCallerJoinsTheRunningFetch() {
        val store = PanelStore()
        val fetch = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val load: () -> ByteArray? = {
            fetch.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); bytes(2)
        }
        // Read-ahead starts the download.
        val readAhead = Thread { store.loadOrJoin("k", 5_000L, load) }.apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        // The panel becomes visible while the download is still running.
        val visibleGot = arrayOfNulls<ByteArray>(1)
        val visible = Thread {
            visibleGot[0] = store.loadOrJoin("k", 5_000L, load)
        }.apply { start() }
        release.countDown()
        readAhead.join(5_000); visible.join(5_000)

        assertArrayEquals("the visible scan reuses the read-ahead bytes", bytes(2), visibleGot[0])
        assertEquals("the visible scan must not start a second download", 1, fetch.get())
    }

    @Test fun cancellationOfTheStarterStillGivesTheBytesToTheOtherCaller() {
        val store = PanelStore()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starter = Thread {
            store.loadOrJoin("panel", 5_000L, { started.countDown(); release.await(5, TimeUnit.SECONDS); bytes(3) })
        }.apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val waiterResult = arrayOfNulls<ByteArray>(1)
        val waiter = Thread { waiterResult[0] = store.loadOrJoin("panel", 5_000L, { error("must not fetch") }) }
            .apply { start() }
        // The read-ahead thread is cancelled (scroll) while the download finishes anyway.
        starter.interrupt()
        release.countDown()
        starter.join(5_000); waiter.join(5_000)

        assertArrayEquals(bytes(3), waiterResult[0])
    }

    @Test fun aCancelledJobStillPublishesTheBytesItAlreadyDownloaded() {
        val store = PanelStore()
        val bytes = bytes(4)
        store.completeFromCancelledJob("p", bytes)

        val fetched = AtomicInteger()
        val again = store.loadOrJoin("p", 1_000L, { fetched.incrementAndGet(); null })
        assertArrayEquals(bytes, again)
        assertEquals(0, fetched.get())
    }

    @Test fun laterCallersGetBytesRightAfterCompletionWithoutAFetch() {
        val store = PanelStore()
        val first = store.loadOrJoin("p", 1_000L, { bytes(5) })
        assertArrayEquals(bytes(5), first)
        val hits = AtomicInteger()
        repeat(50) { store.loadOrJoin("p", 1_000L, { hits.incrementAndGet(); null }) }
        assertEquals(0, hits.get())
    }

    @Test fun aFailedFetchIsHandedToCurrentWaitersButNotRemembered() {
        val store = PanelStore()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starter = Thread { assertNull(store.loadOrJoin("f", 5_000L, { started.countDown(); release.await(5, TimeUnit.SECONDS); null })) }
            .apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val waiter = Thread { assertNull(store.loadOrJoin("f", 5_000L, { null })) }.apply { start() }
        release.countDown()
        starter.join(5_000); waiter.join(5_000)

        // A later attempt is allowed again (transient failures are retried by the caller's policy).
        val retried = AtomicInteger()
        store.loadOrJoin("f", 1_000L, { retried.incrementAndGet(); bytes(6) })
        assertEquals(1, retried.get())
    }

    @Test fun peekNeverDownloads() {
        val store = PanelStore()
        assertNull(store.peek("nope"))
        store.loadOrJoin("yes", 1_000L, { bytes(7) })
        assertArrayEquals(bytes(7), store.peek("yes"))
    }

    @Test fun distinctUrlsWithDifferentQueriesAreDifferentPanels() {
        val store = PanelStore()
        store.loadOrJoin("https://cdn/p.jpg?sign=a", 1_000L, { bytes(8) })
        assertFalse("signed CDN URLs must not be conflated", store.peek("https://cdn/p.jpg?sign=b") != null)
        assertNull(store.peek("https://cdn/p.jpg?sign=b"))
    }

    @Test fun byteBudgetIsEnforced() {
        val store = PanelStore(maxBytes = 100L)
        store.loadOrJoin("a", 1_000L, { ByteArray(60) { 1 } })
        store.loadOrJoin("b", 1_000L, { ByteArray(60) { 2 } })
        val cached = listOf("a", "b").count { store.peek(it) != null }
        assertTrue("budget must keep memory bounded", cached <= 1)
        assertTrue("the newest panel stays", store.peek("b") != null)
    }

    @Test fun timeoutDoesNotCancelTheSharedDownload() {
        val store = PanelStore()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starter = Thread { store.loadOrJoin("slow", 5_000L, { started.countDown(); release.await(5, TimeUnit.SECONDS); bytes(9) }) }
            .apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // A caller with a short budget gives up but must not abort the shared fetch.
        assertNull(store.loadOrJoin("slow", 50L, { error("must not fetch") }))
        assertTrue(store.isInFlight("slow"))
        release.countDown()
        starter.join(5_000)
        assertArrayEquals(bytes(9), store.peek("slow"))
    }
}
