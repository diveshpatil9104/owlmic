package com.owlmic.core.hub

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The one dispatcher every hub runs on: hubs never race each other (section 12.3). */
object HubDispatcher {
    @OptIn(ExperimentalCoroutinesApi::class)
    val default: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
}

/**
 * A hub: one bounded mailbox, drained on [dispatcher]. A full mailbox drops its oldest message, so a burst of
 * stale events (an old report, an old level) never blocks the thread that posts. Exceptions in [handle] are
 * caught and handed to [onFailure], so one bad message never takes the hub down.
 */
abstract class Hub<M>(
    val name: String,
    capacity: Int = 64,
    dispatcher: CoroutineDispatcher = HubDispatcher.default,
) {
    protected val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mailbox = Channel<M>(capacity, BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            for (message in mailbox) {
                try {
                    handle(message)
                } catch (e: Exception) {
                    onFailure(message, e)
                }
            }
        }
    }

    /** Any thread. Never blocks. */
    fun post(message: M) {
        mailbox.trySend(message)
    }

    protected abstract fun handle(message: M)

    protected open fun onFailure(message: M, error: Exception) = Unit

    /** Runs [block] on this hub after [delayMs]. */
    protected fun later(delayMs: Long, block: () -> Unit) {
        scope.launch {
            delay(delayMs)
            block()
        }
    }

    open fun close() {
        mailbox.close()
        scope.cancel()
    }
}

/**
 * Keeps one module running: creates it with [factory], restarts it with [RestartPolicy] when it throws, and
 * reports its health. Call everything from the owning hub's dispatcher.
 */
class Supervised<C>(
    private val factory: () -> Module<C>,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val policy: RestartPolicy = RestartPolicy(),
) {
    private var module: Module<C>? = null
    private var gaveUp: String? = null
    private var running = false

    val name: String get() = module?.name ?: "module"

    fun start() {
        running = true
        gaveUp = null
        launch()
    }

    fun handle(command: C) {
        val m = module ?: return
        runGuarded { m.handle(command) }
    }

    fun health(): Health = gaveUp?.let { Health.Failed(it) } ?: runCatching { module?.health() }.getOrNull() ?: Health.Degraded("starting")

    fun stop() {
        running = false
        module?.let { runCatching { it.stop() } }
        module = null
    }

    private fun launch() {
        val m = factory()
        module = m
        runGuarded { m.start() }
    }

    private fun runGuarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            module?.let { runCatching { it.stop() } }
            module = null
            val delay = policy.nextDelayMs()
            if (delay == null) {
                gaveUp = e.message ?: e.javaClass.simpleName
                return
            }
            schedule(delay) { if (running && module == null) launch() }
        }
    }
}
