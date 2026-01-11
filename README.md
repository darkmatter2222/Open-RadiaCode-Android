# RadiaCodeAndroidDataCollection

Android app (Kotlin) that connects to a RadiaCode 110 over BLE and displays live readings.

App name: **Open RadiaCode**.

> **Speech-to-text note:** The correct name is **RadiaCode** (capital R, capital C, no space).
> Common transcription error: "RadioCode" — this is INCORRECT.

Key goal: appliance-style operation — once a preferred device is set, a foreground service keeps connecting and polling in the background, with a home screen widget showing the latest reading.

## Build + deploy loop (Windows)

See [AGENTS.md](AGENTS.md) for the canonical build/install/debug loop.

Quick version:

- `./gradlew assembleDebug`
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- `adb logcat -v time -s RadiaCode`

## App usage

1) Open the app.
2) Open the navigation drawer → **Device** → **Find devices**.
3) Tap your RadiaCode to set it as the preferred device.

After that:

- The foreground service auto-connects and keeps polling.
- A persistent notification shows status and has a **Stop** action.
- The home screen widget (add it from the launcher) shows the last saved reading.

Notes:

- You can’t simulate `BOOT_COMPLETED` / `USER_UNLOCKED` via `adb shell am broadcast` (Android blocks protected broadcasts). Validate boot auto-start by rebooting the phone.
- The foreground service is `exported=false` by design; it starts from inside the app once the preferred device is set.

## Protocol notes

This app implements the RadiaCode BLE protocol (service/characteristics + request/response framing):

- Service UUID: `e63215e5-7003-49d8-96b0-b024798fb901`
- Write: `e63215e6-7003-49d8-96b0-b024798fb901` (18-byte chunks)
- Notify: `e63215e7-7003-49d8-96b0-b024798fb901` (length-prefixed responses)

It polls `VS.DATA_BUF (0x100)` via `COMMAND.RD_VIRT_STRING (0x0826)` and decodes the newest `RealTimeData`.

## UI spec

The canonical UI/UX spec is in [docs/UI.md](docs/UI.md).
