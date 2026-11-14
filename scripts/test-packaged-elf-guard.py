#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Build small real ARM ELFs and exercise the archive guard through its CLI."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPTS = Path(__file__).resolve().parent


class PackagedElfGuardTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix='rf-elf-regression-')
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.clang = next(Path(os.environ['ANDROID_NDK_HOME']).glob('toolchains/llvm/prebuilt/*/bin/aarch64-linux-android28-clang'))
        self.source = self.root / 'probe.c'
        self.source.write_text('int rf_probe(void) { return 7; }\n')

    def library(self, name, *flags):
        path = self.root / name
        subprocess.run([str(self.clang), '-shared', '-fPIC', '-nostdlib', '-Wl,-z,max-page-size=16384',
                        str(self.source), '-o', str(path), *flags], check=True, capture_output=True, timeout=30)
        return path

    def check(self, libraries, error=None, other_abi=None):
        archive = self.root / 'fixture.aar'
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('assets/ramflux-native-profile.txt', 'release\n')
            if other_abi is not None:
                z.write(other_abi, 'jni/x86_64/' + other_abi.name)
            for library in libraries:
                z.write(library, 'jni/arm64-v8a/' + library.name)
        result = subprocess.run([sys.executable, str(SCRIPTS / 'check-packaged-alignment.py'), str(archive)],
                                capture_output=True, text=True, timeout=30)
        if error:
            self.assertNotEqual(0, result.returncode)
            self.assertIn(error, result.stdout + result.stderr)
        else:
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn('PACKAGED_ALIGNMENT_OK', result.stdout)

    def test_sdk_requires_real_matching_soname(self):
        self.check([self.library('libramflux_sdk.so')], 'SDK SONAME')
        self.check([self.library('libramflux_sdk.so', '-Wl,-soname,/host/libramflux_sdk.so')], 'SDK SONAME')
        self.check([self.library('libramflux_sdk.so', '-Wl,-soname,libramflux_sdk.so')])

    def test_absolute_needed_is_rejected(self):
        dependency = self.library('libdependency.so')
        self.source.write_text('extern int rf_probe(void); int rf_consumer(void) { return rf_probe(); }\n')
        consumer = self.library('libconsumer.so', str(dependency))
        self.check([dependency, consumer], 'Nonportable ELF dependency')

    def test_missing_dependency_including_wrong_abi_is_rejected(self):
        dependency = self.library('libdependency.so', '-Wl,-soname,libdependency.so')
        self.source.write_text('extern int rf_probe(void); int rf_consumer(void) { return rf_probe(); }\n')
        consumer = self.library('libconsumer.so', str(dependency))
        self.check([consumer], 'Missing packaged ELF dependency')
        self.check([consumer], 'Missing packaged ELF dependency', other_abi=dependency)
        self.check([dependency, consumer])

    def test_rpath_and_runpath_reject_host_and_cwd_paths_but_accept_origin(self):
        for dtags in ('--enable-new-dtags', '--disable-new-dtags'):
            for value in ('/host/build/lib', '$ORIGIN:/host/build', '$ORIGIN:', '.', '$ORIGIN/..'):
                with self.subTest(dtags=dtags, path=value):
                    self.check([self.library('libprobe.so', f'-Wl,{dtags},-rpath,{value}')], 'Nonportable ELF')
            for value in ('$ORIGIN', '${ORIGIN}'):
                with self.subTest(dtags=dtags, path=value):
                    self.check([self.library('libprobe.so', f'-Wl,{dtags},-rpath,{value}')])


if __name__ == '__main__':
    unittest.main()
