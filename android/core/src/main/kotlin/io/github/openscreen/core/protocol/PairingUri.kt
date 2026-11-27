package io.github.openscreen.core.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Everything the phone needs from the QR code to reach and authenticate the PC. */
data class PairingInfo(
    val version: Int,
    /** Private IPv4 addresses in preference order. */
    val hosts: List<String>,
    val port: Int,
    /** 32-byte pairing token, base64url without padding. */
    val token: String,
    /** SHA-256 of the receiver's TLS certificate (DER), base64url without padding. */
    val fingerprint: String,
    /** Optional receiver display name. */
    val name: String?,
)

/** Reasons a scanned QR code is rejected, checked in this order (docs/PROTOCOL.md §2). */
enum class PairingUriError {
    NOT_A_PAIRING_URI,
    DUPLICATE_PARAM,
    MISSING_PARAM,
    BAD_VERSION,
    UNSUPPORTED_VERSION,
    BAD_HOST,
    BAD_PORT,
    BAD_TOKEN,
    BAD_FINGERPRINT,
    BAD_NAME,
}

sealed interface PairingUriResult {
    data class Valid(val info: PairingInfo) : PairingUriResult
    data class Invalid(val error: PairingUriError) : PairingUriResult
}

/**
 * Parses `openscreen://pair?...` URIs.
 *
 * Deliberately does not use `android.net.Uri` or `java.net.URI`: their
 * decoding rules (for example `+` as space) differ from the spec, and this
 * object must stay plain JVM code so it can be tested against the shared
 * fixtures without an emulator.
 */
object PairingUri {
    private val KNOWN = setOf("v", "h", "p", "t", "f", "n")
    private val REQUIRED = listOf("v", "h", "p", "t", "f")
    private val B64URL_32 = Regex("^[A-Za-z0-9_-]{43}$")
    private val DECIMAL = Regex("^[1-9][0-9]{0,8}$")
    private val OCTET = Regex("^(0|[1-9][0-9]{0,2})$")
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F]")

    fun parse(uri: String, supportedVersion: Int = ProtocolConstants.PROTOCOL_VERSION): PairingUriResult =
        try {
            PairingUriResult.Valid(parseOrThrow(uri, supportedVersion))
        } catch (e: InvalidPairingUri) {
            PairingUriResult.Invalid(e.error)
        }

    /** RFC 1918 ranges only: 10/8, 172.16/12, 192.168/16. */
    fun isPrivateIpv4(octets: IntArray): Boolean {
        val a = octets[0]
        val b = octets[1]
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    private class InvalidPairingUri(val error: PairingUriError) : Exception(error.name)

    private fun fail(error: PairingUriError): Nothing = throw InvalidPairingUri(error)

    private fun parseOrThrow(uri: String, supportedVersion: Int): PairingInfo {
        val sep = uri.indexOf("://")
        if (sep < 0 || !uri.substring(0, sep).equals(ProtocolConstants.URI_SCHEME, ignoreCase = true)) {
            fail(PairingUriError.NOT_A_PAIRING_URI)
        }
        val rest = uri.substring(sep + 3)
        val q = rest.indexOf('?')
        val host = if (q < 0) rest else rest.substring(0, q)
        if (!host.equals(ProtocolConstants.URI_HOST, ignoreCase = true)) fail(PairingUriError.NOT_A_PAIRING_URI)
        val query = if (q < 0) "" else rest.substring(q + 1)

        // A null value means "present but not decodable".
        val params = HashMap<String, String?>()
        for (segment in query.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            val key = if (eq < 0) segment else segment.substring(0, eq)
            val raw = if (eq < 0) "" else segment.substring(eq + 1)
            if (key !in KNOWN) continue
            if (key in params) fail(PairingUriError.DUPLICATE_PARAM)
            params[key] = percentDecode(raw)
        }
        for (key in REQUIRED) if (key !in params) fail(PairingUriError.MISSING_PARAM)

        val v = params["v"]
        if (v == null || !DECIMAL.matches(v)) fail(PairingUriError.BAD_VERSION)
        val version = v.toInt()
        if (version > supportedVersion) fail(PairingUriError.UNSUPPORTED_VERSION)

        val hosts = (params["h"] ?: fail(PairingUriError.BAD_HOST)).split(',')
        if (hosts.size > ProtocolConstants.MAX_HOSTS) fail(PairingUriError.BAD_HOST)
        for (entry in hosts) {
            val octets = parseIpv4(entry) ?: fail(PairingUriError.BAD_HOST)
            if (!isPrivateIpv4(octets)) fail(PairingUriError.BAD_HOST)
        }

        val p = params["p"]
        if (p == null || !DECIMAL.matches(p) || p.toInt() > 65_535) fail(PairingUriError.BAD_PORT)

        val token = params["t"]
        if (token == null || !B64URL_32.matches(token)) fail(PairingUriError.BAD_TOKEN)
        val fingerprint = params["f"]
        if (fingerprint == null || !B64URL_32.matches(fingerprint)) fail(PairingUriError.BAD_FINGERPRINT)

        var name: String? = null
        if ("n" in params) {
            val n = params["n"] ?: fail(PairingUriError.BAD_NAME)
            val length = n.codePointCount(0, n.length)
            if (length < 1 || length > ProtocolConstants.MAX_NAME_LENGTH || CONTROL.containsMatchIn(n)) {
                fail(PairingUriError.BAD_NAME)
            }
            name = n
        }

        return PairingInfo(version, hosts, p.toInt(), token, fingerprint, name)
    }

    private fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4 || !parts.all { OCTET.matches(it) }) return null
        val octets = IntArray(4) { parts[it].toInt() }
        return if (octets.all { it <= 255 }) octets else null
    }

    /** Strict percent-decoding to UTF-8; `+` stays `+`. Returns null on malformed input. */
    private fun percentDecode(value: String): String? {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(c) && i + 1 < value.length) i + 2 else i + 1
                out.write(value.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
