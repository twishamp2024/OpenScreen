package io.github.openscreen.core.session

import io.github.openscreen.core.protocol.Answer
import io.github.openscreen.core.protocol.Bye
import io.github.openscreen.core.protocol.ErrorMessage
import io.github.openscreen.core.protocol.Hello
import io.github.openscreen.core.protocol.Ice
import io.github.openscreen.core.protocol.IceCandidatePayload
import io.github.openscreen.core.protocol.Offer
import io.github.openscreen.core.protocol.PairingInfo
import io.github.openscreen.core.protocol.ReceiverInfo
import io.github.openscreen.core.protocol.Resume
import io.github.openscreen.core.protocol.Resumed
import io.github.openscreen.core.protocol.StreamState
import io.github.openscreen.core.protocol.Welcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MirrorSessionTest {
    private val pairing = PairingInfo(
        version = 1,
        hosts = listOf("192.168.1.20", "10.0.0.5"),
        port = 47820,
        token = "lXPoAB1-XtnXbHZwLlJ13iMUH7T1NeqCWmA9FvHqSe8",
        fingerprint = "Pj4_A0Jni6caoMTewX711vX8StLStu7E1ZA-89W1rz8",
        name = "Study PC",
    )
    private val receiver = ReceiverInfo("Study PC", "windows", "0.1.0")
    private val sessionId = "Ek5zgusdfAdgwh5fyjpBYw"
    private val resume1 = "nn62UTUoym2gvUPDwDAU-ba7d5i5KIWycUUsxmw_VEM"
    private val resume2 = "LhXfL_phPyiuVSxpUqGox7RKOjFXJTT29h-uwr4GN5g"
    private val answerSdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 102 96\r\na=rtpmap:102 H264/90000\r\na=rtpmap:96 VP8/90000\r\n"

    private class Harness(scope: TestScope) {
        val transport = FakeTransport()
        val peers = FakePeerFactory()
        val session = MirrorSession(
            scope = scope.backgroundScope,
            transport = transport,
            peers = peers,
            environment = FakeEnvironment,
            clock = { scope.testScheduler.currentTime },
        )
        val state get() = session.state.value
    }

    private fun TestScope.connected(): Harness {
        val h = Harness(this)
        h.session.pair(pairing)
        runCurrent()
        h.transport.last.listener.onOpen("192.168.1.20")
        runCurrent()
        h.transport.last.receive(Welcome(1, sessionId, resume1, 60_000, receiver))
        runCurrent()
        h.transport.last.receive(Answer(answerSdp))
        runCurrent()
        h.peers.last.listener.onIceState(IceState.CONNECTED)
        runCurrent()
        return h
    }

    @Test
    fun `happy path pairs, negotiates, streams and stops`() = runTest {
        val h = Harness(this)
        h.session.pair(pairing)
        runCurrent()
        assertEquals(SessionState.Connecting("Study PC"), h.state)
        assertEquals(pairing.hosts, h.transport.last.hosts)

        h.transport.last.listener.onOpen("192.168.1.20")
        runCurrent()
        val hello = h.transport.last.sent.single() as Hello
        assertEquals(pairing.token, hello.token)
        assertEquals("Pixel 8", hello.device.model)

        h.transport.last.receive(Welcome(1, sessionId, resume1, 60_000, receiver))
        runCurrent()
        assertEquals(SessionState.Negotiating(receiver), h.state)
        val offer = h.transport.last.sent.last() as Offer
        assertEquals(false, offer.iceRestart)

        h.peers.last.listener.onLocalCandidate(IceCandidatePayload("candidate:1 1 udp 1 192.168.1.30 5000 typ host", "0", 0))
        h.transport.last.receive(Answer(answerSdp))
        h.transport.last.receive(Ice(IceCandidatePayload("candidate:2 1 udp 1 192.168.1.20 6000 typ host", "0", 0)))
        runCurrent()
        assertTrue(h.transport.last.sent.any { it is Ice })
        assertEquals(1, h.peers.last.answers.size)
        assertEquals(1, h.peers.last.remoteCandidates.size)

        h.peers.last.listener.onIceState(IceState.CONNECTED)
        runCurrent()
        assertEquals(SessionState.Connected(receiver, null), h.state)
        assertEquals(StreamState("ready"), h.transport.last.sent.last())

        val feed = FakeFeed()
        h.session.startStreaming(feed)
        runCurrent()
        assertTrue(h.state.isStreaming)
        assertEquals(feed, h.peers.last.attached)
        val streaming = h.transport.last.sent.last() as StreamState
        assertEquals("streaming", streaming.state)
        assertEquals("H264", streaming.video?.codec)

        h.session.stopStreaming()
        runCurrent()
        assertEquals(StreamState("stopped", reason = "user"), h.transport.last.sent.last())
        assertEquals(null, h.peers.last.attached)

        h.session.disconnect()
        runCurrent()
        assertEquals(Bye("user"), h.transport.last.sent.last())
        assertTrue(h.transport.last.closed)
        assertTrue(h.peers.last.closed)
        assertEquals(SessionState.Idle, h.state)
    }

    @Test
    fun `fatal receiver error is reported`() = runTest {
        val h = Harness(this)
        h.session.pair(pairing)
        runCurrent()
        h.transport.last.listener.onOpen("192.168.1.20")
        h.transport.last.receive(ErrorMessage("TOKEN_EXPIRED", "expired", fatal = true))
        runCurrent()
        assertEquals(SessionError.TOKEN_EXPIRED, (h.state as SessionState.Failed).error)
    }

    @Test
    fun `connection failure before pairing maps to an actionable error`() = runTest {
        val h = Harness(this)
        h.session.pair(pairing)
        runCurrent()
        h.transport.last.listener.onFailure(TransportFailure.TIMEOUT, "timed out")
        runCurrent()
        assertEquals(SessionError.UNREACHABLE, (h.state as SessionState.Failed).error)
        h.session.dismissError()
        runCurrent()
        assertEquals(SessionState.Idle, h.state)
    }

    @Test
    fun `no welcome within the timeout`() = runTest {
        val h = Harness(this)
        h.session.pair(pairing)
        runCurrent()
        h.transport.last.listener.onOpen("192.168.1.20")
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(SessionError.HELLO_TIMEOUT, (h.state as SessionState.Failed).error)
    }

    @Test
    fun `signaling drop resumes the session without re-pairing and keeps streaming`() = runTest {
        val h = connected()
        h.session.startStreaming(FakeFeed())
        runCurrent()

        h.transport.last.listener.onFailure(TransportFailure.OTHER, "reset")
        runCurrent()
        val reconnecting = h.state as SessionState.Reconnecting
        assertEquals(1, reconnecting.attempt)
        assertTrue(reconnecting.streaming)
        assertEquals(1, h.transport.connections.size)

        advanceTimeBy(501)
        runCurrent()
        assertEquals(2, h.transport.connections.size)
        h.transport.last.listener.onOpen("192.168.1.20")
        runCurrent()
        assertEquals(Resume(1, sessionId, resume1), h.transport.last.sent.single())

        h.transport.last.receive(Resumed(sessionId, resume2, 60_000))
        runCurrent()
        assertTrue(h.state is SessionState.Connected)
        assertTrue(h.state.isStreaming)
        assertEquals("streaming", (h.transport.last.sent.last() as StreamState).state)

        // The next drop must present the rotated resume token.
        h.transport.last.listener.onClosed(1006, "gone")
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        h.transport.last.listener.onOpen("192.168.1.20")
        runCurrent()
        assertEquals(Resume(1, sessionId, resume2), h.transport.last.sent.single())
    }

    @Test
    fun `resume after media loss performs an ICE restart`() = runTest {
        val h = connected()
        h.peers.last.listener.onIceState(IceState.DISCONNECTED)
        h.transport.last.listener.onFailure(TransportFailure.OTHER, "wifi off")
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        h.transport.last.listener.onOpen("192.168.1.20")
        h.transport.last.receive(Resumed(sessionId, resume2, 60_000))
        runCurrent()
        assertEquals(true, (h.transport.last.sent.last() as Offer).iceRestart)
        assertEquals(listOf(false, true), h.peers.last.restarts)
    }

    @Test
    fun `gives up when the resume window passes`() = runTest {
        val h = connected()
        h.transport.last.listener.onFailure(TransportFailure.OTHER, "gone")
        runCurrent()
        repeat(40) {
            advanceTimeBy(8_001)
            runCurrent()
            if (h.state is SessionState.Failed) return@repeat
            h.transport.last.listener.onFailure(TransportFailure.TIMEOUT, "still gone")
            runCurrent()
        }
        assertEquals(SessionError.CONNECTION_LOST, (h.state as SessionState.Failed).error)
        assertTrue(testScheduler.currentTime >= 60_000)
    }

    @Test
    fun `ICE failure triggers a restart offer`() = runTest {
        val h = connected()
        h.peers.last.listener.onIceState(IceState.FAILED)
        runCurrent()
        assertEquals(true, (h.transport.last.sent.last() as Offer).iceRestart)
    }

    @Test
    fun `ICE that never connects reports media unreachable`() = runTest {
        val h = Harness(this)
        h.session.pair(pairing)
        runCurrent()
        h.transport.last.listener.onOpen("192.168.1.20")
        h.transport.last.receive(Welcome(1, sessionId, resume1, 60_000, receiver))
        h.transport.last.receive(Answer(answerSdp))
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(SessionError.MEDIA_UNREACHABLE, (h.state as SessionState.Failed).error)
        assertEquals(Bye("user"), h.transport.last.sent.last())
    }

    @Test
    fun `malformed message ends the session as a protocol error`() = runTest {
        val h = connected()
        h.transport.last.listener.onMessage("""{"type":"touch","x":1}""")
        runCurrent()
        assertEquals(SessionError.PROTOCOL_ERROR, (h.state as SessionState.Failed).error)
    }

    @Test
    fun `unsolicited answer is a protocol error`() = runTest {
        val h = connected()
        h.transport.last.receive(Answer(answerSdp))
        runCurrent()
        assertEquals(SessionError.PROTOCOL_ERROR, (h.state as SessionState.Failed).error)
    }

    @Test
    fun `receiver bye ends the session`() = runTest {
        val h = connected()
        h.transport.last.receive(Bye("receiver-closed"))
        runCurrent()
        assertEquals(SessionError.RECEIVER_CLOSED, (h.state as SessionState.Failed).error)
        assertTrue(h.peers.last.closed)
    }
}
