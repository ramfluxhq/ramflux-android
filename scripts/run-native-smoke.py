#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Install exact packaged artifacts and require an actual JNI/SDK instrumented test."""
import argparse
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--app', required=True, type=Path)
parser.add_argument('--test', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
a = parser.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')]
if a.serial:
    adb += ['-s', a.serial]
def run(name, args, timeout=60):
    try:
        result = subprocess.run(adb + args, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        def decoded(value):
            return value.decode(errors='replace') if isinstance(value, bytes) else (value or '')
        (a.output / (name + '.txt')).write_text(decoded(error.stdout) + decoded(error.stderr))
        raise SystemExit(f'NATIVE_SMOKE_FAILED: {name}; timeout={timeout}s') from error
    (a.output / (name + '.txt')).write_text(result.stdout + result.stderr)
    if result.returncode:
        raise SystemExit(f'NATIVE_SMOKE_FAILED: {name}; exit={result.returncode}')
    return result.stdout
abis = run('device-abis', ['shell', 'getprop', 'ro.product.cpu.abilist'])
if 'arm64-v8a' not in abis:
    raise SystemExit('NATIVE_SMOKE_FAILED: device cannot execute the packaged arm64-v8a libraries')
run('install-app', ['install', '-r', str(a.app)], timeout=180)
run('install-test', ['install', '-r', str(a.test)], timeout=180)
def instrument(name, classname):
    output = run(name, ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classname,
        'org.ramflux.android.test/androidx.test.runner.AndroidJUnitRunner'], timeout=180)
    if not re.search(r'OK\s*\(1 test\)', output) or 'FAILURES' in output or 'INSTRUMENTATION_FAILED' in output:
        raise SystemExit(f'NATIVE_SMOKE_FAILED: {classname} did not pass; see {name}.txt')
    return output
try:
    instrument('instrumentation', 'org.ramflux.android.NativeRuntimeSmokeTest')
    instrument('custody', 'org.ramflux.android.CustodyEmulatorTest')
    instrument('custody-reinstall-seed', 'org.ramflux.android.CustodyReinstallSeedTest')
    run('uninstall', ['uninstall', 'org.ramflux.android'])
    run('install-app-again', ['install', '-r', str(a.app)], timeout=180)
    run('install-test-again', ['install', '-r', str(a.test)], timeout=180)
    instrument('custody-reinstall-absent', 'org.ramflux.android.CustodyReinstallAbsentTest')
finally:
    run('runtime-logcat', ['logcat', '-d', '-t', '4000', '-s', 'RamfluxNativeSmoke:I', 'RamfluxCustody:I', 'AndroidRuntime:E', 'linker:E', 'nativeloader:D'])
print('NATIVE_RUNTIME_OK: packaged System.loadLibrary + negotiated SDK open/node.list/close + keystore custody')
