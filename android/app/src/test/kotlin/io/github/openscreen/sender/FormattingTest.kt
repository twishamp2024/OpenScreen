package io.github.openscreen.sender

import io.github.openscreen.core.session.SessionError
import io.github.openscreen.sender.ui.formatBitrate
import io.github.openscreen.sender.ui.formatDuration
import io.github.openscreen.sender.ui.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FormattingTest {
    @Test
    fun durations() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("01:15", formatDuration(75))
        assertEquals("1:00:01", formatDuration(3601))
    }

    @Test
    fun bitrates() {
        assertEquals("750 kbps", formatBitrate(750_000))
        assertEquals("8.0 Mbps", formatBitrate(8_000_000))
    }

    @Test
    fun `every session error has distinct, actionable text`() {
        val bodies = SessionError.entries.map { it.message().body }
        assertEquals(bodies.size, bodies.toSet().size)
        SessionError.entries.forEach { assertNotEquals(0, it.message().title) }
    }
}
