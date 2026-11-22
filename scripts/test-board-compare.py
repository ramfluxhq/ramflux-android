#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Prove the board comparison passes equal frames and fails real deviations."""
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("board", HERE / "compare-board-captures.py")
board = importlib.util.module_from_spec(spec)
spec.loader.exec_module(board)

WIDTH_DP = 40
HEIGHT_DP = 60
SCALE = 2
BACKGROUND = (0xFC, 0xFB, 0xF9)
INK = (0x22, 0x22, 0x22)


def canvas():
    return bytearray(bytes(BACKGROUND) * (WIDTH_DP * SCALE * HEIGHT_DP * SCALE))


def fill(rgb, x, y, w, h, color):
    """Fill a rectangle given in export pixels."""
    width = WIDTH_DP * SCALE
    for row in range(y, y + h):
        for column in range(x, x + w):
            rgb[(row * width + column) * 3 : (row * width + column) * 3 + 3] = bytes(color)


def save(path, rgb):
    board.write_png(path, WIDTH_DP * SCALE, HEIGHT_DP * SCALE, rgb)


class BoardCompareTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="rf-board-compare-")
        root = Path(self.scratch.name)
        self.references = root / "references"
        self.captures = root / "captures"
        self.output = root / "output"
        self.references.mkdir()
        self.captures.mkdir()
        self.reference = canvas()
        fill(self.reference, 10, 20, 40, 10, INK)
        save(self.references / "MOB-T01.light--en.png", self.reference)
        digest = hashlib.sha256((self.references / "MOB-T01.light--en.png").read_bytes()).hexdigest()
        manifest = {
            "schema": 1,
            "design": {"sha256": "a" * 64},
            "frame": {"widthDp": WIDTH_DP, "heightDp": HEIGHT_DP},
            "comparisonScale": SCALE,
            "masks": [{"name": "status-bar", "x": 0, "y": 0, "w": WIDTH_DP, "h": 4, "why": "system"}],
            "boards": [
                {
                    "key": "MOB-T01",
                    "theme": "light",
                    "locale": "en",
                    "node": "n1",
                    "source": "shell/n1.png",
                    "file": "MOB-T01.light--en.png",
                    "sha256": digest,
                }
            ],
        }
        (self.references / "manifest.json").write_text(json.dumps(manifest))

    def tearDown(self):
        self.scratch.cleanup()

    def run_compare(self, *extra):
        command = [sys.executable, str(HERE / "compare-board-captures.py"), str(self.references), str(self.captures), str(self.output), *extra]
        return subprocess.run(command, capture_output=True, text=True)

    def test_equal_capture_matches(self):
        save(self.captures / "MOB-T01.light--en.png", self.reference)
        result = self.run_compare()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("MATCH", result.stdout)
        self.assertTrue((self.output / "MOB-T01.light--en--diff.png").exists())
        self.assertTrue((self.output / "MOB-T01.light--en--pair.png").exists())

    def test_anti_aliasing_shift_of_one_pixel_matches(self):
        capture = canvas()
        fill(capture, 11, 20, 40, 10, INK)
        save(self.captures / "MOB-T01.light--en.png", capture)
        self.assertEqual(0, self.run_compare().returncode)

    def test_masked_status_bar_is_ignored(self):
        capture = bytearray(self.reference)
        fill(capture, 0, 0, 80, 8, INK)
        save(self.captures / "MOB-T01.light--en.png", capture)
        self.assertEqual(0, self.run_compare().returncode)

    def test_shift_over_two_dp_fails_with_region_and_offset(self):
        capture = canvas()
        fill(capture, 10, 30, 40, 10, INK)
        save(self.captures / "MOB-T01.light--en.png", capture)
        result = self.run_compare()
        self.assertEqual(1, result.returncode, result.stdout)
        report = (self.output / "report.tsv").read_text()
        self.assertIn("shifted", report)
        self.assertIn("by (+0,+5)dp", report)

    def test_missing_content_fails(self):
        save(self.captures / "MOB-T01.light--en.png", canvas())
        result = self.run_compare()
        self.assertEqual(1, result.returncode)
        self.assertIn("missing x5 y10 20x5dp", (self.output / "report.tsv").read_text())

    def test_wrong_size_and_missing_capture_fail(self):
        self.assertEqual(1, self.run_compare().returncode)
        self.assertIn("capture missing", (self.output / "report.tsv").read_text())
        board.write_png(self.captures / "MOB-T01.light--en.png", WIDTH_DP, HEIGHT_DP, bytearray(bytes(BACKGROUND) * (WIDTH_DP * HEIGHT_DP)))
        self.assertEqual(1, self.run_compare().returncode)
        self.assertIn("capture is 20x30 dp, board is 40x60 dp", (self.output / "report.tsv").read_text())

    def test_reviewed_deviation_passes_and_new_one_fails(self):
        capture = bytearray(self.reference)
        fill(capture, 10, 60, 20, 20, INK)
        save(self.captures / "MOB-T01.light--en.png", capture)
        expected = self.output.parent / "expected.json"
        self.assertEqual(1, self.run_compare().returncode)
        self.run_compare("--write-expected", str(expected))
        recorded = json.loads(expected.read_text())
        self.assertEqual([{"kind": "extra", "x": 5, "y": 30, "w": 10, "h": 10, "dx": 0, "dy": 0}], recorded["boards"]["MOB-T01.light--en"]["regions"])
        result = self.run_compare("--expected", str(expected))
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("KNOWN", result.stdout)
        fill(capture, 50, 100, 20, 10, INK)
        save(self.captures / "MOB-T01.light--en.png", capture)
        result = self.run_compare("--expected", str(expected))
        self.assertEqual(1, result.returncode)
        self.assertIn("NEW regions", result.stdout)

    def test_fixed_board_is_reported(self):
        save(self.captures / "MOB-T01.light--en.png", self.reference)
        expected = self.output.parent / "expected.json"
        expected.write_text(json.dumps({"boards": {"MOB-T01.light--en": {"percent": 1.0, "regions": []}}}))
        result = self.run_compare("--expected", str(expected))
        self.assertEqual(0, result.returncode)
        self.assertIn("FIXED", result.stdout)

    def test_altered_reference_and_stale_export_are_rejected(self):
        sync = [sys.executable, str(HERE / "sync-board-references.py"), "check", str(self.references)]
        self.assertEqual(0, subprocess.run(sync, capture_output=True).returncode)
        design = self.output.parent / "design"
        (design / "shell").mkdir(parents=True)
        changed = bytearray(self.reference)
        fill(changed, 0, 100, 4, 4, INK)
        save(design / "shell/n1.png", changed)
        stale = subprocess.run(sync + [str(design)], capture_output=True, text=True)
        self.assertEqual(1, stale.returncode)
        self.assertIn("is stale", stale.stdout)
        other_sha = subprocess.run(sync + ["--design-sha", "b" * 64], capture_output=True, text=True)
        self.assertEqual(1, other_sha.returncode)
        save(self.references / "MOB-T01.light--en.png", changed)
        altered = subprocess.run(sync, capture_output=True, text=True)
        self.assertIn("differs from its manifest hash", altered.stdout)
        save(self.captures / "MOB-T01.light--en.png", changed)
        self.assertEqual(1, self.run_compare().returncode)


if __name__ == "__main__":
    unittest.main()
