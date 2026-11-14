#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Check every packaged native ELF; APKs additionally require 16 KB zip alignment."""
import argparse
import os
from elf_policy import validate_dynamic
from pathlib import Path
import subprocess
import tempfile
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--profile', choices=('release', 'dev'), default='release')
p.add_argument('archives', nargs='+', type=Path)
a = p.parse_args()
ndk = Path(os.environ['ANDROID_NDK_HOME'])
prebuilt = list((ndk / 'toolchains/llvm/prebuilt').iterdir())
if len(prebuilt) != 1:
    raise SystemExit('Expected one native NDK toolchain')
readelf = prebuilt[0] / 'bin/llvm-readelf'
for archive in a.archives:
    with tempfile.TemporaryDirectory(prefix='rf-package-alignment-') as scratch, zipfile.ZipFile(archive) as z:
        marker = z.getinfo('assets/ramflux-native-profile.txt')
        if marker.file_size > 16 or z.read(marker) != (a.profile + '\n').encode():
            raise SystemExit(f'Packaged native profile mismatch: {archive.name}; expected={a.profile}')
        entries = [i for i in z.infolist() if i.filename.endswith('.so')]
        if not entries or len(entries) > 16:
            raise SystemExit('Expected 1..16 packaged native libraries')
        for index, entry in enumerate(entries):
            bound = 256 * 1024 * 1024
            measurement = f'{archive.name}!{entry.filename}; profile={a.profile}; measured_bytes={entry.file_size}; bound_bytes={bound}'
            print(f'PACKAGED_SIZE: {measurement}', flush=True)
            if not 0 < entry.file_size <= bound:
                raise SystemExit(f'Packaged native library exceeds bounded size: {measurement}')
            elf = Path(scratch) / f'{index}.so'
            with z.open(entry) as source, elf.open('wb') as destination:
                while chunk := source.read(1024 * 1024):
                    destination.write(chunk)
            output = subprocess.run([str(readelf), '-l', '--wide', str(elf)], capture_output=True, text=True, check=True, timeout=30).stdout
            dynamic = subprocess.run([str(readelf), '-d', '--wide', str(elf)], capture_output=True, text=True, check=True, timeout=30).stdout
            try:
                validate_dynamic(dynamic, entry.filename, set(z.namelist()))
            except ValueError as error:
                raise SystemExit(str(error)) from error
            sections = subprocess.run([str(readelf), '-S', '--wide', str(elf)], capture_output=True, text=True, check=True, timeout=30).stdout
            if '.debug_' in sections or '.zdebug_' in sections:
                raise SystemExit(f'Packaged native debug info was not split: {measurement}')
            alignments = [int(line.split()[-1], 16) for line in output.splitlines() if line.lstrip().startswith('LOAD ')]
            if not alignments or min(alignments) < 16384:
                raise SystemExit(f'ELF alignment below 16 KB: {entry.filename}')
        if archive.suffix == '.apk':
            tool = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/zipalign'
            subprocess.run([str(tool), '-c', '-P', '16', '4', str(archive)], check=True, timeout=30)
        print(f'PACKAGED_ALIGNMENT_OK: {archive.name}; {len(entries)} native libraries; ELF' + (' + APK zipalign' if archive.suffix == '.apk' else ''))
