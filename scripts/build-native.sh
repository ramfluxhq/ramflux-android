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
profile="${RAMFLUX_NATIVE_PROFILE:-release}"
case "$profile" in
  release|dev) ;;
  *) echo 'RAMFLUX_NATIVE_PROFILE must be release or dev' >&2; exit 1 ;;
esac
# Retain line tables for the separate symbol artifact, then strip only packaging copies.
# Optimisation/LTO/codegen remain the SDK's shipping release profile.
export CARGO_PROFILE_RELEASE_STRIP=none
export CARGO_PROFILE_RELEASE_DEBUG=line-tables-only
mkdir -p "$out/jniLibs/arm64-v8a" "$out/include" "$out/symbols/arm64-v8a" "$out/assets"
out="$(cd "$out" && pwd)"
python3 - "$core" "$repo" <<'PYDESC'
import hashlib,json,sys
from pathlib import Path
sdk, repo = map(Path,sys.argv[1:])
pin=json.loads((repo/'sdk-contract/descriptor.json').read_text())
assert hashlib.sha256((sdk/'crates/ramflux-protocol/src/message/descriptor.bin').read_bytes()).hexdigest()==pin['sha256'], 'pinned descriptor mismatch'
PYDESC
# cargo-ndk forwards 16 KB alignment and the stable SDK SONAME to the Rust shared library.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384 -C link-arg=-Wl,-soname,libramflux_sdk.so'
# The pinned SDK packages its mobile SQLCipher/OpenSSL dependency.
cmp "$core/crates/ramflux-sdk/include/ramflux_sdk_methods.json" "$repo/sdk-contract/ramflux_sdk_methods.json"
profile_args=()
if [[ "$profile" == release ]]; then profile_args+=(--release); fi
ndk_args=(-t arm64-v8a --platform 28)
if [[ "$action" == build ]]; then ndk_args+=(-o "$out/jniLibs"); fi
(cd "$core" && cargo ndk "${ndk_args[@]}" "$action" -p ramflux-sdk --lib --features c-abi --locked "${profile_args[@]}")
if [[ "$action" == check ]]; then exit 0; fi
cp "$CARGO_TARGET_DIR/include/ramflux_sdk.h" "$out/include/ramflux_sdk.h"
cmake_bin="${ANDROID_HOME:?Set ANDROID_HOME}/cmake/3.22.1/bin/cmake"
"$cmake_bin" -S "$repo/core/sdk/src/main/cpp" -B "$out/cmake" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ANDROID_HOME/cmake/3.22.1/bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
  -DRAMFLUX_NATIVE_DIR="$out/jniLibs" -DRAMFLUX_HEADER_DIR="$out/include" \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$out/unstripped/arm64-v8a"
"$cmake_bin" --build "$out/cmake" --parallel 2
cp "$out/unstripped/arm64-v8a/libramflux_android.so" "$out/jniLibs/arm64-v8a/libramflux_android.so"
host_tools=("$ndk"/toolchains/llvm/prebuilt/*/bin)
[[ ${#host_tools[@]} == 1 ]] || { echo 'Expected one NDK host toolchain' >&2; exit 1; }
objdump="${host_tools[0]}/llvm-objdump"
for library in "$out/jniLibs/arm64-v8a/"*.so; do
  symbol_file="$out/symbols/arm64-v8a/$(basename "$library").dbg"
  "${host_tools[0]}/llvm-objcopy" --only-keep-debug "$library" "$symbol_file"
  "${host_tools[0]}/llvm-strip" --strip-unneeded "$library"
  "${host_tools[0]}/llvm-objcopy" --add-gnu-debuglink="$symbol_file" "$library"
  "$objdump" -p "$library" | python3 "$repo/scripts/check-elf-alignment.py"
done
printf '%s\n' "$profile" > "$out/assets/ramflux-native-profile.txt"
