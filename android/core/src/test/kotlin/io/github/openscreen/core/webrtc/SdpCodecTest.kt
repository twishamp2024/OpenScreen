package io.github.openscreen.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class SdpCodecTest {
    @Test
    fun `first payload type on the video line wins`() {
        val sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96 102\r\na=rtpmap:102 H264/90000\r\na=rtpmap:96 VP8/90000\r\n"
        assertEquals("VP8", SdpCodec.negotiatedVideoCodec(sdp))
    }

    @Test
    fun `skips rtx and unknown codecs`() {
        val sdp = "v=0\nm=video 9 UDP/TLS/RTP/SAVPF 97 102\na=rtpmap:97 rtx/90000\na=rtpmap:102 h264/90000\n"
        assertEquals("H264", SdpCodec.negotiatedVideoCodec(sdp))
    }

    @Test
    fun `no video line`() {
        assertEquals(null, SdpCodec.negotiatedVideoCodec("v=0\r\nm=audio 9 RTP 0\r\n"))
    }
}
