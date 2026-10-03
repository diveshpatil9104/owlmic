package com.owlmic.core.link

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Closes a transport when a deadline passes. RFCOMM sockets have no read timeout, so every blocking read is
 * bounded this way: the read then fails and the reader ends.
 */
object Watchdog {
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "owlmic-watchdog").apply { isDaemon = true } }

    class Deadline internal constructor(private val target: Closeable) {
        private var pending: ScheduledFuture<*>? = null

        /** (Re)arms: the transport closes [ms] from now unless re-armed or cancelled first. */
        @Synchronized
        fun arm(ms: Long) {
            pending?.cancel(false)
            pending = timer.schedule({ runCatching { target.close() } }, ms, TimeUnit.MILLISECONDS)
        }

        @Synchronized
        fun cancel() {
            pending?.cancel(false)
            pending = null
        }
    }

    fun on(target: Closeable) = Deadline(target)
}
