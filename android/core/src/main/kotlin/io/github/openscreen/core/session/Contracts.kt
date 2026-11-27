package io.github.openscreen.core.session

import io.github.openscreen.core.protocol.Capabilities
import io.github.openscreen.core.protocol.DeviceInfo
import io.github.openscreen.core.protocol.DisplayInfo
import io.github.openscreen.core.protocol.IceCandidatePayload
import io.github.openscreen.core.protocol.VideoInfo

/*
 * Seams between the session state machine and the platform. The real
 * implementations (OkHttp, org.webrtc) live in `signaling/` and `webrtc/`;
 * tests use fakes, so the whole session logic runs on the JVM.
 */

enum class TransportFailure {
    /** No TCP connection to any address before the timeout: isolation, firewall, wrong network. */
    TIMEOUT,
    /** Host answered with a reset: nothing listening on the port (PC app closed or restarted). */
    REFUSED,
    /** TLS certificate did not match the QR fingerprint. */
    CERTIFICATE_MISMATCH,
    /** HTTP/WebSocket upgrade rejected (wrong path or subprotocol). */
    HANDSHAKE_REJECTED,
    /** The phone has no usable Wi-Fi network. */
    NO_NETWORK,
    /** Connection dropped or anything else. */
    OTHER,
}

interface TransportListener {
    fun onOpen(host: String)
    fun onMessage(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(failure: TransportFailure, detail: String?)
}

interface TransportConnection {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

fun interface SignalingTransport {
    /** Tries [hosts] in order; exactly one of onOpen / onFailure follows, then onMessage* and onClosed/onFailure. */
    fun open(hosts: List<String>, port: Int, fingerprint: String, listener: TransportListener): TransportConnection
}

enum class IceState { NEW, CHECKING, CONNECTED, COMPLETED, DISCONNECTED, FAILED, CLOSED }

interface PeerListener {
    /** `null` = end of candidates. */
    fun onLocalCandidate(candidate: IceCandidatePayload?)
    fun onIceState(state: IceState)
}

/** A capture source the peer can send; the real one wraps an org.webrtc VideoTrack. */
interface VideoFeed {
    val info: VideoInfo
}

interface Peer {
    suspend fun createOffer(iceRestart: Boolean): String
    suspend fun setAnswer(sdp: String)
    fun addRemoteCandidate(candidate: IceCandidatePayload?)
    /** Attach (or with `null`, detach) the screen track. Never triggers renegotiation. */
    fun setVideo(feed: VideoFeed?)
    fun close()
}

fun interface PeerFactory {
    fun create(listener: PeerListener): Peer
}

/** What the phone tells the PC about itself in `hello` (no personal data, PROTOCOL.md §4.1). */
interface DeviceEnvironment {
    fun device(): DeviceInfo
    fun display(): DisplayInfo
    fun capabilities(): Capabilities
}
