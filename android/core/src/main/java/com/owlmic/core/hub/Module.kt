package com.owlmic.core.hub

/** What a module reports to its hub once a second (section 11.2). */
sealed interface Health {
    data object Ok : Health

    data class Degraded(val reason: String) : Health

    data class Failed(val reason: String) : Health
}

/**
 * One job, driven by its hub. Every call arrives on the hub dispatcher, so a module needs no locks for its own state.
 * Blocking work belongs on the module's own threads, which report back through the hub's mailbox.
 */
interface Module<C> {
    val name: String

    fun start()

    fun handle(command: C)

    fun health(): Health = Health.Ok

    /** Releases everything. Safe to call more than once. */
    fun stop()
}
