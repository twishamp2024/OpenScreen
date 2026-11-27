import { describe, expect, it, vi } from "vitest";
import { ReceiverPeer, preferH264 } from "./receiverPeer";
import type { SignalMessage } from "./backend";

/** Minimal RTCPeerConnection double recording the calls the viewer makes. */
class FakePC {
  calls: string[] = [];
  remoteDescription: RTCSessionDescriptionInit | null = null;
  localDescription: RTCSessionDescriptionInit | null = null;
  connectionState = "new";
  config: RTCConfiguration;
  onicecandidate: ((e: { candidate: RTCIceCandidate | null }) => void) | null = null;
  ontrack: ((e: unknown) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;
  ondatachannel: ((e: { channel: { close: () => void } }) => void) | null = null;
  constructor(config: RTCConfiguration) {
    this.config = config;
  }
  async setRemoteDescription(d: RTCSessionDescriptionInit) {
    this.calls.push(`remote:${d.type}`);
    this.remoteDescription = d;
  }
  async addIceCandidate(c: RTCIceCandidateInit) {
    this.calls.push(`ice:${c.candidate}`);
  }
  async createAnswer() {
    this.calls.push("createAnswer");
    return { type: "answer" as const, sdp: "v=0\r\nanswer" };
  }
  async setLocalDescription(d: RTCSessionDescriptionInit) {
    this.calls.push("local");
    this.localDescription = d;
  }
  async getStats() {
    return new Map();
  }
  close() {
    this.calls.push("close");
  }
}

function setup() {
  let pc!: FakePC;
  const sent: SignalMessage[] = [];
  const onTrack = vi.fn();
  const onState = vi.fn();
  const peer = new ReceiverPeer({
    createPeerConnection: (c) => {
      pc = new FakePC(c);
      return pc as unknown as RTCPeerConnection;
    },
    sendSignal: (m) => {
      sent.push(m);
    },
    onTrack,
    onState,
  });
  return { peer, pc: () => pc, sent, onTrack, onState };
}

const cand = (n: number) => ({
  candidate: `candidate:${n} 1 udp 1 192.168.1.30 5000 typ host`,
  sdpMid: "0",
  sdpMLineIndex: 0,
});

describe("ReceiverPeer", () => {
  it("uses no STUN/TURN servers and bundles everything", () => {
    const { pc } = setup();
    expect(pc().config.iceServers).toEqual([]);
    expect(pc().config.bundlePolicy).toBe("max-bundle");
    expect(pc().config.rtcpMuxPolicy).toBe("require");
  });

  it("answers an offer", async () => {
    const { peer, pc, sent } = setup();
    await peer.handle({ type: "offer", sdp: "v=0\r\noffer" });
    expect(pc().calls).toEqual(["remote:offer", "createAnswer", "local"]);
    expect(sent).toEqual([{ type: "answer", sdp: "v=0\r\nanswer" }]);
  });

  it("queues candidates that arrive before the offer", async () => {
    const { peer, pc } = setup();
    await peer.handle({ type: "ice", candidate: cand(1) });
    expect(pc().calls).toEqual([]);
    await peer.handle({ type: "offer", sdp: "v=0\r\noffer" });
    await peer.handle({ type: "ice", candidate: cand(2) });
    expect(pc().calls).toEqual([
      "remote:offer",
      `ice:${cand(1).candidate}`,
      "createAnswer",
      "local",
      `ice:${cand(2).candidate}`,
    ]);
  });

  it("ignores end-of-candidates markers", async () => {
    const { peer, pc } = setup();
    await peer.handle({ type: "offer", sdp: "v=0\r\noffer" });
    await peer.handle({ type: "ice", candidate: null });
    await peer.handle({
      type: "ice",
      candidate: { candidate: "", sdpMid: "0", sdpMLineIndex: null },
    });
    expect(pc().calls.filter((c) => c.startsWith("ice:"))).toEqual([]);
  });

  it("answers an ICE-restart offer after the first one, in order", async () => {
    const { peer, sent } = setup();
    const a = peer.handle({ type: "offer", sdp: "v=0\r\n1" });
    const b = peer.handle({ type: "offer", sdp: "v=0\r\n2", iceRestart: true });
    await Promise.all([a, b]);
    expect(sent.filter((m) => m.type === "answer")).toHaveLength(2);
  });

  it("forwards local candidates and the end-of-candidates marker", () => {
    const { pc, sent } = setup();
    pc().onicecandidate?.({
      candidate: {
        candidate: "candidate:9 1 udp 1 192.168.1.20 6000 typ host",
        sdpMid: "0",
        sdpMLineIndex: 0,
      } as RTCIceCandidate,
    });
    pc().onicecandidate?.({ candidate: null });
    expect(sent).toEqual([
      {
        type: "ice",
        candidate: {
          candidate: "candidate:9 1 udp 1 192.168.1.20 6000 typ host",
          sdpMid: "0",
          sdpMLineIndex: 0,
        },
      },
      { type: "ice", candidate: null },
    ]);
  });

  it("refuses data channels (view-only)", () => {
    const { pc } = setup();
    const close = vi.fn();
    pc().ondatachannel?.({ channel: { close } });
    expect(close).toHaveBeenCalled();
  });

  it("reports connection state and stops handling after close", async () => {
    const { peer, pc, onState, sent } = setup();
    pc().connectionState = "connected";
    pc().onconnectionstatechange?.();
    expect(onState).toHaveBeenCalledWith("connected");
    peer.close();
    await peer.handle({ type: "offer", sdp: "v=0\r\nlate" });
    expect(sent).toEqual([]);
  });
});

describe("preferH264", () => {
  it("puts constrained-baseline H.264 first, then H.264, then VP8, keeping the rest in order", () => {
    const codecs = [
      { mimeType: "video/VP8" },
      { mimeType: "video/rtx" },
      { mimeType: "video/AV1" },
      { mimeType: "video/H264", sdpFmtpLine: "packetization-mode=0;profile-level-id=42e01f" },
      {
        mimeType: "video/H264",
        sdpFmtpLine: "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640c1f",
      },
      {
        mimeType: "video/H264",
        sdpFmtpLine: "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
      },
      { mimeType: "video/red" },
    ];
    expect(preferH264(codecs).map((c) => `${c.mimeType} ${c.sdpFmtpLine ?? ""}`.trim())).toEqual([
      "video/H264 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
      "video/H264 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640c1f",
      "video/H264 packetization-mode=0;profile-level-id=42e01f",
      "video/VP8",
      "video/rtx",
      "video/AV1",
      "video/red",
    ]);
  });
});
