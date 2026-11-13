#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Check every packaged native ELF; APKs additionally require 16 KB zip alignment."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

p = argparse.ArgumentParser()
p.add_argument('archives', nargs='+', type=Path)
a = p.parse_args()
ndk = Path(os.environ['ANDROID_NDK_HOME'])
prebuilt = list((ndk / 'toolchains/llvm/prebuilt').iterdir())
if len(prebuilt) != 1:
    raise SystemExit('Expected one native NDK toolchain')
readelf = prebuilt[0] / 'bin/llvm-readelf'
for archive in a.archives:
    with tempfile.TemporaryDirectory(prefix='rf-package-alignment-') as scratch, zipfile.ZipFile(archive) as z:
        entries = [i for i in z.infolist() if i.filename.endswith('.so')]
        if not entries or len(entries) > 16:
            raise SystemExit('Expected 1..16 packaged native libraries')
        for index, entry in enumerate(entries):
            if not 0 < entry.file_size <= 256 * 1024 * 1024:
                raise SystemExit('Packaged native library exceeds bounded size')
            elf = Path(scratch) / f'{index}.so'
            with z.open(entry) as source, elf.open('wb') as destination:
                while chunk := source.read(1024 * 1024):
                    destination.write(chunk)
            output = subprocess.run([str(readelf), '-l', '--wide', str(elf)], capture_output=True, text=True, check=True, timeout=30).stdout
            alignments = [int(line.split()[-1], 16) for line in output.splitlines() if line.lstrip().startswith('LOAD ')]
            if not alignments or min(alignments) < 16384:
                raise SystemExit(f'ELF alignment below 16 KB: {entry.filename}')
        if archive.suffix == '.apk':
            tool = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/zipalign'
            subprocess.run([str(tool), '-c', '-P', '16', '4', str(archive)], check=True, timeout=30)
        print(f'PACKAGED_ALIGNMENT_OK: {archive.name}; {len(entries)} native libraries; ELF' + (' + APK zipalign' if archive.suffix == '.apk' else ''))
