#!/usr/bin/env bash
# Builds the Rust Bridge for Android and generates its Kotlin bindings into this app.
# Needs: rustup targets aarch64-linux-android and x86_64-linux-android, cargo-ndk, Android NDK (ANDROID_NDK_HOME or ~/Android/Sdk/ndk/*).
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
bridge="${BRIDGE_DIR:-$here/../OpenSchool-Bridge}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$HOME"/Android/Sdk/ndk/* | sort -V | tail -1)}"
cd "$bridge"
rm -rf "$here/app/src/main/jniLibs" "$here/app/src/main/kotlin-gen"
cargo ndk -t arm64-v8a -t x86_64 -P 26 -o "$here/app/src/main/jniLibs" build --release
# The bindings are read from a host build: the stripped Android libraries carry no UniFFI metadata.
cargo build
cargo run -q --features cli --bin uniffi-bindgen generate --library target/debug/libopenschool_bridge.so \
  --language kotlin --no-format --out-dir "$here/app/src/main/kotlin-gen"
echo "done: $(find "$here/app/src/main" -name '*.so' | wc -l) libraries, bindings in app/src/main/kotlin-gen"
