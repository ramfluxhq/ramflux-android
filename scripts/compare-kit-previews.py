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

import hashlib
import json
import re
import struct
import sys
import zlib
from pathlib import Path

SCALE = 2
LIMIT = 2.0
PIXEL_STRONG = 0.35
PIXEL_MAE = 48.0
VARIANCE_FLOOR = 64.0
SHELL_NAME = re.compile(r"^(MOB-[A-Z0-9]+)\.(light|dark)--(en|zh)$")
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


def load_table(path):
    if not path.exists():
        return []
    rows = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            rows.append(line)
    return rows


def board_id(name):
    match = SHELL_NAME.match(name)
    if match:
        return match.group(1)
    return name.split(".", 1)[0].split("--", 1)[0]


def ink_count(width, channels, y, rows, bg):
    count = 0
    for x in range(width):
        red, green, blue, alpha = pixel(rows, channels, x, y)
        red, green, blue = over_bg(red, green, blue, alpha, bg)
        if max(abs(red - bg[0]), abs(green - bg[1]), abs(blue - bg[2])) > 24:
            count += 1
    return count


def luma_variance(width, height, channels, rows, bg):
    count = 0
    total = 0
    total_sq = 0
    for y in range(height):
        for x in range(width):
            red, green, blue, alpha = pixel(rows, channels, x, y)
            red, green, blue = over_bg(red, green, blue, alpha, bg)
            lum = (red * 3 + green * 6 + blue) // 10
            total += lum
            total_sq += lum * lum
            count += 1
    mean = total / count
    return total_sq / count - mean * mean


def pixel_error(capture_w, capture_h, channels, rows, scaled, bg):
    _preview_w, _preview_h, preview_rows = scaled
    total = 0
    strong = 0
    accumulated = 0
    height = min(capture_h, len(preview_rows))
    for y in range(height):
        width = min(capture_w, len(preview_rows[y]) // 3)
        for x in range(width):
            red, green, blue, alpha = pixel(rows, channels, x, y)
            red, green, blue = over_bg(red, green, blue, alpha, bg)
            other = preview_rows[y][x * 3 : x * 3 + 3]
            delta = max(abs(red - other[0]), abs(green - other[1]), abs(blue - other[2]))
            accumulated += delta
            if delta > 40:
                strong += 1
            total += 1
    if total == 0:
        return PIXEL_MAE + 1, 1.0
    return accumulated / total, strong / total


def first_ink_y(width, height, channels, rows, bg, limit):
    for y in range(min(limit, height)):
        if ink_count(width, channels, y, rows, bg) > 20:
            return y
    return None


def tab_top(width, height, channels, rows, bg):
    hits = []
    for y in range(max(0, height - 140), max(0, height - 8)):
        count = ink_count(width, channels, y, rows, bg)
        if 15 <= count <= 180:
            hits.append(y)
    if not hits:
        return None
    floor = hits[-1]
    cluster = [y for y in hits if floor - y <= 40]
    return min(cluster)


def structure_reason(name, width, height, channels, rows, bg, layout):
    match = SHELL_NAME.match(name)
    if layout is None or match is None or match.group(2) != "light":
        return ""
    board = match.group(1)
    tolerance = layout["tolerance"]
    reasons = []
    if board in layout["titled"]:
        measured = first_ink_y(width, height, channels, rows, bg, 180)
        spec = layout["titleHeaderY"]
        relative = spec - layout["statusBar"]
        if measured is None or min(abs(measured - spec), abs(measured - relative)) > tolerance:
            reasons.append(f"title y={measured} spec={spec}")
    if board in layout["tabRoots"]:
        measured = tab_top(width, height, channels, rows, bg)
        spec = layout["tabBarY"]
        if measured is None or abs(measured - spec) > tolerance:
            reasons.append(f"tab y={measured} spec={spec}")
    return "; ".join(reasons)


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: compare-kit-previews.py <previews-dir> <captures-dir> <output-dir>")
    previews = Path(sys.argv[1])
    captures = Path(sys.argv[2])
    output = Path(sys.argv[3])
    output.mkdir(parents=True, exist_ok=True)
    gaps = {line.split("\t", 1)[0]: line.split("\t", 1)[-1] for line in load_table(previews / "design-gaps.tsv")}
    layout_path = previews / "shell-layout.json"
    layout = json.loads(layout_path.read_text()) if layout_path.exists() else None
    over = 0
    lines = ["name\tcapture\tmaster\tpreview\tdw\tdh\tflag\tnote"]
    fingerprints = {}
    print(f"{'name':42} {'capture':>11} {'master':>11} {'preview':>11} {'dw':>7} {'dh':>7}")
    captures_found = [path for path in sorted(captures.glob("*.png")) if not path.stem.startswith(SKIP_PREFIXES)]
    if not captures_found:
        print("OVER empty capture directory")
        lines.append("captures\tEMPTY\t\t\t\t\tOVER\tempty capture directory")
        over += 1
    for stem in load_table(previews / "expected-frames.txt"):
        if not (captures / f"{stem}.png").exists():
            print(f"{stem:42} MISSING expected frame")
            lines.append(f"{stem}\tMISSING\t\t\t\t\tOVER\texpected frame absent")
            over += 1
    for capture in captures_found:
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
        reasons = []
        if fail_w or fail_h:
            reasons.append("dimension")
        if board_id(name) in gaps:
            reasons.append("design gap must not be paired")
        if scaled is not None:
            mae, strong = pixel_error(capture_w, capture_h, capture_channels, capture_rows, scaled, bg)
            if strong > PIXEL_STRONG or mae > PIXEL_MAE:
                reasons.append(f"pixels mae={mae:.1f} strong={strong:.3f}")
            if capture_h >= 700:
                variance = luma_variance(capture_w, capture_h, capture_channels, capture_rows, bg)
                if variance < VARIANCE_FLOOR:
                    reasons.append(f"low-variance {variance:.1f}")
            placed = structure_reason(name, capture_w, capture_h, capture_channels, capture_rows, bg, layout)
            if placed:
                reasons.append(placed)
            digest = hashlib.sha256()
            for row in capture_rows:
                digest.update(row)
            fingerprints[(name, digest.digest())] = board_id(name)
        flag = "OVER" if reasons else "ok"
        if flag == "OVER":
            over += 1
        if reasons:
            note = (note + "; " if note else "") + "; ".join(reasons)
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
    grouped = {}
    for (name, digest), board in fingerprints.items():
        match = SHELL_NAME.match(name)
        if match is None:
            continue
        grouped.setdefault((match.group(2), match.group(3), digest), set()).add(board)
    for (theme, locale, _digest), boards in sorted(grouped.items()):
        if len(boards) > 1:
            label = ",".join(sorted(boards))
            print(f"OVER identical pixels {theme} {locale}: {label}")
            lines.append(f"{label}\t\t\t\t\t\tOVER\tidentical pixels on {theme} {locale}")
            over += 1
    for board, reason in sorted(gaps.items()):
        lines.append(f"{board}\t\t\t\t\t\tGAP\t{reason}")
    lines.append("")
    lines.append("notes")
    lines.append("Pushed page titles are 17/600. The page-header spec title token is 20. The ruling overrides that spec.")
    lines.append("The selected tab has no pill. Selection is icon and label color and weight.")
    lines.append("Chinese uses the platform CJK font, so type metrics can differ from Noto Sans SC.")
    lines.append("en/zh samples may differ by up to 4 px because Latin and CJK metrics differ.")
    lines.append("Icon geometry is Lucide. Pencil strokes are a reference, not the vector source.")
    lines.append("Pixel mean channel error above 48, or more than 35 percent of pixels differing by more than 40, fails.")
    lines.append("A full-board luma variance below 64 fails. That is a blank or flat placeholder frame.")
    lines.append("Light titles must sit on spec header y=59, or on that header when the 59px design status bar is absent. Tolerance is 32.")
    lines.append("Light tab bars must start within 32px of spec y=769.")
    lines.append("Two different board ids with identical pixels fail. A listed design gap must not be captured as a match.")
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
