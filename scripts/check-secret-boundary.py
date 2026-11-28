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
actual = {name: (a == 'true', b == 'true', c == 'true', d == 'true', e == 'true', capability) for name, a, b, c, d, e, capability in re.findall(r'"([^"]+)" to MethodPolicy\((true|false), (true|false), (true|false), (true|false), (true|false), "([^"]+)"\)', source)}
expected = {m['name']: tuple(m[k] for k in ('secret_input','secret_output','show_once','key_export','bridge_allowed','capability')) for m in policy['methods']}
assert actual == expected, 'Kotlin policy differs from the SDK artifact'
verify = next(m for m in policy['methods'] if m['name'] == 'contact.verify')
cas = {
    'CONTRACT': 'contract',
    'REQUEST_TYPE': 'request_type',
    'RESPONSE_TYPE': 'response_type',
    'MESSAGE_DESCRIPTOR': 'message_descriptor_blake3',
    'PRODUCT_PIN': 'product_pin',
}
for const, key in cas.items():
    assert f'const val {const} = "{verify[key]}"' in source, f'pinned contact.verify {key} drifted'
caps = (root / 'core/sdk/src/main/kotlin/org/ramflux/sdk/CoreCapabilities.kt').read_text()
for const in cas:
    assert f'ContactVerifyCas.{const}' in caps, f'negotiation does not pin {const}'
codec = (root / 'core/sdk/src/main/kotlin/org/ramflux/sdk/ReplyCodec.kt').read_text()
secret_section = codec[codec.index('    fun decodeSecret'):codec.index('    private const val MAX_FRAME')]
assert not any(x in secret_section for x in ('decodeToString', 'parseToJsonElement', '.toString(', 'String(')), 'secret decoder materializes immutable JSON/String data'
scanner = (root / 'core/sdk/src/main/kotlin/org/ramflux/sdk/ByteJson.kt').read_text()
assert 'decodeToString' not in scanner and 'String(' not in scanner, 'secret scanner must remain byte/char based'
# Methods the MLS GUI batches call by name before the core pin carries them. The transport refuses them with
# UnsupportedVersion while they are missing from the generated policy. Batch W regenerates the policy and empties this set:
# PENDING_PIN must be empty at the W pin, and the assertion below fails the build as soon as a listed method is classified.
PENDING_PIN = {'conversation.security', 'contact.trust.accept_change'}
classified = sorted(PENDING_PIN & set(expected))
assert not classified, f'PENDING_PIN must be empty at the W pin; the policy now classifies {classified}: remove them from PENDING_PIN'
for file in (root / 'core/data/src/main/kotlin/org/ramflux/data').glob('*Repository.kt'):
    text = file.read_text()
    for method in re.findall(r'executeSecret\("([^"]+)"', text):
        assert method in expected or (method in PENDING_PIN and method not in expected), 'unclassified repository method'
    for method in re.findall(r'(?<!Secret)execute\("([^"]+)"', text):
        assert method in expected and not any(expected[method][:2]), 'confidential method uses ordinary JSON path'
print(f'SECRET_BOUNDARY_OK: {len(expected)} SDK classifications; no immutable secret decoder')
