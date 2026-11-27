package io.github.openscreen.core.capture

/**
 * Decides which captured frames reach the encoder.
 *
 * A VirtualDisplay produces a frame per display refresh (60 Hz or more) while
 * anything changes. libwebrtc's source adapter does not throttle screencast
 * frames, so without this every vsync frame reached the hardware encoder,
 * which then dropped frames ("encoder queue full") — measured on a Redmi
 * Note 7S. Pure logic (timestamps in, decisions out) so it is unit tested.
 *
 * - **Pacing:** at most [fps] frames per second. The minimum gap is 85 % of
 *   the nominal interval, so 30 FPS from a 60 Hz display takes every second
 *   frame instead of beating against vsync jitter.
 * - **Settling:** after a resize (rotation), frames are skipped for
 *   [settleNs]: they still show the old layout scaled into the new size while
 *   the encoder restarts at the new resolution.
 */
class FramePacer(fps: Int, private val settleNs: Long = 100_000_000L) {
    private val minGapNs: Long = (1_000_000_000L / fps.coerceIn(1, 240)) * 85 / 100
    private var lastForwardedNs: Long? = null
    private var settleUntilNs: Long = Long.MIN_VALUE

    var forwarded = 0L
        private set
    var skipped = 0L
        private set

    /** Call when the capture size changes. */
    fun onResize(nowNs: Long) {
        settleUntilNs = nowNs + settleNs
    }

    /** True while a resize is settling; callers can schedule a fresh frame for [settleEndNs]. */
    fun isSettling(nowNs: Long) = nowNs < settleUntilNs

    val settleEndNs: Long get() = settleUntilNs

    /** Returns true if the frame captured at [timestampNs] should be sent to the encoder. */
    fun shouldForward(timestampNs: Long): Boolean {
        val last = lastForwardedNs
        val keep = timestampNs >= settleUntilNs && (last == null || timestampNs - last >= minGapNs || timestampNs < last)
        if (keep) {
            lastForwardedNs = timestampNs
            forwarded++
        } else {
            skipped++
        }
        return keep
    }
}
