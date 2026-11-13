#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Bounded theme-extension and contrast checks using the actual token compiler."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('theme_catalog', ROOT / 'scripts/build-theme-catalog.py')
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)


class ThemeCatalogTest(unittest.TestCase):
    def test_extra_named_theme_needs_only_a_token_file(self):
        with tempfile.TemporaryDirectory(prefix='rf-theme-') as scratch:
            base = Path(scratch)
            themes = base / 'themes'
            shutil.copytree(ROOT / 'core/designsystem/src/main/assets/ramflux/themes', themes)
            data = json.loads((themes / 'dark.json').read_text())
            data.update(id='contrast', names={'en': 'High contrast', 'zh-CN': '高对比度'})
            (themes / 'contrast.json').write_text(json.dumps(data))
            compiler.compile_catalog(themes, ROOT / 'core/designsystem/src/main/kotlin/org/ramflux/generated/RFTokens.kt', base / 'java', base / 'res')
            binding = (base / 'java/org/ramflux/designsystem/generated/ThemeTokenFiles.kt').read_text()
            self.assertIn('NamedThemeTokens("contrast", R.string.theme_contrast', binding)
            self.assertIn('高对比度', (base / 'res/values-zh-rCN/theme_names.xml').read_text())
            del data['names']['zh-CN']
            (themes / 'contrast.json').write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                compiler.compile_catalog(themes, ROOT / 'core/designsystem/src/main/kotlin/org/ramflux/generated/RFTokens.kt', base / 'java', base / 'res')

    def test_shipped_text_and_control_token_pairs_meet_contrast(self):
        def luminance(token):
            values = token['$value']['components']
            linear = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in values]
            return sum(c * w for c, w in zip(linear, [.2126, .7152, .0722]))
        for path in (ROOT / 'core/designsystem/src/main/assets/ramflux/themes').glob('*.json'):
            data = json.loads(path.read_text())
            tokens = data['tokens']
            for foreground, background in [('text', 'bg'), ('text', 'surface'), ('secondary', 'bg'),
                    ('on-primary', 'primary'), ('on-badge', 'badge'), ('outgoing-text', 'outgoing')]:
                a, b = luminance(tokens[foreground]), luminance(tokens[background])
                ratio = (max(a, b) + .05) / (min(a, b) + .05)
                self.assertGreaterEqual(ratio, 4.5, f'{data["id"]}: {foreground}/{background}: {ratio:.2f}')
                print(f'CONTRAST_OK: {data["id"]}; {foreground}/{background}; ratio={ratio:.2f}')


if __name__ == '__main__':
    unittest.main()
