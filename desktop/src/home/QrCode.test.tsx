import { describe, expect, it } from "vitest";
import jsQR from "jsqr";
import { encode } from "uqr";
import { qrPath } from "./QrCode";

const URIS = [
  "openscreen://pair?v=1&h=192.168.29.42&p=47820&t=lXPoAB1-XtnXbHZwLlJ13iMUH7T1NeqCWmA9FvHqSe8&f=Pj4_A0Jni6caoMTewX711vX8StLStu7E1ZA-89W1rz8&n=Windows%20PC",
  "openscreen://pair?v=1&h=192.168.0.1,192.168.0.2,10.255.255.255,172.20.0.1&p=65535&t=lXPoAB1-XtnXbHZwLlJ13iMUH7T1NeqCWmA9FvHqSe8&f=Pj4_A0Jni6caoMTewX711vX8StLStu7E1ZA-89W1rz8&n=%E5%B7%A5%E4%BD%9C%E5%AE%A4",
];

/** Rasterizes a module matrix the way the SVG is drawn (white background, black modules). */
function rasterize(data: boolean[][], scale = 4) {
  const size = data.length * scale;
  const pixels = new Uint8ClampedArray(size * size * 4).fill(255);
  data.forEach((row, y) =>
    row.forEach((dark, x) => {
      if (!dark) return;
      for (let dy = 0; dy < scale; dy++)
        for (let dx = 0; dx < scale; dx++) {
          const i = ((y * scale + dy) * size + (x * scale + dx)) * 4;
          pixels[i] = pixels[i + 1] = pixels[i + 2] = 0;
        }
    }),
  );
  return { pixels, size };
}

/** Re-reads the SVG path back into a matrix. */
function pathToMatrix(d: string, size: number): boolean[][] {
  const m = Array.from({ length: size }, () => Array<boolean>(size).fill(false));
  for (const [, x, y, w] of d.matchAll(/M(\d+) (\d+)h(\d+)/g)) {
    for (let i = 0; i < Number(w); i++) m[Number(y)]![Number(x) + i] = true;
  }
  return m;
}

describe("pairing QR code", () => {
  it.each(URIS)("decodes back to the exact pairing link (%#)", (uri) => {
    const qr = encode(uri, { ecc: "M", border: 2 });
    const { pixels, size } = rasterize(qr.data);
    expect(jsQR(pixels, size, size)?.data).toBe(uri);
  });

  it.each(URIS)("SVG path reproduces every module (%#)", (uri) => {
    const qr = encode(uri, { ecc: "M", border: 2 });
    expect(pathToMatrix(qrPath(qr.data), qr.size)).toEqual(qr.data);
  });
});
