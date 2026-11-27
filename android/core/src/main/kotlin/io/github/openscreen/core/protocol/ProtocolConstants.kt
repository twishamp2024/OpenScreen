package io.github.openscreen.core.protocol

/**
 * Protocol constants. Values must match `shared/protocol/constants.json`;
 * `ProtocolConstantsTest` fails if they drift apart.
 */
object ProtocolConstants {
    const val PROTOCOL_VERSION: Int = 1
    const val URI_SCHEME: String = "openscreen"
    const val URI_HOST: String = "pair"
    const val MAX_HOSTS: Int = 4
    const val MAX_NAME_LENGTH: Int = 32

    const val WEBSOCKET_PATH: String = "/v1/signal"
    const val WEBSOCKET_SUBPROTOCOL: String = "openscreen.v1"
    const val DEFAULT_PORT: Int = 47820

    const val MAX_MESSAGE_BYTES: Int = 65_536
    const val MAX_SDP_CHARS: Int = 32_768
    const val MAX_CANDIDATE_CHARS: Int = 1_024

    const val HELLO_TIMEOUT_MS: Long = 10_000
    const val RESUME_WINDOW_MS: Long = 60_000
    const val PING_INTERVAL_MS: Long = 5_000
    const val PEER_DEAD_AFTER_MS: Long = 15_000
    const val TCP_CONNECT_PER_HOST_MS: Long = 3_000
    const val ICE_DISCONNECTED_GRACE_MS: Long = 5_000

    const val RECONNECT_BACKOFF_INITIAL_MS: Long = 500
    const val RECONNECT_BACKOFF_MAX_MS: Long = 8_000
}
