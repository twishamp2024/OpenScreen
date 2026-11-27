package io.github.openscreen.sender.ui

import androidx.annotation.StringRes
import io.github.openscreen.core.protocol.PairingUriError
import io.github.openscreen.core.session.SessionError
import io.github.openscreen.sender.R

/** Title + actionable explanation for each failure (docs/TROUBLESHOOTING.md is the source of these texts). */
data class UserMessage(@StringRes val title: Int, @StringRes val body: Int)

fun SessionError.message(): UserMessage = when (this) {
    SessionError.NO_WIFI -> UserMessage(R.string.err_no_wifi_title, R.string.err_no_wifi_body)
    SessionError.UNREACHABLE -> UserMessage(R.string.err_unreachable_title, R.string.err_unreachable_body)
    SessionError.REFUSED -> UserMessage(R.string.err_refused_title, R.string.err_refused_body)
    SessionError.CERTIFICATE_MISMATCH -> UserMessage(R.string.err_cert_title, R.string.err_cert_body)
    SessionError.HANDSHAKE_REJECTED -> UserMessage(R.string.err_handshake_title, R.string.err_handshake_body)
    SessionError.PROTOCOL_UNSUPPORTED -> UserMessage(R.string.err_version_title, R.string.err_version_body)
    SessionError.TOKEN_INVALID -> UserMessage(R.string.err_token_invalid_title, R.string.err_token_invalid_body)
    SessionError.TOKEN_EXPIRED -> UserMessage(R.string.err_token_expired_title, R.string.err_token_expired_body)
    SessionError.SESSION_BUSY -> UserMessage(R.string.err_busy_title, R.string.err_busy_body)
    SessionError.RATE_LIMITED -> UserMessage(R.string.err_rate_title, R.string.err_rate_body)
    SessionError.HELLO_TIMEOUT -> UserMessage(R.string.err_hello_title, R.string.err_hello_body)
    SessionError.CODEC_UNSUPPORTED -> UserMessage(R.string.err_codec_title, R.string.err_codec_body)
    SessionError.MEDIA_UNREACHABLE -> UserMessage(R.string.err_media_title, R.string.err_media_body)
    SessionError.CONNECTION_LOST -> UserMessage(R.string.err_lost_title, R.string.err_lost_body)
    SessionError.RECEIVER_CLOSED -> UserMessage(R.string.err_closed_title, R.string.err_closed_body)
    SessionError.PROTOCOL_ERROR -> UserMessage(R.string.err_protocol_title, R.string.err_protocol_body)
    SessionError.INTERNAL -> UserMessage(R.string.err_internal_title, R.string.err_internal_body)
}

@StringRes
fun PairingUriError.message(): Int = when (this) {
    PairingUriError.NOT_A_PAIRING_URI -> R.string.qr_not_openscreen
    PairingUriError.UNSUPPORTED_VERSION -> R.string.qr_update_phone
    else -> R.string.qr_damaged
}
