package com.ashraf.novelstudio

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One process-wide store for panel FILES shared by the visible (foreground) scan and the read-ahead.
 *
 * The bug this replaces: read-ahead downloaded a panel, OCRed it and kept only the text. When the same
 * panel became visible it was downloaded again (and OCRed again if the read-ahead had not finished or
 * had been cancelled). Each half kept its own memory of what it had done, and a cancelled read-ahead
 * threw its downloads away.
 *
 * Rules implemented here (all covered by [PanelStoreTest]):
 *  - {@link #loadOrJoin}: at most ONE fetch per panel is in flight process-wide; everybody else awaits
 *    that same future. A panel whose bytes are already cached is served without any network call.
 *  - Completion (success or failure) is delivered to every waiter, whoever started the fetch.
 *  - {@link #completeFromCancelledJob}: a job that is cancelled still stores the bytes it already has,
 *    instead of dropping them on the floor.
 *  - Bounded memory: a byte budget plus a short "recently finished" list so a burst of callers right
 *    after a panel completes does not start a second fetch because the entry was just evicted.
 *
 * Pure JVM: no Android types, so the behaviour is tested without a device.
 */
internal class PanelStore(
    private val maxBytes: Int = 32 * 1024 * 1024,
    private val maxCompletedEntries: Int = 128,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    /** Result of a fetch: the encoded image, or null with a short reason. */
    class Panel(val bytes: ByteArray?, val error: String?)

    private class InFlight(val future: CompletableFuture<Panel>, val startedAt: Long)

    private class Completed(val bytes: ByteArray?, val at: Long)

    private val lock = Any()
    private val bytesByKey = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = false
    }
    private var bytesTotal = 0
    private val completed = object : LinkedHashMap<String, Completed>(maxCompletedEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Completed>?): Boolean =
            size > maxCompletedEntries
    }
    private val inFlight = HashMap<String, InFlight>()

    /** Joins that share the newest fetches; older joiners may start their own (see [loadOrJoin]). */
    private val joiners = AtomicInteger(0)
    private val fetches = AtomicInteger(0)

    val fetchCount: Int get() = fetches.get()

    /**
     * Returns the bytes for [key], downloading at most once.
     *
     * [load] must do the network work and is invoked on the CALLING thread of the first caller; its
     * result completes the shared future. Cancelling the caller's thread must NOT cancel the download
     * (read-ahead is cancelled constantly while scrolling): pass a [load] that is independent of the
     * caller's cancellation state whenever possible.
     *
     * A caller may bound its wait with [timeoutMs]; on timeout it gets null like any other failure,
     * but the download itself keeps running for the other waiters.
     */
    fun loadOrJoin(key: String, timeoutMs: Long, load: () -> ByteArray?): ByteArray? {
        val deadline = nowMs() + timeoutMs
        joiners.incrementAndGet()
        try {
            while (true) {
                val existing: CompletableFuture<Panel>
                val startedHere: Boolean
                synchronized(lock) {
                    // A live cache hit must never start (or join) a fetch.
                    bytesByKey[key]?.let { return it }
                    val done = completed[key]
                    if (done != null && done.bytes != null) {
                        // Promote to the live cache and drop the finished marker.
                        completed.remove(key)
                        putBytesLocked(key, done.bytes)
                        return done.bytes
                    }
                    val running = inFlight[key]
                    if (running != null) {
                        existing = running.future
                        startedHere = false
                    } else {
                        val f = CompletableFuture<Panel>()
                        inFlight[key] = InFlight(f, nowMs())
                        existing = f
                        startedHere = true
                        fetches.incrementAndGet()
                    }
                }
                if (startedHere) {
                    // Cancelling the caller must not cancel the shared download (see the contract
                    // above): a load cut short by this thread's interrupt is retried with the
                    // interrupt cleared, so the bytes still reach everyone waiting on it.
                    var result: Panel
                    var retried = false
                    while (true) {
                        try {
                            result = Panel(load(), null)
                            break
                        } catch (t: Throwable) {
                            if (t !is InterruptedException && !Thread.currentThread().isInterrupted) {
                                result = Panel(null, t.message ?: t.javaClass.simpleName)
                                break
                            }
                            // Clears the flag AND tells us whether this attempt was interrupted.
                            if (!Thread.interrupted() || retried) {
                                abandon(key, existing)
                                throw t
                            }
                            retried = true
                        }
                    }
                    finish(key, result)
                    return result.bytes
                }
                val got = await(existing, (deadline - nowMs()).coerceAtLeast(1L))
                if (got === RETRY) {
                    if (nowMs() >= deadline) return null
                    continue          // the starter was cancelled: this caller becomes the starter
                }
                return got?.bytes      // real result, or a genuine failure/timeout
            }
        } finally {
            joiners.decrementAndGet()
        }
    }

    /** A future completed with this value means "the starter was cancelled; retry as a new fetch". */
    private val RETRY = Panel(null, null)

    /**
     * The starter could not finish (cancelled). Its waiters must not receive a failure, so the slot is
     * cleared and the future is completed with [RETRY], which wakes every waiter so one of them takes
     * the job over instead of waiting out its timeout.
     */
    private fun abandon(key: String, future: CompletableFuture<Panel>) {
        val owned = synchronized(lock) {
            val entry = inFlight[key]
            if (entry != null && entry.future === future) {
                inFlight.remove(key)
                true
            } else false
        }
        if (owned) future.complete(RETRY)
    }

    /** Publishes a result for [key] without starting a fetch (used by a cancelled job that has bytes). */
    fun completeFromCancelledJob(key: String, bytes: ByteArray?) {
        if (bytes == null) return
        finish(key, Panel(bytes, null))
    }

    /** Only success is remembered long-term; failures are handed to the current waiters and forgotten. */
    private fun finish(key: String, result: Panel) {
        val future: CompletableFuture<Panel>?
        synchronized(lock) {
            val entry = inFlight.remove(key)
            future = entry?.future
            if (result.bytes != null) {
                putBytesLocked(key, result.bytes)
                completed[key] = Completed(result.bytes, nowMs())
            }
        }
        future?.complete(result)
    }

    private fun await(future: CompletableFuture<Panel>, timeoutMs: Long): Panel? = try {
        future.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (_: ExecutionException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    /** Bytes already known for [key] (cached or just completed), without network access. */
    fun peek(key: String): ByteArray? = synchronized(lock) {
        bytesByKey[key] ?: completed[key]?.bytes
    }

    fun isInFlight(key: String): Boolean = synchronized(lock) { inFlight.containsKey(key) }

    fun evict(key: String) {
        synchronized(lock) {
            bytesByKey.remove(key)?.let { bytesTotal -= it.size }
            completed.remove(key)
        }
    }

    fun clear() {
        synchronized(lock) {
            bytesByKey.clear(); completed.clear(); bytesTotal = 0
            inFlight.clear()
        }
    }

    private fun putBytesLocked(key: String, bytes: ByteArray) {
        val previous = bytesByKey.put(key, bytes)
        if (previous != null) bytesTotal -= previous.size
        bytesTotal += bytes.size
        val it = bytesByKey.keys.iterator()
        while (bytesTotal > maxBytes && it.hasNext()) {
            val eldest = it.next()
            if (eldest == key) continue
            it.remove()
            bytesTotal -= bytesByKey.remove(eldest)?.size ?: 0
            // The finished marker holds the same bytes; leaving it behind would keep the evicted
            // panel alive and defeat the budget.
            completed.remove(eldest)
        }
    }

    /** True when the thread was interrupted at least once while downloading (diagnostics only). */
    private val interruptedSeen = AtomicBoolean(false)
    fun noteInterrupt() { interruptedSeen.set(true) }
}
