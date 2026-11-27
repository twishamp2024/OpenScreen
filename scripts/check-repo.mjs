#!/usr/bin/env node
// Repository consistency checks (CI job "protocol").
//
//  - required top-level files exist
//  - app versions agree across Android and desktop manifests
//  - protocol version constants agree with shared/protocol/constants.json
//  - view-only guard: no input-injection / control APIs in either app
//  - no secrets or build outputs are tracked by git

import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname, relative, extname } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const read = (p) => readFileSync(join(root, p), "utf8");
let failures = 0;
let checks = 0;
const check = (ok, label) => {
  checks++;
  if (!ok) {
    failures++;
    console.error(`  FAIL ${label}`);
  }
};

console.log("Required files");
for (const p of [
  "README.md", "LICENSE", "CONTRIBUTING.md", "SECURITY.md", ".gitignore",
  "docs/ARCHITECTURE.md", "docs/PROTOCOL.md", "docs/ANDROID_BUILD.md",
  "docs/WINDOWS_BUILD.md", "docs/TROUBLESHOOTING.md", "docs/TESTING.md",
  "shared/protocol/constants.json", "shared/protocol/schema/signaling.v1.schema.json",
  "android/build.gradle.kts", "android/settings.gradle.kts", "android/gradlew",
  "desktop/package.json", "desktop/src-tauri/Cargo.toml", "desktop/src-tauri/tauri.conf.json",
  "desktop/src-tauri/windows/installer-hooks.nsh", ".github/workflows/ci.yml", ".github/workflows/release.yml",
  ".github/pull_request_template.md",
]) check(existsSync(join(root, p)), `${p} exists`);

console.log("Versions");
const pkg = JSON.parse(read("desktop/package.json")).version;
const tauri = JSON.parse(read("desktop/src-tauri/tauri.conf.json")).version;
const cargo = read("desktop/src-tauri/Cargo.toml").match(/^version\s*=\s*"([^"]+)"/m)?.[1];
const android = read("android/gradle.properties").match(/^openscreen\.versionName=(.+)$/m)?.[1]?.trim();
check(pkg === tauri && tauri === cargo, `desktop versions agree (package.json ${pkg}, tauri.conf.json ${tauri}, Cargo.toml ${cargo})`);
check(android === pkg, `Android versionName (${android}) matches desktop (${pkg})`);

console.log("Protocol version constants");
const protocolVersion = JSON.parse(read("shared/protocol/constants.json")).protocolVersion;
const kotlin = read("android/core/src/main/kotlin/io/github/openscreen/core/protocol/ProtocolConstants.kt")
  .match(/PROTOCOL_VERSION:\s*Int\s*=\s*(\d+)/)?.[1];
const rust = read("desktop/src-tauri/src/protocol/constants.rs").match(/PROTOCOL_VERSION:\s*u32\s*=\s*(\d+)/)?.[1];
check(Number(kotlin) === protocolVersion, `Kotlin PROTOCOL_VERSION (${kotlin}) = ${protocolVersion}`);
check(Number(rust) === protocolVersion, `Rust PROTOCOL_VERSION (${rust}) = ${protocolVersion}`);

console.log("View-only guard");
const SKIP_DIRS = new Set(["node_modules", "build", "target", "dist", ".gradle", ".kotlin", "gen"]);
function* sourceFiles(dir, exts) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (!SKIP_DIRS.has(name)) yield* sourceFiles(full, exts);
    } else if (exts.includes(extname(name))) yield full;
  }
}
const forbidden = [
  // Android: anything that could act on the device
  { dir: "android", exts: [".kt", ".java", ".xml"], patterns: [
    /AccessibilityService/, /dispatchGesture\s*\(/, /injectInputEvent/, /BIND_DEVICE_ADMIN/,
    /INJECT_EVENTS/, /DevicePolicyManager/, /\bInstrumentation\(\)\.send/,
  ] },
  // Desktop: never create a data channel or forward input (the viewer only *refuses* incoming ones)
  { dir: "desktop/src", exts: [".ts", ".tsx"], patterns: [/createDataChannel\s*\(/, /sendInput/i] },
  { dir: "desktop/src-tauri/src", exts: [".rs"], patterns: [/SendInput/, /adb\s+shell/] },
];
for (const { dir, exts, patterns } of forbidden) {
  for (const file of sourceFiles(join(root, dir), exts)) {
    if (/[\\/](test|tests)[\\/]/.test(file) || /\.test\.tsx?$/.test(file) || file.endsWith("ManifestPolicyTest.kt")) continue;
    const text = readFileSync(file, "utf8");
    for (const re of patterns) check(!re.test(text), `${relative(root, file)} must not match ${re}`);
  }
}

console.log("Tracked files");
let tracked = null;
try {
  tracked = execFileSync("git", ["ls-files"], { cwd: root, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).split("\n").filter(Boolean);
} catch {
  console.log("  (not a git checkout; skipped)");
}
if (tracked) {
  const bad = /(\.jks|\.keystore|\.p12|\.pfx|\.pem|\.key|\.apk|\.aab|\.msi|\.exe)$|(^|\/)(local\.properties|keystore\.properties|\.env(\..+)?)$|(^|\/)(build|target|node_modules|dist)\//;
  const offenders = tracked.filter((f) => bad.test(f) && !f.endsWith(".env.example"));
  check(offenders.length === 0, `no secrets or build outputs tracked${offenders.length ? `: ${offenders.join(", ")}` : ""}`);
}

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures === 0 ? 0 : 1);
