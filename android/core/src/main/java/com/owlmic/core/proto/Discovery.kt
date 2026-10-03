package com.owlmic.core.proto

/** The phone's probe and the PC's answer (protocol/README.md, section 2). */
object Discovery {
    val PROBE_MAGIC = "OWLMIC?3".toByteArray()
    val ANSWER_MAGIC = "OWLMIC!3".toByteArray()

    internal const val PROBE_FIXED = 8 + 16 + 1
    internal const val ANSWER_FIXED = 8 + 16 + 8 + 2 + 2 + 1 + 1 + 1 + 1

    internal fun startsWith(buf: ByteArray, length: Int, magic: ByteArray) =
        length >= magic.size && (magic.indices).all { buf[it] == magic[it] }
}

class Probe(val phoneId: ByteArray, val name: String) {
    init {
        require(phoneId.size == 16) { "phone ids are 16 bytes" }
    }

    fun encode(): ByteArray {
        val name = cutName(name)
        return Discovery.PROBE_MAGIC + phoneId + byteArrayOf(name.size.toByte()) + name
    }

    companion object {
        /** Null for anything that isn't a whole v3 probe. */
        fun decode(buf: ByteArray, length: Int = buf.size): Probe? {
            val fixed = Discovery.PROBE_FIXED
            if (length < fixed || !Discovery.startsWith(buf, length, Discovery.PROBE_MAGIC)) return null
            val nameLength = buf[fixed - 1].toInt() and 0xFF
            if (length < fixed + nameLength) return null
            return Probe(buf.copyOfRange(8, 24), String(buf, fixed, nameLength))
        }
    }
}

class Answer(
    val pcId: ByteArray,
    /** The first 8 bytes of the SHA-256 of the PC's public key. */
    val keyHint: ByteArray,
    val tcpPort: Int,
    val mediaPort: Int,
    val proto: Int,
    val busy: Boolean,
    val approvalRequired: Boolean,
    /** The link the probe arrived over: 2 USB tethering, 3 Wi-Fi. */
    val link: Int,
    val name: String,
) {
    fun encode(): ByteArray {
        val name = cutName(name)
        val flags = (if (busy) FLAG_BUSY else 0) or (if (approvalRequired) FLAG_APPROVAL_REQUIRED else 0)
        return Discovery.ANSWER_MAGIC + pcId + keyHint +
            byteArrayOf(
                (tcpPort shr 8).toByte(), tcpPort.toByte(), (mediaPort shr 8).toByte(), mediaPort.toByte(),
                proto.toByte(), flags.toByte(), link.toByte(), name.size.toByte(),
            ) + name
    }

    companion object {
        private const val FLAG_BUSY = 1
        private const val FLAG_APPROVAL_REQUIRED = 2

        /** Null for anything that isn't a whole v3 answer. */
        fun decode(buf: ByteArray, length: Int = buf.size): Answer? {
            val fixed = Discovery.ANSWER_FIXED
            if (length < fixed || !Discovery.startsWith(buf, length, Discovery.ANSWER_MAGIC)) return null
            val nameLength = buf[fixed - 1].toInt() and 0xFF
            if (length < fixed + nameLength) return null
            val flags = buf[37].toInt()
            return Answer(
                pcId = buf.copyOfRange(8, 24),
                keyHint = buf.copyOfRange(24, 32),
                tcpPort = buf.u16(32),
                mediaPort = buf.u16(34),
                proto = buf[36].toInt() and 0xFF,
                busy = flags and FLAG_BUSY != 0,
                approvalRequired = flags and FLAG_APPROVAL_REQUIRED != 0,
                link = buf[38].toInt() and 0xFF,
                name = String(buf, fixed, nameLength),
            )
        }
    }
}
