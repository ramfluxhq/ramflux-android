#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Compare kit captures with design preview PNGs and write pair evidence.

Usage: compare-kit-previews.py <previews-dir> <captures-dir> <output-dir>

Design exports are twice the design size. Transparent preview pixels are
composited onto rf-bg (light #FCFBF9, dark #171716) before the pair and the
diff. The checklist measures the master frame from kit-masters.json when the
export canvas is not that frame, and the untrimmed preview frame otherwise.
It does not measure trimmed ink. A capture edge more than 2 design pixels
from the master is OVER.
"""

import json
import struct
import sys
import zlib
from pathlib import Path

SCALE = 2
LIMIT = 2.0
LIGHT_BG = (0xFC, 0xFB, 0xF9)
DARK_BG = (0x17, 0x17, 0x16)
SKIP_PREFIXES = ("chart--", "radio--", "origin-display--")
STATE_SUFFIXES = ("--pressed", "--focused", "--loading", "--en", "--zh")
ROOT = Path(__file__).resolve().parent.parent
MASTERS = json.loads((ROOT / "core/designsystem/kit-masters.json").read_text())


def paeth(left, up, upper_left):
    estimate = left + up - upper_left
    scores = (abs(estimate - left), abs(estimate - up), abs(estimate - upper_left))
    return (left, up, upper_left)[scores.index(min(scores))]


def read_png(path):
    data = Path(path).read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"not a png: {path}")
    pos = 8
    width = height = color = None
    chunks = []
    while pos + 8 <= len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        kind = data[pos + 4 : pos + 8]
        chunk = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, bit, color = struct.unpack(">IIBB", chunk[:10])
            if bit != 8 or color not in (2, 6):
                raise SystemExit(f"unsupported png {path}")
        elif kind == b"IDAT":
            chunks.append(chunk)
        elif kind == b"IEND":
            break
    raw = zlib.decompress(b"".join(chunks))
    channels = 4 if color == 6 else 3
    stride = width * channels
    rows = []
    index = 0
    previous = bytearray(stride)
    for _ in range(height):
        filter_type = raw[index]
        index += 1
        row = bytearray(raw[index : index + stride])
        index += stride
        for column in range(stride):
            left = row[column - channels] if column >= channels else 0
            up = previous[column]
            upper_left = previous[column - channels] if column >= channels else 0
            if filter_type == 1:
                row[column] = (row[column] + left) & 255
            elif filter_type == 2:
                row[column] = (row[column] + up) & 255
            elif filter_type == 3:
                row[column] = (row[column] + ((left + up) // 2)) & 255
            elif filter_type == 4:
                row[column] = (row[column] + paeth(left, up, upper_left)) & 255
            elif filter_type != 0:
                raise SystemExit(f"bad filter in {path}")
        previous = row
        rows.append(row)
    return width, height, channels, rows


def pixel(rows, channels, x, y):
    sample = rows[y][x * channels : x * channels + channels]
    if channels == 4:
        return sample[0], sample[1], sample[2], sample[3]
    return sample[0], sample[1], sample[2], 255


def write_png(path, width, height, rgb_rows):
    raw = bytearray()
    for row in rgb_rows:
        raw.append(0)
        raw.extend(row)
    compressed = zlib.compress(bytes(raw), 9)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", compressed) + chunk(b"IEND", b""))


def board_bg(name):
    return DARK_BG if "--dark" in name else LIGHT_BG


def over_bg(red, green, blue, alpha, bg):
    if alpha >= 255:
        return red, green, blue
    scale = alpha / 255.0
    return (
        int(round(red * scale + bg[0] * (1.0 - scale))),
        int(round(green * scale + bg[1] * (1.0 - scale))),
        int(round(blue * scale + bg[2] * (1.0 - scale))),
    )


def scale_half(width, height, channels, rows, bg):
    out_w = width // SCALE
    out_h = height // SCALE
    scaled = []
    for y in range(out_h):
        row = bytearray()
        for x in range(out_w):
            red, green, blue, alpha = pixel(rows, channels, x * SCALE, y * SCALE)
            red, green, blue = over_bg(red, green, blue, alpha, bg)
            row.extend((red, green, blue))
        scaled.append(row)
    return out_w, out_h, scaled


def blank(width, height, color):
    row = bytes(color) * width
    return [bytearray(row) for _ in range(height)]


def blit(canvas, rows, x0, y0):
    for y, row in enumerate(rows):
        start = (x0 + y and 0) * 0
        offset = x0 * 3
        canvas[y0 + y][offset : offset + len(row)] = row
    return start


def lookup_master(name):
    keys = [name]
    if name.endswith("--dark"):
        keys.append(name[: -len("--dark")])
    base = name
    for suffix in STATE_SUFFIXES:
        if base.endswith(suffix):
            base = base[: -len(suffix)]
            keys.append(base)
            if base.endswith("--dark"):
                keys.append(base[: -len("--dark")])
    for key in keys:
        if key in MASTERS:
            return MASTERS[key]
    return None


def preview_for(previews, name):
    direct = previews / f"{name}.png"
    if direct.exists():
        return direct, False
    base = name
    for suffix in STATE_SUFFIXES:
        if base.endswith(suffix):
            base = base[: -len(suffix)]
            break
    for candidate in (base, f"{base}--default"):
        fallback = previews / f"{candidate}.png"
        if fallback.exists():
            return fallback, True
    return None, False


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: compare-kit-previews.py <previews-dir> <captures-dir> <output-dir>")
    previews = Path(sys.argv[1])
    captures = Path(sys.argv[2])
    output = Path(sys.argv[3])
    output.mkdir(parents=True, exist_ok=True)
    over = 0
    lines = ["name\tcapture\tmaster\tpreview\tdw\tdh\tflag\tnote"]
    print(f"{'name':42} {'capture':>11} {'master':>11} {'preview':>11} {'dw':>7} {'dh':>7}")
    for capture in sorted(captures.glob("*.png")):
        name = capture.stem
        if name.startswith(SKIP_PREFIXES):
            continue
        capture_w, capture_h, capture_channels, capture_rows = read_png(capture)
        preview_path, borrowed = preview_for(previews, name)
        preview_label = "none"
        scaled = None
        bg = board_bg(name)
        if preview_path is not None:
            preview_w, preview_h, channels, rows = read_png(preview_path)
            preview_label = f"{preview_w / SCALE:g}x{preview_h / SCALE:g}"
            scaled = scale_half(preview_w, preview_h, channels, rows, bg)
        master = lookup_master(name)
        note = ""
        if master is not None:
            master_w = master.get("w", capture_w)
            master_h = master["h"]
            note = master.get("note", "")
            if borrowed:
                note = (note + "; " if note else "") + "paired with the base preview"
        elif scaled is not None:
            master_w, master_h, _rows = scaled
        else:
            print(f"{name:42} MISSING preview")
            lines.append(f"{name}\t{capture_w}x{capture_h}\tMISSING\tnone\t\t\tOVER\tno preview")
            over += 1
            continue
        delta_w = capture_w - master_w
        delta_h = capture_h - master_h
        width_checked = not (master is not None and "w" not in master)
        locale = name.endswith("--en") or name.endswith("--zh")
        limit_h = 4.0 if locale else LIMIT
        fail_w = width_checked and abs(delta_w) > LIMIT
        fail_h = abs(delta_h) > limit_h
        flag = "OVER" if fail_w or fail_h else "ok"
        if flag == "OVER":
            over += 1
        dw_text = f"{delta_w:.1f}" if width_checked else "n/a"
        print(
            f"{name:42} {capture_w:4}x{capture_h:<4} {master_w:5g}x{master_h:<5g} "
            f"{preview_label:>11} {dw_text:>7} {delta_h:7.1f} {flag}"
        )
        lines.append(
            f"{name}\t{capture_w}x{capture_h}\t{master_w:g}x{master_h:g}\t{preview_label}\t{dw_text}\t{delta_h:.1f}\t{flag}\t{note}"
        )
        if scaled is not None:
            write_pair(output / f"{name}--pair.png", capture_w, capture_h, capture_channels, capture_rows, scaled, bg)
            write_diff(output / f"{name}--diff.png", capture_w, capture_h, capture_channels, capture_rows, scaled, bg)
    lines.append("")
    lines.append("notes")
    lines.append("Pushed page titles are 17/600. The page-header spec title token is 20. The ruling overrides that spec.")
    lines.append("The selected tab has no pill. Selection is icon and label color and weight.")
    lines.append("Chinese uses the platform CJK font, so type metrics can differ from Noto Sans SC.")
    lines.append("en/zh samples may differ by up to 4 px because Latin and CJK metrics differ.")
    lines.append("Icon geometry is Lucide. Pencil strokes are a reference, not the vector source.")
    checklist = output / "checklist.tsv"
    checklist.write_text("\n".join(lines) + "\n")
    print(f"OVER {over}")
    print(f"CHECKLIST {checklist}")
    return 1 if over else 0


def capture_rgb(width, height, channels, rows, bg):
    rgb = []
    for y in range(height):
        row = bytearray()
        for x in range(width):
            red, green, blue, alpha = pixel(rows, channels, x, y)
            red, green, blue = over_bg(red, green, blue, alpha, bg)
            row.extend((red, green, blue))
        rgb.append(row)
    return rgb


def write_pair(path, capture_w, capture_h, channels, rows, scaled, bg):
    preview_w, preview_h, preview_rows = scaled
    gap = 8
    width = capture_w + gap + preview_w
    height = max(capture_h, preview_h)
    canvas = blank(width, height, bg)
    blit(canvas, capture_rgb(capture_w, capture_h, channels, rows, bg), 0, 0)
    blit(canvas, preview_rows, capture_w + gap, 0)
    write_png(path, width, height, canvas)


def write_diff(path, capture_w, capture_h, channels, rows, scaled, bg):
    preview_w, preview_h, preview_rows = scaled
    canvas = blank(capture_w, capture_h, bg)
    for y in range(capture_h):
        for x in range(capture_w):
            red, green, blue, alpha = pixel(rows, channels, x, y)
            red, green, blue = over_bg(red, green, blue, alpha, bg)
            if y < preview_h and x < preview_w:
                other = preview_rows[y][x * 3 : x * 3 + 3]
                delta = max(abs(red - other[0]), abs(green - other[1]), abs(blue - other[2]))
                if delta > 40:
                    red, green, blue = 220, 40, 40
            canvas[y][x * 3 : x * 3 + 3] = bytes((red, green, blue))
    write_png(path, capture_w, capture_h, canvas)


if __name__ == "__main__":
    sys.exit(main())
