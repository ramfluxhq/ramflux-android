#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Import an immutable SDK policy and generate transport/repository classifications."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import importlib.util
import tempfile
import sys

p = argparse.ArgumentParser()
p.add_argument('sdk')
p.add_argument('revision')
a = p.parse_args()
root = Path(__file__).resolve().parent.parent
payload = subprocess.check_output(['git', '-C', a.sdk, 'show', a.revision + ':crates/ramflux-sdk/include/ramflux_sdk_methods.json'])
policy = json.loads(payload)
assert policy['abi_major'] == 2 and policy['policy_version'] == 1
(root / 'sdk-contract/ramflux_sdk_methods.json').write_bytes(payload)
(root / 'sdk-contract/pin.json').write_text(json.dumps({'revision': subprocess.check_output(['git', '-C', a.sdk, 'rev-parse', a.revision], text=True).strip(), 'policy_sha256': hashlib.sha256(payload).hexdigest()}, indent=2) + '\n')
# Derive the expected descriptor from the same immutable revision, never negotiation echo.
descriptor = subprocess.check_output(['git', '-C', a.sdk, 'show', a.revision + ':crates/ramflux-protocol/src/message/descriptor.bin'])
helper = subprocess.check_output(['git', '-C', a.sdk, 'show', a.revision + ':scripts/reference/username_directory/pure_blake3.py'])
assert len(descriptor) <= 1024 * 1024 and len(helper) <= 1024 * 1024
with tempfile.TemporaryDirectory(prefix='rf-descriptor-pin-') as directory:
    helper_path = Path(directory) / 'pinned_blake3.py'
    helper_path.write_bytes(helper)
    spec = importlib.util.spec_from_file_location('_rf_pinned_blake3', helper_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    hasher = module.Hasher()
    hasher.update(descriptor)
    fingerprint = hasher.finalize().hex()
(root / 'sdk-contract/descriptor.json').write_text(json.dumps({
    'revision': subprocess.check_output(['git', '-C', a.sdk, 'rev-parse', a.revision], text=True).strip(),
    'blake3': fingerprint, 'sha256': hashlib.sha256(descriptor).hexdigest(),
}, indent=2) + '\n')
h = '// SPDX-License-Identifier: AGPL-3.0-or-later\n// Copyright (c) 2026 Span Brain\n// Generated from the pinned SDK method policy.\n\n'
rows = '\n'.join('        "' + m['name'] + '" to MethodPolicy(' + ', '.join(str(m[k]).lower() for k in ['secret_input','secret_output','show_once']) + ', "' + m['capability'] + '"),' for m in policy['methods'])
(root / 'core/sdk/src/main/kotlin/org/ramflux/sdk/MethodPolicy.kt').write_text(h + '''package org.ramflux.sdk

data class MethodPolicy(val secretInput: Boolean, val secretOutput: Boolean, val showOnce: Boolean, val capability: String) {
    val confidential: Boolean get() = secretInput || secretOutput
}

object SdkMethodPolicy {
    val methods: Map<String, MethodPolicy> = mapOf(
''' + rows + '\n    )\n}\n')
# All repository surfaces come from the same immutable method inventory.
groups = {"Accounts": ["account", "identity"], "Nodes": ["node"], "Conversations": ["conversation", "message", "object", "stream"], "Contacts": ["contact"], "Groups": ["group"], "Agents": ["bot"], "Devices": ["device"], "Permissions": ["grant", "mcp"], "Tasks": ["task"], "Surfaces": ["surface", "a2i", "a2ui"], "Calls": ["call"], "Settings": ["sync"]}
for name, prefixes in groups.items():
    selected = [m for m in policy["methods"] if m["name"].split(".")[0] in prefixes and m["name"] != "account.create"]
    declarations, implementations = [], []
    for method in selected:
        parts = method["name"].split(".") if len(prefixes) > 1 else method["name"].split(".")[1:]
        function = parts[0] + "".join(p[0].upper() + p[1:] for p in parts[1:])
        secret = method["secret_input"] or method["secret_output"]
        body = "SecretBody" if method["secret_input"] else "JsonObject"
        reply = "SecretReply" if secret else "JsonObject"
        signature = "suspend fun " + function + "(context: RepositoryContext, body: " + body + "): CoreResult<" + reply + ">"
        declarations.append("    " + signature)
        argument = "body" if method["secret_input"] or not secret else 'SecretBody.publicJson("' + method["name"] + '", body)'
        call = "executeSecret" if secret else "execute"
        implementations.append("    override " + signature + " =\n        " + call + "(\"" + method["name"] + "\", \"" + method["capability"] + "\", context, " + argument + ")")
    imports = "import kotlinx.serialization.json.JsonObject\nimport org.ramflux.sdk.CoreResult\nimport org.ramflux.sdk.SecretBody\nimport org.ramflux.sdk.SecretReply\n"
    output = h + "package org.ramflux.data\n\n" + imports + "\ninterface " + name + "Repository {\n" + "\n".join(declarations) + "\n}\n\nclass Default" + name + "Repository(transport: RepositoryTransport) : SdkRepository(transport), " + name + "Repository {\n" + "\n\n".join(implementations) + "\n}\n"
    (root / ("core/data/src/main/kotlin/org/ramflux/data/" + name + "Repository.kt")).write_text(output)
