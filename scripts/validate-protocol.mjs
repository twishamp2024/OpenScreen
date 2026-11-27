#!/usr/bin/env node
// Validates the shared protocol definitions (CI job "protocol").
//
//  1. Every fixtures/valid/*.json passes the signaling schema.
//  2. Every fixtures/invalid/*.json fails it.
//  3. Error codes in the schema match constants.json.
//  4. Every pairing-URI vector produces the expected result with the
//     reference parser in lib/pairing-uri.mjs.
//  5. Messages stay under the size limit.
//
// Usage: npm ci --prefix scripts && node scripts/validate-protocol.mjs

import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { isDeepStrictEqual } from "node:util";
import Ajv2020 from "ajv/dist/2020.js";
import { parsePairingUri, PairingUriError } from "./lib/pairing-uri.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "shared", "protocol");
const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));

let failures = 0;
let checks = 0;
const check = (ok, label) => {
  checks++;
  if (!ok) {
    failures++;
    console.error(`  FAIL ${label}`);
  }
};

const schema = readJson(join(root, "schema", "signaling.v1.schema.json"));
const constants = readJson(join(root, "constants.json"));
const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false, allowUnionTypes: true });
const validate = ajv.compile(schema);

console.log("Signaling message fixtures");
for (const kind of ["valid", "invalid"]) {
  const dir = join(root, "fixtures", kind);
  const files = readdirSync(dir).filter((f) => f.endsWith(".json")).sort();
  check(files.length > 0, `${kind}/ has fixtures`);
  for (const file of files) {
    const text = readFileSync(join(dir, file), "utf8");
    const ok = validate(JSON.parse(text));
    const expected = kind === "valid";
    check(ok === expected, `${kind}/${file} ${expected ? "should pass" : "should fail"} the schema` +
      (ok || expected === false ? "" : `: ${ajv.errorsText(validate.errors)}`));
    check(Buffer.byteLength(text) <= constants.limits.maxMessageBytes, `${kind}/${file} within size limit`);
  }
}

console.log("Constants");
const schemaCodes = schema.$defs.error.properties.code.enum;
check(isDeepStrictEqual([...schemaCodes].sort(), [...constants.errorCodes].sort()),
  "schema error codes match constants.errorCodes");
check(schema.$defs.sdp.maxLength === constants.limits.maxSdpChars, "schema SDP limit matches constants");
check(schema.$defs.ice.properties.candidate.oneOf[1].properties.candidate.maxLength === constants.limits.maxCandidateChars,
  "schema candidate limit matches constants");

console.log("Pairing URI vectors");
const vectors = readJson(join(root, "fixtures", "pairing-uris.json"));
check(vectors.supportedVersion === constants.protocolVersion, "vector version matches protocolVersion");
for (const c of vectors.valid) {
  try {
    const actual = parsePairingUri(c.uri, vectors.supportedVersion);
    check(isDeepStrictEqual(actual, c.expected), `valid "${c.name}" parses to expected value (got ${JSON.stringify(actual)})`);
  } catch (e) {
    check(false, `valid "${c.name}" threw ${e.code ?? e}`);
  }
}
for (const c of vectors.invalid) {
  check(constants.pairingUriErrors.includes(c.error), `invalid "${c.name}" uses a known error code`);
  try {
    parsePairingUri(c.uri, vectors.supportedVersion);
    check(false, `invalid "${c.name}" was accepted`);
  } catch (e) {
    check(e instanceof PairingUriError && e.code === c.error,
      `invalid "${c.name}" fails with ${c.error} (got ${e.code ?? e})`);
  }
}

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures === 0 ? 0 : 1);
