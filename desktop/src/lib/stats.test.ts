import { describe, expect, it } from "vitest";
import { classify, computeStats, formatBitrate, formatMs } from "./stats";

const report = (t: number, bytes: number, recv: number, lost: number) => [
  {
    type: "inbound-rtp",
    kind: "video",
    timestamp: t,
    bytesReceived: bytes,
    packetsReceived: recv,
    packetsLost: lost,
    framesPerSecond: 30,
    frameWidth: 864,
    frameHeight: 1920,
    jitterBufferDelay: 3,
    jitterBufferEmittedCount: 100,
    framesDecoded: 100,
    totalDecodeTime: 0.5,
    codecId: "c1",
    decoderImplementation: "D3D11VideoDecoder",
    freezeCount: 0,
  },
  { type: "codec", id: "c1", mimeType: "video/H264" },
  { type: "candidate-pair", nominated: true, state: "succeeded", currentRoundTripTime: 0.008 },
];

describe("computeStats", () => {
  it("derives bitrate and loss from consecutive samples", () => {
    const first = computeStats(report(1000, 0, 0, 0), null);
    expect(first.stats.bitrateBps).toBeNull();
    const second = computeStats(report(2000, 1_000_000, 990, 10), first.sample);
    expect(second.stats.bitrateBps).toBe(8_000_000);
    expect(second.stats.packetLossPct).toBeCloseTo(1.0);
    expect(second.stats.codec).toBe("H264");
    expect(second.stats.width).toBe(864);
    expect(second.stats.rttMs).toBeCloseTo(8);
    expect(second.stats.jitterBufferMs).toBeCloseTo(30);
    expect(second.stats.decodeMs).toBeCloseTo(5);
    expect(second.stats.estimatedLatencyMs).toBeCloseTo(4 + 30 + 5);
    expect(second.stats.decoder).toBe("D3D11VideoDecoder");
  });

  it("handles reports without video yet", () => {
    const r = computeStats(
      [{ type: "candidate-pair", state: "succeeded", currentRoundTripTime: 0.01 }],
      null,
    );
    expect(r.sample).toBeNull();
    expect(r.stats.rttMs).toBeCloseTo(10);
    expect(r.stats.quality).toBe("unknown"); // no video yet: no grade
  });
});

describe("classify", () => {
  it("grades connection quality", () => {
    expect(classify(30, 10, 0)).toBe("good");
    expect(classify(18, 10, 0)).toBe("fair");
    expect(classify(30, 200, 0)).toBe("poor");
    expect(classify(30, 10, 8)).toBe("poor");
    expect(classify(null, null, null)).toBe("unknown");
  });
});

describe("formatting", () => {
  it("formats bitrates and durations", () => {
    expect(formatBitrate(null)).toBe("—");
    expect(formatBitrate(750_000)).toBe("750 kbps");
    expect(formatBitrate(8_000_000)).toBe("8.0 Mbps");
    expect(formatMs(12.4)).toBe("12 ms");
  });
});
