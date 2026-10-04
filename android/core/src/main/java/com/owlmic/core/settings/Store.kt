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
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything Owlmic keeps (section 18.3, section 21): the phone's identity, the PCs it paired with, their settings,
 * manual addresses and which permissions were asked. One JSON file, written atomically on [write]'s thread; secrets
 * are wrapped by [wrapper] once, when they are first stored. Thread-safe.
 *
 * Opening throws when the Keystore fails for now; the caller tries again later. Only a key that is gone for good
 * (an unwrap that returns null) gives the phone a fresh identity.
 */
class Store(
    private val file: File,
    private val wrapper: KeyWrapper,
    private val now: () -> Long = System::currentTimeMillis,
    private val write: Executor = Executors.newSingleThreadExecutor { Thread(it, "owlmic-store").apply { isDaemon = true } },
) {
    class Pc(
        val id: String,
        var name: String,
        val staticPub: ByteArray,
        val pairingKey: ByteArray,
        /** [pairingKey] as stored: wrapped once, so saving never goes through the Keystore. */
        val wrappedKey: String,
        var approved: Boolean,
        var lastUsedAt: Long,
        var refusedAt: Long,
        var btAddress: String?,
        var settings: Map<String, Versioned>,
        var pending: Set<String>,
    )

    val phoneId: ByteArray
    val identity: Crypto.KeyPair
    private val wrappedIdentity: String
    private val pcs = LinkedHashMap<String, Pc>()
    private val addresses = mutableListOf<String>()
    private val asked = mutableSetOf<String>()
    private var offlineSettings: Map<String, Versioned> = SettingValues.defaults()
    private val latest = AtomicReference<String?>(null)

    init {
        val json = if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull() else null
        val id = json?.optString("phoneId")?.takeIf { it.length == 32 }
        val pub = json?.optString("identityPublic")?.fromBase64()
        val wrapped = json?.optString("identityPrivate").orEmpty()
        val priv = wrapped.fromBase64()?.let(wrapper::unwrap)
        if (json != null && id != null && pub != null && priv != null) {
            phoneId = id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            identity = Crypto.restore(priv, pub)
            wrappedIdentity = wrapped
            json.optJSONArray("pcs")?.let { readPcs(it) }
            json.optJSONArray("addresses")?.let { a -> repeat(a.length()) { addresses += a.getString(it) } }
            json.optJSONArray("asked")?.let { a -> repeat(a.length()) { asked += a.getString(it) } }
            json.optJSONObject("settings")?.let { offlineSettings = SettingValues.defaults() + readSettings(it) }
        } else {
            // A first start, or a Keystore key that is gone for good: a fresh identity, so PCs approve this phone again.
            phoneId = Crypto.randomBytes(16)
            identity = Crypto.generate()
            wrappedIdentity = wrapper.wrap(Crypto.privateScalar(identity.private)).toBase64()
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
        val sameKey = existing != null && existing.pairingKey.contentEquals(pairingKey) && existing.wrappedKey.isNotEmpty()
        pcs[pcId] = Pc(
            pcId, name, staticPub, pairingKey,
            wrappedKey = if (sameKey) existing.wrappedKey else wrapper.wrap(pairingKey).toBase64(),
            approved = true, lastUsedAt = now(), refusedAt = 0,
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
            pcs[pcId] = Pc(pcId, name, ByteArray(0), ByteArray(0), "", false, 0, now(), null, SettingValues.defaults(), emptySet())
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

    /** Permissions asked for at least once (section 8.1). */
    @Synchronized
    fun asked(): Set<String> = asked.toSet()

    @Synchronized
    fun markAsked(permission: String) {
        if (asked.add(permission)) save()
    }

    private fun readPcs(a: JSONArray) {
        repeat(a.length()) { i ->
            val o = a.getJSONObject(i)
            val pub = o.optString("staticPub").fromBase64() ?: ByteArray(0)
            val wrapped = o.optString("pairingKey")
            val key = wrapped.fromBase64()?.let(wrapper::unwrap) ?: ByteArray(0)
            val approved = o.optBoolean("approved") && key.isNotEmpty()
            pcs[o.getString("id")] = Pc(
                o.getString("id"), o.optString("name"), pub, key, if (key.isEmpty()) "" else wrapped,
                approved, o.optLong("lastUsedAt"), o.optLong("refusedAt"),
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

    /** Called with the lock held: the snapshot is taken here, the disk write happens on [write]'s thread. */
    private fun save() {
        val json = JSONObject()
            .put("phoneId", phoneId.toHex())
            .put("identityPublic", identity.public.toBase64())
            .put("identityPrivate", wrappedIdentity)
            .put("settings", writeSettings(offlineSettings))
            .put("addresses", JSONArray(addresses))
            .put("asked", JSONArray(asked.toList()))
            .put(
                "pcs",
                JSONArray(
                    pcs.values.map { p ->
                        JSONObject().put("id", p.id).put("name", p.name).put("staticPub", p.staticPub.toBase64())
                            .put("pairingKey", p.wrappedKey)
                            .put("approved", p.approved).put("lastUsedAt", p.lastUsedAt).put("refusedAt", p.refusedAt)
                            .put("btAddress", p.btAddress ?: "").put("settings", writeSettings(p.settings)).put("pending", JSONArray(p.pending.toList()))
                    },
                ),
            )
        latest.set(json.toString())
        write.execute {
            // Several saves in a row write once: whichever runs first takes the newest snapshot.
            val text = latest.getAndSet(null) ?: return@execute
            runCatching { writeAtomically(text) }
        }
    }

    /** A temp file, flushed to the disk, then renamed: a crash or power loss never leaves half a file. */
    private fun writeAtomically(text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
