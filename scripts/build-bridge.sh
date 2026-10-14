#!/usr/bin/env bash
# Builds the Rust Bridge for Android and generates its Kotlin bindings into this app.
# Needs: rustup targets aarch64-linux-android and x86_64-linux-android, cargo-ndk, an Android NDK.
# The NDK: the first non-empty of ANDROID_NDK_HOME, ANDROID_NDK_LATEST_HOME, ANDROID_NDK_ROOT (GitHub runners set this one),
# else the newest in $ANDROID_HOME/ndk/ or ~/Android/Sdk/ndk/.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
bridge="${BRIDGE_DIR:-$here/../OpenSchool-Bridge}"
newest() { ls -d "$1"/ndk/* 2>/dev/null | sort -V | tail -1 || true; }
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"
[ -n "$ndk" ] || ndk="$(newest "${ANDROID_HOME:-/nonexistent}")"
[ -n "$ndk" ] || ndk="$(newest "$HOME/Android/Sdk")"
if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; fi
export ANDROID_NDK_HOME="$ndk"
echo "NDK: $ANDROID_NDK_HOME"
# 16 KB pages (Android 15+ devices, required by Google Play): NDK r28+ aligns this way by default, r27 (GitHub runners) does not.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
cd "$bridge"
rm -rf "$here/app/src/main/jniLibs" "$here/app/src/main/kotlin-gen"
cargo ndk -t arm64-v8a -t x86_64 -P 26 -o "$here/app/src/main/jniLibs" build --release
# The bindings are read from a host build: the stripped Android libraries carry no UniFFI metadata.
cargo build
cargo run -q --features cli --bin uniffi-bindgen generate --library target/debug/libopenschool_bridge.so \
  --language kotlin --no-format --out-dir "$here/app/src/main/kotlin-gen"
# Refuse libraries that would not load on a 16 KB-page device.
for so in "$here"/app/src/main/jniLibs/*/*.so; do
  if readelf -lW "$so" | awk '/LOAD/ {print $NF}' | grep -qv '^0x4000$'; then echo "not 16 KB aligned: $so" >&2; exit 1; fi
done
echo "done: $(find "$here/app/src/main" -name '*.so' | wc -l) libraries (16 KB aligned), bindings in app/src/main/kotlin-gen"
