package io.github.openscreen.core.webrtc

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities

/**
 * Process-wide WebRTC objects: one EGL context and one factory, shared by the
 * capturer (GPU textures) and the hardware encoder so frames never leave the GPU.
 */
class WebRtcEngine private constructor(context: Context) {
    // Must run before ANY other org.webrtc call: it loads libjingle_peerconnection_so.
    // (Kotlin runs initializers top to bottom; creating the encoder factory first
    // crashed real devices with UnsatisfiedLinkError in SoftwareVideoEncoderFactory.)
    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
    }

    val eglBase: EglBase = EglBase.create()
    private val encoderFactory = DefaultVideoEncoderFactory(
        eglBase.eglBaseContext,
        /* enableIntelVp8Encoder = */ true,
        /* enableH264HighProfile = */ false, // Constrained Baseline: no B-frames, lowest latency
    )
    val factory: PeerConnectionFactory

    init {
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            // Never decodes (send-only), but libwebrtc only accepts codec preferences that the
            // connection can also *receive*; a software-only decoder list (no H.264) made
            // setCodecPreferences fail silently and the offer fell back to VP8 first.
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setOptions(PeerConnectionFactory.Options().apply { disableNetworkMonitor = false })
            .createPeerConnectionFactory()
    }

    /** Codec names (H264, VP8, …) the encoder factory can produce, best first. */
    fun supportedCodecs(): List<String> =
        encoderFactory.supportedCodecs.map { it.name.uppercase() }.distinct()
            .sortedBy { CODEC_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
            .filter { it in CODEC_ORDER }

    /**
     * Sender codec preferences: H.264 Constrained Baseline (packetization-mode 1)
     * first, any other H.264, then VP8; RTX/RED/FEC kept for loss recovery.
     */
    fun videoCodecPreferences(): List<RtpCapabilities.CodecCapability> {
        val all = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        fun rank(c: RtpCapabilities.CodecCapability): Int {
            val name = c.name.uppercase()
            val profile = c.parameters["profile-level-id"]?.lowercase()
            val mode = c.parameters["packetization-mode"]
            return when {
                name == "H264" && profile?.startsWith("42e0") == true && mode == "1" -> 0
                name == "H264" && mode == "1" -> 1
                name == "H264" -> 2
                name == "VP8" -> 3
                name in setOf("RTX", "RED", "ULPFEC", "FLEXFEC-03") -> 5
                else -> 9 // VP9/AV1 deliberately last: not universally hardware-encoded
            }
        }
        return all.sortedBy(::rank).filter { rank(it) < 9 }
    }

    companion object {
        private val CODEC_ORDER = listOf("H264", "VP8", "VP9", "AV1")

        @Volatile
        private var instance: WebRtcEngine? = null

        fun get(context: Context): WebRtcEngine =
            instance ?: synchronized(this) { instance ?: WebRtcEngine(context).also { instance = it } }

        /** True when a hardware H.264 encoder claims 1080p at 60 FPS (used to offer the 60 FPS option). */
        fun hardwareSupports1080p60(): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.isHardwareAccelerated }
                .filter { info -> info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                .any { info: MediaCodecInfo ->
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                        ?: return@any false
                    caps.areSizeAndRateSupported(1920, 1080, 60.0) || caps.areSizeAndRateSupported(1080, 1920, 60.0)
                }
        }.getOrDefault(false)
    }
}
