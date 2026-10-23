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
    private val maxBytes: Long = 32L * 1024 * 1024,
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
    private val bytesTotal = longArrayOf(0L)
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
        val existing: CompletableFuture<Panel>
        val startedHere: Boolean
        synchronized(lock) {
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
        joiners.incrementAndGet()
        try {
            if (startedHere) {
                val result = try {
                    Panel(load(), null)
                } catch (t: Throwable) {
                    Panel(null, t.message ?: t.javaClass.simpleName)
                }
                finish(key, result)
                return result.bytes
            }
            return await(existing, timeoutMs)?.bytes
        } finally {
            joiners.decrementAndGet()
        }
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
            bytesByKey.remove(key)?.let { bytesTotal[0] -= it.size }
            completed.remove(key)
        }
    }

    fun clear() {
        synchronized(lock) {
            bytesByKey.clear(); completed.clear(); bytesTotal[0] = 0L
            inFlight.clear()
        }
    }

    private fun putBytesLocked(key: String, bytes: ByteArray) {
        val previous = bytesByKey.put(key, bytes)
        if (previous != null) bytesTotal[0] -= previous.size
        bytesTotal[0] += bytes.size
        val it = bytesByKey.keys.iterator()
        while (bytesTotal[0] > maxBytes && it.hasNext()) {
            val eldest = it.next()
            if (eldest == key) continue
            it.remove()
            bytesTotal[0] -= bytesByKey.remove(eldest)?.size ?: 0
        }
    }

    /** True when the thread was interrupted at least once while downloading (diagnostics only). */
    private val interruptedSeen = AtomicBoolean(false)
    fun noteInterrupt() { interruptedSeen.set(true) }
}
