package com.owlmic.core.hub

/**
 * When a failed module may start again: after 100 ms, 500 ms, then 2 s, and at most 5 times a minute.
 * Past that it stays down and the hub reports it as failed.
 */
class RestartPolicy(private val now: () -> Long = System::currentTimeMillis) {
    private val restarts = ArrayDeque<Long>()

    /** The delay before the next restart, or null when the module has used up its restarts. */
    fun nextDelayMs(): Long? {
        val t = now()
        while (restarts.isNotEmpty() && t - restarts.first() >= WINDOW_MS) restarts.removeFirst()
        if (restarts.size >= MAX_PER_WINDOW) return null
        val delay = BACKOFF_MS[minOf(restarts.size, BACKOFF_MS.lastIndex)]
        restarts.addLast(t)
        return delay
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(100, 500, 2_000)
        const val MAX_PER_WINDOW = 5
        const val WINDOW_MS = 60_000L
    }
}
