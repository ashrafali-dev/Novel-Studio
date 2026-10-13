package com.ashraf.novelstudio

import android.content.Context

/**
 * Faster completion checks used only by the AI Real-time Translation feature.
 *
 * While the chatbot is generating, check often enough to notice the stop state quickly.
 * Once streaming appears to stop, confirm the answer is stable across a few quick polls,
 * rather than waiting several seconds. Ordinary chapter translations keep their old timing.
 */
internal object RealtimeCompletionPolicy {
    private const val NORMAL_POLL_MS = 500L
    private const val REALTIME_FIRST_POLL_MS = 60L
    private const val REALTIME_STREAMING_POLL_MS = 180L
    private const val REALTIME_IDLE_POLL_MS = 80L

    fun enabled(context: Context): Boolean =
        DeepSeekRealtimeTranslation.isEnabled(context)

    fun firstPollDelayMs(context: Context): Long =
        if (enabled(context)) REALTIME_FIRST_POLL_MS else 300L

    fun nextPollDelayMs(context: Context, streaming: Boolean, replyAvailable: Boolean = true): Long {
        if (!enabled(context)) return NORMAL_POLL_MS
        return if (streaming || !replyAvailable) REALTIME_STREAMING_POLL_MS else REALTIME_IDLE_POLL_MS
    }

    fun stableThreshold(context: Context, normalThreshold: Int, realtimeThreshold: Int = 3): Int =
        if (enabled(context)) realtimeThreshold else normalThreshold

    fun noStopSignalThreshold(context: Context, normalThreshold: Int, realtimeThreshold: Int = 4): Int =
        if (enabled(context)) realtimeThreshold else normalThreshold
}
