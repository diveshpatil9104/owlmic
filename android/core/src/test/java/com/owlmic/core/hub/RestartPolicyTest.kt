package com.owlmic.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestartPolicyTest {
    private var t = 0L
    private val policy = RestartPolicy { t }

    @Test
    fun backsOffThenGivesUpAfterFiveInAMinute() {
        assertEquals(100L, policy.nextDelayMs())
        assertEquals(500L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
        assertNull(policy.nextDelayMs())
    }

    @Test
    fun restartsOlderThanAMinuteNoLongerCount() {
        repeat(5) { policy.nextDelayMs() }
        t = 60_000
        assertEquals(100L, policy.nextDelayMs())
    }
}
