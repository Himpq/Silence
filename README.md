# Silence
A powerful LSPosed module.  

> NO HOOK, NO LIFE.

## Features
- Freeze Apps
- Frontground App Listening
- Customization Performance

## Requirements

Silence needs root. It is not optional — the module relies on the Xposed framework
(VectorMatrix / LSPosed) to hook `system_server`, and the freeze/thaw work is executed by a
root helper process (`silence-daemon`) that is launched from the app.

- **Xposed framework**: enable Silence in the **system** scope, then reboot.
- **Root**: Magisk or KernelSU. Used to start and keep the `silence-daemon` helper alive.

## First run

1. Install Silence and open it once. The first launch asks for root and extracts
   `silence-daemon` to `/data/local/tmp/`.
2. Open VectorMatrix / LSPosed and enable Silence in the **system** scope.
3. Reboot.

After that, `silence-daemon` is started automatically on every boot.

## Troubleshooting: daemon does not start after reboot

The freeze chain is executed by `silence-daemon`, a root process that Silence starts on
`BOOT_COMPLETED`. If the daemon is not running, nothing can be frozen **or thawed**, so
check this first:

```
adb shell su -c "ps -A -o PID,NAME | grep silence-daemon"
```

No output means the app was never launched at boot. The most common cause on
Xiaomi / HyperOS devices is that the system blocks the broadcast from starting the app
process. The log line looks like this:

```
W BroadcastQueueInjector: Unable to launch app <package> for broadcast
  Intent { act=android.intent.action.BOOT_COMPLETED }: process is not permitted to auto start
```

HyperOS keeps its own "auto start" allowlist that is separate from Android's
`RECEIVE_BOOT_COMPLETED` permission, and it defaults to **denied**. The app cannot request
it at runtime — it has to be granted once by hand:

> Settings → 应用设置 → 应用管理 → Silence → 自启动 → 允许

Silence detects this state and shows a hint on the settings page when it is missing.

Workarounds if auto start is unavailable: open Silence once after each reboot, or disable
the vendor's background restriction for the package.

## Notice
- This app is **free** and **open-source**.
- We're **not responsible** for any damage to your device caused by using this module.
  Please use it at your own risk.
