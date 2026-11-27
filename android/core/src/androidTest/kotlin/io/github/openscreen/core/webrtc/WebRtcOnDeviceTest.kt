package io.github.openscreen.core.webrtc

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.openscreen.core.protocol.IceCandidatePayload
import io.github.openscreen.core.session.IceState
import io.github.openscreen.core.session.PeerListener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Runs the real libwebrtc on a device (`./gradlew :core:connectedAndroidTest`).
 * Catches what JVM tests cannot: native library loading order, the device's
 * encoder list, and the shape of a real offer.
 */
@RunWith(AndroidJUnit4::class)
class WebRtcOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun engineInitializesAndReportsAVideoCodec() {
        val engine = WebRtcEngine.get(context)
        val codecs = engine.supportedCodecs()
        assertTrue("no encoder codecs: $codecs", codecs.isNotEmpty())
        assertTrue("VP8 software fallback missing: $codecs", "VP8" in codecs)
        val prefs = engine.videoCodecPreferences().map { it.name.uppercase() }
        Log.i("OpenScreenTest", "encoder codecs=$codecs preferences=$prefs")
        assertTrue("codec preferences empty", prefs.isNotEmpty())
        if ("H264" in codecs) assertTrue("H.264 must be preferred first: $prefs", prefs.first() == "H264")
    }

    @Test
    fun offerIsSendOnlyVideoWithoutDataOrAudio() = runBlocking {
        val candidates = CopyOnWriteArrayList<IceCandidatePayload?>()
        val peer = WebRtcPeer(
            WebRtcEngine.get(context),
            object : PeerListener {
                override fun onLocalCandidate(candidate: IceCandidatePayload?) {
                    candidates += candidate
                }
                override fun onIceState(state: IceState) = Unit
            },
        )
        try {
            val sdp = peer.createOffer(iceRestart = false)
            sdp.lines().filter { it.startsWith("m=video") || it.startsWith("a=rtpmap") }
                .forEach { Log.i("OpenScreenTest", "offer: $it") }
            assertTrue(sdp.startsWith("v=0"))
            // The preference list being right is not enough: check what the offer actually says.
            if ("H264" in WebRtcEngine.get(context).supportedCodecs()) {
                assertTrue("offer must lead with H.264: $sdp", SdpCodec.negotiatedVideoCodec(sdp) == "H264")
                assertTrue("no H.265/AV1/VP9 offered", listOf("H265", "AV1", "VP9").none { "rtpmap:" in sdp && " $it/" in sdp })
            }
            assertTrue("one video m-line", sdp.lines().count { it.startsWith("m=") } == 1 && "m=video" in sdp)
            assertTrue("sendonly", "a=sendonly" in sdp)
            assertTrue("no data channel", "m=application" !in sdp)
            assertTrue("no audio", "m=audio" !in sdp)
            // Host candidates only (no STUN), gathered within a few seconds.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (candidates.none { it == null } && System.nanoTime() < deadline) Thread.sleep(100)
            val lines = candidates.filterNotNull().map { it.candidate }
            assertTrue("gathered no candidates", lines.isNotEmpty())
            assertTrue("only host candidates: $lines", lines.all { " typ host" in it })
        } finally {
            peer.close()
        }
    }
}
