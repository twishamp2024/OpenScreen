package io.github.openscreen.core.signaling

import io.github.openscreen.core.net.PinnedCertificateTrustManager
import io.github.openscreen.core.protocol.ProtocolConstants
import io.github.openscreen.core.session.SignalingTransport
import io.github.openscreen.core.session.TransportConnection
import io.github.openscreen.core.session.TransportFailure
import io.github.openscreen.core.session.TransportListener
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/**
 * `wss://<host>:<port>/v1/signal` with the receiver's certificate pinned to
 * the QR fingerprint (docs/PROTOCOL.md §3). Hosts are tried in order.
 *
 * @param socketFactory returns the Wi-Fi network's socket factory so LAN
 *   traffic never goes out over cellular when Wi-Fi has no Internet.
 */
class OkHttpSignalingTransport(
    private val socketFactory: () -> SocketFactory? = { null },
    private val connectTimeoutMs: Long = ProtocolConstants.TCP_CONNECT_PER_HOST_MS,
    private val pingIntervalMs: Long = ProtocolConstants.PING_INTERVAL_MS,
) : SignalingTransport {

    override fun open(
        hosts: List<String>,
        port: Int,
        fingerprint: String,
        listener: TransportListener,
    ): TransportConnection = Attempt(hosts, port, fingerprint, listener).also { it.next() }

    private inner class Attempt(
        private val hosts: List<String>,
        private val port: Int,
        fingerprint: String,
        private val listener: TransportListener,
    ) : TransportConnection {
        private val trustManager = PinnedCertificateTrustManager(fingerprint)
        private val client: OkHttpClient = buildClient(trustManager)
        private var index = 0
        private var worst: TransportFailure? = null
        private var worstDetail: String? = null
        private val closed = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)

        @Volatile
        private var socket: WebSocket? = null

        fun next() {
            if (closed.get()) return
            if (index >= hosts.size) {
                if (finished.compareAndSet(false, true)) {
                    listener.onFailure(worst ?: TransportFailure.TIMEOUT, worstDetail)
                }
                client.dispatcher.executorService.shutdown()
                return
            }
            val host = hosts[index++]
            val request = Request.Builder()
                .url("wss://$host:$port${ProtocolConstants.WEBSOCKET_PATH}")
                .header("Sec-WebSocket-Protocol", ProtocolConstants.WEBSOCKET_SUBPROTOCOL)
                .build()
            socket = client.newWebSocket(request, HostListener(host))
        }

        override fun send(text: String): Boolean = socket?.send(text) ?: false

        override fun close(code: Int, reason: String) {
            if (!closed.compareAndSet(false, true)) return
            socket?.close(code, reason) ?: socket?.cancel()
            client.dispatcher.executorService.shutdown()
        }

        private fun record(f: TransportFailure, detail: String?) {
            if (worst == null || PRIORITY.indexOf(f) < PRIORITY.indexOf(worst)) {
                worst = f
                worstDetail = detail
            }
        }

        private inner class HostListener(private val host: String) : WebSocketListener() {
            private var opened = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.header("Sec-WebSocket-Protocol") != ProtocolConstants.WEBSOCKET_SUBPROTOCOL) {
                    webSocket.cancel()
                    record(TransportFailure.HANDSHAKE_REJECTED, "missing subprotocol")
                    return next()
                }
                opened = true
                if (closed.get()) return webSocket.cancel()
                finished.set(true)
                listener.onOpen(host)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!closed.get()) listener.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary frames are a protocol error (PROTOCOL.md §3).
                webSocket.close(1003, "binary frames not allowed")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.get()) listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed.get()) return
                if (opened) return listener.onFailure(TransportFailure.OTHER, t.message)
                val failure = classify(t, response, trustManager)
                if (failure == TransportFailure.CERTIFICATE_MISMATCH) {
                    // Do not try other hosts: someone answered with the wrong identity.
                    if (finished.compareAndSet(false, true)) listener.onFailure(failure, t.message)
                    return
                }
                record(failure, "$host: ${t.message}")
                next()
            }
        }
    }

    private fun buildClient(trustManager: PinnedCertificateTrustManager): OkHttpClient {
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // liveness comes from pings
            .pingInterval(pingIntervalMs, TimeUnit.MILLISECONDS)
            .sslSocketFactory(tls.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // identity is the pinned certificate, not a hostname
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
            .retryOnConnectionFailure(false)
        socketFactory()?.let { builder.socketFactory(it) }
        return builder.build()
    }

    companion object {
        private val PRIORITY = listOf(
            TransportFailure.CERTIFICATE_MISMATCH,
            TransportFailure.HANDSHAKE_REJECTED,
            TransportFailure.REFUSED,
            TransportFailure.NO_NETWORK,
            TransportFailure.TIMEOUT,
            TransportFailure.OTHER,
        )

        /**
         * Maps a connect failure to what the user should do. OkHttp wraps the
         * OS error (e.g. "Failed to connect to /x" caused by "Connection
         * refused"), so the whole cause chain is inspected.
         */
        internal fun classify(t: Throwable, response: Response?, tm: PinnedCertificateTrustManager): TransportFailure {
            val chain = generateSequence(t) { it.cause }.take(8).toList()
            val text = chain.joinToString(" | ") { it.message.orEmpty() }
            return when {
                tm.lastMismatch -> TransportFailure.CERTIFICATE_MISMATCH
                response != null -> TransportFailure.HANDSHAKE_REJECTED
                text.contains("refused", ignoreCase = true) -> TransportFailure.REFUSED
                text.contains("ENETUNREACH") || text.contains("Network is unreachable", true) -> TransportFailure.NO_NETWORK
                chain.any { it is SocketTimeoutException || it is NoRouteToHostException } -> TransportFailure.TIMEOUT
                chain.any { it is ConnectException } -> TransportFailure.TIMEOUT
                chain.any { it is SSLException } -> TransportFailure.HANDSHAKE_REJECTED
                else -> TransportFailure.OTHER
            }
        }
    }
}
