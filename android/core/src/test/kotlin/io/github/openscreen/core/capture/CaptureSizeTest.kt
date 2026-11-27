package io.github.openscreen.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CaptureSizeTest {
    @Test
    fun `scales a tall phone to the preset long edge, aligned to 16`() {
        assertEquals(CaptureSize(864, 1920), CaptureSizes.compute(1080, 2400, QualityPreset.P1080))
        assertEquals(CaptureSize(576, 1280), CaptureSizes.compute(1080, 2400, QualityPreset.P720))
    }

    @Test
    fun `keeps landscape orientation`() {
        assertEquals(CaptureSize(1920, 864), CaptureSizes.compute(2400, 1080, QualityPreset.P1080))
    }

    @Test
    fun `never upscales a small display`() {
        assertEquals(CaptureSize(720, 1280), CaptureSizes.compute(720, 1280, QualityPreset.P1080))
    }

    @Test
    fun `alignment distorts the aspect ratio by less than one percent`() {
        val displays = listOf(1080 to 2400, 1440 to 3120, 1080 to 2340, 1260 to 2800, 720 to 1600, 1600 to 2560, 1812 to 2176)
        for ((w, h) in displays) for (preset in QualityPreset.entries) {
            val s = CaptureSizes.compute(w, h, preset)
            assertEquals(0, s.width % 16)
            assertEquals(0, s.height % 16)
            val error = abs(s.width.toDouble() / s.height - w.toDouble() / h) / (w.toDouble() / h)
            assertTrue("$w×$h $preset → $s error $error", error < 0.01)
        }
    }

    @Test
    fun `60 fps gets more bitrate and is only offered when supported`() {
        assertEquals(12_000_000, QualityPreset.P1080.maxBitrateFor(60))
        assertEquals(8_000_000, QualityPreset.P1080.maxBitrateFor(30))
        assertEquals(30, FrameRates.clamp(60, supports60 = false))
        assertEquals(60, FrameRates.clamp(60, supports60 = true))
    }
}
