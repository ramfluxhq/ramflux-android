#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
set -euo pipefail
# SQLCipher's existing product feature needs an Android libcrypto and its headers.
# This supplies the dependency without changing SDK features or storage format.
base="${CARGO_TARGET_DIR:?Set CARGO_TARGET_DIR}/android-deps/openssl-3.6.3-ndk28-api28"
ndk="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
if [[ ! -f "$base/install/lib/libcrypto.a" ]]; then
    mkdir -p "$base"
    archive="$base/openssl-3.6.3.tar.gz"
    curl -fL --retry 2 --max-time 120 --max-filesize 134217728 \
        https://github.com/openssl/openssl/releases/download/openssl-3.6.3/openssl-3.6.3.tar.gz -o "$archive"
    python3 - "$archive" <<'PY'
import hashlib
import sys
with open(sys.argv[1], 'rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
if digest != '243a86649cf6f23eeb6a2ff2456e09e5d77dd9018a54d3d96b0c6bdd6ba6c7f1':
    raise SystemExit('OpenSSL source checksum mismatch')
PY
    tar -xzf "$archive" -C "$base"
    export ANDROID_NDK_ROOT="$ndk"
    export PATH="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
    (
        cd "$base/openssl-3.6.3"
        ./Configure android-arm64 -D__ANDROID_API__=28 no-shared no-tests -fPIC \
            --prefix="$base/install" --libdir=lib
        make -j2 build_libs
        make install_dev
    ) >&2
fi
printf '%s\n' "$base/install"
