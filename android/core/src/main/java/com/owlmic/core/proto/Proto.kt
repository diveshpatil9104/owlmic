package com.owlmic.core.proto

/** Owlmic wire formats, protocol version 3 (protocol/README.md). No I/O: just bytes in and out. */
object Proto {
    const val VERSION = 3
    const val PORT_CONTROL = 7653
    const val PORT_DISCOVERY = 7654
    const val PORT_MEDIA = 7655

    /** Names longer than this are cut, at a character boundary. */
    const val MAX_NAME_BYTES = 255
}

internal fun cutName(name: String): ByteArray {
    val bytes = name.toByteArray()
    if (bytes.size <= Proto.MAX_NAME_BYTES) return bytes
    var end = Proto.MAX_NAME_BYTES
    // Step back over UTF-8 continuation bytes (10xxxxxx) so no character is split.
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.copyOf(end)
}

internal fun ByteArray.u16(at: Int) = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

internal fun ByteArray.u32(at: Int): Long =
    ((this[at].toLong() and 0xFF) shl 24) or ((this[at + 1].toLong() and 0xFF) shl 16) or
        ((this[at + 2].toLong() and 0xFF) shl 8) or (this[at + 3].toLong() and 0xFF)
