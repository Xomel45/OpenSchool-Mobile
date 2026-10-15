# OpenSchool for Android

Unofficial client for «Моя школа» (Gosuslugi). Kotlin + Jetpack Compose; all talking to the server is done by the Rust
[Bridge](../OpenSchool-Bridge), called through UniFFI bindings.

## Build

```bash
export ANDROID_HOME=~/Android/Sdk
./scripts/build-bridge.sh      # Rust library for arm64-v8a, armeabi-v7a and x86_64 + Kotlin bindings (needs cargo-ndk and the NDK)
./gradlew assembleRelease      # the wrapper is Gradle 8.13: the Android plugin does not work with Gradle 9.6+
./gradlew testDebugUnitTest
adb install -r app/build/outputs/apk/release/app-release.apk
```

`app/src/main/jniLibs` and `app/src/main/kotlin-gen` are produced by the script and are not in git.

**Which APK to install:** the release build (R8-optimised, with baseline profiles) is several times faster than the debug build on
cheap phones; CI uploads both (`openschool-android-release`). It is signed with `OPENSCHOOL_KEYSTORE` (+ `_PASSWORD`, `OPENSCHOOL_KEY_ALIAS`,
`OPENSCHOOL_KEY_PASSWORD`) when set (CI: the `ANDROID_KEYSTORE_B64` … secrets), otherwise with the debug key of the machine that built it
(then a newer build cannot update it: uninstall first).

**Phones:** Android 8+ (minSdk 26). 32-bit Android on a 64-bit CPU (Redmi 9A and other phones with 2 GB) is supported (armeabi-v7a).
All native libraries are 16 KB-page aligned (Android 15+). The login page needs a system WebView/Chrome 80 or newer: an older one
is detected and the app asks to update it.

## What is inside

Home, Schedule, Tasks, Marks, Settings (look, background, scale, notifications, network), the "what's new" feed (new marks,
homework, absences, timetable changes), background check (WorkManager), evening summary of the next school day, tests marked in the
timetable, own notes for lessons, search over the school year, charts and «what I missed», `.ics` export, sharing the day's homework,
homework attachments, home-screen widgets, «current lesson» in the notification shade (optional), SMS code autofill on the login
page (SMS User Consent), settings files shared with the desktop app.
Not ported: video background, the custom colour picker, the Terminal/Sunrise previews.
