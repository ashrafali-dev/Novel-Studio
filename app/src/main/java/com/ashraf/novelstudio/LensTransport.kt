package com.ashraf.novelstudio

import java.io.InterruptedIOException

/**
 * Describes what one Lens request really received. Kept separate from the Android engine so the
 * status/answer bookkeeping (the source of the confusing "http=200 paragraphs=0" and "http=0" lines
 * in the OCR log) is unit-testable on the JVM.
 */
internal object LensTransport {

    /**
     * The server's answer for one POST: the HTTP status code and what it returned. It is a Throwable so
     * the status travels inside an IOException through the retry/hedge machinery (see HttpStatus).
     */
    class Answer(val status: Int, val meta: String) : java.io.IOException(meta)

    /**
     * Network layers wrap the transport failure (`SocketTimeoutException`, interrupted reads) inside a
     * request-level error. Only a successful answer (2xx) is recovered from that chain: it is the one
     * case that explains a reply without a text layer — the server said 200 but did not include the
     * objects it normally does. A 4xx/5xx or a pure transport error is reported as itself.
     */
    fun answerCause(error: Throwable): Answer? =
        generateSequence(error) { it.cause }
            .take(8)
            .filterIsInstance<Answer>()
            .firstOrNull { it.status in 200..299 }

    /** Short, log-safe description of a failure (class + trimmed root message). */
    fun describe(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.take(8).last()
        val head = error.javaClass.simpleName
        val tail = (root.message ?: error.message ?: "").replace(Regex("\\s+"), " ").trim().take(110)
        val rootName = if (root !== error) root.javaClass.simpleName + (if (tail.isNotEmpty()) ": " else "") else ""
        return head + (if (tail.isNotEmpty() || rootName.isNotEmpty()) " ($rootName$tail)" else "")
    }

    /** True when the thread was interrupted (a cancelled scan), as opposed to a network fault. */
    fun isCancellation(error: Throwable): Boolean = error is InterruptedIOException || error is InterruptedException
}
