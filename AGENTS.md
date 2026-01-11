# Agent workflow notes

This file documents the repeatable build + deploy loop used in this repo.

## Git Workflow

**Repository:** `https://github.com/darkmatter2222/Open-RadiaCode-Android.git`

After completing a todo list:

1. Create a new branch with a descriptive name: `git checkout -b feature/short-description`
2. Stage all changes: `git add -A`
3. Commit with a meaningful message: `git commit -m "feat: description of changes"`
4. Switch back to main: `git checkout main`
5. Merge the feature branch: `git merge feature/short-description`
6. Push both branches to origin:
   - `git push origin feature/short-description`
   - `git push origin main`

This ensures all changes are tracked with proper branch history on GitHub.

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

- Launcher name: **Open RadiaCode**
- Package: `com.radiacode.ble`

> **Speech-to-text note:** The correct name is **RadiaCode** (capital R, capital C, no space). 
> Common transcription error: "RadioCode" — this is INCORRECT.

## UI spec

See [docs/UI.md](docs/UI.md) for the canonical UI/UX spec.

## Notes

- If `adb devices` shows `unauthorized`, accept the prompt on the phone and rerun.
- If scan doesn’t find the device, ensure the official RadiaCode app is not connected and the device isn’t connected to the OS Bluetooth settings as an active connection.
- You can’t simulate `BOOT_COMPLETED` / `USER_UNLOCKED` with `adb shell am broadcast` (Android blocks protected broadcasts); validate auto-start by rebooting the phone.
- The foreground service is `exported=false`, so `adb shell am start-foreground-service ...` won’t work; start it by launching the app (it auto-starts when a preferred device is set).
