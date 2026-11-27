package io.github.openscreen.core.session

import io.github.openscreen.core.protocol.ReceiverInfo
import io.github.openscreen.core.protocol.VideoInfo

sealed interface SessionState {
    data object Idle : SessionState

    data class Connecting(val receiverName: String?) : SessionState

    /** Signaling is up; WebRTC transport is being established. */
    data class Negotiating(val receiver: ReceiverInfo) : SessionState

    /** Media path is up. [video] is non-null while the screen is being streamed. */
    data class Connected(val receiver: ReceiverInfo, val video: VideoInfo?) : SessionState

    /** Temporarily lost; capture keeps running so the user does not have to consent again. */
    data class Reconnecting(val receiver: ReceiverInfo, val attempt: Int, val streaming: Boolean) : SessionState

    data class Failed(val error: SessionError, val detail: String? = null) : SessionState
}

val SessionState.isStreaming: Boolean
    get() = (this is SessionState.Connected && video != null) || (this is SessionState.Reconnecting && streaming)

val SessionState.isActive: Boolean
    get() = this is SessionState.Connecting || this is SessionState.Negotiating ||
        this is SessionState.Connected || this is SessionState.Reconnecting

/** Every failure the user can see. The app maps each to actionable text (TROUBLESHOOTING.md). */
enum class SessionError {
    NO_WIFI,
    UNREACHABLE,
    REFUSED,
    CERTIFICATE_MISMATCH,
    HANDSHAKE_REJECTED,
    PROTOCOL_UNSUPPORTED,
    TOKEN_INVALID,
    TOKEN_EXPIRED,
    SESSION_BUSY,
    RATE_LIMITED,
    HELLO_TIMEOUT,
    CODEC_UNSUPPORTED,
    MEDIA_UNREACHABLE,
    CONNECTION_LOST,
    RECEIVER_CLOSED,
    PROTOCOL_ERROR,
    INTERNAL,
    ;

    companion object {
        fun fromTransport(f: TransportFailure): SessionError = when (f) {
            TransportFailure.TIMEOUT -> UNREACHABLE
            TransportFailure.REFUSED -> REFUSED
            TransportFailure.CERTIFICATE_MISMATCH -> CERTIFICATE_MISMATCH
            TransportFailure.HANDSHAKE_REJECTED -> HANDSHAKE_REJECTED
            TransportFailure.NO_NETWORK -> NO_WIFI
            TransportFailure.OTHER -> UNREACHABLE
        }

        fun fromProtocolCode(code: String): SessionError = when (code) {
            "PROTOCOL_UNSUPPORTED" -> PROTOCOL_UNSUPPORTED
            "TOKEN_INVALID" -> TOKEN_INVALID
            "TOKEN_EXPIRED" -> TOKEN_EXPIRED
            "SESSION_BUSY" -> SESSION_BUSY
            "RESUME_REJECTED" -> CONNECTION_LOST
            "RATE_LIMITED" -> RATE_LIMITED
            "HELLO_TIMEOUT" -> HELLO_TIMEOUT
            "CODEC_UNSUPPORTED" -> CODEC_UNSUPPORTED
            "MALFORMED_MESSAGE", "UNEXPECTED_MESSAGE", "MESSAGE_TOO_LARGE" -> PROTOCOL_ERROR
            else -> INTERNAL
        }
    }
}
