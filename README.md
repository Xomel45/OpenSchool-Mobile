# OpenSchool for Android

Unofficial client for «Моя школа» (Gosuslugi). Kotlin + Jetpack Compose; all talking to the server is done by the Rust
[Bridge](../OpenSchool-Bridge), called through UniFFI bindings.

## Build

```bash
export ANDROID_HOME=~/Android/Sdk
./scripts/build-bridge.sh      # Rust library for arm64-v8a and x86_64 + Kotlin bindings (needs cargo-ndk and the NDK)
./gradlew assembleDebug        # the wrapper is Gradle 8.13: the Android plugin does not work with Gradle 9.6+
./gradlew testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`app/src/main/jniLibs` and `app/src/main/kotlin-gen` are produced by the script and are not in git.

## What is inside

Home, Schedule, Tasks, Marks, Settings (look, background, scale, notifications, network), the "what's new" feed, background
check (WorkManager), SMS code autofill on the login page (SMS User Consent), settings files shared with the desktop app.
Not ported: video background, the custom colour picker, the Terminal/Sunrise previews.
