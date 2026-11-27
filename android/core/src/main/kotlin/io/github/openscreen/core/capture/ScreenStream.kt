package io.github.openscreen.core.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.github.openscreen.core.protocol.VideoInfo
import io.github.openscreen.core.session.VideoFeed
import io.github.openscreen.core.webrtc.WebRtcEngine
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The screen track handed to the peer connection. */
class ScreenFeed internal constructor(
    val track: VideoTrack,
    @Volatile override var info: VideoInfo,
    val maxBitrateBps: Int,
    val minBitrateBps: Int,
) : VideoFeed

/**
 * MediaProjection → VirtualDisplay → SurfaceTexture (GPU) → VideoSource.
 *
 * Owns everything between the projection and the WebRTC track. The caller
 * owns the [MediaProjection] (it must register its callback before calling
 * [start], as required on Android 14+) and must call [release] exactly once.
 */
class ScreenStream(
    context: Context,
    private val engine: WebRtcEngine,
    private val projection: MediaProjection,
    private val preset: QualityPreset,
    private val fps: Int,
    private val densityDpi: Int,
) {
    private val appContext = context.applicationContext
    private val textureHelper: SurfaceTextureHelper =
        SurfaceTextureHelper.create("OpenScreenCapture", engine.eglBase.eglBaseContext)
    private val source: VideoSource = engine.factory.createVideoSource(/* isScreencast = */ true)
    private val track: VideoTrack = engine.factory.createVideoTrack("screen", source)
    private val capturer = ProjectionCapturer()
    private var size: CaptureSize? = null

    val framesCaptured: Long get() = capturer.frames

    fun start(displayWidth: Int, displayHeight: Int): ScreenFeed {
        val s = CaptureSizes.compute(displayWidth, displayHeight, preset)
        size = s
        capturer.initialize(textureHelper, appContext, source.capturerObserver)
        capturer.startCapture(s.width, s.height, fps)
        source.adaptOutputFormat(s.width, s.height, fps)
        return ScreenFeed(
            track = track,
            info = VideoInfo(s.width, s.height, fps, codec = "H264", rotation = 0),
            maxBitrateBps = preset.maxBitrateFor(fps),
            minBitrateBps = preset.minBitrateBps,
        )
    }

    /** Called on rotation / display size change. Returns the new info, or null if unchanged. */
    fun resize(feed: ScreenFeed, displayWidth: Int, displayHeight: Int): VideoInfo? {
        val s = CaptureSizes.compute(displayWidth, displayHeight, preset)
        if (s == size) return null
        size = s
        capturer.changeCaptureFormat(s.width, s.height, fps)
        source.adaptOutputFormat(s.width, s.height, fps)
        return feed.info.copy(width = s.width, height = s.height).also { feed.info = it }
    }

    fun release() {
        runCatching { capturer.stopCapture() }
        capturer.dispose()
        track.dispose()
        source.dispose()
        textureHelper.dispose()
    }

    /**
     * Minimal capturer over an existing MediaProjection. Android 14+ allows only
     * one createVirtualDisplay() per projection, so size changes use resize().
     */
    private inner class ProjectionCapturer : VideoCapturer, VideoSink {
        private var observer: CapturerObserver? = null
        private val pacer = FramePacer(fps)
        private var display: VirtualDisplay? = null
        private var width = 0
        private var height = 0
        @Volatile var frames = 0L
        @Volatile private var lastFrameAt = 0L
        private val handler: Handler get() = textureHelper.handler

        private val keepAlive = object : Runnable {
            override fun run() {
                // A static screen produces no frames; re-send the last one so the
                // encoder can answer keyframe requests and late packets recover.
                if (frames > 0 && SystemClock.elapsedRealtime() - lastFrameAt > KEEPALIVE_MS) {
                    textureHelper.forceFrame()
                }
                handler.postDelayed(this, KEEPALIVE_MS / 2)
            }
        }

        override fun initialize(helper: SurfaceTextureHelper, context: Context, observer: CapturerObserver) {
            this.observer = observer
        }

        override fun startCapture(width: Int, height: Int, framerate: Int) {
            this.width = width
            this.height = height
            onHandler {
                textureHelper.setTextureSize(width, height)
                display = projection.createVirtualDisplay(
                    "OpenScreen",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    Surface(textureHelper.surfaceTexture),
                    null,
                    null,
                )
                observer?.onCapturerStarted(true)
                textureHelper.startListening(this)
                handler.postDelayed(keepAlive, KEEPALIVE_MS)
            }
        }

        override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
            this.width = width
            this.height = height
            onHandler {
                textureHelper.setTextureSize(width, height)
                display?.resize(width, height, densityDpi)
                // Skip the frames that still show the old layout while the encoder restarts,
                // then send one fresh frame so even a static screen appears right away.
                pacer.onResize(System.nanoTime())
                handler.postDelayed({ if (frames > 0) textureHelper.forceFrame() }, SETTLE_MS + 10)
            }
        }

        override fun stopCapture() {
            onHandler {
                handler.removeCallbacks(keepAlive)
                textureHelper.stopListening()
                display?.release()
                display = null
                observer?.onCapturerStopped()
                Log.i(TAG, "capture stopped: ${pacer.forwarded} frames sent, ${pacer.skipped} paced/settling")
            }
        }

        override fun onFrame(frame: VideoFrame) {
            frames++
            lastFrameAt = SystemClock.elapsedRealtime()
            // Never hand the encoder more than the selected frame rate (see FramePacer).
            if (pacer.shouldForward(frame.timestampNs)) observer?.onFrameCaptured(frame)
        }

        override fun dispose() = Unit
        override fun isScreencast(): Boolean = true

        private fun onHandler(block: () -> Unit) {
            if (handler.looper.isCurrentThread) return block()
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    block()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val TAG = "OpenScreenCapture"
        const val KEEPALIVE_MS = 500L
        const val SETTLE_MS = 100L
    }
}
