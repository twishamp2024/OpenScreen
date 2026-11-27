package io.github.openscreen.sender.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import io.github.openscreen.core.capture.FrameRates
import io.github.openscreen.core.capture.ScreenFeed
import io.github.openscreen.core.capture.ScreenStream
import io.github.openscreen.core.net.Subnets
import io.github.openscreen.core.net.WifiNetworkTracker
import io.github.openscreen.core.protocol.Capabilities
import io.github.openscreen.core.protocol.DeviceInfo
import io.github.openscreen.core.protocol.DisplayInfo
import io.github.openscreen.core.protocol.PairingInfo
import io.github.openscreen.core.session.DeviceEnvironment
import io.github.openscreen.core.session.MirrorSession
import io.github.openscreen.core.session.Peer
import io.github.openscreen.core.session.PeerFactory
import io.github.openscreen.core.session.PeerListener
import io.github.openscreen.core.session.SessionState
import io.github.openscreen.core.session.isActive
import io.github.openscreen.core.signaling.OkHttpSignalingTransport
import io.github.openscreen.core.webrtc.SenderStats
import io.github.openscreen.core.webrtc.WebRtcEngine
import io.github.openscreen.core.webrtc.WebRtcPeer
import io.github.openscreen.sender.BuildConfig
import io.github.openscreen.sender.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Wires the platform implementations into [MirrorSession] and owns the
 * capture pipeline. Process-scoped (see OpenScreenApp).
 */
class SenderController(private val context: Context) {
    private val loop = Executors.newSingleThreadExecutor { r -> Thread(r, "openscreen-session") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + loop)
    private val main = Handler(Looper.getMainLooper())
    private val engine by lazy { WebRtcEngine.get(context) }

    val settings = SettingsStore(context)
    val wifi = WifiNetworkTracker(context) { session.networkChanged() }
    val supports60Fps: Boolean by lazy { WebRtcEngine.hardwareSupports1080p60() }

    @Volatile
    private var currentPeer: WebRtcPeer? = null

    val session: MirrorSession by lazy {
        MirrorSession(
            scope = scope,
            transport = OkHttpSignalingTransport(socketFactory = { wifi.socketFactory() }),
            peers = PeerFactory { listener: PeerListener ->
                WebRtcPeer(engine, listener).also { currentPeer = it } as Peer
            },
            environment = Environment(),
        )
    }

    val state: StateFlow<SessionState> get() = session.state

    private val _stats = MutableStateFlow<SenderStats?>(null)
    val stats: StateFlow<SenderStats?> = _stats.asStateFlow()

    private val _streamingSince = MutableStateFlow<Long?>(null)
    /** `SystemClock.elapsedRealtime()` when the current capture started. */
    val streamingSince: StateFlow<Long?> = _streamingSince.asStateFlow()

    private var pairedHost: String? = null
    val receiverAddress: String? get() = pairedHost

    private var stream: ScreenStream? = null
    private var feed: ScreenFeed? = null
    private var statsJob: Job? = null

    init {
        wifi.start()
        scope.launch {
            state.collect { s ->
                if (s.isActive) startStats() else stopStats()
                if (!s.isActive && stream != null) {
                    // Session ended (error, PC closed, user disconnected): stop capturing.
                    main.post { StreamingService.stop(context) }
                }
            }
        }
    }

    fun pair(info: PairingInfo) {
        val w = wifi.status.value
        val hosts = Subnets.prioritize(info.hosts, w.ipv4, w.prefixLength)
        pairedHost = hosts.first()
        session.pair(info, hosts)
    }

    fun disconnect() = session.disconnect()
    fun dismissError() = session.dismissError()

    // ── capture (called by StreamingService on the main thread) ──

    fun startCapture(projection: MediaProjection) {
        stopCapture(null)
        val s = settings.settings.value
        val fps = FrameRates.clamp(s.fps, supports60Fps)
        val (w, h) = displaySize()
        val screen = ScreenStream(context, engine, projection, s.preset, fps, densityDpi())
        stream = screen
        val f = screen.start(w, h)
        feed = f
        _streamingSince.value = SystemClock.elapsedRealtime()
        displayManager.registerDisplayListener(displayListener, main)
        session.startStreaming(f)
    }

    /** @param reason protocol stop reason, or null when the session is already gone. */
    fun stopCapture(reason: String?) {
        val screen = stream ?: return
        displayManager.unregisterDisplayListener(displayListener)
        if (reason != null) session.stopStreaming(reason)
        stream = null
        feed = null
        _streamingSince.value = null
        // Release after the session detached the track (posted to the session loop first).
        scope.launch { screen.release() }
    }

    val isCapturing: Boolean get() = stream != null

    private val displayManager get() = context.getSystemService(DisplayManager::class.java)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val screen = stream ?: return
            val f = feed ?: return
            val (w, h) = displaySize()
            screen.resize(f, w, h)?.let { session.updateVideo(it) }
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    // ── stats ──

    private fun startStats() {
        if (statsJob?.isActive == true) return
        statsJob = scope.launch {
            while (isActive) {
                _stats.value = runCatching { currentPeer?.stats() }.getOrNull()
                delay(1_000)
            }
        }
    }

    private fun stopStats() {
        statsJob?.cancel()
        statsJob = null
        _stats.value = null
    }

    // ── device info ──

    private fun displaySize(): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun densityDpi(): Int = context.resources.displayMetrics.densityDpi

    private fun rotationDegrees(): Int {
        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private inner class Environment : DeviceEnvironment {
        override fun device() = DeviceInfo(
            manufacturer = Build.MANUFACTURER.take(64).ifBlank { "Unknown" },
            model = Build.MODEL.take(64).ifBlank { "Android device" },
            androidVersion = Build.VERSION.RELEASE.take(16).ifBlank { "?" },
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME.take(32),
        )

        override fun display(): DisplayInfo {
            val (w, h) = displaySize()
            return DisplayInfo(w, h, densityDpi(), rotationDegrees())
        }

        override fun capabilities() = Capabilities(
            codecs = engine.supportedCodecs().ifEmpty { listOf("VP8") },
            maxFps = if (supports60Fps) 60 else 30,
        )
    }
}
