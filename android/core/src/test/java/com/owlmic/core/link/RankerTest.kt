package com.owlmic.core.link

import com.owlmic.core.hub.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankerTest {
    private val now = 1_000_000_000L

    private fun pc(id: String, link: LinkKind = LinkKind.WIFI, busy: Boolean = false) = Candidate(id, "PC-$id", link, busy = busy)

    private fun known(vararg m: PcMemory) = m.associateBy { it.id }

    @Test
    fun theLastUsedPcComesFirst() {
        val k = known(PcMemory("a", "A", true, lastUsedAt = 10), PcMemory("b", "B", true, lastUsedAt = 20))
        val order = Ranker.order(listOf(pc("a"), pc("b"), pc("c")), k, now)
        assertEquals(listOf("b", "a", "c"), order.map { it.pcId })
        assertEquals(Decision.Connect(pc("b")), Ranker.decide(listOf(pc("a"), pc("b"), pc("c")), k, now))
    }

    @Test
    fun aPcIsTriedOverItsBestLink() {
        val order = Ranker.order(listOf(pc("a", LinkKind.WIFI), pc("a", LinkKind.USB_TETHERING)), emptyMap(), now)
        assertEquals(listOf(LinkKind.USB_TETHERING), order.map { it.link })
    }

    @Test
    fun theUsbDebuggingTunnelWinsWithoutAnId() {
        val tunnel = Candidate(null, "", LinkKind.USB_DEBUGGING)
        val k = known(PcMemory("a", "A", true, lastUsedAt = 10))
        assertEquals(Decision.Connect(tunnel), Ranker.decide(listOf(pc("a"), tunnel), k, now))
    }

    @Test
    fun aSingleNewPcConnectsSeveralAreChosen() {
        assertEquals(Decision.Connect(pc("x")), Ranker.decide(listOf(pc("x")), emptyMap(), now))
        val d = Ranker.decide(listOf(pc("x"), pc("y")), emptyMap(), now)
        assertTrue(d is Decision.Choose)
        assertEquals(setOf("x", "y"), (d as Decision.Choose).candidates.map { it.pcId }.toSet())
    }

    @Test
    fun aBusyPcIsStillDialledSoItCanSayWhoHasIt() {
        val k = known(PcMemory("a", "A", true, lastUsedAt = 10))
        assertEquals(Decision.Connect(pc("a", busy = true)), Ranker.decide(listOf(pc("a", busy = true)), k, now))
    }

    @Test
    fun aBusyPcGoesBelowFreeOnes() {
        val k = known(PcMemory("a", "A", true, lastUsedAt = 20), PcMemory("b", "B", true, lastUsedAt = 10))
        val candidates = listOf(pc("a", busy = true), pc("b"))
        assertEquals(listOf("b", "a"), Ranker.order(candidates, k, now).map { it.pcId })
        assertEquals(Decision.Connect(pc("b")), Ranker.decide(candidates, k, now))
    }

    @Test
    fun aPcThatRefusedRecentlyGoesLastAndIsNotDialled() {
        val k = known(PcMemory("a", "A", false, 0, refusedAt = now - 60_000))
        assertEquals(Decision.Wait, Ranker.decide(listOf(pc("a")), k, now))
        assertEquals(listOf("b", "a"), Ranker.order(listOf(pc("a"), pc("b")), k, now).map { it.pcId })
        // Ten minutes later it is an ordinary new PC again.
        assertEquals(Decision.Connect(pc("a")), Ranker.decide(listOf(pc("a")), k, now + Ranker.REFUSED_FOR_MS))
    }

    @Test
    fun nothingFoundMeansWait() {
        assertEquals(Decision.Wait, Ranker.decide(emptyList(), emptyMap(), now))
    }
}
