#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Write Lucide stroke icons as Android vector drawables. Geometry comes from Lucide, not the design export.

The icon list and the mirroring table live in core/designsystem/kit-icons.json, shared with
KitIconMirrorTest: icons listed under "mirror" get android:autoMirrored="true"; "lucide" maps a Kit name to
the Lucide file name where Lucide renamed the icon (for example help-circle). Missing drawables are
fetched from Lucide (--refresh fetches all); existing drawables only have their mirroring flag brought
in line with the table, so the table can be applied offline.
"""
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DRAWABLE = ROOT / "core/designsystem/src/main/res/drawable"
ICONS = ROOT / "core/designsystem/src/main/kotlin/org/ramflux/designsystem/kit/KitIcons.kt"
SPEC = ROOT / "core/designsystem/kit-icons.json"
LUCIDE = "https://raw.githubusercontent.com/lucide-icons/lucide/0.544.0/icons/{name}.svg"
NS = {"svg": "http://www.w3.org/2000/svg"}


def num(value, default=0.0):
    if value is None:
        return default
    return float(str(value).replace("px", ""))


def points(value):
    parts = re.split(r"[,\s]+", value.strip())
    return [float(part) for part in parts if part]


def circle_path(cx, cy, radius):
    return f"M{cx - radius},{cy}a{radius},{radius} 0 1,0 {radius * 2},0a{radius},{radius} 0 1,0 {-radius * 2},0"


def rect_path(x, y, width, height):
    return f"M{x},{y}h{width}v{height}h{-width}z"


def poly_path(coords, close):
    if len(coords) < 4:
        raise ValueError("polyline needs two points")
    data = f"M{coords[0]},{coords[1]}"
    for index in range(2, len(coords), 2):
        data += f"L{coords[index]},{coords[index + 1]}"
    return data + ("z" if close else "")


ARGS = {
    "M": 2, "m": 2, "L": 2, "l": 2, "H": 1, "h": 1, "V": 1, "v": 1,
    "C": 6, "c": 6, "S": 4, "s": 4, "Q": 4, "q": 4, "T": 2, "t": 2,
    "A": 7, "a": 7, "Z": 0, "z": 0,
}
NUMBER = re.compile(r"[+-]?(?:\d*\.\d+|\d+)(?:[eE][+-]?\d+)?")


def normalize_path(data):
    tokens = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|[+-]?(?:\d*\.\d+|\d+)(?:[eE][+-]?\d+)?", data)
    if not tokens:
        return data
    index = 0
    parts = []

    def take(count):
        nonlocal index
        values = tokens[index:index + count]
        if len(values) != count or any(item.isalpha() for item in values):
            raise ValueError(f"short path data: {data}")
        index += count
        return values

    while index < len(tokens):
        command = tokens[index]
        if command not in ARGS:
            raise ValueError(f"bad path command {command} in {data}")
        index += 1
        count = ARGS[command]
        if count == 0:
            parts.append(command)
            continue
        first = True
        while index < len(tokens) and not tokens[index].isalpha():
            values = take(count)
            current = command
            if not first and command in ("M", "m"):
                current = "L" if command == "M" else "l"
            parts.append(current + " " + " ".join(values))
            first = False
            if index >= len(tokens) or tokens[index].isalpha():
                break
        if first:
            raise ValueError(f"command {command} has no arguments in {data}")
    return " ".join(parts)


def element_path(node):
    tag = node.tag.rsplit("}", 1)[-1]
    if tag == "path":
        raw = node.attrib.get("d")
        return normalize_path(raw) if raw else None
    if tag == "circle":
        return circle_path(num(node.attrib.get("cx")), num(node.attrib.get("cy")), num(node.attrib.get("r")))
    if tag == "ellipse":
        rx, ry = num(node.attrib.get("rx")), num(node.attrib.get("ry"))
        cx, cy = num(node.attrib.get("cx")), num(node.attrib.get("cy"))
        return f"M{cx - rx},{cy}a{rx},{ry} 0 1,0 {rx * 2},0a{rx},{ry} 0 1,0 {-rx * 2},0"
    if tag == "line":
        return f"M{num(node.attrib.get('x1'))},{num(node.attrib.get('y1'))}L{num(node.attrib.get('x2'))},{num(node.attrib.get('y2'))}"
    if tag == "rect":
        return rect_path(num(node.attrib.get("x")), num(node.attrib.get("y")), num(node.attrib.get("width")), num(node.attrib.get("height")))
    if tag == "polyline":
        return poly_path(points(node.attrib["points"]), False)
    if tag == "polygon":
        return poly_path(points(node.attrib["points"]), True)
    return None


def filled(node, root):
    fill = node.attrib.get("fill", root.attrib.get("fill", "none"))
    return fill not in ("none", "transparent")


MIRROR_ATTRIBUTE = '    android:autoMirrored="true"'


def with_mirroring(xml, mirror):
    """Set or remove the autoMirrored flag of the vector element; the result does not depend on the input flag."""
    lines = []
    for line in xml.splitlines():
        if line.strip() == 'android:autoMirrored="true">':
            lines[-1] += ">"
        elif line != MIRROR_ATTRIBUTE:
            lines.append(line.replace(' android:autoMirrored="true"', ""))
    if mirror:
        end = next(index for index, line in enumerate(lines) if line.lstrip().startswith("android:") and line.rstrip().endswith('">'))
        lines[end] = lines[end].rstrip()[:-1]
        lines.insert(end + 1, MIRROR_ATTRIBUTE + ">")
    return "\n".join(lines) + "\n"


def drawable(name, svg):
    root = ET.fromstring(svg)
    view_box = root.attrib.get("viewBox", "0 0 24 24").split()
    width, height = view_box[2], view_box[3]
    stroke = root.attrib.get("stroke-width", "2")
    cap = root.attrib.get("stroke-linecap", "round")
    join = root.attrib.get("stroke-linejoin", "round")
    paths = []
    for node in root.iter():
        data = element_path(node)
        if not data:
            continue
        local_stroke = node.attrib.get("stroke-width", stroke)
        if filled(node, root):
            paths.append(
                f'    <path android:fillColor="#FFFFFFFF" android:pathData="{data}"/>'
            )
        else:
            paths.append(
                "    <path"
                ' android:fillColor="#00000000"'
                ' android:strokeColor="#FFFFFFFF"'
                f' android:strokeWidth="{local_stroke}"'
                f' android:strokeLineCap="{cap}"'
                f' android:strokeLineJoin="{join}"'
                f' android:pathData="{data}"/>'
            )
    if not paths:
        raise ValueError(f"{name} has no geometry")
    body = "\n".join(paths)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        f'    android:viewportWidth="{width}"\n'
        f'    android:viewportHeight="{height}">\n'
        f"{body}\n"
        "</vector>\n"
    )


def resource(name):
    return "ic_kit_" + name.replace("-", "_")


def kotlin(names):
    rows = ",\n".join(
        f'        "{name}" to R.drawable.{resource(name)}' for name in names
    )
    return (
        "// SPDX-License-Identifier: AGPL-3.0-or-later\n"
        "// Copyright (c) 2026 Span Brain\n\n"
        "package org.ramflux.designsystem.kit\n\n"
        "import org.ramflux.core.designsystem.R\n\n"
        "internal object KitIcons {\n"
        "    val ids = mapOf(\n"
        f"{rows}\n"
        "    )\n"
        "}\n"
    )


def main():
    arguments = [argument for argument in sys.argv[1:] if not argument.startswith("--")]
    spec = Path(arguments[0]) if arguments else SPEC
    table = json.loads(spec.read_text())
    names = table["icons"]
    sources = table.get("lucide", {})
    if set(sources) - set(names):
        raise SystemExit(f"lucide table names unknown icons: {sorted(set(sources) - set(names))}")
    mirror = set(table["mirror"])
    unknown = (mirror | set(table["neverMirror"])) - set(names)
    if unknown:
        raise SystemExit(f"mirroring table names unknown icons: {sorted(unknown)}")
    if mirror & set(table["neverMirror"]):
        raise SystemExit(f"icons both mirrored and never mirrored: {sorted(mirror & set(table['neverMirror']))}")
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    for name in names:
        target = DRAWABLE / f"{resource(name)}.xml"
        if target.exists() and "--refresh" not in sys.argv:
            current = target.read_text()
        else:
            with urllib.request.urlopen(LUCIDE.format(name=sources.get(name, name)), timeout=30) as response:
                current = drawable(name, response.read())
            print(name)
        updated = with_mirroring(current, name in mirror)
        if updated != current or not target.exists():
            target.write_text(updated)
    ICONS.parent.mkdir(parents=True, exist_ok=True)
    ICONS.write_text(kotlin(names))
    print(f"ICONS {len(names)}")


if __name__ == "__main__":
    main()
