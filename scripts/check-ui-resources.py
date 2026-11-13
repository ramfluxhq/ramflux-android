#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Require complete launch locales and resource-backed host UI text in every installed module."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent


def resource_keys(directory):
    result = {}
    for file in sorted(directory.glob('*.xml')):
        for node in ET.parse(file).getroot():
            if node.tag not in ('string', 'plurals', 'string-array'):
                continue
            name = node.attrib['name']
            if name in result:
                raise ValueError(f'Duplicate resource: {file}:{name}')
            result[name] = node
    return result


def validate(root):
    provider = root / 'core/designsystem/src/main/kotlin/org/ramflux/designsystem/AppLanguageProvider.kt'
    if provider.exists() and not re.search(r'enableSplit\s*=\s*false', (root / 'app/build.gradle.kts').read_text()):
        raise ValueError('Runtime language switching requires all locale resources in the application bundle')
    modules = [root / 'app', root / 'core/designsystem', *[p for p in sorted((root / 'feature').glob('*/impl')) if (p / 'build.gradle.kts').exists()]]
    total = 0
    for module in modules:
        resources = module / 'src/main/res'
        english = resource_keys(resources / 'values')
        chinese = resource_keys(resources / 'values-zh-rCN')
        if set(english) != set(chinese):
            raise ValueError(f'Locale key mismatch: {module.relative_to(root)}: {set(english) ^ set(chinese)}')
        for key, value in english.items():
            translated = chinese[key]
            a = set(re.findall(r'%\d+\$[sd]', ''.join(value.itertext())))
            b = set(re.findall(r'%\d+\$[sd]', ''.join(translated.itertext())))
            for node in [value, translated]:
                literal = ''.join(node.itertext())
                if '%' in re.sub(r'%(?:\d+\$)?[sd%]', '', literal):
                    raise ValueError(f'Invalid format placeholder: {module.relative_to(root)}:{key}')
            if value.tag != translated.tag or a != b or not ''.join(translated.itertext()).strip():
                raise ValueError(f'Invalid translation/format: {module.relative_to(root)}:{key}')
        total += len(english)
        for source in (module / 'src/main').glob('**/*.kt'):
            if 'generated' in source.parts:
                continue
            text = source.read_text()
            if re.search(r'\bText\(\s*(?:text\s*=\s*)?"[^"]+"|contentDescription\s*=\s*"[^"]+"|UiText.Data\("[^"]+"', text):
                raise ValueError(f'Hard-coded direct host text: {source.relative_to(root)}')
            # Strip comments without altering string literals, then inspect every quoted value.
            chunks = re.finditer(r'"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*', text)
            for match in chunks:
                quoted = match.group()
                if not quoted.startswith('"'):
                    continue
                value = quoted[1:-1]
                if not value or re.fullmatch(r'[a-z0-9_:/.$-]+|S\d+', value):
                    continue  # Stable ids, token/schema names and empty text, never human labels.
                prefix = text[max(0, match.start() - 80):match.start()]
                if re.search(r'(?:CancellationException|@SuppressLint)\(\s*$', prefix) or value.endswith('(<redacted>)'):
                    continue  # Non-user diagnostics have no UI rendering path.
                raise ValueError(f'Hard-coded host text: {source.relative_to(root)}:{text.count(chr(10), 0, match.start()) + 1}: {quoted}')
            if re.search(r'\bColor\(0x|#[0-9a-fA-F]{6}\b|(?<![\w.])\d+(?:\.\d+)?\.(?:dp|sp)\b', text):
                raise ValueError(f'Hard-coded style instead of theme tokens: {source.relative_to(root)}')
    return len(modules), total


if __name__ == '__main__':
    count, total = validate(ROOT)
    print(f'UI_RESOURCES_OK: {count} modules; {total} complete en/zh-CN keys; no hard-coded host text/style')
