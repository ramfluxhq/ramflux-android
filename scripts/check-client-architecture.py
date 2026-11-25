#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Fail if a UI module bypasses Data or depends on a sibling implementation.

Under push/, Google, Firebase and Play services classes appear only in push/international/fcm, and the push
modules keep their dependency directions (push/README.md).
"""
from pathlib import Path
import re
root = Path(__file__).resolve().parent.parent
for base in ('app', 'feature', 'core/designsystem', 'core/navigation', 'push'):
    for path in (root/base).glob('**/src/main/**/*.kt'):
        text = path.read_text()
        for removed in ('HoldConfirmation', 'RFHoldPresentation', 'hold_presentation'):
            assert removed not in text, f'Removed hold confirmation component: {path.relative_to(root)}'
        assert 'org.ramflux.sdk' not in text, f'SDK type above Data: {path.relative_to(root)}'
        if path.name.endswith('Screen.kt'):
            assert 'kotlinx.coroutines' not in text, f'Async work in Screen: {path.relative_to(root)}'
GOOGLE = re.compile(r'\bcom\.google\.(?:firebase|android\.gms)\b')
for base in ('app', 'core', 'feature', 'push'):
    for path in (root/base).glob('**/src/**/*.kt'):
        if 'international/fcm/' in path.relative_to(root).as_posix():
            continue
        assert not GOOGLE.search(path.read_text()), f'Google class outside push/international/fcm: {path.relative_to(root)}'
PUSH_FORBIDDEN = {
    'push/api': (':core:data', ':core:sdk', ':push:impl', ':push:international'),
    'push/impl': (':core:sdk', ':push:international'),
    'push/international/compat': (':push:impl', ':core:data', ':core:sdk', ':push:international:fcm'),
    'push/international/fcm': (':push:impl', ':core:data', ':core:sdk', ':push:international:compat'),
}
for module, forbidden in PUSH_FORBIDDEN.items():
    text = (root/module/'build.gradle.kts').read_text()
    for target in forbidden:
        assert not re.search(r'project\("' + re.escape(target) + r'(?::[^"\n]+)?"\)', text), f'{module} must not depend on {target}'
for path in (root/'feature').glob('*/impl/build.gradle.kts'):
    text = path.read_text()
    assert not re.search(r'project\(":feature:[^"\n]+:impl"\)', text), f'Sibling implementation dependency: {path}'
print('CLIENT_ARCHITECTURE_OK: UI has no SDK imports; Screens have no coroutine imports; no sibling impl dependency; Google classes only in push/international/fcm; push module dependencies hold')
