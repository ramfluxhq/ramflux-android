#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Compile named DTCG token files to typed bindings/resources; no theme-name switch in app code."""
import argparse
import json
import math
from pathlib import Path
import re
from xml.sax.saxutils import escape


def compile_catalog(themes, schema, java_output, res_output):
    fields = re.findall(r'val (\w+): (Color|Float|String)', schema.read_text().split('object RFDesign')[0])
    if not fields or len(fields) > 256:
        raise ValueError('Invalid generated token schema')
    files = sorted(themes.glob('*.json'))
    if not 2 <= len(files) <= 16:
        raise ValueError('Expected 2..16 bounded theme files')
    rows = []
    if any(path.stat().st_size > 128 * 1024 for path in files):
        raise ValueError('Theme file exceeds bound')
    locale_sets = [set(json.loads(path.read_text())['names']) for path in files]
    locales = set().union(*locale_sets)
    if not {'en', 'zh-CN'} <= locales or any(keys != locales for keys in locale_sets):
        raise ValueError('Every theme needs the same complete locale names, including en and zh-CN')
    if any(not re.fullmatch('[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*', locale) for locale in locales):
        raise ValueError('Invalid locale tag')
    texts = {locale: [] for locale in sorted(locales)}
    seen = set()
    for path in files:
        if path.stat().st_size > 128 * 1024:
            raise ValueError('Theme file exceeds bound')
        theme = json.loads(path.read_text())
        identity = theme['id']
        if not re.fullmatch('[a-z][a-z0-9_]{0,63}', identity) or identity in seen or not isinstance(theme['dark'], bool):
            raise ValueError('Invalid theme identity/mode')
        seen.add(identity)
        if set(theme['names']) != set(texts):
            raise ValueError('Every theme needs en and zh-CN names')
        for locale in texts:
            name = theme['names'][locale]
            if not isinstance(name, str) or not 1 <= len(name) <= 100:
                raise ValueError('Invalid localized theme name')
            texts[locale].append((identity, name))
        tokens = theme['tokens']
        assignments = []
        for name, kind in fields:
            token = tokens[name.replace('_', '-')]
            value = token['$value']
            if kind == 'Color':
                if token['$type'] != 'color' or value['colorSpace'] != 'srgb':
                    raise ValueError('Expected sRGB token')
                channels = [value.get('alpha', 1), *value['components']]
                if len(channels) != 4 or any(not isinstance(c, (int, float)) or not math.isfinite(c) or not 0 <= c <= 1 for c in channels):
                    raise ValueError('Invalid color components')
                encoded = ''.join(f'{round(c * 255):02X}' for c in channels)
                rendered = f'Color(0x{encoded})'
            elif kind == 'String':
                if token['$type'] != 'fontFamily' or not isinstance(value, str) or not 1 <= len(value) <= 100:
                    raise ValueError('Invalid font token')
                rendered = json.dumps(value).replace('$', '\\$')
            else:
                number = value['value']
                if token['$type'] != 'dimension' or value['unit'] != 'px' or not math.isfinite(number) or not 0 <= number <= 10000:
                    raise ValueError('Invalid dimension token')
                rendered = f'{float(number)}f'
            assignments.append(f'                {name} = {rendered},')
        fonts = [tokens[k]['$value'] for k in ['font-latin', 'font']]
        if any(not isinstance(f, str) or len(f) > 100 for f in fonts):
            raise ValueError('Invalid font token')
        layout = theme['component_tokens']
        layout_values = [layout[key] for key in ['row_height', 'avatar_size', 'avatar_text_size', 'hairline']]
        if any(not isinstance(v, (int, float)) or not math.isfinite(v) or not 0 < v <= 1000 for v in layout_values):
            raise ValueError('Invalid component layout tokens')
        rendered_layout = ', '.join(f'{float(v)}f' for v in layout_values)
        rows.append('        NamedThemeTokens(' + json.dumps(identity) + f', R.string.theme_{identity}, ' + str(theme['dark']).lower() + ',\n'
                    + '            RFTheme(\n' + '\n'.join(assignments) + '\n            ), ' + ', '.join(json.dumps(f).replace('$', '\\$') for f in fonts) + ', ThemeLayoutTokens(' + rendered_layout + ')),')
    if not {'light', 'dark'} <= seen:
        raise ValueError('Light and dark are required fallbacks')
    binding = java_output / 'org/ramflux/designsystem/generated/ThemeTokenFiles.kt'
    binding.parent.mkdir(parents=True, exist_ok=True)
    binding.write_text('// SPDX-License-Identifier: AGPL-3.0-or-later\n// Copyright (c) 2026 Span Brain\n'
        '// Generated from token files. Edit the theme data instead.\npackage org.ramflux.designsystem.generated\n'
        'import androidx.compose.ui.graphics.Color\nimport org.ramflux.generated.RFTheme\n'
        'import org.ramflux.designsystem.NamedThemeTokens\nimport org.ramflux.designsystem.ThemeLayoutTokens\nimport org.ramflux.core.designsystem.R\n'
        'object ThemeTokenFiles {\n    val themes = listOf(\n' + '\n'.join(rows) + '\n    )\n}\n')
    for locale, entries in texts.items():
        folder = 'values' if locale == 'en' else 'values-zh-rCN' if locale == 'zh-CN' else 'values-b+' + locale.replace('-', '+')
        target = res_output / folder / 'theme_names.xml'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('<?xml version="1.0" encoding="utf-8"?>\n<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->\n<resources>\n'
            + ''.join(f'    <string name="theme_{key}">' + escape(value).replace("'", "\\'") + '</string>\n' for key, value in entries) + '</resources>\n')
    # Launcher/window roles also consume the shared token sets, with OS light/dark variants.
    for identity, folder in [('light', 'values'), ('dark', 'values-night')]:
        definition = next(json.loads(path.read_text()) for path in files if json.loads(path.read_text())['id'] == identity)
        entries = []
        for output_name, token_name in [('rf_brand_primary', 'primary'), ('rf_brand_on_primary', 'on-primary'), ('rf_brand_background', 'bg')]:
            color = definition['tokens'][token_name]['$value']
            encoded = ''.join(f'{round(v * 255):02X}' for v in [color.get('alpha', 1), *color['components']])
            entries.append(f'    <color name="{output_name}">#{encoded}</color>')
        launcher = definition['tokens']['composer-min']['$value']['value']
        entries.append(f'    <dimen name="rf_launcher_size">{float(launcher)}dp</dimen>')
        target = res_output / folder / 'theme_brand.xml'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('<?xml version="1.0" encoding="utf-8"?>\n<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->\n<resources>\n'
            + '\n'.join(entries) + '\n</resources>\n')
    print(f'THEME_CATALOG_OK: {len(rows)} token sets; {len(fields)} fields; complete en/zh-CN names')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for option in ['themes', 'schema', 'java', 'res']:
        parser.add_argument('--' + option, type=Path, required=True)
    args = parser.parse_args()
    compile_catalog(args.themes, args.schema, args.java, args.res)
