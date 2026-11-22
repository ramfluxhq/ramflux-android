#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Compare board captures with the current design exports, pixel by pixel.

Usage:
  compare-board-captures.py <references-dir> <captures-dir> <output-dir>
      [--expected <expected-deviations.json>] [--write-expected <file>]

<references-dir> holds manifest.json and the reference PNGs (see
sync-board-references.py). Each manifest board with capture != false must have
a capture named <key>.<theme>--<locale>.png of exactly the reference size
(393x852 dp at the comparison scale, 2 export pixels per dp).

Per board the comparison
  * excludes the manifest masks (system status bar, home indicator) and every
    reference pixel that is not fully opaque (the rounded frame corners);
  * counts a pixel as different when its largest channel difference exceeds
    CHANNEL_TOLERANCE and the anti-aliasing test fails: an edge pixel whose
    value appears within one export pixel in the other image, in both
    directions, is anti-aliasing or sub-pixel placement, not a deviation;
  * reports the percentage of differing pixels and the differing regions:
    differing pixels grouped on a 1 dp grid, bounding boxes, size and an
    offset estimate in dp, and a kind (missing, extra, shifted, different);
  * lists every region larger than LIMIT_DP in either direction.

Without --expected any listed region fails the board. With --expected, a
board passes when every listed region lies inside a reviewed region of that
board and the percentage does not grow by more than the recorded tolerance;
anything else is a NEW deviation and fails. --write-expected records the
current result (keeping existing reasons) for review.
Outputs: <name>--pair.png (capture | reference), <name>--diff.png, report.tsv
and report.md in <output-dir>.
"""

import hashlib
import json
import struct
import sys
import zlib
from collections import deque
from pathlib import Path

CHANNEL_TOLERANCE = 10
LIMIT_DP = 2
GROUP_RADIUS_DP = 3
SHIFT_SEARCH_DP = 12
REGION_REPORT_LIMIT = 12
DEFAULT_PERCENT_TOLERANCE = 0.5
DEFAULT_REGION_SLACK_DP = 4
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


# ---------------------------------------------------------------- PNG input and output


def _paeth(left, up, upper_left):
    estimate = left + up - upper_left
    a = abs(estimate - left)
    b = abs(estimate - up)
    c = abs(estimate - upper_left)
    if a <= b and a <= c:
        return left
    if b <= c:
        return up
    return upper_left


def read_png(path):
    """Return (width, height, rgb bytearray, alpha bytearray)."""
    data = Path(path).read_bytes()
    if data[:8] != PNG_SIGNATURE:
        raise ValueError(f"not a png: {path}")
    pos = 8
    width = height = color = None
    chunks = []
    while pos + 8 <= len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        kind = data[pos + 4 : pos + 8]
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, bit, color, _c, _f, interlace = struct.unpack(">IIBBBBB", body[:13])
            if bit != 8 or color not in (2, 6) or interlace != 0:
                raise ValueError(f"unsupported png {path}: bit {bit} color {color} interlace {interlace}")
        elif kind == b"IDAT":
            chunks.append(body)
        elif kind == b"IEND":
            break
    raw = zlib.decompress(b"".join(chunks))
    channels = 4 if color == 6 else 3
    stride = width * channels
    previous = bytearray(stride)
    rgb = bytearray(width * height * 3)
    alpha = bytearray(b"\xff") * (width * height)
    index = 0
    for y in range(height):
        filter_type = raw[index]
        index += 1
        row = bytearray(raw[index : index + stride])
        index += stride
        if filter_type == 1:
            for column in range(channels, stride):
                row[column] = (row[column] + row[column - channels]) & 255
        elif filter_type == 2:
            row = bytearray((a + b) & 255 for a, b in zip(row, previous))
        elif filter_type == 3:
            for column in range(stride):
                left = row[column - channels] if column >= channels else 0
                row[column] = (row[column] + ((left + previous[column]) >> 1)) & 255
        elif filter_type == 4:
            for column in range(stride):
                if column >= channels:
                    row[column] = (row[column] + _paeth(row[column - channels], previous[column], previous[column - channels])) & 255
                else:
                    row[column] = (row[column] + previous[column]) & 255
        elif filter_type != 0:
            raise ValueError(f"bad filter in {path}")
        previous = row
        base = y * width
        if channels == 3:
            rgb[base * 3 : (base + width) * 3] = row
        else:
            rgb[base * 3 : (base + width) * 3 : 3] = row[0::4]
            rgb[base * 3 + 1 : (base + width) * 3 : 3] = row[1::4]
            rgb[base * 3 + 2 : (base + width) * 3 : 3] = row[2::4]
            alpha[base : base + width] = row[3::4]
    return width, height, rgb, alpha


def write_png(path, width, height, rgb):
    raw = bytearray()
    stride = width * 3
    for y in range(height):
        raw.append(0)
        raw.extend(rgb[y * stride : (y + 1) * stride])

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    Path(path).write_bytes(PNG_SIGNATURE + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))


def composite(rgb, alpha, background):
    """Blend non-opaque pixels over background in place."""
    for index, value in enumerate(alpha):
        if value == 255:
            continue
        scale = value / 255.0
        base = index * 3
        for channel in range(3):
            rgb[base + channel] = int(round(rgb[base + channel] * scale + background[channel] * (1.0 - scale)))


def modal_color(rgb, alpha):
    counts = {}
    for index in range(0, len(alpha), 7):
        if alpha[index] == 255:
            base = index * 3
            key = (rgb[base], rgb[base + 1], rgb[base + 2])
            counts[key] = counts.get(key, 0) + 1
    if not counts:
        return (255, 255, 255)
    return max(counts.items(), key=lambda item: item[1])[0]


# ---------------------------------------------------------------- pixel comparison


def _close(rgb_a, a, rgb_b, b):
    return (
        abs(rgb_a[a] - rgb_b[b]) <= CHANNEL_TOLERANCE
        and abs(rgb_a[a + 1] - rgb_b[b + 1]) <= CHANNEL_TOLERANCE
        and abs(rgb_a[a + 2] - rgb_b[b + 2]) <= CHANNEL_TOLERANCE
    )


def _found_nearby(source, index, other, width, height):
    """True when the source pixel's value occurs within one pixel in the other image."""
    x = index % width
    y = index // width
    base = index * 3
    for dy in (-1, 0, 1):
        ny = y + dy
        if ny < 0 or ny >= height:
            continue
        for dx in (-1, 0, 1):
            nx = x + dx
            if (dx == 0 and dy == 0) or nx < 0 or nx >= width:
                continue
            if _close(source, base, other, (ny * width + nx) * 3):
                return True
    return False


def differing_pixels(width, height, reference, capture, excluded):
    """Return a bytearray marking differing pixels (1) after anti-aliasing tolerance."""
    marks = bytearray(width * height)
    stride = width * 3
    for y in range(height):
        row_start = y * stride
        if reference[row_start : row_start + stride] == capture[row_start : row_start + stride]:
            continue
        for x in range(width):
            index = y * width + x
            if excluded[index]:
                continue
            base = index * 3
            if _close(reference, base, capture, base):
                continue
            if _found_nearby(capture, index, reference, width, height) and _found_nearby(reference, index, capture, width, height):
                continue
            marks[index] = 1
    return marks


def build_exclusions(width, height, alpha, masks, scale):
    excluded = bytearray(width * height)
    for index, value in enumerate(alpha):
        if value != 255:
            excluded[index] = 1
    for mask in masks:
        x0 = max(0, int(mask["x"] * scale))
        y0 = max(0, int(mask["y"] * scale))
        x1 = min(width, int((mask["x"] + mask["w"]) * scale))
        y1 = min(height, int((mask["y"] + mask["h"]) * scale))
        for y in range(y0, y1):
            excluded[y * width + x0 : y * width + x1] = b"\x01" * (x1 - x0)
    return excluded


# ---------------------------------------------------------------- regions


def cell_grid(marks, width, height, scale):
    """Count differing pixels per 1 dp cell."""
    cols = width // scale
    rows = height // scale
    grid = [0] * (cols * rows)
    for index, value in enumerate(marks):
        if value:
            x = (index % width) // scale
            y = (index // width) // scale
            if x < cols and y < rows:
                grid[y * cols + x] += 1
    return cols, rows, grid


def label_regions(cols, rows, grid):
    """Group marked cells that lie within GROUP_RADIUS_DP of each other."""
    marked = [index for index, value in enumerate(grid) if value]
    owner = {}
    regions = []
    radius = GROUP_RADIUS_DP
    marked_set = set(marked)
    for start in marked:
        if start in owner:
            continue
        label = len(regions)
        queue = deque([start])
        owner[start] = label
        cells = []
        while queue:
            cell = queue.popleft()
            cells.append(cell)
            cx = cell % cols
            cy = cell // cols
            for dy in range(-radius, radius + 1):
                ny = cy + dy
                if ny < 0 or ny >= rows:
                    continue
                for dx in range(-radius, radius + 1):
                    nx = cx + dx
                    if nx < 0 or nx >= cols:
                        continue
                    neighbour = ny * cols + nx
                    if neighbour in marked_set and neighbour not in owner:
                        owner[neighbour] = label
                        queue.append(neighbour)
        xs = [cell % cols for cell in cells]
        ys = [cell // cols for cell in cells]
        regions.append(
            {
                "x": min(xs),
                "y": min(ys),
                "w": max(xs) - min(xs) + 1,
                "h": max(ys) - min(ys) + 1,
                "pixels": sum(grid[cell] for cell in cells),
            }
        )
    regions.sort(key=lambda region: region["pixels"], reverse=True)
    return regions


def ink_grid(rgb, width, height, scale, background, excluded):
    """Per-dp mean distance from the board background (0 = background)."""
    cols = width // scale
    rows = height // scale
    grid = [0.0] * (cols * rows)
    area = scale * scale
    for cy in range(rows):
        for cx in range(cols):
            total = 0
            for sy in range(scale):
                base_index = (cy * scale + sy) * width + cx * scale
                for sx in range(scale):
                    index = base_index + sx
                    if excluded[index]:
                        continue
                    base = index * 3
                    total += max(
                        abs(rgb[base] - background[0]), abs(rgb[base + 1] - background[1]), abs(rgb[base + 2] - background[2])
                    )
            grid[cy * cols + cx] = total / area
    return cols, rows, grid


def _profile(grid, cols, x0, y0, x1, y1, axis):
    if axis == 0:
        return [sum(grid[y * cols + x] for y in range(y0, y1)) for x in range(x0, x1)]
    return [sum(grid[y * cols + x] for x in range(x0, x1)) for y in range(y0, y1)]


def _best_shift(reference, capture, search):
    best = (float("inf"), 0)
    length = len(reference)
    for shift in range(-search, search + 1):
        error = 0.0
        for position in range(length):
            other = position + shift
            value = capture[other] if 0 <= other < length else 0.0
            error += abs(reference[position] - value)
        if error < best[0] or (error == best[0] and abs(shift) < abs(best[1])):
            best = (error, shift)
    return best[1]


def _error(ref_grid, cap_grid, cols, rows, x0, y0, x1, y1, dx, dy):
    total = 0.0
    for y in range(y0, y1):
        for x in range(x0, x1):
            nx = x + dx
            ny = y + dy
            other = cap_grid[ny * cols + nx] if 0 <= nx < cols and 0 <= ny < rows else 0.0
            total += abs(ref_grid[y * cols + x] - other)
    return total


def classify(region, ref_ink, cap_ink, cols, rows):
    search = SHIFT_SEARCH_DP
    x0 = max(0, region["x"] - search)
    y0 = max(0, region["y"] - search)
    x1 = min(cols, region["x"] + region["w"] + search)
    y1 = min(rows, region["y"] + region["h"] + search)
    bx0, by0 = region["x"], region["y"]
    bx1, by1 = region["x"] + region["w"], region["y"] + region["h"]
    ref_total = sum(ref_ink[y * cols + x] for y in range(by0, by1) for x in range(bx0, bx1))
    cap_total = sum(cap_ink[y * cols + x] for y in range(by0, by1) for x in range(bx0, bx1))
    if ref_total <= 0.05 * cap_total:
        return "extra", 0, 0
    if cap_total <= 0.05 * ref_total:
        return "missing", 0, 0
    dx = _best_shift(_profile(ref_ink, cols, x0, by0, x1, by1, 0), _profile(cap_ink, cols, x0, by0, x1, by1, 0), search)
    dy = _best_shift(_profile(ref_ink, cols, bx0, y0, bx1, y1, 1), _profile(cap_ink, cols, bx0, y0, bx1, y1, 1), search)
    if dx == 0 and dy == 0:
        return "different", 0, 0
    before = _error(ref_ink, cap_ink, cols, rows, bx0, by0, bx1, by1, 0, 0)
    after = _error(ref_ink, cap_ink, cols, rows, bx0, by0, bx1, by1, dx, dy)
    if after < 0.5 * before:
        return "shifted", dx, dy
    return "different", dx, dy


# ---------------------------------------------------------------- evidence images


def draw_box(rgb, width, height, region, scale, color):
    x0 = region["x"] * scale
    y0 = region["y"] * scale
    x1 = min(width - 1, (region["x"] + region["w"]) * scale)
    y1 = min(height - 1, (region["y"] + region["h"]) * scale)
    for thickness in range(2):
        for x in range(x0, x1 + 1):
            for y in (y0 + thickness, y1 - thickness):
                if 0 <= y < height:
                    rgb[(y * width + x) * 3 : (y * width + x) * 3 + 3] = bytes(color)
        for y in range(y0, y1 + 1):
            for x in (x0 + thickness, x1 - thickness):
                if 0 <= x < width:
                    rgb[(y * width + x) * 3 : (y * width + x) * 3 + 3] = bytes(color)


def diff_image(width, height, reference, marks, excluded, regions, scale):
    out = bytearray(len(reference))
    for index in range(width * height):
        base = index * 3
        if marks[index]:
            out[base : base + 3] = b"\xdc\x28\x28"
        elif excluded[index]:
            out[base : base + 3] = b"\xc8\xd8\xf0"
        else:
            luma = (reference[base] * 3 + reference[base + 1] * 6 + reference[base + 2]) // 10
            faded = 255 - (255 - luma) // 3
            out[base : base + 3] = bytes((faded, faded, faded))
    for region in regions:
        draw_box(out, width, height, region, scale, (0xF0, 0x90, 0x00))
    return out


def pair_image(width, height, capture, reference, background):
    gap = 16
    total = width * 2 + gap
    out = bytearray(bytes(background) * (total * height))
    for y in range(height):
        row = y * width * 3
        target = y * total * 3
        out[target : target + width * 3] = capture[row : row + width * 3]
        start = target + (width + gap) * 3
        out[start : start + width * 3] = reference[row : row + width * 3]
    return total, out


# ---------------------------------------------------------------- verdict


def inside(region, known, slack):
    return (
        region["x"] >= known["x"] - slack
        and region["y"] >= known["y"] - slack
        and region["x"] + region["w"] <= known["x"] + known["w"] + slack
        and region["y"] + region["h"] <= known["y"] + known["h"] + slack
    )


def verdict(name, result, expected):
    listed = result["listed"]
    if result.get("error"):
        return "FAIL", result["error"]
    boards = expected.get("boards", {}) if expected else {}
    entry = boards.get(name)
    if not listed:
        if entry:
            return "FIXED", "matches the design; remove it from the expected-deviation file"
        return "MATCH", ""
    if expected is None:
        return "FAIL", f"{len(listed)} regions over {LIMIT_DP} dp"
    if entry is None:
        return "FAIL", f"NEW: {len(listed)} regions over {LIMIT_DP} dp and no reviewed entry"
    tolerance = expected.get("tolerance", {})
    slack = tolerance.get("regionSlackDp", DEFAULT_REGION_SLACK_DP)
    allowed = entry["percent"] + tolerance.get("percentPoints", DEFAULT_PERCENT_TOLERANCE)
    fresh = [region for region in listed if not any(inside(region, known, slack) for known in entry["regions"])]
    reasons = []
    if fresh:
        reasons.append("NEW regions " + ", ".join(region_text(region) for region in fresh[:4]))
    if result["percent"] > allowed:
        reasons.append(f"NEW: differing {result['percent']:.2f}% exceeds reviewed {entry['percent']:.2f}%")
    if reasons:
        return "FAIL", "; ".join(reasons)
    return "KNOWN", entry.get("reason", "")


def region_text(region):
    text = f"{region['kind']} x{region['x']} y{region['y']} {region['w']}x{region['h']}dp"
    if region["kind"] == "shifted":
        text += f" by ({region['dx']:+d},{region['dy']:+d})dp"
    return text


# ---------------------------------------------------------------- main


def compare_board(board, manifest, references, captures, output):
    scale = manifest["comparisonScale"]
    name = board["file"][: -len(".png")]
    reference_path = references / board["file"]
    capture_path = captures / board["file"]
    result = {"name": name, "percent": 0.0, "regions": [], "listed": []}
    if hashlib.sha256(reference_path.read_bytes()).hexdigest() != board["sha256"]:
        result["error"] = "reference differs from its manifest hash; run sync-board-references.py"
        return result
    if not capture_path.exists():
        result["error"] = "capture missing"
        return result
    ref_w, ref_h, reference, ref_alpha = read_png(reference_path)
    cap_w, cap_h, capture, cap_alpha = read_png(capture_path)
    if (cap_w, cap_h) != (ref_w, ref_h):
        result["error"] = f"capture is {cap_w / scale:g}x{cap_h / scale:g} dp, board is {ref_w / scale:g}x{ref_h / scale:g} dp"
        return result
    background = modal_color(reference, ref_alpha)
    composite(capture, cap_alpha, background)
    excluded = build_exclusions(ref_w, ref_h, ref_alpha, manifest.get("masks", []), scale)
    composite(reference, ref_alpha, background)
    marks = differing_pixels(ref_w, ref_h, reference, capture, excluded)
    compared = len(excluded) - sum(excluded)
    differing = sum(marks)
    result["percent"] = 100.0 * differing / compared if compared else 0.0
    cols, rows, grid = cell_grid(marks, ref_w, ref_h, scale)
    regions = label_regions(cols, rows, grid)
    listed = [region for region in regions if region["w"] > LIMIT_DP or region["h"] > LIMIT_DP]
    if listed:
        _c, _r, ref_ink = ink_grid(reference, ref_w, ref_h, scale, background, excluded)
        _c, _r, cap_ink = ink_grid(capture, ref_w, ref_h, scale, background, excluded)
        for region in listed:
            kind, dx, dy = classify(region, ref_ink, cap_ink, cols, rows)
            region.update(kind=kind, dx=dx, dy=dy)
    result["regions"] = regions
    result["listed"] = listed
    write_png(output / f"{name}--diff.png", ref_w, ref_h, diff_image(ref_w, ref_h, reference, marks, excluded, listed, scale))
    total, pair = pair_image(ref_w, ref_h, capture, reference, background)
    write_png(output / f"{name}--pair.png", total, ref_h, pair)
    return result


def option(args, name):
    if name in args:
        index = args.index(name)
        value = args[index + 1]
        del args[index : index + 2]
        return value
    return None


def main():
    args = sys.argv[1:]
    expected_path = option(args, "--expected")
    write_path = option(args, "--write-expected")
    if len(args) != 3:
        raise SystemExit(__doc__)
    references, captures, output = (Path(arg) for arg in args)
    output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((references / "manifest.json").read_text())
    expected = json.loads(Path(expected_path).read_text()) if expected_path else None
    lines = ["board\tstatus\tdiffering %\tlisted regions\tlargest regions (dp)\tnote"]
    markdown = [
        "| Board | Status | Differing pixels | Regions > 2 dp | Largest regions (x, y, w x h dp; offset) | Note |",
        "|---|---|---:|---:|---|---|",
    ]
    failures = 0
    results = {}
    print(f"{'board':30} {'status':7} {'diff%':>7} {'regions':>7}")
    for board in manifest["boards"]:
        name = board["file"][: -len(".png")]
        if board.get("capture", True) is False:
            lines.append(f"{name}\tNOT CAPTURED\t\t\t\t{board['reason']}")
            markdown.append(f"| {name} | not captured | | | | {board['reason']} |")
            print(f"{name:30} {'skip':7}")
            continue
        result = compare_board(board, manifest, references, captures, output)
        results[name] = result
        status, note = verdict(name, result, expected)
        if status == "FAIL":
            failures += 1
        top = "; ".join(region_text(region) for region in result["listed"][:REGION_REPORT_LIMIT])
        lines.append(f"{name}\t{status}\t{result['percent']:.2f}\t{len(result['listed'])}\t{top}\t{note}")
        markdown.append(
            f"| {name} | {status} | {result['percent']:.2f}% | {len(result['listed'])} | {top.replace('; ', '<br>')} | {note} |"
        )
        print(f"{name:30} {status:7} {result['percent']:7.2f} {len(result['listed']):7}  {note}")
    unknown = sorted(set((expected or {}).get("boards", {})) - set(results))
    for name in unknown:
        failures += 1
        lines.append(f"{name}\tFAIL\t\t\t\texpected-deviation entry names no captured board")
        print(f"{name:30} FAIL    expected-deviation entry names no captured board")
    (output / "report.tsv").write_text("\n".join(lines) + "\n")
    notes = [
        "",
        f"Comparison at {manifest['comparisonScale']} export pixels per dp on the {manifest['frame']['widthDp']}x{manifest['frame']['heightDp']} dp frame.",
        f"A pixel differs when a channel differs by more than {CHANNEL_TOLERANCE} and its value does not occur within one export pixel in the other image.",
        f"Regions group differing pixels within {GROUP_RADIUS_DP} dp; regions larger than {LIMIT_DP} dp in either direction are listed.",
        "Masked: " + ", ".join(f"{mask['name']} ({mask['why']})" for mask in manifest.get("masks", [])) + "; transparent frame corners.",
    ]
    (output / "report.md").write_text("\n".join(markdown + notes) + "\n")
    if write_path:
        previous = json.loads(Path(write_path).read_text()) if Path(write_path).exists() else {}
        reasons = {name: entry.get("reason", "") for name, entry in previous.get("boards", {}).items()}
        recorded = {
            "schema": 1,
            "note": previous.get(
                "note",
                "Reviewed deviations of the board captures from the design. CI fails on deviations outside these regions.",
            ),
            "tolerance": previous.get(
                "tolerance", {"percentPoints": DEFAULT_PERCENT_TOLERANCE, "regionSlackDp": DEFAULT_REGION_SLACK_DP}
            ),
            "boards": {},
        }
        for name, result in sorted(results.items()):
            if result.get("error") or not result["listed"]:
                continue
            recorded["boards"][name] = {
                "percent": round(result["percent"], 2),
                "reason": reasons.get(name, ""),
                "regions": [
                    {key: region[key] for key in ("kind", "x", "y", "w", "h", "dx", "dy")} for region in result["listed"]
                ],
            }
        Path(write_path).write_text(json.dumps(recorded, indent=2, ensure_ascii=False) + "\n")
        print(f"EXPECTED_WRITTEN {write_path}")
    print(f"BOARD_FAILURES {failures}")
    print(f"REPORT {output / 'report.md'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
