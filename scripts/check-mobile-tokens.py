#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Compare hand-written RFTokens with the design token file.

The design file's SHA-256 is pinned. CI passes that file and fails if the
bytes drift from the pin or if RFTokens disagrees with it. A second path,
when the caller has the spec file, must be byte-identical to the first.
"""
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DESIGN_SHA256 = "5af83dc4aafa883c1bfaa4a33c222be577567eb4327953ba80a7f0d78d680632"
SOURCE = ROOT / "core/designsystem/src/main/kotlin/org/ramflux/generated/RFTokens.kt"
THEMES = ROOT / "core/designsystem/src/main/assets/ramflux/themes"


def argb(value):
    raw = value.removeprefix("#")
    if len(raw) == 6:
        return int(raw, 16) | 0xFF000000
    if len(raw) == 8:
        return int(raw[6:8] + raw[0:6], 16)
    raise ValueError(f"bad color {value}")


def theme_block(text, name):
    match = re.search(rf"val {name} = RFTheme\((.*?)\)\n", text, re.S)
    if not match:
        raise SystemExit(f"missing RFDesign.{name}")
    return match.group(1)


def field_map(block):
    found = {}
    for match in re.finditer(r"(\w+)\s*=\s*(Color\(0x([0-9A-Fa-f]+)\)|(-?\d+(?:\.\d+)?)f|\"([^\"]*)\")", block):
        name = match.group(1)
        if match.group(3):
            found[name] = int(match.group(3), 16)
        elif match.group(4):
            found[name] = float(match.group(4))
        else:
            found[name] = match.group(5)
    return found


def component_hex(node):
    parts = node["components"]
    alpha = node.get("alpha", 1)
    channels = [int(round(float(part) * 255)) for part in parts]
    alpha_byte = int(round(float(alpha) * 255))
    return (alpha_byte << 24) | (channels[0] << 16) | (channels[1] << 8) | channels[2]


def main():
    if len(sys.argv) not in (2, 3):
        raise SystemExit("usage: check-mobile-tokens.py <design-tokens.json> [spec-tokens.json]")
    token_path = Path(sys.argv[1])
    raw = token_path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != DESIGN_SHA256:
        raise SystemExit(f"TOKEN_DIGEST {digest} != {DESIGN_SHA256}")
    if len(sys.argv) == 3:
        spec_bytes = Path(sys.argv[2]).read_bytes()
        spec_digest = hashlib.sha256(spec_bytes).hexdigest()
        if spec_bytes != raw or spec_digest != DESIGN_SHA256:
            raise SystemExit(f"TOKEN_SPEC_MISMATCH {spec_digest} != {DESIGN_SHA256}")
    spec = json.loads(raw)
    text = SOURCE.read_text()
    light = field_map(theme_block(text, "light"))
    dark = field_map(theme_block(text, "dark"))
    errors = []
    for key, modes in spec["color"]["values"].items():
        field = key.removeprefix("rf-").replace("-", "_")
        for mode, block in (("light", light), ("dark", dark)):
            expected = argb(modes[mode])
            actual = block.get(field)
            if actual != expected:
                errors.append(f"{mode} {field}: {actual} != {expected:#010x}")
    numbers = {}
    numbers.update({key.removeprefix("rf-").replace("-", "_"): float(value) for key, value in spec["typography"]["sizesPx"]["values"].items()})
    numbers.update({key.removeprefix("rf-").replace("-", "_"): float(value) for key, value in spec["radii"]["variables"].items()})
    numbers.update({key.removeprefix("rf-").replace("-", "_"): float(value) for key, value in spec["dimensions"]["variables"].items()})
    for field, expected in numbers.items():
        if light.get(field) != expected or dark.get(field) != expected:
            errors.append(f"{field}: light {light.get(field)} dark {dark.get(field)} != {expected}")
    families = {
        "font": spec["typography"]["families"]["rf-font"],
        "font_latin": spec["typography"]["families"]["rf-font-latin"],
        "font_serif": spec["typography"]["families"]["rf-font-serif"],
        "font_mono": spec["typography"]["families"]["rf-font-mono"],
    }
    for field, expected in families.items():
        if light.get(field) != expected or dark.get(field) != expected:
            errors.append(f"{field}: {light.get(field)}/{dark.get(field)} != {expected}")
    for name in ("light", "dark"):
        theme = json.loads((THEMES / f"{name}.json").read_text())["tokens"]
        block = light if name == "light" else dark
        for field, value in block.items():
            token = theme.get(field.replace("_", "-"))
            if token is None:
                errors.append(f"theme {name} missing {field}")
                continue
            kind = token["$type"]
            if kind == "color":
                actual = component_hex(token["$value"])
                if actual != value:
                    errors.append(f"theme {name} {field}: {actual:#010x} != {value:#010x}")
            elif kind == "dimension":
                actual = float(token["$value"]["value"])
                if actual != value:
                    errors.append(f"theme {name} {field}: {actual} != {value}")
            elif kind == "fontFamily":
                if token["$value"] != value:
                    errors.append(f"theme {name} {field}: {token['$value']} != {value}")
            else:
                errors.append(f"theme {name} {field}: unsupported {kind}")
    if errors:
        raise SystemExit("TOKEN_MISMATCH\n" + "\n".join(errors))
    print(f"TOKENS_OK {len(spec['color']['values'])} colors")


if __name__ == "__main__":
    main()
