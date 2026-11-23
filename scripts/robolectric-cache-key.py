#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Print the cache key part for the Robolectric android-all jars.

The key covers only the Robolectric version and the android-all jar versions in
gradle/libs.versions.toml, so adding an unrelated library keeps the cached jars.
On a hosted runner the value is also written to $GITHUB_OUTPUT as `hash=...`.
"""
import hashlib
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "gradle" / "libs.versions.toml"
KEYS = ("robolectric", "androidAllSdk28", "androidAllSdk35")


def main():
    found = {}
    for line in CATALOG.read_text().splitlines():
        match = re.match(r'^(\w+)\s*=\s*"([^"]+)"', line)
        if match and match.group(1) in KEYS:
            found[match.group(1)] = match.group(2)
    missing = [key for key in KEYS if key not in found]
    if missing:
        sys.exit(f"missing versions in {CATALOG}: {', '.join(missing)}")
    digest = hashlib.sha256("\n".join(f"{k}={found[k]}" for k in KEYS).encode()).hexdigest()[:32]
    print(f"hash={digest}")
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a") as handle:
            handle.write(f"hash={digest}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
