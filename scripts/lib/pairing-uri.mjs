// Reference parser for OpenScreen pairing URIs (docs/PROTOCOL.md, section 2).
//
// This is NOT used by either app. It exists so the shared test vectors in
// shared/protocol/fixtures/pairing-uris.json can be checked for internal
// consistency before (and independently of) the Kotlin and Rust parsers.
// It is written without URL libraries on purpose, mirroring the explicit
// rules in the spec rather than any one platform's URL parser quirks.

const KNOWN = ["v", "h", "p", "t", "f", "n"];
const REQUIRED = ["v", "h", "p", "t", "f"];
const B64URL_32 = /^[A-Za-z0-9_-]{43}$/;
const DECIMAL = /^[1-9][0-9]{0,8}$/;
const OCTET = /^(0|[1-9][0-9]{0,2})$/;
const CONTROL = /[\u0000-\u001F\u007F]/;

export class PairingUriError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

function fail(code) {
  throw new PairingUriError(code);
}

function decode(value) {
  try {
    return decodeURIComponent(value);
  } catch {
    return null;
  }
}

export function isPrivateIpv4(octets) {
  const [a, b] = octets;
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

function parseIpv4(text) {
  const parts = text.split(".");
  if (parts.length !== 4 || !parts.every((p) => OCTET.test(p))) return null;
  const octets = parts.map(Number);
  return octets.every((o) => o <= 255) ? octets : null;
}

export function parsePairingUri(uri, supportedVersion = 1) {
  const sep = uri.indexOf("://");
  if (sep < 0 || uri.slice(0, sep).toLowerCase() !== "openscreen") fail("NOT_A_PAIRING_URI");
  const rest = uri.slice(sep + 3);
  const q = rest.indexOf("?");
  const host = q < 0 ? rest : rest.slice(0, q);
  if (host.toLowerCase() !== "pair") fail("NOT_A_PAIRING_URI");
  const query = q < 0 ? "" : rest.slice(q + 1);

  const params = new Map();
  for (const segment of query.split("&")) {
    if (segment === "") continue;
    const eq = segment.indexOf("=");
    const key = eq < 0 ? segment : segment.slice(0, eq);
    const raw = eq < 0 ? "" : segment.slice(eq + 1);
    if (!KNOWN.includes(key)) continue;
    if (params.has(key)) fail("DUPLICATE_PARAM");
    params.set(key, decode(raw));
  }
  for (const key of REQUIRED) if (!params.has(key)) fail("MISSING_PARAM");

  const v = params.get("v");
  if (v === null || !DECIMAL.test(v)) fail("BAD_VERSION");
  const version = Number(v);
  if (version > supportedVersion) fail("UNSUPPORTED_VERSION");

  const h = params.get("h");
  if (h === null) fail("BAD_HOST");
  const hosts = h.split(",");
  if (hosts.length < 1 || hosts.length > 4) fail("BAD_HOST");
  for (const entry of hosts) {
    const octets = parseIpv4(entry);
    if (!octets || !isPrivateIpv4(octets)) fail("BAD_HOST");
  }

  const p = params.get("p");
  if (p === null || !DECIMAL.test(p) || Number(p) > 65535) fail("BAD_PORT");

  const t = params.get("t");
  if (t === null || !B64URL_32.test(t)) fail("BAD_TOKEN");
  const f = params.get("f");
  if (f === null || !B64URL_32.test(f)) fail("BAD_FINGERPRINT");

  let name = null;
  if (params.has("n")) {
    name = params.get("n");
    const length = name === null ? 0 : [...name].length;
    if (name === null || length < 1 || length > 32 || CONTROL.test(name)) fail("BAD_NAME");
  }

  return { version, hosts, port: Number(p), token: t, fingerprint: f, name };
}
