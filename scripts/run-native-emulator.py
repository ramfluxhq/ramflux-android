#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Own one CI emulator; retry boot once before installing or testing any APK."""
import argparse
from contextlib import contextmanager
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time


class BootFailure(RuntimeError):
    """The emulator exited or did not reach sys.boot_completed within its deadline."""


def run_with_boot_retry(boot, smoke):
    for attempt in (1, 2):
        # Catch only entry failure. Anything after yielding a booted emulator is final.
        manager = boot(attempt)
        try:
            device = manager.__enter__()
        except BootFailure:
            if attempt == 2:
                raise
            print('EMULATOR_BOOT_RETRY: one retry before APK installation', flush=True)
            continue
        try:
            smoke(device)
        finally:
            manager.__exit__(None, None, None)
        return


@contextmanager
def boot_emulator(sdk, avd_env, output, attempt):
    serial = 'emulator-5554'
    adb = str(sdk / 'platform-tools/adb')
    with (output / f'emulator-boot-{attempt}.log').open('w') as log:
        process = subprocess.Popen([
            str(sdk / 'emulator/emulator'), '-avd', 'ramflux-native-ci', '-port', '5554',
            '-no-window', '-gpu', 'swiftshader_indirect', '-noaudio', '-no-boot-anim',
            '-camera-back', 'none', '-camera-front', 'none', '-no-snapshot', '-wipe-data',
            '-cores', '2', '-memory', '2048'
        ], env=avd_env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 180
            while process.poll() is None and time.monotonic() < deadline:
                try:
                    result = subprocess.run([adb, '-s', serial, 'shell', 'getprop', 'sys.boot_completed'],
                                            capture_output=True, text=True, timeout=10)
                    if result.returncode == 0 and result.stdout.strip() == '1':
                        print(f'EMULATOR_BOOT_OK: attempt={attempt}', flush=True)
                        yield serial
                        return
                except subprocess.TimeoutExpired:
                    pass
                time.sleep(1)
            raise BootFailure(f'Emulator boot failed on attempt {attempt}; see emulator-boot-{attempt}.log')
        finally:
            # Signal only the process created here, never a shared adb server or another emulator.
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--app', required=True, type=Path)
    parser.add_argument('--test', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    sdk = Path(os.environ['ANDROID_HOME'])
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    # A unique AVD directory avoids clobbering any existing emulator or retry snapshot.
    with tempfile.TemporaryDirectory(prefix='rf-native-avd-') as scratch:
        avd_env = dict(os.environ, ANDROID_AVD_HOME=scratch)
        manager = shutil.which('avdmanager')
        if manager is None:
            raise RuntimeError('avdmanager is missing from the SDK command-line tools PATH')
        subprocess.run([manager, 'create', 'avd', '--name', 'ramflux-native-ci', '--package',
                        'system-images;android-35;google_apis;x86_64', '--device', 'pixel_5'],
                       input='no\n', text=True, env=avd_env, check=True, timeout=90)
        run_with_boot_retry(
            lambda attempt: boot_emulator(sdk, avd_env, output, attempt),
            lambda serial: subprocess.run([
                sys.executable, str(Path(__file__).with_name('run-native-smoke.py')),
                '--serial', serial, '--app', str(args.app), '--test', str(args.test), '--output', str(output)
            ], check=True, timeout=600)
        )


if __name__ == '__main__':
    main()
