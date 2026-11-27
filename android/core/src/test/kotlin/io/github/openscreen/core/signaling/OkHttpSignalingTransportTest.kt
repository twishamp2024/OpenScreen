package io.github.openscreen.core.signaling

import io.github.openscreen.core.net.PinnedCertificateTrustManager
import io.github.openscreen.core.session.TransportFailure
import io.github.openscreen.core.session.TransportListener
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real TLS + WebSocket handshakes against a local server (no Android needed). */
class OkHttpSignalingTransportTest {
    private val cert = HeldCertificate.Builder().commonName("OpenScreen Receiver").addSubjectAlternativeName("127.0.0.1").build()
    private val server = MockWebServer()
    private val events = LinkedBlockingQueue<String>()

    private val listener = object : TransportListener {
        override fun onOpen(host: String) { events += "open:$host" }
        override fun onMessage(text: String) { events += "msg:$text" }
        override fun onClosed(code: Int, reason: String) { events += "closed:$code" }
        override fun onFailure(failure: TransportFailure, detail: String?) { events += "fail:$failure" }
    }

    @Before
    fun setUp() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun upgrade(protocol: String? = "openscreen.v1") {
        val builder = MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"bye","reason":"user"}""")
            }
        })
        if (protocol != null) builder.setHeader("Sec-WebSocket-Protocol", protocol)
        server.enqueue(builder.build())
    }

    private fun next(): String = events.poll(10, TimeUnit.SECONDS) ?: "timeout"

    private val goodPin get() = PinnedCertificateTrustManager.fingerprintOf(cert.certificate.encoded)

    @Test
    fun `connects when the certificate matches the pin and requests the subprotocol`() {
        upgrade()
        val conn = OkHttpSignalingTransport().open(listOf("127.0.0.1"), server.port, goodPin, listener)
        assertEquals("open:127.0.0.1", next())
        assertEquals("""msg:{"type":"bye","reason":"user"}""", next())
        val request = server.takeRequest()
        assertEquals("/v1/signal", request.url.encodedPath)
        assertEquals("openscreen.v1", request.headers["Sec-WebSocket-Protocol"])
        conn.close(1000, "done")
    }

    @Test
    fun `rejects a server whose certificate does not match`() {
        upgrade()
        val otherPin = PinnedCertificateTrustManager.fingerprintOf(HeldCertificate.Builder().build().certificate.encoded)
        OkHttpSignalingTransport().open(listOf("127.0.0.1"), server.port, otherPin, listener)
        assertEquals("fail:CERTIFICATE_MISMATCH", next())
    }

    @Test
    fun `rejects a server that does not confirm the subprotocol`() {
        upgrade(protocol = null)
        OkHttpSignalingTransport().open(listOf("127.0.0.1"), server.port, goodPin, listener)
        assertEquals("fail:HANDSHAKE_REJECTED", next())
    }

    @Test
    fun `reports refused when nothing listens, after trying every host`() {
        val freePort = ServerSocket(0).use { it.localPort }
        OkHttpSignalingTransport().open(listOf("127.0.0.1", "127.0.0.1"), freePort, goodPin, listener)
        assertEquals("fail:REFUSED", next())
    }
}
