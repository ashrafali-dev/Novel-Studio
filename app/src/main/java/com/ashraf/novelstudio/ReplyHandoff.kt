package com.ashraf.novelstudio

/**
 * Whether the reply text seen by the completion poll can be used directly by finishJob instead of
 * scanning the whole chatbot page a second time.
 *
 * The poll already locates the answer (it measures its length and reads `__trk()` text); finishJob used
 * to ignore that and call `readText()` → `__scan()`, which walks every text node of the page again.
 * On long chats that is the expensive part of "the answer is ready" → "the answer is shown".
 *
 * It is only used when it cannot change the outcome:
 *  - the poll's text must be non-blank and the job must have seen a reply at all;
 *  - a reply that still carries paragraph markers is handed on as-is (finishJob strips them and keeps
 *    the marked text for DOM alignment, exactly like readText() would return);
 *  - any failure or mismatch falls back to readText(), so correctness never depends on this shortcut.
 */
internal object ReplyHandoff {

    /** Minimum length below which the poll's text is not trusted (chat pages show status lines too). */
    const val MIN_USABLE_CHARS = 2

    fun usablePollText(text: String?, gotReply: Boolean, completed: Boolean): String? {
        if (!completed || !gotReply) return null
        val t = text ?: return null
        if (t.trim().length < MIN_USABLE_CHARS) return null
        return t
    }

    /** How many times a chrome-only read is re-read before the job is reported as "no text". */
    const val MAX_RE_READS = 3

    /**
     * Whether an empty (chrome-only) read should be retried instead of failing the job.
     *
     * A read can come back as nothing but UI labels ("Write a message…", "Message collapsed Show more")
     * while the answer sits on the page: the DOM tracker misses the answer's own nodes for that poll.
     * Reporting "the chatbot did not send any text" there was wrong. [attempts] is how many re-reads this
     * job already spent, so a page that truly has no answer still fails after a bounded wait.
     */
    fun shouldReRead(meaningful: Boolean, attempts: Int): Boolean =
        !meaningful && attempts < MAX_RE_READS
}
