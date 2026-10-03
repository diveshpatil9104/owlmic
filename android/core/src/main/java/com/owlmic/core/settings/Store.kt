package com.owlmic.core.settings

import com.owlmic.core.fromBase64
import com.owlmic.core.link.PcKey
import com.owlmic.core.link.PcMemory
import com.owlmic.core.proto.Crypto
import com.owlmic.core.toBase64
import com.owlmic.core.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything Owlmic keeps (section 18.3, section 21): the phone's identity, the PCs it paired with, their settings,
 * and manual addresses. One JSON file, written atomically; secrets wrapped by [wrapper]. Thread-safe.
 */
class Store(private val file: File, private val wrapper: KeyWrapper, private val now: () -> Long = System::currentTimeMillis) {
    class Pc(
        val id: String,
        var name: String,
        val staticPub: ByteArray,
        val pairingKey: ByteArray,
        var approved: Boolean,
        var lastUsedAt: Long,
        var refusedAt: Long,
        var btAddress: String?,
        var settings: Map<String, Versioned>,
        var pending: Set<String>,
    )

    val phoneId: ByteArray
    val identity: Crypto.KeyPair
    private val pcs = LinkedHashMap<String, Pc>()
    private val addresses = mutableListOf<String>()
    private var offlineSettings: Map<String, Versioned> = SettingValues.defaults()

    init {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        val restored = json?.let { j ->
            val id = j.optString("phoneId").takeIf { it.length == 32 }
            val pub = j.optString("identityPublic").fromBase64()
            val priv = j.optString("identityPrivate").fromBase64()?.let(wrapper::unwrap)
            if (id != null && pub != null && priv != null) id to Crypto.restore(priv, pub) else null
        }
        if (restored != null) {
            phoneId = restored.first.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            identity = restored.second
            json.optJSONArray("pcs")?.let { readPcs(it) }
            json.optJSONArray("addresses")?.let { a -> repeat(a.length()) { addresses += a.getString(it) } }
            json.optJSONObject("settings")?.let { offlineSettings = SettingValues.defaults() + readSettings(it) }
        } else {
            // A first start, or secrets that can't be opened any more: a fresh identity, so PCs approve this phone again.
            phoneId = Crypto.randomBytes(16)
            identity = Crypto.generate()
            save()
        }
    }

    @Synchronized
    fun known(): Map<String, PcMemory> = pcs.mapValues { (_, p) -> PcMemory(p.id, p.name, p.approved, p.lastUsedAt, p.refusedAt) }

    @Synchronized
    fun key(pcId: String): PcKey? = pcs[pcId]?.takeIf { it.approved }?.let { PcKey(it.staticPub, it.pairingKey) }

    @Synchronized
    fun pc(pcId: String): Pc? = pcs[pcId]

    @Synchronized
    fun remember(pcId: String, name: String, staticPub: ByteArray, pairingKey: ByteArray, btAddress: String?) {
        val existing = pcs[pcId]
        pcs[pcId] = Pc(
            pcId, name, staticPub, pairingKey, approved = true, lastUsedAt = now(), refusedAt = 0,
            btAddress = btAddress?.uppercase() ?: existing?.btAddress,
            settings = existing?.settings ?: offlineSettings,
            pending = existing?.pending ?: emptySet(),
        )
        save()
    }

    @Synchronized
    fun refused(pcId: String, name: String) {
        val p = pcs[pcId]
        if (p != null) {
            p.refusedAt = now()
            p.name = name
        } else {
            pcs[pcId] = Pc(pcId, name, ByteArray(0), ByteArray(0), false, 0, now(), null, SettingValues.defaults(), emptySet())
        }
        save()
    }

    @Synchronized
    fun clearRefusal(pcId: String) {
        pcs[pcId]?.refusedAt = 0
        save()
    }

    @Synchronized
    fun forget(pcId: String) {
        pcs.remove(pcId)
        save()
    }

    @Synchronized
    fun btAddresses(): Set<String> = pcs.values.mapNotNull { it.btAddress }.toSet()

    /** The settings of [pcId]'s pairing, or the phone's own when no PC is connected. */
    @Synchronized
    fun settings(pcId: String?): Map<String, Versioned> = pcId?.let { pcs[it]?.settings } ?: offlineSettings

    @Synchronized
    fun pending(pcId: String?): Set<String> = pcId?.let { pcs[it]?.pending } ?: emptySet()

    @Synchronized
    fun saveSettings(pcId: String?, values: Map<String, Versioned>, pending: Set<String>) {
        val p = pcId?.let { pcs[it] }
        if (p != null) {
            p.settings = values
            p.pending = pending
        } else {
            offlineSettings = values
        }
        save()
    }

    @Synchronized
    fun addresses(): List<String> = addresses.toList()

    @Synchronized
    fun addAddress(address: String) {
        val a = address.trim()
        if (a.isNotEmpty() && a !in addresses) addresses += a
        save()
    }

    @Synchronized
    fun removeAddress(address: String) {
        addresses.remove(address)
        save()
    }

    private fun readPcs(a: JSONArray) {
        repeat(a.length()) { i ->
            val o = a.getJSONObject(i)
            val pub = o.optString("staticPub").fromBase64() ?: ByteArray(0)
            val key = o.optString("pairingKey").fromBase64()?.let(wrapper::unwrap) ?: ByteArray(0)
            val approved = o.optBoolean("approved") && key.isNotEmpty()
            pcs[o.getString("id")] = Pc(
                o.getString("id"), o.optString("name"), pub, key, approved, o.optLong("lastUsedAt"), o.optLong("refusedAt"),
                o.optString("btAddress").ifEmpty { null },
                SettingValues.defaults() + (o.optJSONObject("settings")?.let(::readSettings) ?: emptyMap()),
                o.optJSONArray("pending")?.let { p -> (0 until p.length()).map { p.getString(it) }.toSet() } ?: emptySet(),
            )
        }
    }

    private fun readSettings(o: JSONObject): Map<String, Versioned> = o.keys().asSequence()
        .mapNotNull { id ->
            val v = o.optJSONObject(id) ?: return@mapNotNull null
            val value = v.optString("value")
            if (SettingValues.isValid(id, value)) id to Versioned(value, v.optLong("version")) else null
        }.toMap()

    private fun writeSettings(values: Map<String, Versioned>) =
        JSONObject().also { o -> values.forEach { (id, v) -> o.put(id, JSONObject().put("value", v.value).put("version", v.version)) } }

    private fun save() {
        val json = JSONObject()
            .put("phoneId", phoneId.toHex())
            .put("identityPublic", identity.public.toBase64())
            .put("identityPrivate", wrapper.wrap(Crypto.privateScalar(identity.private)).toBase64())
            .put("settings", writeSettings(offlineSettings))
            .put("addresses", JSONArray(addresses))
            .put(
                "pcs",
                JSONArray(
                    pcs.values.map { p ->
                        JSONObject().put("id", p.id).put("name", p.name).put("staticPub", p.staticPub.toBase64())
                            .put("pairingKey", if (p.pairingKey.isEmpty()) "" else wrapper.wrap(p.pairingKey).toBase64())
                            .put("approved", p.approved).put("lastUsedAt", p.lastUsedAt).put("refusedAt", p.refusedAt)
                            .put("btAddress", p.btAddress ?: "").put("settings", writeSettings(p.settings)).put("pending", JSONArray(p.pending.toList()))
                    },
                ),
            )
        // Write a temp file, then rename: a crash mid-write never leaves half a file.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
