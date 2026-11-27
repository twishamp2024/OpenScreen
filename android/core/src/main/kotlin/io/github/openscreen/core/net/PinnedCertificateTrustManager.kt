package io.github.openscreen.core.net

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

/**
 * Accepts exactly one server certificate: the one whose SHA-256 (over the DER
 * encoding) equals the fingerprint from the QR code (docs/PROTOCOL.md §3).
 *
 * CA chains and hostnames are deliberately ignored — the receiver's
 * certificate is self-signed and ephemeral, and the QR code is the trust
 * anchor. Comparison is constant-time.
 */
@SuppressLint("CustomX509TrustManager")
class PinnedCertificateTrustManager(fingerprintB64Url: String) : X509TrustManager {
    private val expected: ByteArray = try {
        Base64.getUrlDecoder().decode(fingerprintB64Url)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("fingerprint is not base64url", e)
    }.also { require(it.size == 32) { "fingerprint must be 32 bytes" } }

    /** Set when the last handshake failed because of a pin mismatch (used for a precise error message). */
    @Volatile
    var lastMismatch: Boolean = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
        val actual = sha256(leaf.encoded)
        if (!MessageDigest.isEqual(actual, expected)) {
            lastMismatch = true
            throw CertificateException("server certificate does not match the paired PC")
        }
        lastMismatch = false
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun sha256(der: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(der)

        fun fingerprintOf(der: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(der))
    }
}
