# Cybion Worker for Android

Turn an Android phone into a [Cybion](https://cybion.ntnl.io) Worker.

The app runs as a foreground service, keeps an outbound-only SSE connection to
the Cybion Controller, and executes tool calls on the device:

- `bash` — commands through `/system/bin/sh` (inside the app sandbox).
- `computer_use` — screen control through an accessibility service: tap, swipe,
  long-press, text input, screenshots (Android 11+), UI tree, app launch, deep
  links, and global navigation keys.
- `view_image` — read an image file (PNG, JPEG, WebP, or GIF, up to 4 MiB)
  the app can access into the conversation.
- `diagnostics` — the connection check used by the Cybion web console.

`browser_control` is not available on Android and reports an explicit error.

## Status

Early development (`v0.1.x`). Built for personal use against the hosted Cybion
Controller; this is not a Play Store app.

## Requirements

- Android 11 (API 30) or newer, arm64 device.
- A Cybion account with a Worker slot available.

## Install

Download the latest APK from this repository's Releases and sideload it.

## Setup

1. Open the app and turn on **Enable Worker**.
2. Grant notifications and the battery-optimization exemption, and enable the
   **Cybion Worker** accessibility service.
3. Approve the pairing: the app shows a 12-character code and a link. Approve
   the device from the Cybion console (`Workers → Connect a device`).
4. Run the connection check in the console, then send tasks to the new Worker.

## Controller entry selection

The Controller answers on more than one entry domain: `.io` is the globally
distributed edge, `.top` reaches the same Controller through the mainland one.
Which is better depends on the network the phone is on — and a phone moves — so
the app measures instead of assuming:

- Pairing and startup probe every configured entry through the public `/health`
  endpoint (three samples each, concurrently) and connect through whichever
  answers best; an unreachable entry is skipped rather than retried forever.
- The app switches entry after three consecutive failed reconnects, and
  re-checks every 30 minutes while connected. A re-check only moves it when the
  entry in use stops answering or is at least 20% slower, and never within a
  minute of the previous switch, so a flaky mobile network cannot make it flap.
- A switch closes the event stream so it reconnects through the new entry.
  Heartbeats, receipts, results and upgrade reports resolve the entry per
  attempt, so an upload in flight follows the switch instead of failing.
- The config keeps the full list in the optional `controller_urls` field; a
  config with only `controller_url` keeps behaving exactly as before.

## Security model

Like the desktop Worker, this app is a remote-control agent, not a sandbox.

- The connection is outbound HTTPS only; the phone opens no listening ports.
- The access token lives in the app's private storage; pairing submitted only
  its SHA-256 hash.
- Whoever controls the paired Cybion account controls the phone within the
  limits below. Turn the switch off to stop everything.

## Limitations

- `bash` runs as the app's sandbox user (no root, no other apps' data).
- The screen must be unlocked for touch actions to reach apps.
- Screenshots require Android 11+.
- Remote upgrades download the official APK from the Cybion mirror (GitHub
  Releases fallback), verify its SHA-256 checksum and signing certificate, then
  hand it to the system installer for confirmation (a notification carries the
  same action when the installer cannot open by itself); the worker restarts
  into the new version afterwards. The first upgrade may ask you to allow
  installs from this app; on MIUI/HyperOS, builds up to 0.1.7 need one manual
  sideload first.

## Development

Toolchain: Rust (pinned in `rust/rust-toolchain.toml`), JDK 17, Android SDK 35,
NDK `27.2.12479018`, and `cargo-ndk`.

```sh
cd android
ANDROID_HOME=<sdk> JAVA_HOME=<jdk17> ./gradlew :app:assembleDebug
```

`pr-check` runs Rust formatting, clippy, tests, and builds the debug APK.

## Documentation

- [docs/design.md](docs/design.md) — architecture and decisions.
- [docs/protocol.md](docs/protocol.md) — the wire protocol as implemented.
- [docs/permissions.md](docs/permissions.md) — grants and OEM notes.
- [docs/troubleshooting.md](docs/troubleshooting.md) — failure modes and fixes.

The wire protocol is the Cybion Worker protocol; the desktop implementation
lives at [zccz14/cybion-worker](https://github.com/zccz14/cybion-worker).

## License

MIT
