#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
set -euo pipefail
# Product SDK and cbindgen header must be from the same checkout/build.
core="${RAMFLUX_CORE_DIR:?Set RAMFLUX_CORE_DIR to the product checkout with C ABI v2}"
ndk="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28 or newer}"
repo="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$repo/core/sdk/build/generated/native}"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:?Set a persistent Cargo target directory}"
export CARGO_BUILD_JOBS=2
export ANDROID_NDK_HOME="$ndk"
case "${2:-build}" in
  check) action=check ;;
  build) action=build ;;
  *) echo 'Expected build or check' >&2; exit 1 ;;
esac
mkdir -p "$out/jniLibs/arm64-v8a" "$out/include"
out="$(cd "$out" && pwd)"
# cargo-ndk forwards the 16 KB link flags to the one Rust shared library.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384'
openssl_dir="$(bash "$repo/scripts/build-openssl.sh")"
export OPENSSL_LIB_DIR="$openssl_dir/lib"
export OPENSSL_INCLUDE_DIR="$openssl_dir/include"
ndk_args=(-t arm64-v8a --platform 28)
if [[ "$action" == build ]]; then ndk_args+=(-o "$out/jniLibs"); fi
(cd "$core" && cargo ndk "${ndk_args[@]}" "$action" -p ramflux-sdk --lib --features c-abi --locked)
if [[ "$action" == check ]]; then exit 0; fi
cp "$CARGO_TARGET_DIR/include/ramflux_sdk.h" "$out/include/ramflux_sdk.h"
cmake_bin="${ANDROID_HOME:?Set ANDROID_HOME}/cmake/3.22.1/bin/cmake"
"$cmake_bin" -S "$repo/core/sdk/src/main/cpp" -B "$out/cmake" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ANDROID_HOME/cmake/3.22.1/bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
  -DRAMFLUX_NATIVE_DIR="$out/jniLibs" -DRAMFLUX_HEADER_DIR="$out/include" \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$out/jniLibs/arm64-v8a"
"$cmake_bin" --build "$out/cmake" --parallel 2
objdump="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-objdump"
for library in "$out/jniLibs/arm64-v8a/"*.so; do
  "$objdump" -p "$library" | python3 "$repo/scripts/check-elf-alignment.py"
done
