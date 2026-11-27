package io.github.openscreen.core.session

import io.github.openscreen.core.protocol.Capabilities
import io.github.openscreen.core.protocol.DecodeResult
import io.github.openscreen.core.protocol.DeviceInfo
import io.github.openscreen.core.protocol.DisplayInfo
import io.github.openscreen.core.protocol.IceCandidatePayload
import io.github.openscreen.core.protocol.SignalingCodec
import io.github.openscreen.core.protocol.SignalingMessage
import io.github.openscreen.core.protocol.VideoInfo

class FakeConnection(val hosts: List<String>, val listener: TransportListener) : TransportConnection {
    val sentText = mutableListOf<String>()
    var closed = false
    val sent: List<SignalingMessage>
        get() = sentText.map { (SignalingCodec.decode(it) as DecodeResult.Ok).message }

    override fun send(text: String): Boolean {
        sentText += text
        return !closed
    }

    override fun close(code: Int, reason: String) {
        closed = true
    }

    fun receive(m: SignalingMessage) = listener.onMessage(SignalingCodec.encode(m))
}

class FakeTransport : SignalingTransport {
    val connections = mutableListOf<FakeConnection>()
    val last: FakeConnection get() = connections.last()

    override fun open(hosts: List<String>, port: Int, fingerprint: String, listener: TransportListener) =
        FakeConnection(hosts, listener).also { connections += it }
}

class FakePeer(val listener: PeerListener) : Peer {
    var offers = 0
    val restarts = mutableListOf<Boolean>()
    val answers = mutableListOf<String>()
    val remoteCandidates = mutableListOf<IceCandidatePayload?>()
    var attached: VideoFeed? = null
    var closed = false

    override suspend fun createOffer(iceRestart: Boolean): String {
        offers++
        restarts += iceRestart
        return "v=0\r\no=- $offers 1 IN IP4 0.0.0.0\r\n"
    }

    override suspend fun setAnswer(sdp: String) {
        answers += sdp
    }

    override fun addRemoteCandidate(candidate: IceCandidatePayload?) {
        remoteCandidates += candidate
    }

    override fun setVideo(feed: VideoFeed?) {
        attached = feed
    }

    override fun close() {
        closed = true
    }
}

class FakePeerFactory : PeerFactory {
    val peers = mutableListOf<FakePeer>()
    val last: FakePeer get() = peers.last()
    override fun create(listener: PeerListener): Peer = FakePeer(listener).also { peers += it }
}

object FakeEnvironment : DeviceEnvironment {
    override fun device() = DeviceInfo("Google", "Pixel 8", "15", 35, "0.1.0")
    override fun display() = DisplayInfo(1080, 2400, 420, 0)
    override fun capabilities() = Capabilities(listOf("H264", "VP8"), 60)
}

class FakeFeed(override val info: VideoInfo = VideoInfo(864, 1920, 30, "H264", 0)) : VideoFeed
