# QStar Light

Current development version: **0.5.35**.

Native Android app for two QStar/YOBIS PZ-05 BLE headlight controllers.

## Goals
- One APK for phone and Android head unit.
- Android 10+ support, with Android 12+ BLE runtime permissions.
- Responsive portrait phone UI and a dedicated landscape layout for 1024x600-class head units.
- Two QStar devices, connected one after another rather than with simultaneous connect storms.
- Serialized writes to lamp A then lamp B with spacing between writes.
- Drive Light-compatible random challenge/AES handshake, state parsing and 4-digit password support.
- Yellow / warm / white presets, CCT slider, brightness slider, power.
- RSSI diagnostics.
- Home-screen widget: Yellow, White, brightness down/up.
- Optional boot routine: power on, set yellow, fade to the saved color.

## Default devices
The first build is preloaded with the two controllers from the field test:

- `QStar~D35D` / `C2:15:11:00:D3:5D`
- `QStar~F072` / `F2:16:11:00:F0:72`

Use Scan -> Select 2 if the addresses ever change or the app is used with a different pair.

## Important design choice
The stock app can start writes to multiple devices in parallel. QStar Light deliberately serializes device operations. Connection attempts are staggered and control writes are sent to one lamp, then the other. This is intended to reduce the dual-GATT instability observed during testing.

## Build
Open the folder in Android Studio. Use JDK 17. The project is configured for:

- minSdk 26
- targetSdk 35
- compileSdk 35
- AGP 8.7.3
- Kotlin 2.0.21

This archive does not contain a Gradle wrapper binary or Android SDK. Android Studio can use/download the required Gradle/SDK components.

## First field test
1. Install on the Fold.
2. Grant Bluetooth permissions. Android 10 also requires location permission for BLE scanning.
3. Stand near the headlights for the first test.
4. Press Connect.
5. Verify both device rows reach `READY`.
6. Test Yellow, White and brightness.
7. Confirm D35D accepts the default password `1234`. If not, open Passwords and enter the actual 4-digit password.
8. Only after the phone test, sideload the same APK to the CYCLONE head unit.

## Hardware limit
Software can improve reconnect/retry behavior but cannot repair genuinely weak RF. The boot fade can only start after Android has booted and BLE has connected to the controllers. It cannot animate before the head unit/phone is alive.

## Settings synchronization and diagnostics (0.5.7)

- Install 0.5.7 on both devices for acknowledged, bidirectional settings synchronization.
- While connected, setting sliders publish the latest value every 120 ms and flush on release. This is the UI send interval, not a guaranteed network latency. Missing acknowledgements are retried after 1.4 seconds; offline settings reconcile after reconnecting.
- Revisions are monotonic per device; a stable origin ID resolves equal-revision conflicts. Older snapshots and acknowledgements cannot mark newer edits synchronized. Concurrent offline edits resolve by the ordered revision/origin pair; keep device clocks accurate.
- Shared configuration: lamp power, color, brightness, startup/fade profile, strobe profile, selected lamps and their BLE passwords. Role, pairing PIN, host, autostart, connection policy and installer options remain device-local.
- **Синхронізувати зараз** re-exchanges the saved settings. **Перевірити оновлення додатка зараз** checks GitHub immediately when a network is available; Android's installer confirmation still applies.
- The third **Діагностика** tab shows timestamped service, transport, command, sync and updater events. Events are collected while the UI is closed. The on-device rotating journal is capped at two 512 KiB files, with up to 2,000 recent events available to copy/export as UTF-8 TXT; the screen shows the last 300. Large clipboard copies are shortened with an explicit notice; TXT export retains all 2,000 entries. PINs and BLE passwords are redacted.
- Automated checks: `gradle :app:testDebugUnitTest :app:assembleDebug`. Robolectric tests cover settings reconciliation, preserved local options, third-tab navigation and slider gesture exclusion. Real phone/head-unit transport latency and BLE operation still require a connected-device check.
