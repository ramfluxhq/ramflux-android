#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Copy the card host fixtures of one pinned core checkout into card-fixtures/ with a SHA-256 manifest.

Usage:
  sync-card-fixtures.py CORE --commit REV [--check] [--repo DIR]

CORE is the core checkout; its HEAD must be REV (full 40-hex commit) and its fixture paths must be clean. The copied
files are: crates/ramflux-card/host-fixtures/** and the scenario batches
crates/ramflux-card/fixtures/{approval,chart,form,complete_form,task}.{json,mount,edit,updated,tree}.json.
card-fixtures/MANIFEST.json records {core_rev, hash, files:[{path, sha256}]}.

The copies are never edited. A sync refuses when a copy on disk no longer matches the manifest it replaces. --check
(CI and the realrun build) refuses on any drift: a file that differs from the manifest, a file missing or added, a
manifest for another revision, or a core checkout whose fixtures differ from the copies.
SHA-256 (not BLAKE3) keeps the script on the standard library, so it runs under `python3 -I`.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

FIXTURE_ROOT = "crates/ramflux-card"
SCENARIOS = ("approval", "chart", "form", "complete_form", "task")
SCENARIO_SUFFIXES = ("json", "mount", "edit", "updated", "tree")
MANIFEST = "MANIFEST.json"
REV_PATTERN = re.compile(r"^[0-9a-f]{40}$")


def fail(message):
    print(f"sync-card-fixtures: {message}", file=sys.stderr)
    sys.exit(1)


def source_files(core):
    """The fixture files at the core checkout, as sorted paths relative to the copy root."""
    base = core / FIXTURE_ROOT
    found = {}
    for path in sorted((base / "host-fixtures").rglob("*")):
        if path.is_file():
            found[path.relative_to(base).as_posix()] = path
    for name in SCENARIOS:
        for suffix in SCENARIO_SUFFIXES:
            path = base / "fixtures" / (f"{name}.json" if suffix == "json" else f"{name}.{suffix}.json")
            if not path.is_file():
                fail(f"core fixture missing: {path.relative_to(core).as_posix()}")
            found[path.relative_to(base).as_posix()] = path
    if not any(key.startswith("host-fixtures/") for key in found):
        fail("core has no host-fixtures")
    return dict(sorted(found.items()))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(core, *args):
    result = subprocess.run(["git", "-C", str(core), *args], capture_output=True, text=True, check=False)
    if result.returncode != 0:
        fail(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def check_core(core, rev):
    head = git(core, "rev-parse", "HEAD").strip()
    if head != rev:
        fail(f"core HEAD is {head}, not the pinned revision {rev}")
    dirty = git(core, "status", "--porcelain", "--", FIXTURE_ROOT).strip()
    if dirty:
        fail("core fixture paths have uncommitted changes; the copy would not match the revision")


def load_manifest(repo):
    path = repo / MANIFEST
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        fail(f"{MANIFEST} is unreadable: {error}")
    if data.get("hash") != "sha256" or not isinstance(data.get("files"), list):
        fail(f"{MANIFEST} has an unknown layout")
    return data


def copies_on_disk(repo):
    """Every file under the copy root except the manifest, as sorted relative paths."""
    return sorted(p.relative_to(repo).as_posix() for p in repo.rglob("*") if p.is_file() and p.name != MANIFEST)


def verify_copies(repo, manifest):
    expected = {row["path"]: row["sha256"] for row in manifest["files"]}
    on_disk = copies_on_disk(repo)
    problems = []
    for path in sorted(set(expected) - set(on_disk)):
        problems.append(f"missing copy: {path}")
    for path in sorted(set(on_disk) - set(expected)):
        problems.append(f"unlisted copy: {path}")
    for path, sha in expected.items():
        target = repo / path
        if target.is_file() and digest(target) != sha:
            problems.append(f"copy differs from manifest: {path}")
    return problems


def sync(args):
    core = Path(args.core).resolve()
    repo = Path(args.repo).resolve()
    rev = args.commit
    if not REV_PATTERN.match(rev):
        fail("--commit must be a full 40-hex revision")
    check_core(core, rev)
    sources = source_files(core)
    if args.check:
        manifest = load_manifest(repo)
        if manifest is None:
            fail(f"{MANIFEST} is missing")
        if manifest.get("core_rev") != rev:
            fail(f"{MANIFEST} is for core {manifest.get('core_rev')}, not {rev}")
        problems = verify_copies(repo, manifest)
        listed = {row["path"] for row in manifest["files"]}
        if listed != set(sources):
            problems.append("manifest file list differs from the core fixture list")
        for path in sorted(listed & set(sources)):
            if digest(sources[path]) != next(r["sha256"] for r in manifest["files"] if r["path"] == path):
                problems.append(f"core fixture differs from the copy: {path}")
        if problems:
            for line in problems:
                print(line, file=sys.stderr)
            fail(f"{len(problems)} fixture drift problem(s) against core {rev}")
        print(f"card fixtures match core {rev}: {len(listed)} files")
        return
    old = load_manifest(repo)
    if old is not None:
        problems = verify_copies(repo, old)
        if problems:
            for line in problems:
                print(line, file=sys.stderr)
            fail("existing copies were edited or differ from their manifest; refusing to overwrite")
        for stale in copies_on_disk(repo):
            if stale not in {row["path"] for row in old["files"]}:
                fail(f"unexpected file in the copy root: {stale}")
    rows = []
    for rel, source in sources.items():
        target = repo / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        data = source.read_bytes()
        target.write_bytes(data)
        rows.append({"path": rel, "sha256": hashlib.sha256(data).hexdigest()})
    for stale in set(row["path"] for row in (old or {"files": []})["files"]) - set(sources):
        (repo / stale).unlink(missing_ok=True)
    manifest = {"core_rev": rev, "hash": "sha256", "files": rows}
    (repo / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"synced {len(rows)} card fixture files from core {rev}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("core", help="core checkout (product repository root)")
    parser.add_argument("--commit", required=True, help="pinned core revision, full 40-hex")
    parser.add_argument("--check", action="store_true", help="verify the copies instead of syncing")
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent / "card-fixtures"),
                        help="the card-fixtures directory (default: card-fixtures/ of this repository)")
    sync(parser.parse_args())


if __name__ == "__main__":
    main()
