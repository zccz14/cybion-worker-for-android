# Permissions and OEM notes

The worker needs four grants from the user; everything else is handled by the
app. The main screen shows one row per grant with a button that opens the
relevant system screen.

| Grant | Why it is needed |
| --- | --- |
| Notifications | The foreground-service notification is the user-visible signal that the phone currently accepts remote tasks, and Android requires it for long-running foreground services. |
| Accessibility service | The only public Android mechanism for gestures (`dispatchGesture`), text input, screenshots (`takeScreenshot`, API 30+), the UI tree, and global keys. Without it, `computer_use` reports an explicit error. |
| Battery-optimization exemption | Keeps the OS from suspending the worker when the screen is off. |
| Autostart (OEM settings) | Lets the worker come back after reboot and after aggressive background kills. |

The controlled self-upgrade needs one conditional toggle: **Allow from this
source** (install unknown apps) for this app. Android links to that setting
from the install prompt; test rigs can pre-grant it with
`adb shell appops set io.ntnl.cybion.worker REQUEST_INSTALL_PACKAGES allow`.

## Xiaomi / HyperOS notes

- `adb install` shows a "USB 安装提示" confirmation that requires an unlocked
  screen; without confirmation the install fails with
  `INSTALL_FAILED_USER_RESTRICTED`. Enable "Install via USB" in Developer
  options.
- Sideloaded apps may get their notification app-op set to `ignore`. Check and
  fix it with:
  `adb shell cmd appops set io.ntnl.cybion.worker POST_NOTIFICATION allow`.
- App-driven upgrades show an install confirmation as well; keep the screen
  unlocked when the upgrade dialog appears.
- MIUI kills background processes aggressively even with a foreground service.
  The battery exemption helps; since 0.1.5 the app also restarts the worker
  whenever it is reopened while **Enable Worker** is on, so reopening the app
  is enough to bring the worker back.

## Security model

The app is a remote-control agent: an approved account can run shell commands
and control the screen. Approve only pairing codes that this phone displayed.
For the same reason `browser_control` is unavailable on Android and reports an
explicit error instead of silently doing nothing.

## Screen and device requirements

- Android 11 (API 30) or newer; screenshots require API 30+.
- Touch input needs the screen to be on. Gestures may be suppressed while the
  keyguard is showing on some OEM builds.
