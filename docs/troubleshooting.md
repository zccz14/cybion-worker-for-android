# Troubleshooting

Start with `adb logcat -s cybion-worker` (engine logs) and the status screen in
the app; both name the current phase.

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| "Could not contact Cybion" | The phone has no default network | Check Wi-Fi; mobile data can be off without any indicator on this screen. Tap **Retry** after fixing connectivity. |
| Pairing code expired | Codes live for 10 minutes | Toggle **Enable Worker** off and on to mint a new code. |
| Worker shows Stopped after hours | The OEM killed the service | Reopen the app; since 0.1.5 the worker restarts automatically while **Enable Worker** is on. |
| "invalid or expired bearer token" | The worker was revoked in the console while the phone was offline | **Reset pairing** in the app, then pair again. |
| Accessibility shows Missing | The system dropped the service (force-stop, update, OEM cleanup) | Re-enable **Cybion Worker** under Settings → Accessibility. |
| Screenshot fails or is black | Screen off, or device below API 30 | Wake the device and retry. |
| One `409` on heartbeat right after a restart | Normal boot-id handoff | Ignore it; the next heartbeat succeeds. |
| "browser_control is not supported" | By design on Android | Use `computer_use` with `open`, or the `open` action, instead. |
| Upgrade waiting for the install to finish | The phone has not confirmed the system install prompt yet | Unlock the phone and confirm the install dialog; the console keeps the pending state until the restart reports the new version. |
| Upgrade failed: cancelled | The install confirmation was dismissed | Request the upgrade again from the console; the current version keeps running. |
| Upgrade failed after a restart | The new version did not come up | Check `adb logcat -s cybion-worker`; the worker keeps the previous version and the upgrade can be requested again. |

## Where state lives

| File | Content |
| --- | --- |
| `worker.toml` | Worker configuration: controller URL, worker id, access token. |
| `worker.pairing.json` | Pending pairing (removed after approval). |
| `upgrade/cybion-worker-android-aarch64.apk` | Latest downloaded, verified APK for a controlled upgrade (replaced on each attempt). |

**Reset pairing** in the app removes both files. Both live in the app's private
data directory (`/data/data/io.ntnl.cybion.worker/files`), reachable with
`adb shell run-as io.ntnl.cybion.worker`.
