package io.github.openscreen.core.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Signaling messages (docs/PROTOCOL.md §4). Field constraints are enforced by
 * [validate]; `SignalingCodec` rejects anything the shared JSON schema rejects.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface SignalingMessage {
    fun validate(): String?
}

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val appVersion: String,
)

@Serializable
data class DisplayInfo(val width: Int, val height: Int, val densityDpi: Int, val rotation: Int)

@Serializable
data class Capabilities(val codecs: List<String>, val maxFps: Int)

@Serializable
data class ReceiverInfo(val name: String, val os: String, val appVersion: String)

@Serializable
data class VideoInfo(val width: Int, val height: Int, val fps: Int, val codec: String, val rotation: Int)

@Serializable
data class IceCandidatePayload(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?)

@Serializable
data class VersionRange(val min: Int, val max: Int)

// ── Sender → receiver ──────────────────────────────────────────────

@Serializable
@SerialName("hello")
data class Hello(
    val protocol: Int,
    val token: String,
    val device: DeviceInfo,
    val display: DisplayInfo,
    val capabilities: Capabilities,
    val features: List<String> = emptyList(),
) : SignalingMessage {
    override fun validate(): String? = firstError(
        check(protocol >= 1, "protocol"),
        check(isToken(token), "token"),
        check(device.manufacturer.len() in 1..64, "device.manufacturer"),
        check(device.model.len() in 1..64, "device.model"),
        check(device.androidVersion.len() in 1..16, "device.androidVersion"),
        check(device.sdkInt in 29..100, "device.sdkInt"),
        check(device.appVersion.len() in 1..32, "device.appVersion"),
        check(display.width in PIXELS && display.height in PIXELS, "display size"),
        check(display.densityDpi in 1..2000, "display.densityDpi"),
        check(display.rotation in ROTATIONS, "display.rotation"),
        check(
            capabilities.codecs.size in 1..8 &&
                capabilities.codecs.all { it in CODECS } &&
                capabilities.codecs.toSet().size == capabilities.codecs.size,
            "capabilities.codecs",
        ),
        check(capabilities.maxFps in 1..240, "capabilities.maxFps"),
        validateFeatures(features),
    )
}

@Serializable
@SerialName("resume")
data class Resume(val protocol: Int, val sessionId: String, val resumeToken: String) : SignalingMessage {
    override fun validate(): String? = firstError(
        check(protocol >= 1, "protocol"),
        check(isSessionId(sessionId), "sessionId"),
        check(isToken(resumeToken), "resumeToken"),
    )
}

@Serializable
@SerialName("offer")
data class Offer(val sdp: String, val iceRestart: Boolean = false) : SignalingMessage {
    override fun validate(): String? = check(isSdp(sdp), "sdp")
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("stream-state")
data class StreamState(
    val state: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val video: VideoInfo? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reason: String? = null,
) : SignalingMessage {
    override fun validate(): String? = firstError(
        check(state in STREAM_STATES, "state"),
        check(state != STATE_STREAMING || video != null, "video required when streaming"),
        video?.let {
            firstError(
                check(it.width in PIXELS && it.height in PIXELS, "video size"),
                check(it.fps in 1..240, "video.fps"),
                check(it.codec in CODECS, "video.codec"),
                check(it.rotation in ROTATIONS, "video.rotation"),
            )
        },
        check(reason == null || reason in STOP_REASONS, "reason"),
    )

    companion object {
        const val STATE_READY = "ready"
        const val STATE_STREAMING = "streaming"
        const val STATE_PAUSED = "paused"
        const val STATE_STOPPED = "stopped"
    }
}

// ── Receiver → sender ──────────────────────────────────────────────

@Serializable
@SerialName("welcome")
data class Welcome(
    val protocol: Int,
    val sessionId: String,
    val resumeToken: String,
    val resumeWindowMs: Long,
    val receiver: ReceiverInfo,
    val features: List<String> = emptyList(),
) : SignalingMessage {
    override fun validate(): String? = firstError(
        check(protocol >= 1, "protocol"),
        check(isSessionId(sessionId), "sessionId"),
        check(isToken(resumeToken), "resumeToken"),
        check(resumeWindowMs in 0..300_000, "resumeWindowMs"),
        check(isDisplayName(receiver.name), "receiver.name"),
        check(receiver.os == "windows", "receiver.os"),
        check(receiver.appVersion.len() in 1..32, "receiver.appVersion"),
        validateFeatures(features),
    )
}

@Serializable
@SerialName("resumed")
data class Resumed(val sessionId: String, val resumeToken: String, val resumeWindowMs: Long) : SignalingMessage {
    override fun validate(): String? = firstError(
        check(isSessionId(sessionId), "sessionId"),
        check(isToken(resumeToken), "resumeToken"),
        check(resumeWindowMs in 0..300_000, "resumeWindowMs"),
    )
}

@Serializable
@SerialName("answer")
data class Answer(val sdp: String) : SignalingMessage {
    override fun validate(): String? = check(isSdp(sdp), "sdp")
}

// ── Both directions ────────────────────────────────────────────────

@Serializable
@SerialName("ice")
data class Ice(val candidate: IceCandidatePayload?) : SignalingMessage {
    override fun validate(): String? = candidate?.let {
        firstError(
            check(it.candidate.length <= ProtocolConstants.MAX_CANDIDATE_CHARS, "candidate"),
            check(it.sdpMid == null || it.sdpMid.length <= 32, "sdpMid"),
            check(it.sdpMLineIndex == null || it.sdpMLineIndex in 0..15, "sdpMLineIndex"),
            check(it.sdpMid != null || it.sdpMLineIndex != null, "sdpMid or sdpMLineIndex required"),
        )
    }
}

@Serializable
@SerialName("bye")
data class Bye(val reason: String) : SignalingMessage {
    override fun validate(): String? = check(reason in BYE_REASONS, "reason")
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("error")
data class ErrorMessage(
    val code: String,
    val message: String,
    val fatal: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val supported: VersionRange? = null,
) : SignalingMessage {
    override fun validate(): String? = firstError(
        check(code in ERROR_CODES, "code"),
        check(message.length <= 256, "message"),
        supported?.let { check(it.min >= 1 && it.max >= 1, "supported") },
    )
}

// ── Codec ──────────────────────────────────────────────────────────

sealed interface DecodeResult {
    data class Ok(val message: SignalingMessage) : DecodeResult
    data class Invalid(val reason: String) : DecodeResult
}

object SignalingCodec {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true // PROTOCOL.md §4: unknown fields are ignored…
        explicitNulls = true
        encodeDefaults = true
        isLenient = false
        coerceInputValues = false
        allowTrailingComma = false
    }

    fun encode(message: SignalingMessage): String {
        message.validate()?.let { throw IllegalArgumentException("Refusing to send invalid message: $it") }
        return json.encodeToString(SignalingMessage.serializer(), message)
    }

    fun decode(text: String): DecodeResult {
        if (text.toByteArray(Charsets.UTF_8).size > ProtocolConstants.MAX_MESSAGE_BYTES) {
            return DecodeResult.Invalid("message too large")
        }
        val message = try {
            json.decodeFromString(SignalingMessage.serializer(), text) // …unknown types are not.
        } catch (e: SerializationException) {
            return DecodeResult.Invalid(e.message ?: "malformed")
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Invalid(e.message ?: "malformed")
        }
        return message.validate()?.let { DecodeResult.Invalid(it) } ?: DecodeResult.Ok(message)
    }
}

// ── Validation helpers ─────────────────────────────────────────────

private val PIXELS = 1..16_384
private val ROTATIONS = setOf(0, 90, 180, 270)
private val CODECS = setOf("H264", "VP8", "VP9", "AV1")
private val STREAM_STATES = setOf("ready", "streaming", "paused", "stopped")
private val STOP_REASONS = setOf("user", "projection-revoked", "encoder-error", "app-backgrounded")
private val BYE_REASONS = setOf("user", "receiver-closed", "sender-closed", "timeout", "error")
val ERROR_CODES = setOf(
    "PROTOCOL_UNSUPPORTED", "TOKEN_INVALID", "TOKEN_EXPIRED", "SESSION_BUSY", "RESUME_REJECTED",
    "MALFORMED_MESSAGE", "UNEXPECTED_MESSAGE", "MESSAGE_TOO_LARGE", "RATE_LIMITED", "HELLO_TIMEOUT",
    "CODEC_UNSUPPORTED", "INTERNAL_ERROR",
)
private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
private val SESSION_ID = Regex("^[A-Za-z0-9_-]{22}$")
private val FEATURE = Regex("^[a-z0-9][a-z0-9.-]*$")
private val CONTROL = Regex("[\\u0000-\\u001F\\u007F]")

private fun String.len(): Int = codePointCount(0, length)
private fun check(ok: Boolean, field: String): String? = if (ok) null else "invalid $field"
private fun firstError(vararg errors: String?): String? = errors.firstOrNull { it != null }
private fun isToken(s: String) = TOKEN.matches(s)
private fun isSessionId(s: String) = SESSION_ID.matches(s)
private fun isSdp(s: String) = s.len() in 3..ProtocolConstants.MAX_SDP_CHARS && s.startsWith("v=0")
private fun isDisplayName(s: String) = s.len() in 1..ProtocolConstants.MAX_NAME_LENGTH && !CONTROL.containsMatchIn(s)
private fun validateFeatures(f: List<String>): String? = check(
    f.size <= 16 && f.toSet().size == f.size && f.all { it.len() in 1..32 && FEATURE.matches(it) },
    "features",
)
