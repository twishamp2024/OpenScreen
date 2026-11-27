package io.github.openscreen.core.capture

import kotlin.math.max
import kotlin.math.roundToInt

/** User-selectable output quality (docs/ARCHITECTURE.md §5). Values are targets, not measurements. */
enum class QualityPreset(val longEdge: Int, val maxBitrateBps: Int, val minBitrateBps: Int) {
    P720(longEdge = 1280, maxBitrateBps = 4_000_000, minBitrateBps = 300_000),
    P1080(longEdge = 1920, maxBitrateBps = 8_000_000, minBitrateBps = 500_000),
    ;

    /** Bitrate ceiling for a frame rate: 60 FPS gets 1.5× the 30 FPS budget. */
    fun maxBitrateFor(fps: Int): Int = if (fps > 30) (maxBitrateBps * 3) / 2 else maxBitrateBps
}

data class CaptureSize(val width: Int, val height: Int)

object CaptureSizes {
    /** Hardware H.264 encoders on many SoCs misbehave with dimensions that are not multiples of 16. */
    const val ALIGNMENT = 16

    /**
     * Scales the physical display down so its long edge is at most [QualityPreset.longEdge]
     * (never up), keeping the display's orientation, then aligns both sides to [ALIGNMENT].
     *
     * Alignment changes the aspect ratio by at most a few tenths of a percent; the receiver
     * letterboxes with the decoded frame's real aspect ratio, so nothing is stretched.
     */
    fun compute(displayWidth: Int, displayHeight: Int, preset: QualityPreset): CaptureSize {
        require(displayWidth > 0 && displayHeight > 0) { "display size must be positive" }
        val longEdge = max(displayWidth, displayHeight)
        val scale = minOf(1.0, preset.longEdge.toDouble() / longEdge)
        return CaptureSize(align(displayWidth * scale), align(displayHeight * scale))
    }

    private fun align(value: Double): Int =
        max(ALIGNMENT, (value / ALIGNMENT).roundToInt() * ALIGNMENT)
}

/** Frame-rate options; 60 is offered only when [supports60] says the encoder can sustain it. */
object FrameRates {
    const val DEFAULT = 30
    val ALL = listOf(30, 60)

    fun clamp(requested: Int, supports60: Boolean): Int = if (requested >= 60 && supports60) 60 else DEFAULT
}
