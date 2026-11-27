import { useMemo } from "react";
import { encode } from "uqr";

/** SVG path covering every dark module, one `h` run per horizontal stretch. */
export function qrPath(data: boolean[][]): string {
  let d = "";
  data.forEach((row, y) => {
    let x = 0;
    while (x < row.length) {
      if (!row[x]) {
        x++;
        continue;
      }
      const start = x;
      while (x < row.length && row[x]) x++;
      d += `M${start} ${y}h${x - start}v1h-${x - start}z`;
    }
  });
  return d;
}

/**
 * Pairing QR code. Always dark-on-light with a quiet zone whatever the theme:
 * phone scanners need the contrast. Uses `uqr`, which (unlike `qrcode`) works
 * with Tauri's `freezePrototype` hardening.
 */
export function QrCode({ value }: { value: string }) {
  const qr = useMemo(() => encode(value, { ecc: "M", border: 2 }), [value]);
  return (
    <svg
      className="qr__img"
      viewBox={`0 0 ${qr.size} ${qr.size}`}
      shapeRendering="crispEdges"
      role="img"
      aria-label="Pairing QR code. Scan it with OpenScreen on your phone."
    >
      <rect width={qr.size} height={qr.size} fill="#ffffff" />
      <path d={qrPath(qr.data)} fill="#000000" />
    </svg>
  );
}
