package io.github.openscreen.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FramePacerTest {
    private val ms = 1_000_000L

    /** Feeds [seconds] of display frames at [hz] with optional jitter; returns frames forwarded. */
    private fun run(pacer: FramePacer, hz: Double, seconds: Int, jitterMs: Double = 0.0, startNs: Long = 0): Int {
        val rnd = Random(42)
        val period = 1e9 / hz
        var count = 0
        for (i in 0 until (hz * seconds).toInt()) {
            val jitter = if (jitterMs == 0.0) 0.0 else (rnd.nextDouble() * 2 - 1) * jitterMs * ms
            if (pacer.shouldForward(startNs + (i * period + jitter).toLong())) count++
        }
        return count
    }

    @Test
    fun `30 FPS from a 60 Hz display takes every second frame`() {
        assertEquals(300, run(FramePacer(30), hz = 60.0, seconds = 10))
    }

    @Test
    fun `stays at the target under vsync jitter`() {
        val n = run(FramePacer(30), hz = 60.0, seconds = 10, jitterMs = 2.0)
        assertTrue("forwarded $n", n in 290..310)
    }

    @Test
    fun `90 and 120 Hz displays are paced down too`() {
        assertTrue(run(FramePacer(30), hz = 90.0, seconds = 10) in 290..310)
        assertTrue(run(FramePacer(30), hz = 120.0, seconds = 10) in 290..310)
    }

    @Test
    fun `60 FPS passes every 60 Hz frame`() {
        assertEquals(600, run(FramePacer(60), hz = 60.0, seconds = 10))
    }

    @Test
    fun `slow sources are never throttled`() {
        assertEquals(50, run(FramePacer(30), hz = 5.0, seconds = 10))
    }

    @Test
    fun `frames are skipped while a resize settles, then flow again`() {
        val pacer = FramePacer(30, settleNs = 100 * ms)
        assertTrue(pacer.shouldForward(0))
        pacer.onResize(nowNs = 50 * ms)
        assertTrue(pacer.isSettling(100 * ms))
        assertFalse(pacer.shouldForward(100 * ms))
        assertFalse(pacer.shouldForward(149 * ms))
        assertFalse(pacer.isSettling(150 * ms))
        assertTrue(pacer.shouldForward(150 * ms))
        assertEquals(150 * ms, pacer.settleEndNs)
    }

    @Test
    fun `a clock jump backwards does not stall the stream`() {
        val pacer = FramePacer(30)
        assertTrue(pacer.shouldForward(10_000 * ms))
        assertTrue(pacer.shouldForward(5 * ms))
        assertEquals(2, pacer.forwarded)
    }
}
