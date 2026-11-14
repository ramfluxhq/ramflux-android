#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Pin retry boundaries: boot at most twice, APK install/test at most once."""
from contextlib import contextmanager
import importlib.util
from pathlib import Path
import subprocess
import unittest

spec = importlib.util.spec_from_file_location('runner', Path(__file__).with_name('run-native-emulator.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class EmulatorRetryTest(unittest.TestCase):
    def exercise(self, failed_boots=0, failure=None):
        self.boots, self.smokes, self.closed = [], [], []

        @contextmanager
        def boot(attempt):
            self.boots.append(attempt)
            if attempt <= failed_boots:
                raise runner.BootFailure('boot deadline')
            try:
                yield 'owned-device'
            finally:
                self.closed.append(attempt)

        def smoke(device):
            self.smokes.append(device)
            if failure is not None:
                raise failure

        runner.run_with_boot_retry(boot, smoke)

    def test_first_boot_success_runs_once(self):
        self.exercise()
        self.assertEqual([1], self.boots)
        self.assertEqual(['owned-device'], self.smokes)
        self.assertEqual([1], self.closed)

    def test_one_boot_failure_retries_once(self):
        self.exercise(failed_boots=1)
        self.assertEqual([1, 2], self.boots)
        self.assertEqual(['owned-device'], self.smokes)
        self.assertEqual([2], self.closed)

    def test_two_boot_failures_fail_without_testing(self):
        with self.assertRaises(runner.BootFailure):
            self.exercise(failed_boots=2)
        self.assertEqual([1, 2], self.boots)
        self.assertEqual([], self.smokes)

    def test_install_test_timeout_and_even_boot_named_errors_after_boot_are_never_retried(self):
        for failure in (subprocess.CalledProcessError(1, 'instrument'), subprocess.CalledProcessError(1, 'install'),
                        subprocess.TimeoutExpired('instrument', 180), runner.BootFailure('after boot')):
            with self.subTest(failure=failure), self.assertRaises(type(failure)):
                self.exercise(failure=failure)
            self.assertEqual([1], self.boots)
            self.assertEqual(['owned-device'], self.smokes)
            self.assertEqual([1], self.closed)


if __name__ == '__main__':
    unittest.main()
