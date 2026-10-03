package com.owlmic.core.proto

import com.owlmic.core.proto.Vectors.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryTest {
    private val vectors = Vectors.load("discovery.json")

    @Test
    fun probesMatchTheVectors() {
        val probes = vectors.getJSONArray("probes")
        for (i in 0 until probes.length()) {
            val v = probes.getJSONObject(i)
            val probe = Probe(hex(v.getString("phoneId")), v.getString("phoneName"))
            assertArrayEquals(v.getString("name"), hex(v.getString("hex")), probe.encode())
            val decoded = Probe.decode(hex(v.getString("hex")))!!
            assertArrayEquals(probe.phoneId, decoded.phoneId)
            assertEquals(probe.name, decoded.name)
        }
    }

    @Test
    fun answersMatchTheVectors() {
        val answers = vectors.getJSONArray("answers")
        for (i in 0 until answers.length()) {
            val v = answers.getJSONObject(i)
            val answer = Answer(
                pcId = hex(v.getString("pcId")),
                keyHint = hex(v.getString("keyHint")),
                tcpPort = v.getInt("tcpPort"),
                mediaPort = v.getInt("mediaPort"),
                proto = v.getInt("proto"),
                busy = v.getBoolean("busy"),
                approvalRequired = v.getBoolean("approvalRequired"),
                link = v.getInt("link"),
                name = v.getString("pcName"),
            )
            assertArrayEquals(v.getString("name"), hex(v.getString("hex")), answer.encode())
            val d = Answer.decode(hex(v.getString("hex")))!!
            assertArrayEquals(answer.pcId, d.pcId)
            assertArrayEquals(answer.keyHint, d.keyHint)
            assertEquals(listOf(answer.tcpPort, answer.mediaPort, answer.proto, answer.link), listOf(d.tcpPort, d.mediaPort, d.proto, d.link))
            assertEquals(listOf(answer.busy, answer.approvalRequired), listOf(d.busy, d.approvalRequired))
            assertEquals(answer.name, d.name)
        }
    }

    @Test
    fun ignoresWhatIsNotAWholeV3Packet() {
        val ignored = vectors.getJSONArray("ignored")
        for (i in 0 until ignored.length()) {
            val v = ignored.getJSONObject(i)
            val bytes = hex(v.getString("hex"))
            if (v.getString("parser") == "probe") assertNull(v.getString("name"), Probe.decode(bytes))
            else assertNull(v.getString("name"), Answer.decode(bytes))
        }
    }

    @Test
    fun longNamesAreCutAtACharacterBoundary() {
        val probe = Probe(ByteArray(16), "é".repeat(200))
        assertEquals("é".repeat(127), Probe.decode(probe.encode())!!.name)
    }
}
