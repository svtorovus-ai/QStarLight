# QStar Light

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

## Signing and OTA updates

The update signing key is intentionally not stored in this repository. Stable APK updates must use the same signing key as the version already installed on the phone and head unit. GitHub Actions can build a stable release when the repository secrets `QSTAR_KEYSTORE_B64`, `QSTAR_STORE_PASSWORD`, `QSTAR_KEY_ALIAS`, and `QSTAR_KEY_PASSWORD` are configured. Without them, CI builds a debug APK only.
