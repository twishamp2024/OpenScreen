package io.github.openscreen.core.webrtc

import android.util.Log
import io.github.openscreen.core.capture.ScreenFeed
import io.github.openscreen.core.protocol.IceCandidatePayload
import io.github.openscreen.core.session.IceState
import io.github.openscreen.core.session.Peer
import io.github.openscreen.core.session.PeerFactory
import io.github.openscreen.core.session.PeerListener
import io.github.openscreen.core.session.VideoFeed
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Snapshot of sender statistics for the diagnostics panel. */
data class SenderStats(
    val framesPerSecond: Double?,
    val bitrateBps: Long?,
    val frameWidth: Int?,
    val frameHeight: Int?,
    val roundTripMs: Double?,
    val availableBitrateBps: Long?,
    val qualityLimitation: String?,
    val encoder: String?,
)

class WebRtcPeerFactory(private val engine: WebRtcEngine) : PeerFactory {
    override fun create(listener: PeerListener): Peer = WebRtcPeer(engine, listener)
}

/**
 * One send-only peer connection (docs/PROTOCOL.md §8): no STUN/TURN, one
 * video m-section, no data channel, no audio.
 */
class WebRtcPeer(private val engine: WebRtcEngine, private val listener: PeerListener) : Peer {
    private val pc: PeerConnection
    private val transceiver: RtpTransceiver
    /** Result of the last setParameters call (exposed for on-device tests). */
    internal var lastParametersApplied: Boolean? = null
        private set
    internal val senderParameters: RtpParameters get() = transceiver.sender.parameters
    private var lastBytes: Long? = null
    private var lastStatsUs: Double? = null

    init {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.LOW_COST // never cellular
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            networkPreference = PeerConnection.AdapterType.WIFI
            keyType = PeerConnection.KeyType.ECDSA
        }
        pc = checkNotNull(engine.factory.createPeerConnection(config, Observer())) { "createPeerConnection failed" }
        transceiver = pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("openscreen")),
        )
        transceiver.setCodecPreferences(engine.videoCodecPreferences())
    }

    override suspend fun createOffer(iceRestart: Boolean): String {
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val offer = suspendCancellableCoroutine { cont ->
            pc.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("createOffer: $error"))
            }, constraints)
        }
        setDescription(local = true, offer)
        return offer.description
    }

    override suspend fun setAnswer(sdp: String) =
        setDescription(local = false, SessionDescription(SessionDescription.Type.ANSWER, sdp))

    private suspend fun setDescription(local: Boolean, sdp: SessionDescription) =
        suspendCancellableCoroutine { cont ->
            val observer = object : SdpAdapter() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("setDescription: $error"))
            }
            if (local) pc.setLocalDescription(observer, sdp) else pc.setRemoteDescription(observer, sdp)
        }

    override fun addRemoteCandidate(candidate: IceCandidatePayload?) {
        if (candidate == null || candidate.candidate.isEmpty()) return
        pc.addIceCandidate(IceCandidate(candidate.sdpMid ?: "0", candidate.sdpMLineIndex ?: 0, candidate.candidate))
    }

    override fun setVideo(feed: VideoFeed?) {
        val sender = transceiver.sender
        val screen = feed as? ScreenFeed
        sender.setTrack(screen?.track, /* takeOwnership = */ false)
        if (screen != null) {
            val params = sender.parameters
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            params.encodings.firstOrNull()?.apply {
                maxBitrateBps = screen.maxBitrateBps
                minBitrateBps = screen.minBitrateBps
                maxFramerate = screen.info.fps
                scaleResolutionDownBy = 1.0
            }
            lastParametersApplied = sender.setParameters(params)
            if (lastParametersApplied == false) {
                Log.w(TAG, "setParameters rejected: bitrate/frame-rate caps not applied")
            }
        }
    }

    suspend fun stats(): SenderStats = suspendCancellableCoroutine { cont ->
        pc.getStats { report ->
            val all = report.statsMap.values
            val out = all.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" }?.members
            val pair = all.firstOrNull {
                it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
            }?.members
            val bytes = (out?.get("bytesSent") as? Number)?.toLong()
            val ts = report.timestampUs
            val bitrate = if (bytes != null && lastBytes != null && lastStatsUs != null && ts > lastStatsUs!!) {
                ((bytes - lastBytes!!) * 8 * 1_000_000.0 / (ts - lastStatsUs!!)).toLong()
            } else {
                null
            }
            lastBytes = bytes
            lastStatsUs = ts
            cont.resume(
                SenderStats(
                    framesPerSecond = (out?.get("framesPerSecond") as? Number)?.toDouble(),
                    bitrateBps = bitrate,
                    frameWidth = (out?.get("frameWidth") as? Number)?.toInt(),
                    frameHeight = (out?.get("frameHeight") as? Number)?.toInt(),
                    roundTripMs = (pair?.get("currentRoundTripTime") as? Number)?.toDouble()?.times(1000),
                    availableBitrateBps = (pair?.get("availableOutgoingBitrate") as? Number)?.toLong(),
                    qualityLimitation = out?.get("qualityLimitationReason") as? String,
                    encoder = out?.get("encoderImplementation") as? String,
                ),
            )
        }
    }

    override fun close() {
        runCatching { transceiver.sender.setTrack(null, false) }
        pc.dispose()
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) =
            listener.onLocalCandidate(IceCandidatePayload(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) listener.onLocalCandidate(null)
        }

        // PeerConnectionState combines ICE and DTLS, so CONNECTED means media can flow.
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = listener.onIceState(
            when (newState) {
                PeerConnection.PeerConnectionState.NEW -> IceState.NEW
                PeerConnection.PeerConnectionState.CONNECTING -> IceState.CHECKING
                PeerConnection.PeerConnectionState.CONNECTED -> IceState.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> IceState.DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> IceState.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> IceState.CLOSED
            },
        )

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = channel.close() // view-only: never accept one
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private companion object {
        const val TAG = "OpenScreenPeer"
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
