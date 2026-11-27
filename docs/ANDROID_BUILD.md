# Building OpenScreen for Android

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 17 or newer (CI uses 21) |
| Android SDK | Platform **37.1** (`platforms;android-37.1`), Build-Tools 36+ |
| Gradle | Provided by the wrapper (`gradlew`) — do not install separately |
| Android Studio | Optional; any recent stable release that supports AGP 9.5 |

Toolchain pinned in [`android/gradle/libs.versions.toml`](../android/gradle/libs.versions.toml):
AGP 9.5.3, Kotlin 2.4.21, Compose BOM 2026.11.01.

`ANDROID_HOME` must point at your SDK, or create `android/local.properties`
(never committed):

```
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

## Build and test

From `android/`:

```
./gradlew test              # unit tests (incl. shared-protocol conformance tests)
./gradlew lint              # Android Lint
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`.

Install on a connected device: `./gradlew installDebug` (or `adb install`).
The debug build has the application ID `io.github.openscreen.sender.debug`,
so it installs next to a release build.

## Module layout

```
android/
├── app/     Compose UI, foreground service, QR scanner (application)
└── core/    protocol, signaling, WebRTC session, capture (library)
```

The `core` unit tests read `shared/protocol/` directly; Gradle passes the path
as the `openscreen.protocolDir` system property, so always run them through
Gradle.

## Release builds and signing

Release signing reads **only environment variables**; no keystore or password
is ever stored in the repository:

| Variable | Meaning |
|---|---|
| `OPENSCREEN_KEYSTORE_PATH` | path to the `.jks` keystore |
| `OPENSCREEN_KEYSTORE_PASSWORD` | keystore password |
| `OPENSCREEN_KEY_ALIAS` | key alias |
| `OPENSCREEN_KEY_PASSWORD` | key password |

```
./gradlew assembleRelease   # app/build/outputs/apk/release/
```

Without those variables the release APK is built unsigned. In CI the
keystore is stored base64-encoded in a GitHub Actions secret and decoded to a
temporary file only for the release job (Phase 11).

Create a keystore once, keep it **outside** the repository, and back it up —
losing it means users cannot upgrade in place:

```
keytool -genkeypair -v -keystore openscreen-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias openscreen
```

## Version numbers

`openscreen.versionName` / `openscreen.versionCode` in
`android/gradle.properties` are the single source of truth.
`scripts/check-repo.mjs` verifies the version name matches the desktop app.
