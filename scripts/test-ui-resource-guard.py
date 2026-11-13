#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Prove the UI guard rejects missing translations, malformed formats and direct host text."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('guard', Path(__file__).with_name('check-ui-resources.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ResourceGuardTest(unittest.TestCase):
    def test_runtime_locale_provider_requires_bundled_languages(self):
        with tempfile.TemporaryDirectory(prefix='rf-locale-bundle-') as scratch:
            root = Path(scratch)
            provider = root / 'core/designsystem/src/main/kotlin/org/ramflux/designsystem/AppLanguageProvider.kt'
            provider.parent.mkdir(parents=True)
            provider.touch()
            app = root / 'app/build.gradle.kts'
            app.parent.mkdir(parents=True)
            app.write_text('android {}')
            with self.assertRaisesRegex(ValueError, 'all locale resources'):
                guard.validate(root)
            app.write_text('android { bundle { language { enableSplit = false } } }')
            guard.validate(root)

    def test_regressions_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix='rf-locale-guard-') as scratch:
            root = Path(scratch)
            files = []
            for folder in ['values', 'values-zh-rCN']:
                file = root / 'app/src/main/res' / folder / 'strings.xml'
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('<resources><string name="title">Title</string></resources>')
                files.append(file)
            self.assertEqual((2, 1), guard.validate(root))
            source = root / 'app/src/main/kotlin/Screen.kt'
            source.parent.mkdir(parents=True)
            source.write_text('fun Screen() { Text("hello") }')
            with self.assertRaisesRegex(ValueError, 'Hard-coded'):
                guard.validate(root)
            source.write_text('fun Screen() { Text(peerData) }')
            guard.validate(root)
            files[1].write_text('<resources />')
            with self.assertRaisesRegex(ValueError, 'Locale key mismatch'):
                guard.validate(root)
            files[1].write_text('<resources><string name="title">%1%1$s</string></resources>')
            with self.assertRaises(ValueError):
                guard.validate(root)


if __name__ == '__main__':
    unittest.main()
