# READ FIRST — QStarLight

Перед змінами прочитай `AGENTS.md` і `docs/PROJECT_GUIDE.md`.

## Головне

QStarLight — один APK з двома ролями:
- PHONE;
- HUB / Android head unit.

Це не два незалежні застосунки. Вони використовують спільний settings model, BLE core і remote protocol.

Не переписуй routing, BLE connection strategy, sync conflict rules, autostart або updater "для простоти".

## Джерело істини версії

README може відставати.

На момент аудиту фактичний Gradle:
- versionName: 0.5.47
- versionCode: 55

Дивись `app/build.gradle.kts`.

## Особливо крихке

- два BLE контролери;
- serial connect/write policy;
- challenge/AES handshake;
- PHONE↔HUB routing;
- bidirectional config sync;
- role-local vs shared settings;
- one-hour phone offline grace;
- head-unit boot/wake behavior;
- stable APK signing;
- updater/signature validation.

Не чіпай ці контракти побічно.
