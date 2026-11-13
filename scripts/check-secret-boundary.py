#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Guard SDK-generated classifications and byte-only secret decode entrypoints."""
import hashlib
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
raw = (root / 'sdk-contract/ramflux_sdk_methods.json').read_bytes()
pin = json.loads((root / 'sdk-contract/pin.json').read_text())
assert hashlib.sha256(raw).hexdigest() == pin['policy_sha256'], 'pinned policy changed'
policy = json.loads(raw)
source = (root / 'core/sdk/src/main/kotlin/org/ramflux/sdk/MethodPolicy.kt').read_text()
actual = {name: tuple(v == 'true' for v in values) for name, *values in re.findall(r'"([^"]+)" to MethodPolicy\((true|false), (true|false), (true|false)\)', source)}
expected = {m['name']: tuple(m[k] for k in ('secret_input','secret_output','show_once')) for m in policy['methods']}
assert actual == expected, 'Kotlin policy differs from the SDK artifact'
codec = (root / 'core/sdk/src/main/kotlin/org/ramflux/sdk/ReplyCodec.kt').read_text()
secret_section = codec[codec.index('    fun decodeSecret'):codec.index('    private const val MAX_FRAME')]
assert not any(x in secret_section for x in ('decodeToString', 'parseToJsonElement', '.toString(', 'String(')), 'secret decoder materializes immutable JSON/String data'
scanner = (root / 'core/sdk/src/main/kotlin/org/ramflux/sdk/ByteJson.kt').read_text()
assert 'decodeToString' not in scanner and 'String(' not in scanner, 'secret scanner must remain byte/char based'
for file in (root / 'core/data/src/main/kotlin/org/ramflux/data').glob('*Repository.kt'):
    text = file.read_text()
    for method in re.findall(r'executeSecret\("([^"]+)"', text):
        assert method in expected, 'unclassified repository method'
    for method in re.findall(r'(?<!Secret)execute\("([^"]+)"', text):
        assert method in expected and not any(expected[method][:2]), 'confidential method uses ordinary JSON path'
print(f'SECRET_BOUNDARY_OK: {len(expected)} SDK classifications; no immutable secret decoder')
