#!/usr/bin/env bash
# Build the gentle-mobile-core Android artifacts reproducibly:
#
#   . scripts/android-env.sh   (the script sources it itself if not already)
#   scripts/build-core-android.sh
#
# Steps:
#   1. host build of the cdylib — only used by uniffi-bindgen to read the
#      scaffolding (an Android .so cannot be dlopen'd on the host);
#   2. generate the Kotlin bindings into the app's source set (committed);
#   3. cross-compile the cdylib for x86_64-linux-android (emulator) and
#      aarch64-linux-android (phone);
#   4. copy the .so files into jniLibs/ where Gradle packages them
#      (git-ignored — run this script before any Gradle build).
#
# Nothing here installs toolchains: android-env.sh documents them.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# The env script is written to be sourced; source it unless the toolchain is
# already on PATH.
if ! command -v cargo >/dev/null 2>&1 || [ -z "${ANDROID_NDK_HOME:-}" ]; then
    # shellcheck disable=SC1091
    . scripts/android-env.sh
fi

CRATE="gentle-mobile-core"
LIBNAME="libgentle_mobile_core.so"
JNILIBS="ui-android/app/src/main/jniLibs"
KOTLIN_SRC="ui-android/app/src/main/java"
GEN_DIR="$(mktemp -d)"
trap 'rm -rf "$GEN_DIR"' EXIT

echo "== 1/4 host build (bindgen input only)"
cargo build -p "$CRATE" --release

echo "== 2/4 Kotlin bindings"
# The bindgen binary lives in this crate (src/bin/uniffi-bindgen.rs) and
# forwards to uniffi::uniffi_bindgen_main, so the binding generator version
# always matches the scaffolding version.
cargo run -q -p "$CRATE" --bin uniffi-bindgen -- generate \
    --library "target/release/$LIBNAME" \
    --config "core/uniffi.toml" \
    --language kotlin \
    --out-dir "$GEN_DIR"
rm -rf "$KOTLIN_SRC/com/gentlemanprogramming/gentlemobile/core"
mkdir -p "$KOTLIN_SRC/com/gentlemanprogramming/gentlemobile"
cp -r "$GEN_DIR/com/gentlemanprogramming/gentlemobile/core" \
    "$KOTLIN_SRC/com/gentlemanprogramming/gentlemobile/"
echo "   generated $(find "$KOTLIN_SRC/com/gentlemanprogramming/gentlemobile/core" -name '*.kt' | wc -l) Kotlin file(s)"

echo "== 3/4 cross-compile: x86_64-linux-android (emulator) + aarch64-linux-android (phone)"
cargo build -p "$CRATE" --release --target x86_64-linux-android
cargo build -p "$CRATE" --release --target aarch64-linux-android

echo "== 4/4 jniLibs"
for pair in "x86_64:x86_64-linux-android" "arm64-v8a:aarch64-linux-android"; do
    abi="${pair%%:*}"
    triple="${pair##*:}"
    mkdir -p "$JNILIBS/$abi"
    cp "target/$triple/release/$LIBNAME" "$JNILIBS/$abi/"
done

echo "done: jniLibs + Kotlin bindings are in place; run the Gradle build next."
