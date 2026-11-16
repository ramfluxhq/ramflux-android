#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Compare kit captures with 2x design preview PNGs.

Usage: compare-kit-previews.py <previews-dir> <captures-dir>

Design exports are twice the design size. A border whose pixels match the
corner color is removed before the size is halved, so canvas padding is not
a component delta. A width or height delta over 2 design pixels is OVER.
"""

import struct
import sys
import zlib
from pathlib import Path

SCALE = 2
LIMIT = 2.0
SKIP_PREFIXES = ("chart--", "radio--", "origin-display--")


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


def content_box(width, height, channels, rows):
    corner = pixel(rows, channels, 0, 0)

    def background(x, y):
        sample = pixel(rows, channels, x, y)
        if corner[3] < 16 and sample[3] < 16:
            return True
        return all(abs(sample[channel] - corner[channel]) <= 18 for channel in range(4))

    top = 0
    while top < height and all(background(x, top) for x in range(width)):
        top += 1
    bottom = height - 1
    while bottom >= top and all(background(x, bottom) for x in range(width)):
        bottom -= 1
    if top > bottom:
        return 0, 0, width, height
    left = 0
    while left < width and all(background(left, y) for y in range(top, bottom + 1)):
        left += 1
    right = width - 1
    while right >= left and all(background(right, y) for y in range(top, bottom + 1)):
        right -= 1
    return left, top, right - left + 1, bottom - top + 1


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: compare-kit-previews.py <previews-dir> <captures-dir>")
    previews = Path(sys.argv[1])
    captures = Path(sys.argv[2])
    over = 0
    print(f"{'name':42} {'capture':>11} {'preview':>11} {'trimmed':>11} {'dw':>7} {'dh':>7} basis")
    for capture in sorted(captures.glob("*.png")):
        name = capture.stem
        if name.startswith(SKIP_PREFIXES):
            continue
        preview_path = previews / f"{name}.png"
        if not preview_path.exists():
            print(f"{name:42} MISSING preview")
            over += 1
            continue
        capture_w, capture_h, _, _ = read_png(capture)
        preview_w, preview_h, channels, rows = read_png(preview_path)
        _, _, trimmed_w, trimmed_h = content_box(preview_w, preview_h, channels, rows)
        plain_w = preview_w / SCALE
        plain_h = preview_h / SCALE
        design_w = trimmed_w / SCALE
        design_h = trimmed_h / SCALE
        plain_score = max(abs(capture_w - plain_w), abs(capture_h - plain_h))
        trim_score = max(abs(capture_w - design_w), abs(capture_h - design_h))
        use_trim = trim_score + 0.5 < plain_score
        delta_w = capture_w - (design_w if use_trim else plain_w)
        delta_h = capture_h - (design_h if use_trim else plain_h)
        flag = "OVER" if abs(delta_w) > LIMIT or abs(delta_h) > LIMIT else "ok"
        if flag == "OVER":
            over += 1
        chosen = "trim" if use_trim else "frame"
        print(
            f"{name:42} {capture_w:4}x{capture_h:<4} {plain_w:5g}x{plain_h:<5g} "
            f"{design_w:5g}x{design_h:<5g} {delta_w:7.1f} {delta_h:7.1f} {chosen:5} {flag}"
        )
    print(f"OVER {over}")
    return 1 if over else 0


if __name__ == "__main__":
    sys.exit(main())
