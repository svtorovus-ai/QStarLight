# QStarLight — правила для агентів

## MUST READ

1. `00_READ_FIRST.md`
2. `README.md`
3. `docs/PROJECT_GUIDE.md`
4. `.github/workflows/android.yml`
5. `app/build.gradle.kts`
6. `app/src/main/AndroidManifest.xml`

## 1. Product model

Один APK `ua.grey.qstarlight` працює і на телефоні, і на Android head unit.

Role визначає runtime behavior:
- HUB напряму володіє BLE connection до ламп;
- PHONE може керувати напряму або через HUB залежно від актуального routing/state.

Не створюй окремі несумісні code paths для одного й того самого command.

## 2. BLE contract

Два контролери підключаються/керуються обережно та послідовно.

Причина: паралельні GATT connect/write створювали instability.

Не "оптимізуй" на simultaneous connect/write без реального device testing.

Handshake/characteristics/password behavior не змінювати навмання.

## 3. Command routing

Критичні компоненти:
- `QStarBleService`;
- `ControlDispatcher`;
- `RemoteLinkService`;
- `HubTransport`;
- `BlePrefs`.

Команда з UI/widget/remote повинна дійти до того пристрою, який реально контролює лампи.

Не плутати:
- config sync;
- command delivery;
- BLE ownership;
- runtime link state.

Зелений "synced" не означає, що command реально дійшла до ламп.

## 4. PHONE/HUB local settings

Не всі settings синхронізуються.

Shared:
- lamp power;
- white/CCT;
- brightness;
- startup/fade;
- strobe;
- selected lamps;
- BLE passwords.

Device-local:
- role;
- hub pairing PIN;
- host;
- autostart;
- connection policy;
- installer/root update options та інші device-specific flags.

Не починай синхронізувати local-only settings "для зручності".

## 5. Sync revisions

Поточний sync використовує revision + stable origin для conflict resolution.

Не замінювати last-write-wins по локальному clock без аналізу.
Не дозволяти старому ACK позначати новішу edit як synced.
Offline edits мають reconcile після reconnect.

## 6. Phone background policy

PHONE не повинен висіти в foreground безкінечно, якщо давно нема ламп/HUB.

Існує offline grace logic.

Не створювати новий grace window лише через Android service recreation/null intent.
Не робити START_STICKY способом "щоб точно жило".

## 7. HUB autostart

Head unit має спеціальні boot/wake/ACC intents.

Не видаляти OEM wake actions як "зайві" без фізичного тесту на CYCLONE.

BootReceiver/AutoWakeReceiver/PresenceMonitor пов'язані.

## 8. Welcome/startup/strobe

Це stateful BLE sequences, а не просто UI animation.

Не запускати Welcome до готовності потрібних ламп.
Не змішувати startup profile зі failsafe/default color.
Strobe timing не повинен блокувати main/service thread.

## 9. Widget

Widget — повноцінна control surface.

Його command path має бути тим самим, що й app UI.
Не робити widget-only fake state.

## 10. Diagnostics

Diagnostics є production debugging surface.

Не логувати PIN/passwords.
Не прибирати timestamps/autoscroll/export/connection history.
Remote/BLE status повинні описувати реальний transport state.

## 11. Signing

applicationId:
`ua.grey.qstarlight`.

Production update повинен бути підписаний тим самим stable certificate.

GitHub secrets:
- `QSTAR_KEYSTORE_B64`
- `QSTAR_STORE_PASSWORD`
- `QSTAR_KEY_ALIAS`
- `QSTAR_KEY_PASSWORD`

Debug signer fallback не є production signing replacement.

## 12. Build

CI:
- JDK 17;
- SDK 35;
- Gradle 8.9;
- unit tests;
- debug or stable release build;
- publish `QStarLight.apk` + SHA256 under tag `v<versionName>`.

Перед release versionCode має зростати.

## 13. Updater

Updater/remote APK install перевіряє:
- package name;
- version;
- signing certificate.

Не послаблювати ці перевірки.

Root install є device-specific optional behavior, не загальний спосіб обходити Android installer.

## 14. Regression

Перед release:
- обидві лампи;
- PHONE direct;
- PHONE→HUB;
- HUB direct;
- widget;
- sync;
- offline/reconnect;
- boot;
- one-hour grace;
- welcome from white/yellow;
- strobe;
- brightness/CCT;
- updater over installed stable signature;
- head-unit physical test.

Build без hardware test не доводить BLE/transport correctness.
