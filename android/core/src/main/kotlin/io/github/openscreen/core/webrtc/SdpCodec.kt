package io.github.openscreen.core.webrtc

/** Small, pure SDP helpers (unit tested without org.webrtc). */
object SdpCodec {
    private val KNOWN = mapOf("H264" to "H264", "VP8" to "VP8", "VP9" to "VP9", "AV1" to "AV1")

    /**
     * The codec the answerer picked: the first payload type on the `m=video`
     * line, resolved through its `a=rtpmap`. Returns null if not found.
     */
    fun negotiatedVideoCodec(sdp: String): String? {
        val lines = sdp.split("\r\n", "\n")
        val mLine = lines.firstOrNull { it.startsWith("m=video ") } ?: return null
        val payloadTypes = mLine.split(' ').drop(3)
        for (pt in payloadTypes) {
            val rtpmap = lines.firstOrNull { it.startsWith("a=rtpmap:$pt ") } ?: continue
            val name = rtpmap.substringAfter(' ').substringBefore('/').uppercase()
            KNOWN[name]?.let { return it }
        }
        return null
    }
}
