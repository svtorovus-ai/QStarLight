# QStarLight — технічна карта

## Призначення

QStarLight керує двома BLE headlight controllers з телефону та Android head unit.

Один application package використовується в обох ролях.

## BLE layer

`QStarBleService` координує direct BLE:
- discovery/connect;
- handshake;
- state;
- serialized commands;
- readiness;
- reconnect.

Поточна стратегія навмисно уникає connect storm до двох контролерів.

## Remote layer

PHONE і HUB взаємодіють через:
- `RemoteLinkService`;
- `HubTransport`;
- discovery/command ports;
- synchronized state/config.

Remote transport не є BLE transport.
HUB отримує command і лише потім застосовує її до BLE.

## ControlDispatcher

Це важлива routing boundary.

UI, widget та інші surfaces мають викликати спільний dispatcher, а не напряму реалізовувати різні control algorithms.

## Preferences/state

`BlePrefs` містить:
- persistent user config;
- role;
- selected devices;
- sync revisions;
- runtime link state;
- timestamps;
- connection policy.

Перед додаванням нового key визнач:
1. shared чи local;
2. default;
3. migration;
4. sync behavior;
5. diagnostics;
6. effect on boot/reconnect.

## Config sync

Вимоги:
- bidirectional;
- acknowledged;
- monotonic revision;
- origin-aware equal revision resolution;
- offline reconciliation;
- no stale ACK success.

UI send interval не дорівнює transport guarantee.

## Presence/background

PHONE lifecycle повинен зберігати батарею.
HUB може мати постійніший ownership, бо є автомобільним контролером.

Не переносити HUB lifecycle на PHONE.

## Boot/head unit

Manifest містить стандартні й OEM/head-unit wake intents.
Вони існують через реальне обладнання.

Будь-яка зміна boot logic має перевірятись на CYCLONE, не лише emulator/phone.

## Startup / welcome

Startup profiles задають стартовий/цільовий white level, brightness і fade.

Welcome є окремою once-per-power/connect поведінкою.
Не змішувати ці state machines.

## Update pipeline

`.github/workflows/android.yml`:
1. checkout;
2. JDK17;
3. SDK35;
4. Gradle8.9;
5. restore stable or persistent debug signing;
6. unit tests;
7. build;
8. verify APK;
9. publish release asset;
10. publish SHA256.

Production release має використовувати stable signing secrets.

## App updater

Update-related components можуть:
- перевіряти GitHub release;
- transfer APK між PHONE/HUB;
- validate received APK;
- request Android install.

`UpdateManager.validateReceivedApk` є security boundary: package/version/signature checks не послаблювати.

## Версія

На момент аудиту source of truth:
- 0.5.47
- versionCode 55.

README згадує старішу dev version і не є release source of truth.

## Hardware regression matrix

Перевір:
- controller A only;
- B only;
- A+B;
- weak signal;
- disconnect одного;
- reconnect одного;
- phone owns lamps;
- hub owns lamps;
- phone command through hub;
- widget through current owner;
- settings edit on each device;
- both devices edit offline then reconnect;
- ACC/boot/wake;
- phone leaves vehicle and grace expires.

## Не робити

- parallel GATT "optimization" без hardware proof;
- duplicate controller state per UI;
- sync role/PIN accidentally;
- keep phone forever foreground;
- trust "connected" UI without command ACK/state;
- downgrade signing/versionCode;
- publish debug-signed update as stable.
