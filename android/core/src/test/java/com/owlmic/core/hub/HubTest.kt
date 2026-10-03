package com.owlmic.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HubTest {
    private class Recorder(private val done: CountDownLatch) : Hub<Int>("test") {
        val seen = mutableListOf<Int>()
        val threads = mutableSetOf<Thread>()
        var failures = 0

        override fun handle(message: Int) {
            threads += Thread.currentThread()
            if (message < 0) error("bad")
            seen += message
            done.countDown()
        }

        override fun onFailure(message: Int, error: Exception) {
            failures++
        }
    }

    @Test
    fun handlesInOrderAndSurvivesAFailingMessage() {
        val done = CountDownLatch(3)
        val hub = Recorder(done)
        hub.post(1)
        hub.post(-1)
        hub.post(2)
        hub.post(3)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2, 3), hub.seen)
        assertEquals(1, hub.failures)
        hub.close()
    }

    private class Flaky(private val failStart: Int) : Module<String> {
        var started = 0
        override val name = "flaky"

        override fun start() {
            started++
            if (started <= failStart) error("no device")
        }

        override fun handle(command: String) = Unit

        override fun health(): Health = Health.Ok

        override fun stop() = Unit
    }

    @Test
    fun supervisedRestartsWithBackoffThenGivesUp() {
        val module = Flaky(failStart = 100)
        val delays = mutableListOf<Long>()
        val pending = ArrayDeque<() -> Unit>()
        val s = Supervised({ module }, { d, block -> delays += d; pending += block }, RestartPolicy { 0 })
        s.start()
        while (pending.isNotEmpty()) pending.removeFirst()()
        assertEquals(listOf(100L, 500L, 2_000L, 2_000L, 2_000L), delays)
        assertEquals(6, module.started)
        assertTrue(s.health() is Health.Failed)
    }

    @Test
    fun supervisedRecoversWhenTheModuleStarts() {
        val module = Flaky(failStart = 1)
        val pending = ArrayDeque<() -> Unit>()
        val s = Supervised({ module }, { _, block -> pending += block })
        s.start()
        while (pending.isNotEmpty()) pending.removeFirst()()
        assertEquals(2, module.started)
        assertEquals(Health.Ok, s.health())
    }
}
