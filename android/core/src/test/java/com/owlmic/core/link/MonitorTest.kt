package com.owlmic.core.link

import com.owlmic.core.link.Reconnector.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorTest {
    private var t = 1_000_000L

    @Test
    fun theLadderClimbsOneStepPerStallAndStopsAtHold() {
        val r = Reconnector { t }
        assertEquals(Step.RESTART_SOURCE, r.stall())
        t += 1_000
        assertEquals(Step.RECREATE_MEDIA, r.stall())
        t += 1_000
        assertEquals(Step.REHANDSHAKE, r.stall())
        t += 1_000
        assertEquals(Step.SWITCH_LINK, r.stall())
        t += 1_000
        assertEquals(Step.HOLD, r.stall())
        assertFalse(r.worthTelling())
        t += 1_000
        assertEquals(Step.HOLD, r.stall())
        assertTrue(r.worthTelling())
    }

    @Test
    fun aQuietSpellOrRecoveryStartsANewIncident() {
        val r = Reconnector { t }
        r.stall()
        r.stall()
        t += Reconnector.QUIET_MS + 1
        assertEquals(Step.RESTART_SOURCE, r.stall())
        r.stall()
        r.recovered()
        assertEquals(Step.RESTART_SOURCE, r.stall())
    }

    @Test
    fun aLinkIsDeadAfterThreeSilentSecondsOnWirelessTwoOnCable() {
        val wifi = Monitor(wireless = true) { t }
        val usb = Monitor(wireless = false) { t }
        wifi.heard()
        usb.heard()
        t += 2_001
        assertFalse(wifi.dead())
        assertTrue(usb.dead())
        t += 1_000
        assertTrue(wifi.dead())
        wifi.heard()
        assertFalse(wifi.dead())
    }

    @Test
    fun aWarmStandbyIsJudgedByItsSlowerHeartbeat() {
        val standby = Monitor(wireless = false) { t }
        standby.heard()
        // Pinged every 2 s, its answer can be just over 2 s old: not dead yet.
        t += Monitor.STANDBY_HEARTBEAT_MS + 100
        assertFalse(standby.dead(Monitor.STANDBY_HEARTBEAT_MS))
        t += Monitor.STANDBY_HEARTBEAT_MS
        assertTrue(standby.dead(Monitor.STANDBY_HEARTBEAT_MS))
    }

    @Test
    fun reportsEveryTenSeconds() {
        val m = Monitor(wireless = true) { t }
        val stats = InboundStats { t * 1_000 }
        assertNull(m.report(stats, null))
        t += Monitor.REPORT_EVERY_MS
        val report = m.report(stats, 3)
        assertEquals(3, report!!.thermal)
    }

    @Test
    fun inboundLossAndJitterFollowRfc3550() {
        var us = 0L
        val s = InboundStats { us }
        // Seq 0..9 with 2 and 5 missing, every packet exactly on time.
        for (seq in 0L..9L) {
            if (seq == 2L || seq == 5L) continue
            us = seq * 10_000
            s.add(seq, seq * 10_000, 100)
        }
        val (loss, jitterMs, kbps) = s.take(1_000)
        assertEquals(20.0, loss, 0.01)
        assertEquals(0, jitterMs)
        assertEquals(6, kbps)
    }
}
