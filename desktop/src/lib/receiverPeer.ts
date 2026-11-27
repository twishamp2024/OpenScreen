import type { IceCandidatePayload, SignalMessage } from "./backend";

/**
 * Codec order for the answer: H.264 Constrained Baseline (packetization-mode 1)
 * first, then other H.264, then VP8; everything else (RTX/RED/FEC, VP9, AV1…)
 * keeps its relative order after those. Pure for testing.
 */
export function preferH264<T extends { mimeType: string; sdpFmtpLine?: string }>(codecs: T[]): T[] {
  const rank = (c: T) => {
    const mime = c.mimeType.toLowerCase();
    const fmtp = (c.sdpFmtpLine ?? "").toLowerCase();
    if (
      mime === "video/h264" &&
      fmtp.includes("packetization-mode=1") &&
      fmtp.includes("profile-level-id=42e0")
    )
      return 0;
    if (mime === "video/h264" && fmtp.includes("packetization-mode=1")) return 1;
    if (mime === "video/h264") return 2;
    if (mime === "video/vp8") return 3;
    return 4;
  };
  return codecs
    .map((c, i) => ({ c, i }))
    .sort((a, b) => rank(a.c) - rank(b.c) || a.i - b.i)
    .map((x) => x.c);
}

export type PeerState = "new" | "connecting" | "connected" | "disconnected" | "failed" | "closed";

export interface ReceiverPeerOptions {
  /** Injected for tests; defaults to the WebView's RTCPeerConnection. */
  createPeerConnection?: (config: RTCConfiguration) => RTCPeerConnection;
  sendSignal: (message: SignalMessage) => Promise<void> | void;
  onTrack: (stream: MediaStream) => void;
  onState: (state: PeerState) => void;
  onError?: (error: unknown) => void;
}

/**
 * WebRTC answerer for the viewer window (docs/PROTOCOL.md §8): receive-only,
 * no STUN/TURN, no data channels. The phone always offers; this side never
 * renegotiates on its own, so there is no glare.
 */
export class ReceiverPeer {
  readonly pc: RTCPeerConnection;
  private pendingCandidates: RTCIceCandidateInit[] = [];
  private closed = false;
  /** Serializes offer handling so an ICE-restart offer never races the previous one. */
  private chain: Promise<void> = Promise.resolve();

  constructor(private readonly options: ReceiverPeerOptions) {
    const create = options.createPeerConnection ?? ((c) => new RTCPeerConnection(c));
    this.pc = create({ iceServers: [], bundlePolicy: "max-bundle", rtcpMuxPolicy: "require" });

    this.pc.onicecandidate = (e) => {
      const c = e.candidate;
      const candidate: IceCandidatePayload | null =
        c && c.candidate
          ? { candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex }
          : null;
      void Promise.resolve(options.sendSignal({ type: "ice", candidate })).catch(this.reportError);
    };
    this.pc.ontrack = (e) => {
      const receiver = e.receiver as RTCRtpReceiver & { jitterBufferTarget?: number | null };
      // Ask for the smallest jitter buffer the network allows: latency over smoothness.
      if ("jitterBufferTarget" in receiver) receiver.jitterBufferTarget = 0;
      options.onTrack(e.streams[0] ?? new MediaStream([e.track]));
    };
    this.pc.onconnectionstatechange = () => options.onState(this.pc.connectionState as PeerState);
    // View-only: a data channel would be a protocol violation; refuse it.
    this.pc.ondatachannel = (e) => e.channel.close();
  }

  /** Feed one signaling message from the phone. */
  handle(message: SignalMessage): Promise<void> {
    if (this.closed) return Promise.resolve();
    switch (message.type) {
      case "offer":
        this.chain = this.chain.then(() => this.onOffer(message.sdp)).catch(this.reportError);
        return this.chain;
      case "ice":
        return this.onRemoteCandidate(message.candidate).catch(this.reportError);
      default:
        return Promise.resolve();
    }
  }

  private async onOffer(sdp: string) {
    await this.pc.setRemoteDescription({ type: "offer", sdp });
    this.preferH264InAnswer();
    const queued = this.pendingCandidates;
    this.pendingCandidates = [];
    for (const c of queued) await this.pc.addIceCandidate(c);
    const answer = await this.pc.createAnswer();
    await this.pc.setLocalDescription(answer);
    const local = this.pc.localDescription;
    await this.options.sendSignal({ type: "answer", sdp: local?.sdp ?? answer.sdp ?? "" });
  }

  private async onRemoteCandidate(c: IceCandidatePayload | null) {
    if (!c || c.candidate === "") return; // end-of-candidates needs no action
    const init: RTCIceCandidateInit = {
      candidate: c.candidate,
      sdpMid: c.sdpMid,
      sdpMLineIndex: c.sdpMLineIndex,
    };
    if (!this.pc.remoteDescription) {
      this.pendingCandidates.push(init); // trickled before the offer was applied
      return;
    }
    await this.pc.addIceCandidate(init);
  }

  /** Best effort: older WebView2 builds without setCodecPreferences keep the offer's order. */
  private preferH264InAnswer() {
    const caps =
      typeof RTCRtpReceiver !== "undefined" ? RTCRtpReceiver.getCapabilities?.("video") : null;
    if (!caps || typeof this.pc.getTransceivers !== "function") return;
    for (const t of this.pc.getTransceivers()) {
      try {
        t.setCodecPreferences?.(preferH264(caps.codecs));
      } catch (e) {
        this.reportError(e);
      }
    }
  }

  getStats(): Promise<RTCStatsReport> {
    return this.pc.getStats();
  }

  close() {
    this.closed = true;
    this.pc.close();
  }

  private reportError = (e: unknown) => {
    this.options.onError?.(e);
  };
}
