package com.ashraf.novelstudio

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * OCR results for comic panels, shared by the visible scan and the read-ahead of one Web Mode
 * extractor, with in-flight de-duplication.
 *
 * Before: read-ahead stored a result only AFTER it finished; a visible scan of the same panel that
 * started while the read-ahead was still reading ran the whole OCR again. Reusing the in-flight work
 * is what turns "the visible panel is read twice" into "the visible panel waits for the reading that
 * is already happening".
 *
 * Keys identify the PICTURE, not the URL string (see [key]): signed CDN URLs change on every render of
 * the same image, so keying by URL alone both missed reuse and kept stale entries. The cache bounds its
 * entry count and remembers the bytes-backed last verification time so a real edit is re-read.
 */
internal class PanelOcrCache(
    private val maxEntries: Int = 64
) {
    class Value(val text: String, val bubbles: List<WmBubble>)

    private class Completed(val value: Value, val at: Long)

    private val lock = Any()
    private val completed = object : LinkedHashMap<String, Completed>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Completed>?): Boolean =
            size > maxEntries
    }
    private val inFlight = HashMap<String, CompletableFuture<Value>>()

    /**
     * [key] is [key]. Returns the cached result, joins a reading already in progress, or runs [read]
     * (the caller's thread performs the work for the first caller).
     */
    fun getOrJoin(key: String, timeoutMs: Long, read: () -> Value?): Value? {
        val future: CompletableFuture<Value>
        synchronized(lock) {
            completed[key]?.let { return it.value }
            val running = inFlight[key]
            if (running != null) {
                future = running
            } else {
                val f = CompletableFuture<Value>()
                inFlight[key] = f
                future = f
                // First caller computes below, outside the lock. A result of null means "read, but
                // nothing usable" and is passed to the joiners as such instead of as a failure.
                val result = try {
                    read()
                } catch (t: Throwable) {
                    // Never leave joiners hanging on a reading that died (e.g. the OCR engine refused).
                    synchronized(lock) { inFlight.remove(key) }
                    f.completeExceptionally(t)
                    throw t
                }
                synchronized(lock) {
                    inFlight.remove(key)
                    if (result != null) completed[key] = Completed(result, System.currentTimeMillis())
                }
                f.complete(result)
                return result
            }
        }
        return try {
            future.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    fun put(key: String, value: Value) {
        synchronized(lock) { completed[key] = Completed(value, System.currentTimeMillis()) }
    }

    fun get(key: String): Value? = synchronized(lock) { completed[key]?.value }

    fun isInFlight(key: String): Boolean = synchronized(lock) { inFlight.containsKey(key) }

    fun clear() {
        synchronized(lock) { completed.clear() }
    }

    companion object {
        /**
         * Identity of the picture: where it was served from without the cache-busting/signature query,
         * plus its size. Two renders of the same panel (different signed query) share one reading;
         * genuinely different images (different path, different geometry) never do.
         */
        fun key(src: String, w: Int, h: Int): String {
            val base = src.substringBefore('?').substringBefore('#').trim()
            return "$base|$w|$h"
        }
    }
}
