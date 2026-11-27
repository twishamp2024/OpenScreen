/** Viewer statistics derived from successive `getStats()` reports. */
export interface ViewerStats {
  fps: number | null;
  bitrateBps: number | null;
  width: number | null;
  height: number | null;
  codec: string | null;
  rttMs: number | null;
  jitterBufferMs: number | null;
  decodeMs: number | null;
  /** RTT + jitter buffer + decode: what WebRTC can measure of the receive path. Not glass-to-glass. */
  estimatedLatencyMs: number | null;
  packetLossPct: number | null;
  freezeCount: number | null;
  decoder: string | null;
  quality: "good" | "fair" | "poor" | "unknown";
}

export interface StatsSample {
  timestampMs: number;
  bytesReceived: number;
  packetsReceived: number;
  packetsLost: number;
}

type Entry = Record<string, unknown>;

const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const str = (v: unknown): string | null => (typeof v === "string" ? v : null);

/**
 * Pure function so it can be unit tested with plain objects instead of a real
 * RTCStatsReport. Returns the stats and the sample to pass in next time.
 */
export function computeStats(
  entries: Iterable<Entry>,
  previous: StatsSample | null,
): { stats: ViewerStats; sample: StatsSample | null } {
  const list = [...entries];
  const inbound = list.find((e) => e.type === "inbound-rtp" && e.kind === "video");
  const pair =
    list.find(
      (e) => e.type === "candidate-pair" && e.nominated === true && e.state === "succeeded",
    ) ?? list.find((e) => e.type === "candidate-pair" && e.state === "succeeded");
  const codecEntry = inbound
    ? list.find((e) => e.type === "codec" && e.id === inbound.codecId)
    : undefined;

  if (!inbound) {
    return { stats: empty(num(pair?.currentRoundTripTime)), sample: null };
  }

  const timestampMs = num(inbound.timestamp) ?? Date.now();
  const sample: StatsSample = {
    timestampMs,
    bytesReceived: num(inbound.bytesReceived) ?? 0,
    packetsReceived: num(inbound.packetsReceived) ?? 0,
    packetsLost: Math.max(0, num(inbound.packetsLost) ?? 0),
  };

  let bitrateBps: number | null = null;
  let packetLossPct: number | null = null;
  if (previous && timestampMs > previous.timestampMs) {
    const dt = (timestampMs - previous.timestampMs) / 1000;
    bitrateBps = Math.max(0, ((sample.bytesReceived - previous.bytesReceived) * 8) / dt);
    const got = sample.packetsReceived - previous.packetsReceived;
    const lost = sample.packetsLost - previous.packetsLost;
    packetLossPct = got + lost > 0 ? (Math.max(0, lost) / (got + lost)) * 100 : 0;
  }

  const emitted = num(inbound.jitterBufferEmittedCount);
  const jbDelay = num(inbound.jitterBufferDelay);
  const jitterBufferMs = emitted && jbDelay !== null ? (jbDelay / emitted) * 1000 : null;
  const decoded = num(inbound.framesDecoded);
  const decodeTime = num(inbound.totalDecodeTime);
  const decodeMs = decoded && decodeTime !== null ? (decodeTime / decoded) * 1000 : null;
  const rttS = num(pair?.currentRoundTripTime);
  const rttMs = rttS !== null ? rttS * 1000 : null;
  const parts = [rttMs !== null ? rttMs / 2 : null, jitterBufferMs, decodeMs];
  const estimatedLatencyMs = parts.every((p) => p !== null)
    ? (parts as number[]).reduce((a, b) => a + b, 0)
    : null;
  const fps = num(inbound.framesPerSecond);
  const mime = str(codecEntry?.mimeType);

  return {
    stats: {
      fps,
      bitrateBps,
      width: num(inbound.frameWidth),
      height: num(inbound.frameHeight),
      codec: mime ? mime.replace(/^video\//i, "").toUpperCase() : null,
      rttMs,
      jitterBufferMs,
      decodeMs,
      estimatedLatencyMs,
      packetLossPct,
      freezeCount: num(inbound.freezeCount),
      decoder: str(inbound.decoderImplementation),
      quality: classify(fps, rttMs, packetLossPct),
    },
    sample,
  };
}

/** Coarse connection-quality badge. Thresholds are UI heuristics, not performance claims. */
export function classify(
  fps: number | null,
  rttMs: number | null,
  lossPct: number | null,
): ViewerStats["quality"] {
  if (fps === null && rttMs === null) return "unknown";
  if ((lossPct ?? 0) > 5 || (rttMs ?? 0) > 150 || (fps !== null && fps < 10)) return "poor";
  if ((lossPct ?? 0) > 1 || (rttMs ?? 0) > 60 || (fps !== null && fps < 20)) return "fair";
  return "good";
}

function empty(rttS: number | null): ViewerStats {
  return {
    fps: null,
    bitrateBps: null,
    width: null,
    height: null,
    codec: null,
    rttMs: rttS !== null ? rttS * 1000 : null,
    jitterBufferMs: null,
    decodeMs: null,
    estimatedLatencyMs: null,
    packetLossPct: null,
    freezeCount: null,
    decoder: null,
    quality: "unknown",
  };
}

export function formatBitrate(bps: number | null): string {
  if (bps === null) return "—";
  return bps >= 1e6 ? `${(bps / 1e6).toFixed(1)} Mbps` : `${Math.round(bps / 1000)} kbps`;
}

export function formatMs(ms: number | null): string {
  return ms === null ? "—" : `${Math.round(ms)} ms`;
}
