package com.owlmic.core.link

import com.owlmic.core.hub.LinkKind
import java.io.Closeable
import java.net.InetAddress

/**
 * A way to reach a PC. A USB debugging tunnel has no [pcId] until its handshake: the cable already says which PC is meant.
 * [opened] is a connection the Seeker already made, handed over so the tunnel isn't dialled twice.
 */
data class Candidate(
    val pcId: String?,
    val name: String,
    val link: LinkKind,
    val host: InetAddress? = null,
    val tcpPort: Int = 0,
    val mediaPort: Int = 0,
    val busy: Boolean = false,
    val approvalRequired: Boolean = false,
    val hotspot: Boolean = false,
    val keyHint: String? = null,
    val btAddress: String? = null,
    val opened: Closeable? = null,
)

/** What the phone remembers about a PC it has met. */
data class PcMemory(
    val id: String,
    val name: String,
    val approved: Boolean,
    val lastUsedAt: Long,
    /** When it last said no or was busy: such a PC moves down the list for a while, never blocked forever. */
    val refusedAt: Long = 0,
)

sealed interface Decision {
    data class Connect(val candidate: Candidate) : Decision

    data class Choose(val candidates: List<Candidate>) : Decision

    data object Wait : Decision
}

/** Picks the right PC when several answer (section 14.4). Pure, so it is tested without a network. */
object Ranker {
    const val REFUSED_FOR_MS = 10 * 60_000L

    /** Best first: the PC used last, other approved PCs (recent first), new PCs, busy PCs, then those that recently said no. */
    fun order(candidates: List<Candidate>, known: Map<String, PcMemory>, now: Long): List<Candidate> {
        // One entry per PC: its best link.
        val perPc = candidates.groupBy { it.pcId ?: "usb:${it.link}" }.values.map { links -> links.minBy { it.link.ordinal } }
        val lastUsed = lastUsedId(known)
        return perPc.sortedWith(
            compareBy<Candidate>(
                { rank(it, known, lastUsed, now) },
                { -(it.pcId?.let { id -> known[id]?.lastUsedAt } ?: 0L) },
                { it.link.ordinal },
            ),
        )
    }

    /**
     * Connects by itself only to an approved PC, to the PC at the other end of a USB debugging cable, or to a new PC
     * when it is the only one around. Several new PCs: the user chooses. When only busy PCs are left, the best of them
     * is still dialled: its REJECT names the phone that has it, and a held session of this phone resumes.
     */
    fun decide(candidates: List<Candidate>, known: Map<String, PcMemory>, now: Long): Decision {
        val ordered = order(candidates, known, now)
        val first = ordered.firstOrNull() ?: return Decision.Wait
        val last = lastUsedId(known)
        return when (rank(first, known, last, now)) {
            0, 1, 2, BUSY -> Decision.Connect(first)
            3 -> {
                val fresh = ordered.filter { rank(it, known, last, now) == 3 }
                if (fresh.size == 1) Decision.Connect(fresh.single()) else Decision.Choose(fresh)
            }
            else -> Decision.Wait
        }
    }

    private fun lastUsedId(known: Map<String, PcMemory>) = known.values.filter { it.approved }.maxByOrNull { it.lastUsedAt }?.id

    private const val BUSY = 4
    private const val REFUSED = 5

    private fun rank(c: Candidate, known: Map<String, PcMemory>, lastUsed: String?, now: Long): Int {
        if (c.link == LinkKind.USB_DEBUGGING && c.pcId == null) return 0
        val memory = c.pcId?.let { known[it] }
        if (memory != null && memory.refusedAt > 0 && now - memory.refusedAt < REFUSED_FOR_MS) return REFUSED
        if (c.busy) return BUSY
        return when {
            c.pcId != null && c.pcId == lastUsed -> 1
            memory?.approved == true -> 2
            else -> 3
        }
    }
}
