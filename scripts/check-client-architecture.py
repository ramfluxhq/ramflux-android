#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Fail if a UI module bypasses Data or depends on a sibling implementation."""
from pathlib import Path
import re
root = Path(__file__).resolve().parent.parent
for base in ('app', 'feature', 'core/designsystem', 'core/navigation'):
    for path in (root/base).glob('**/src/main/**/*.kt'):
        text = path.read_text()
        for removed in ('HoldConfirmation', 'RFHoldPresentation', 'hold_presentation'):
            assert removed not in text, f'Removed hold confirmation component: {path.relative_to(root)}'
        assert 'org.ramflux.sdk' not in text, f'SDK type above Data: {path.relative_to(root)}'
        if path.name.endswith('Screen.kt'):
            assert 'kotlinx.coroutines' not in text, f'Async work in Screen: {path.relative_to(root)}'
for path in (root/'feature').glob('*/impl/build.gradle.kts'):
    text = path.read_text()
    assert not re.search(r'project\(":feature:[^"\n]+:impl"\)', text), f'Sibling implementation dependency: {path}'
print('CLIENT_ARCHITECTURE_OK: UI has no SDK imports; Screens have no coroutine imports; no sibling impl dependency')
