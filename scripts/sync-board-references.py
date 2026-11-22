#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Copy design board exports into the board reference set and keep its manifest honest.

Usage:
  sync-board-references.py import <references-dir> <design-boards-dir> [--design-sha <sha256>]
  sync-board-references.py check <references-dir> [<design-boards-dir>] [--design-sha <sha256>]

The manifest (<references-dir>/manifest.json) is the single list of board
references. Each entry names the board key, theme, locale, the Pen node id of
the export and its path under the design boards directory. "import" copies
every export to its reference file name and records the sha256 of the copied
bytes. "check" fails when a reference file is missing, unlisted or differs from
its recorded hash, and, when the design boards directory is given, when an
export no longer equals the committed reference (a stale reference). A
--design-sha that differs from the manifest's design file hash also fails:
references exported from another design revision must be re-imported.
"""

import hashlib
import json
import shutil
import struct
import sys
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def png_size(path):
    data = Path(path).read_bytes()[:24]
    if data[:8] != PNG_SIGNATURE:
        raise SystemExit(f"not a png: {path}")
    return struct.unpack(">II", data[16:24])


def load(references):
    manifest_path = references / "manifest.json"
    return manifest_path, json.loads(manifest_path.read_text())


def expected_size(manifest):
    scale = manifest["comparisonScale"]
    frame = manifest["frame"]
    return frame["widthDp"] * scale, frame["heightDp"] * scale


def option(args, name):
    if name in args:
        index = args.index(name)
        if index + 1 >= len(args):
            raise SystemExit(f"{name} needs a value")
        value = args[index + 1]
        del args[index : index + 2]
        return value
    return None


def check_design_sha(manifest, design_sha, problems):
    if design_sha is not None and design_sha != manifest["design"]["sha256"]:
        problems.append(
            f"design file sha256 {design_sha} differs from manifest {manifest['design']['sha256']}: re-export and import"
        )


def import_references(references, design_dir, design_sha):
    manifest_path, manifest = load(references)
    if design_sha is not None:
        manifest["design"]["sha256"] = design_sha
    size = expected_size(manifest)
    for board in manifest["boards"]:
        source = design_dir / board["source"]
        if not source.exists():
            raise SystemExit(f"missing export {source} for {board['file']}")
        if png_size(source) != size:
            raise SystemExit(f"{source} is {png_size(source)}, expected {size}")
        shutil.copyfile(source, references / board["file"])
        board["sha256"] = sha256(references / board["file"])
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    print(f"BOARD_REFERENCES_IMPORTED: {len(manifest['boards'])} boards from {design_dir}")


def check_references(references, design_dir, design_sha):
    _manifest_path, manifest = load(references)
    problems = []
    check_design_sha(manifest, design_sha, problems)
    size = expected_size(manifest)
    listed = set()
    keys = set()
    for board in manifest["boards"]:
        file = references / board["file"]
        listed.add(board["file"])
        identity = (board["key"], board["theme"], board["locale"])
        if identity in keys:
            problems.append(f"duplicate reference {identity}")
        keys.add(identity)
        expected_name = f"{board['key']}.{board['theme']}--{board['locale']}.png"
        if board["file"] != expected_name:
            problems.append(f"{board['file']} must be named {expected_name}")
        if not file.exists():
            problems.append(f"missing reference {board['file']}")
            continue
        if png_size(file) != size:
            problems.append(f"{board['file']} is {png_size(file)}, expected {size}")
        if sha256(file) != board.get("sha256"):
            problems.append(f"{board['file']} differs from its manifest hash")
        if not board.get("capture", True) and not board.get("reason"):
            problems.append(f"{board['file']} is not captured and gives no reason")
        if design_dir is not None:
            source = design_dir / board["source"]
            if not source.exists():
                problems.append(f"export {board['source']} for {board['file']} is gone from the design boards")
            elif sha256(source) != board.get("sha256"):
                problems.append(f"{board['file']} is stale: export {board['source']} changed")
    for file in sorted(references.glob("*.png")):
        if file.name not in listed:
            problems.append(f"unlisted reference {file.name}")
    for problem in problems:
        print(f"STALE {problem}")
    if problems:
        print(f"BOARD_REFERENCES_FAILED: {len(problems)} problems")
        return 1
    print(f"BOARD_REFERENCES_OK: {len(manifest['boards'])} boards; design {manifest['design']['sha256'][:12]}")
    return 0


def main():
    args = sys.argv[1:]
    design_sha = option(args, "--design-sha")
    if len(args) >= 2 and args[0] == "import" and len(args) == 3:
        import_references(Path(args[1]), Path(args[2]), design_sha)
        return 0
    if len(args) in (2, 3) and args[0] == "check":
        design_dir = Path(args[2]) if len(args) == 3 else None
        return check_references(Path(args[1]), design_dir, design_sha)
    raise SystemExit(__doc__)


if __name__ == "__main__":
    sys.exit(main())
