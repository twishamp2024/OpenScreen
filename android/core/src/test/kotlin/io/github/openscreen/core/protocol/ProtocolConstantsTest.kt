package io.github.openscreen.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fails when ProtocolConstants drifts from shared/protocol/constants.json. */
class ProtocolConstantsTest {
    private val c = SharedFixtures.json("constants.json")
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `constants match the shared definition`() {
        assertEquals(c.int("protocolVersion"), ProtocolConstants.PROTOCOL_VERSION)

        val uri = c.obj("pairingUri")
        assertEquals(uri.str("scheme"), ProtocolConstants.URI_SCHEME)
        assertEquals(uri.str("host"), ProtocolConstants.URI_HOST)
        assertEquals(uri.int("maxHosts"), ProtocolConstants.MAX_HOSTS)
        assertEquals(uri.int("maxNameLength"), ProtocolConstants.MAX_NAME_LENGTH)

        val ws = c.obj("websocket")
        assertEquals(ws.str("path"), ProtocolConstants.WEBSOCKET_PATH)
        assertEquals(ws.str("subprotocol"), ProtocolConstants.WEBSOCKET_SUBPROTOCOL)
        assertEquals(c.obj("ports").int("default"), ProtocolConstants.DEFAULT_PORT)

        val limits = c.obj("limits")
        assertEquals(limits.int("maxMessageBytes"), ProtocolConstants.MAX_MESSAGE_BYTES)
        assertEquals(limits.int("maxSdpChars"), ProtocolConstants.MAX_SDP_CHARS)
        assertEquals(limits.int("maxCandidateChars"), ProtocolConstants.MAX_CANDIDATE_CHARS)

        val t = c.obj("timeoutsMs")
        assertEquals(t.long("helloTimeout"), ProtocolConstants.HELLO_TIMEOUT_MS)
        assertEquals(t.long("resumeWindow"), ProtocolConstants.RESUME_WINDOW_MS)
        assertEquals(t.long("pingInterval"), ProtocolConstants.PING_INTERVAL_MS)
        assertEquals(t.long("peerDeadAfter"), ProtocolConstants.PEER_DEAD_AFTER_MS)
        assertEquals(t.long("tcpConnectPerHost"), ProtocolConstants.TCP_CONNECT_PER_HOST_MS)
        assertEquals(t.long("iceDisconnectedGrace"), ProtocolConstants.ICE_DISCONNECTED_GRACE_MS)

        val backoff = c.obj("reconnectBackoffMs")
        assertEquals(backoff.long("initial"), ProtocolConstants.RECONNECT_BACKOFF_INITIAL_MS)
        assertEquals(backoff.long("max"), ProtocolConstants.RECONNECT_BACKOFF_MAX_MS)
    }
}
