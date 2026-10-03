# Protocol

The app speaks the same worker protocol as the desktop `cybion-worker` CLI:
outbound HTTPS only, no listening sockets. This document describes the wire
behavior the app implements so it can be compared against the controller
without reading the Rust source.

## Identity and pairing

- The device generates two secrets locally: `device_secret` (proves possession
  of the pending pairing) and `access_token` (the long-lived worker
  credential). Only their hashes travel to the controller.
- Pairing starts with `POST /worker/v1/pairings` carrying `device_secret`,
  `token_hash`, `hostname`, `platform`, and `version`. The reply contains a
  `user_code` (12 hex characters, displayed as `XXXX-XXXX-XXXX`) with a
  600-second expiry.
- The device polls `GET /worker/v1/pairings/{id}` (bearer `device_secret`)
  every 3 seconds until the status becomes `approved`, `cancelled`, or the
  pairing expires (HTTP 410). The pending pairing is persisted in
  `worker.pairing.json`, so a restart resumes polling instead of minting a new
  code.
- The user approves the code in the Cybion console. The app deep-links to
  `https://cybion.ntnl.io/#/workers?code=…` from the pairing card.

## Event stream

`GET /worker/v1/users/{user_id}/workers/{worker_id}/events` (SSE) carries all
server-to-device traffic. Headers: `Authorization: Bearer <access_token>`,
`x-cybion-worker-boot-id`, and `x-cybion-worker-version`.

| Event | Payload | Meaning |
| --- | --- | --- |
| `tool_call` | `{id, thread_id, name, arguments}` | execute a tool; checks ride the same event |
| `cancel` | `{id}` | abort a running call |
| `upgrade` | `{id, version, boot_id}` | controlled APK upgrade; see "Controlled upgrades" below |
| `heartbeat` | `{}` | keep-alive marker |

- A 45-second idle timeout ends the session and triggers reconnect with bounded
  exponential backoff, capped at 30 seconds and jittered.
- The **boot id** is a fresh UUID per worker process. The controller uses it to
  deduplicate deliveries and, when the boot changes, fails calls that the
  previous process had not acknowledged (`execution_outcome: unknown`). While
  a restart is being noticed, one transient `409 report from an obsolete
  Worker process` can appear on `POST .../heartbeat`; the next heartbeat
  succeeds.

## Delivery, receipts, and results

For every received call (duplicates included) the device posts a receipt to
`POST .../{checks|calls}/{call_id}/received`, executes the tool, and posts the
result to `POST .../{checks|calls}/{call_id}/result` as
`{"result": …, "failed": bool}`. Both posts are retried until confirmed, and
`Retry-After` responses are honored.

- `checks` is the route family for `diagnostics`; every other tool uses
  `calls`.
- **Deduplication**: a call is executed once per boot, keyed by the call ID
  plus a SHA-256 fingerprint of `{thread_id, name, arguments}`. A replayed ID
  with identical arguments is skipped; a replayed ID with different arguments
  is dropped with a warning.
- **Cancellation**: `cancel` events signal per-call watch channels. `bash`
  kills the process group with `SIGKILL`; calls not yet started exit before
  running.

## Controlled upgrades

The console can queue a target version for the Worker. On `upgrade`:

1. The device downloads `cybion-worker-android-aarch64.apk` for the requested
   version from the Controller mirror (`{controller}/worker-release/{version}/`,
   GitHub Releases fallback) together with its `.sha256` checksum.
2. The archive must match the checksum, must be signed by the official release
   certificate, and its `versionName` must equal the requested version; any
   mismatch aborts the upgrade before the installer is engaged.
3. The verified APK is streamed into a `PackageInstaller` session, and the
   system shows its own confirmation prompt before installing.
4. The Worker reports `installing` once the installer owns the upgrade, and
   `failed` with a reason on any error or cancellation. Success is not
   reported: the Controller infers it from the version that the restarting
   Worker reports, and a restart without the new version is recorded as
   `failed`.
5. `android.intent.action.MY_PACKAGE_REPLACED` brings the worker back after the
   update while it is enabled, so an attended upgrade needs only the system
   confirmation.

## Liveness

Every 10 seconds: `POST .../heartbeat` with `{hostname, version}` and
`POST .../resources` with `{logical_cpus, cpu_usage_percent,
memory_used_bytes, memory_total_bytes}`. The controller marks a worker offline
when the last report is stale.

## Version floor

The controller enables worker checks (diagnostics) for workers at version
`0.1.4` or newer. The app implements the full checks route family, and its
version tracks that supported-worker floor.
