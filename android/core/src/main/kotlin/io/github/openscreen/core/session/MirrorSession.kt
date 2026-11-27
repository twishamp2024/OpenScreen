package io.github.openscreen.core.session

import io.github.openscreen.core.protocol.Answer
import io.github.openscreen.core.protocol.Bye
import io.github.openscreen.core.protocol.DecodeResult
import io.github.openscreen.core.protocol.ErrorMessage
import io.github.openscreen.core.protocol.Hello
import io.github.openscreen.core.protocol.Ice
import io.github.openscreen.core.protocol.IceCandidatePayload
import io.github.openscreen.core.protocol.Offer
import io.github.openscreen.core.protocol.PairingInfo
import io.github.openscreen.core.protocol.ProtocolConstants
import io.github.openscreen.core.protocol.ReceiverInfo
import io.github.openscreen.core.protocol.Resume
import io.github.openscreen.core.protocol.Resumed
import io.github.openscreen.core.protocol.SignalingCodec
import io.github.openscreen.core.protocol.SignalingMessage
import io.github.openscreen.core.protocol.StreamState
import io.github.openscreen.core.protocol.VideoInfo
import io.github.openscreen.core.protocol.Welcome
import io.github.openscreen.core.webrtc.SdpCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The sender's session state machine (docs/ARCHITECTURE.md §4.2, §9).
 *
 * All inputs — user actions, transport and peer callbacks, timers — are
 * turned into [Event]s and handled one at a time on [scope], so no locking is
 * needed and the logic is deterministic under test (virtual time).
 */
class MirrorSession(
    private val scope: CoroutineScope,
    private val transport: SignalingTransport,
    private val peers: PeerFactory,
    private val environment: DeviceEnvironment,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timings: Timings = Timings(),
) {
    data class Timings(
        val welcomeTimeoutMs: Long = ProtocolConstants.HELLO_TIMEOUT_MS,
        val iceConnectTimeoutMs: Long = 15_000,
        val iceDisconnectedGraceMs: Long = ProtocolConstants.ICE_DISCONNECTED_GRACE_MS,
        val backoffInitialMs: Long = ProtocolConstants.RECONNECT_BACKOFF_INITIAL_MS,
        val backoffMaxMs: Long = ProtocolConstants.RECONNECT_BACKOFF_MAX_MS,
        val maxIceRestartsWithoutSuccess: Int = 3,
    )

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val events = Channel<Event>(Channel.UNLIMITED)

    // ── session data (only touched from the event loop) ──
    private var pairing: PairingInfo? = null
    private var receiver: ReceiverInfo? = null
    private var sessionId: String? = null
    private var resumeToken: String? = null
    private var resumeWindowMs: Long = ProtocolConstants.RESUME_WINDOW_MS
    private var connection: TransportConnection? = null
    private var connectionGen = 0
    private var transportOpen = false
    private var peer: Peer? = null
    private var peerGen = 0
    private var iceState = IceState.NEW
    private var everConnected = false
    private var awaitingAnswer = false
    private var restartPending = false
    private var restartsWithoutSuccess = 0
    private var reconnectAttempt = 0
    private var disconnectedAt: Long? = null
    private var feed: VideoFeed? = null
    private var negotiatedCodec: String? = null
    private var timer: Job? = null
    private var timerKind: TimerKind? = null
    private var timerGen = 0

    init {
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(SessionError.INTERNAL, e.message)
                }
            }
        }
    }

    // ── public API (thread-safe: everything is posted to the loop) ──

    fun pair(info: PairingInfo, orderedHosts: List<String> = info.hosts) =
        post(Event.Pair(info, orderedHosts))

    fun startStreaming(feed: VideoFeed) = post(Event.StartStreaming(feed))
    fun updateVideo(info: VideoInfo) = post(Event.VideoChanged(info))
    fun stopStreaming(reason: String = "user") = post(Event.StopStreaming(reason))
    fun disconnect() = post(Event.Disconnect)
    fun dismissError() = post(Event.Dismiss)

    /** Call when the phone's network changes (e.g. Wi-Fi came back) to retry immediately. */
    fun networkChanged() = post(Event.NetworkChanged)

    private fun post(e: Event) {
        events.trySend(e)
    }

    // ── events ──

    private sealed interface Event {
        data class Pair(val info: PairingInfo, val hosts: List<String>) : Event
        data class StartStreaming(val feed: VideoFeed) : Event
        data class VideoChanged(val info: VideoInfo) : Event
        data class StopStreaming(val reason: String) : Event
        data object Disconnect : Event
        data object Dismiss : Event
        data object NetworkChanged : Event
        data class TOpen(val gen: Int) : Event
        data class TMessage(val gen: Int, val text: String) : Event
        data class TClosed(val gen: Int, val code: Int, val reason: String) : Event
        data class TFailure(val gen: Int, val failure: TransportFailure, val detail: String?) : Event
        data class PCandidate(val gen: Int, val candidate: IceCandidatePayload?) : Event
        data class PIce(val gen: Int, val state: IceState) : Event
        data class Timer(val kind: TimerKind, val gen: Int) : Event
    }

    private enum class TimerKind { WELCOME, ICE_CONNECT, ICE_GRACE, RECONNECT }

    private suspend fun handle(e: Event) {
        when (e) {
            is Event.Pair -> onPair(e)
            is Event.StartStreaming -> onStartStreaming(e.feed)
            is Event.VideoChanged -> if (feed != null) sendStreaming(e.info)
            is Event.StopStreaming -> onStopStreaming(e.reason)
            Event.Disconnect -> onDisconnect()
            Event.Dismiss -> if (_state.value is SessionState.Failed) _state.value = SessionState.Idle
            Event.NetworkChanged -> onNetworkChanged()
            is Event.TOpen -> if (e.gen == connectionGen) onTransportOpen()
            is Event.TMessage -> if (e.gen == connectionGen) onText(e.text)
            is Event.TClosed -> if (e.gen == connectionGen) onTransportLost(null, "closed ${e.code} ${e.reason}")
            is Event.TFailure -> if (e.gen == connectionGen) onTransportLost(e.failure, e.detail)
            is Event.PCandidate -> if (e.gen == peerGen) sendCandidate(e.candidate)
            is Event.PIce -> if (e.gen == peerGen) onIceState(e.state)
            is Event.Timer -> if (e.gen == timerGen && e.kind == timerKind) onTimer(e.kind)
        }
    }

    // ── pairing / connecting ──

    private fun onPair(e: Event.Pair) {
        if (_state.value.isActive) teardown(sendBye = true)
        pairing = e.info
        sessionId = null
        resumeToken = null
        receiver = null
        everConnected = false
        restartsWithoutSuccess = 0
        _state.value = SessionState.Connecting(e.info.name)
        openTransport(e.hosts)
    }

    private fun openTransport(hosts: List<String>) {
        val p = pairing ?: return
        val gen = ++connectionGen
        transportOpen = false
        connection = transport.open(hosts, p.port, p.fingerprint, object : TransportListener {
            override fun onOpen(host: String) = post(Event.TOpen(gen))
            override fun onMessage(text: String) = post(Event.TMessage(gen, text))
            override fun onClosed(code: Int, reason: String) = post(Event.TClosed(gen, code, reason))
            override fun onFailure(failure: TransportFailure, detail: String?) =
                post(Event.TFailure(gen, failure, detail))
        })
    }

    private fun onTransportOpen() {
        transportOpen = true
        val id = sessionId
        val token = resumeToken
        if (id != null && token != null) {
            send(Resume(ProtocolConstants.PROTOCOL_VERSION, id, token))
        } else {
            val p = pairing ?: return
            send(
                Hello(
                    protocol = ProtocolConstants.PROTOCOL_VERSION,
                    token = p.token,
                    device = environment.device(),
                    display = environment.display(),
                    capabilities = environment.capabilities(),
                ),
            )
        }
        startTimer(TimerKind.WELCOME, timings.welcomeTimeoutMs)
    }

    private suspend fun onText(text: String) {
        val message = when (val r = SignalingCodec.decode(text)) {
            is DecodeResult.Ok -> r.message
            is DecodeResult.Invalid -> return fail(SessionError.PROTOCOL_ERROR, r.reason, sendBye = true)
        }
        onMessage(message)
    }

    private suspend fun onMessage(m: SignalingMessage) {
        when (m) {
            is Welcome -> onWelcome(m)
            is Resumed -> onResumed(m)
            is Answer -> onAnswer(m)
            is Ice -> peer?.addRemoteCandidate(m.candidate)
            is ErrorMessage -> if (m.fatal) {
                fail(SessionError.fromProtocolCode(m.code), m.message)
            }
            is Bye -> fail(SessionError.RECEIVER_CLOSED, m.reason)
            // Sender-only message types arriving here are a protocol violation.
            else -> fail(SessionError.PROTOCOL_ERROR, "unexpected ${m::class.simpleName}", sendBye = true)
        }
    }

    private suspend fun onWelcome(m: Welcome) {
        if (sessionId != null || _state.value !is SessionState.Connecting) {
            return fail(SessionError.PROTOCOL_ERROR, "unexpected welcome", sendBye = true)
        }
        cancelTimer()
        sessionId = m.sessionId
        resumeToken = m.resumeToken
        resumeWindowMs = m.resumeWindowMs
        receiver = m.receiver
        _state.value = SessionState.Negotiating(m.receiver)
        val gen = ++peerGen
        iceState = IceState.NEW
        peer = peers.create(object : PeerListener {
            override fun onLocalCandidate(candidate: IceCandidatePayload?) = post(Event.PCandidate(gen, candidate))
            override fun onIceState(state: IceState) = post(Event.PIce(gen, state))
        })
        sendOffer(iceRestart = false)
        startTimer(TimerKind.ICE_CONNECT, timings.iceConnectTimeoutMs)
    }

    private suspend fun onResumed(m: Resumed) {
        if (m.sessionId != sessionId) return fail(SessionError.PROTOCOL_ERROR, "resumed wrong session", sendBye = true)
        cancelTimer()
        resumeToken = m.resumeToken
        resumeWindowMs = m.resumeWindowMs
        reconnectAttempt = 0
        disconnectedAt = null
        val r = receiver ?: return
        if (iceState == IceState.CONNECTED || iceState == IceState.COMPLETED) {
            _state.value = SessionState.Connected(r, feed?.info)
            feed?.let { sendStreaming(it.info) }
        } else {
            _state.value = SessionState.Negotiating(r)
            requestIceRestart()
        }
    }

    // ── negotiation ──

    private suspend fun sendOffer(iceRestart: Boolean) {
        val p = peer ?: return
        val sdp = p.createOffer(iceRestart)
        awaitingAnswer = true
        send(Offer(sdp, iceRestart))
    }

    private suspend fun onAnswer(m: Answer) {
        if (!awaitingAnswer) return fail(SessionError.PROTOCOL_ERROR, "unsolicited answer", sendBye = true)
        awaitingAnswer = false
        negotiatedCodec = SdpCodec.negotiatedVideoCodec(m.sdp) ?: negotiatedCodec
        peer?.setAnswer(m.sdp)
        if (restartPending) {
            restartPending = false
            sendOffer(iceRestart = true)
        }
    }

    private suspend fun requestIceRestart() {
        if (!transportOpen || peer == null) return
        if (restartsWithoutSuccess >= timings.maxIceRestartsWithoutSuccess) {
            return fail(SessionError.MEDIA_UNREACHABLE, "ICE failed after $restartsWithoutSuccess restarts", sendBye = true)
        }
        restartsWithoutSuccess++
        if (awaitingAnswer) {
            restartPending = true
        } else {
            sendOffer(iceRestart = true)
        }
        startTimer(TimerKind.ICE_CONNECT, timings.iceConnectTimeoutMs)
    }

    private fun sendCandidate(c: IceCandidatePayload?) {
        // Candidates gathered while signaling is down are dropped: an ICE restart follows resume.
        if (transportOpen && sessionId != null) send(Ice(c))
    }

    private suspend fun onIceState(s: IceState) {
        iceState = s
        val r = receiver ?: return
        when (s) {
            IceState.CONNECTED, IceState.COMPLETED -> {
                everConnected = true
                restartsWithoutSuccess = 0
                if (timerKind == TimerKind.ICE_CONNECT || timerKind == TimerKind.ICE_GRACE) cancelTimer()
                if (_state.value !is SessionState.Reconnecting) {
                    _state.value = SessionState.Connected(r, feed?.info)
                    if (feed == null) send(StreamState(StreamState.STATE_READY)) else sendStreaming(feed!!.info)
                }
            }
            IceState.DISCONNECTED -> if (_state.value is SessionState.Connected) {
                startTimer(TimerKind.ICE_GRACE, timings.iceDisconnectedGraceMs)
            }
            IceState.FAILED -> requestIceRestart()
            else -> Unit
        }
    }

    // ── streaming ──

    private fun onStartStreaming(f: VideoFeed) {
        val s = _state.value
        if (s !is SessionState.Connected && s !is SessionState.Reconnecting) return
        feed = f
        peer?.setVideo(f)
        if (s is SessionState.Connected) {
            _state.value = s.copy(video = f.info)
            sendStreaming(f.info)
        } else if (s is SessionState.Reconnecting) {
            _state.value = s.copy(streaming = true)
        }
    }

    private fun sendStreaming(raw: VideoInfo) {
        val info = raw.copy(codec = negotiatedCodec ?: raw.codec)
        val s = _state.value
        if (s is SessionState.Connected) _state.value = s.copy(video = info)
        if (transportOpen) send(StreamState(StreamState.STATE_STREAMING, video = info))
    }

    private fun onStopStreaming(reason: String) {
        if (feed == null) return
        feed = null
        peer?.setVideo(null)
        when (val s = _state.value) {
            is SessionState.Connected -> _state.value = s.copy(video = null)
            is SessionState.Reconnecting -> _state.value = s.copy(streaming = false)
            else -> Unit
        }
        if (transportOpen) send(StreamState(StreamState.STATE_STOPPED, reason = reason))
    }

    // ── loss and recovery ──

    private suspend fun onTransportLost(failure: TransportFailure?, detail: String?) {
        transportOpen = false
        connection = null
        awaitingAnswer = false
        restartPending = false
        val r = receiver
        if (sessionId == null || r == null) {
            // Never got a session: this is a connection error, not a drop.
            if (_state.value is SessionState.Connecting) {
                fail(SessionError.fromTransport(failure ?: TransportFailure.OTHER), detail)
            }
            return
        }
        if (_state.value is SessionState.Failed || _state.value is SessionState.Idle) return
        if (failure == TransportFailure.CERTIFICATE_MISMATCH) {
            return fail(SessionError.CERTIFICATE_MISMATCH, detail)
        }
        val since = disconnectedAt ?: clock().also { disconnectedAt = it }
        if (clock() - since >= resumeWindowMs) return fail(SessionError.CONNECTION_LOST, detail)
        reconnectAttempt++
        _state.value = SessionState.Reconnecting(r, reconnectAttempt, feed != null)
        startTimer(TimerKind.RECONNECT, backoff(reconnectAttempt))
    }

    private fun backoff(attempt: Int): Long {
        var d = timings.backoffInitialMs
        repeat(attempt - 1) { d = minOf(d * 2, timings.backoffMaxMs) }
        return d
    }

    private suspend fun onNetworkChanged() {
        if (_state.value is SessionState.Reconnecting && timerKind == TimerKind.RECONNECT) {
            cancelTimer()
            onTimer(TimerKind.RECONNECT)
        }
    }

    private suspend fun onTimer(kind: TimerKind) {
        timer = null
        timerKind = null
        timerGen++
        when (kind) {
            TimerKind.WELCOME -> if (sessionId == null) {
                fail(SessionError.HELLO_TIMEOUT, "no welcome", sendBye = false)
            } else {
                // Resume got no answer: drop this connection and try again.
                connection?.close(1000, "resume timeout")
                onTransportLost(TransportFailure.OTHER, "resume timeout")
            }
            TimerKind.ICE_CONNECT -> if (iceState != IceState.CONNECTED && iceState != IceState.COMPLETED) {
                if (everConnected) requestIceRestart() else fail(SessionError.MEDIA_UNREACHABLE, "ICE timeout", sendBye = true)
            }
            TimerKind.ICE_GRACE -> if (iceState == IceState.DISCONNECTED) requestIceRestart()
            TimerKind.RECONNECT -> {
                val since = disconnectedAt
                if (since != null && clock() - since >= resumeWindowMs) {
                    fail(SessionError.CONNECTION_LOST, "resume window expired")
                } else {
                    pairing?.let { openTransport(it.hosts) }
                }
            }
        }
    }

    // ── teardown ──

    private fun onDisconnect() {
        if (!_state.value.isActive && _state.value !is SessionState.Failed) return
        teardown(sendBye = true)
        _state.value = SessionState.Idle
    }

    private fun fail(error: SessionError, detail: String?, sendBye: Boolean = false) {
        teardown(sendBye)
        _state.value = SessionState.Failed(error, detail)
    }

    private fun teardown(sendBye: Boolean) {
        cancelTimer()
        if (sendBye && transportOpen) send(Bye("user"))
        connectionGen++
        peerGen++
        connection?.close(1000, "bye")
        connection = null
        transportOpen = false
        peer?.close()
        peer = null
        feed = null
        sessionId = null
        resumeToken = null
        awaitingAnswer = false
        restartPending = false
        iceState = IceState.NEW
        reconnectAttempt = 0
        disconnectedAt = null
    }

    private fun send(m: SignalingMessage) {
        connection?.send(SignalingCodec.encode(m))
    }

    private fun startTimer(kind: TimerKind, delayMs: Long) {
        timer?.cancel()
        timerKind = kind
        val gen = ++timerGen
        timer = scope.launch {
            delay(delayMs)
            post(Event.Timer(kind, gen))
        }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
        timerKind = null
        timerGen++
    }
}
