# QStar / YOBIS PZ-05 protocol used by this project

This project was built from static analysis of the user-provided Drive Light APK plus live nRF Connect observations.

## GATT
- Write service: `FFD5`
- Write characteristic: `FFD9`
- Notify service: `FFD0`
- Notify characteristic: `FFD4`

## Drive Light handshake
When a connection starts, Drive Light generates 16 random bytes, each in the range `1..90`. It inserts those bytes into:

`FB [16-byte challenge] FA`

The same challenge is reused for handshake retries within that connection. The controller replies with:

`F9 [AES-128-ECB(challenge)] F8`

AES key:

`D0 F9 F4 8C 59 A2 69 1D 20 53 CB DA 80 84 43 93`

After validation Drive Light writes `EF 01 77`, then `C5 F0 5C`.

## CCT command
`56 WW YY BB SS 00 AA`

- `WW`: white 0..100
- `YY`: yellow 0..100; stock UI uses `100-WW`
- `BB`: brightness 5..100
- `SS`: effect/mode, `00` for steady

Power on: `DD 23 33`

Power off: `DD 24 33`

## State
`66 10 PP YY WW BB SS ... 99`

`PP=23` means on, `PP=24` means off.

## Password
Password frame for 1234: `CF 01 02 03 04 FC`.

Password status frame is `2F ... PP ... F2`, where byte 8 is 1 when a password is required.
