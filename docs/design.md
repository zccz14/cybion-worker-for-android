# Design

The app is a Cybion Worker: it receives `tool_call` events from the Controller
over an outbound SSE connection and posts results back over HTTPS. There are no
listening sockets on the phone.

## Layers

| Layer | Language | Responsibility |
| --- | --- | --- |
| Protocol core | Rust | Pairing, SSE session, delivery receipts, dedup, retries, heartbeats, resources, tool dispatch |
| Platform bridge | Rust -> Kotlin (JNI) | Gestures, text input, screenshots, UI tree, app launch, global actions |
| Android shell | Kotlin | Foreground service, accessibility service, boot receiver, minimal UI |
| Build | Gradle + cargo-ndk | Produces one APK containing the Rust `cdylib` |

## Wire protocol (as implemented)

| Step | Request |
| --- | --- |
| Pair | `POST /worker/v1/pairings` then poll `GET /worker/v1/pairings/{id}` |
| Events | `GET /worker/v1/users/{user}/workers/{worker}/events` (SSE, `x-cybion-worker-boot-id`, `x-cybion-worker-version`) |
| Receipt | `POST .../{checks\|calls}/{call_id}/received` |
| Result | `POST .../{checks\|calls}/{call_id}/result` |
| Liveness | `POST .../heartbeat` and `POST .../resources` every 10 s |
| Upgrade | answered with `POST .../upgrade {"status":"failed"}` (APK updates are manual) |

## Tool coverage

`bash` maps to `/system/bin/sh` with process-group timeout and cancellation.
`computer_use` keeps the desktop action names (`click`, `type`, `screenshot`)
and adds Android-specific actions (`swipe`, `long_press`, `ui_tree`, `launch`,
`open`, `key`) that reuse the existing argument schema. `browser_control` is
rejected with an explicit error and reported as `missing_dependency` in
diagnostics.

## Decisions

- Outbound-only SSE: works behind NAT/mobile networks and needs no inbound
  port (unlike local-server designs).
- Rust core + minimal Kotlin shell: Android requires JVM entry points for the
  activity, service, and accessibility service; everything else stays in Rust.
- Foreground service with `specialUse` type: the worker is a user-visible,
  user-enabled remote-execution agent.
- The app version tracks the controller's supported-worker floor (`0.1.4`):
  the console only offers checks (diagnostics) to workers at or above it.
