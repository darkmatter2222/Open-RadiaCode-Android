# Agent workflow notes

This file documents the repeatable build + deploy loop used in this repo.

## Prereqs

- Android SDK installed at `C:\Users\ryans\AppData\Local\Android\Sdk` (or set `ANDROID_SDK_ROOT`)
- JDK 17 installed (or use Android Studio embedded JBR)
- Phone connected over USB with **USB debugging** enabled

If you hit `JAVA_HOME is not set` when running Gradle, you can use Android Studio's embedded JBR for the current PowerShell session:

- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`
- `$env:Path = "$env:JAVA_HOME\bin;$env:Path"`

## Build + install (fast loop)

From repo root:

- `./gradlew assembleDebug`
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Debug logs

- `adb logcat -v time -s RadiaCode`

Optional checks:

- `adb devices`
- `adb shell pm list packages | findstr /i com.radiacode.ble`

## App identity

- Launcher name: **RadiaCode Reader**
- Package: `com.radiacode.ble`

## Notes

- If `adb devices` shows `unauthorized`, accept the prompt on the phone and rerun.
- If scan doesn’t find the device, ensure the official RadiaCode app is not connected and the device isn’t connected to the OS Bluetooth settings as an active connection.
- You can’t simulate `BOOT_COMPLETED` / `USER_UNLOCKED` with `adb shell am broadcast` (Android blocks protected broadcasts); validate auto-start by rebooting the phone.
- The foreground service is `exported=false`, so `adb shell am start-foreground-service ...` won’t work; start it by launching the app (it auto-starts when a preferred device is set).
