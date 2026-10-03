package com.owlmic.core.settings

import com.owlmic.core.hub.Hub
import com.owlmic.core.proto.SettingChange

sealed interface SettingsMsg {
    /** The pairing in use: [pcId] null when no PC was ever connected. [pcValues] is WELCOME's settings, on connect only. */
    class Use(val pcId: String?, val connected: Boolean, val pcValues: Map<String, String>? = null) : SettingsMsg

    /** The user changed a setting on the phone. */
    class Change(val id: String, val value: String) : SettingsMsg

    /** The PC changed shared settings (SETTINGS). */
    class Remote(val changes: List<SettingChange>) : SettingsMsg
}

sealed interface SettingsEvent {
    /** All current values; [changed] says which moved, so only those are applied live. */
    class Values(val values: Map<String, String>, val changed: Set<String>) : SettingsEvent

    /** Shared changes for the PC. */
    class Push(val changes: List<SettingChange>) : SettingsEvent
}

/**
 * One settings model per pairing (section 18.1): shared settings sync both ways with versions, changes made while the
 * PC is away wait and go out on reconnect, and everything applies live.
 */
class SettingsHub(private val store: Store, private val emit: (SettingsEvent) -> Unit) : Hub<SettingsMsg>("settings") {
    private var pcId: String? = null
    private var connected = false
    private var values: Map<String, Versioned> = store.settings(null)
    private var pending: Set<String> = emptySet()

    init {
        post(SettingsMsg.Use(null, false))
    }

    override fun handle(message: SettingsMsg) {
        when (message) {
            is SettingsMsg.Use -> {
                pcId = message.pcId
                connected = message.connected
                values = SettingValues.defaults() + store.settings(pcId)
                pending = store.pending(pcId)
                val pc = message.pcValues
                if (pc != null && connected) {
                    values = SettingValues.applyWelcome(values, pc, pending)
                    if (pending.isNotEmpty()) emit(SettingsEvent.Push(pending.mapNotNull { id -> values[id]?.let { SettingChange(id, it.value, it.version) } }))
                    pending = emptySet()
                }
                store.saveSettings(pcId, values, pending)
                emit(SettingsEvent.Values(SettingValues.plain(values), values.keys))
            }
            is SettingsMsg.Change -> {
                val before = values[message.id]?.value
                values = SettingValues.change(values, message.id, message.value)
                val now = values[message.id] ?: return
                if (before == now.value) return
                if (SettingValues.isShared(message.id) && pcId != null) {
                    if (connected) emit(SettingsEvent.Push(listOf(SettingChange(message.id, now.value, now.version)))) else pending = pending + message.id
                }
                store.saveSettings(pcId, values, pending)
                emit(SettingsEvent.Values(SettingValues.plain(values), setOf(message.id)))
            }
            is SettingsMsg.Remote -> {
                val (next, changed) = SettingValues.applyRemote(values, message.changes)
                values = next
                pending = pending - changed
                store.saveSettings(pcId, values, pending)
                if (changed.isNotEmpty()) emit(SettingsEvent.Values(SettingValues.plain(values), changed))
            }
        }
    }
}
