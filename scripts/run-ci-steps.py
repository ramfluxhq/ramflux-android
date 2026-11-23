#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Run the `run:` steps of a job in .github/workflows/ci.yml locally, as CI runs them.

The commands are read from the workflow, so local verification cannot drift from CI.
CI is set to "true" like on a hosted runner, which also includes the screenshot tests
in the plain unit-test tasks.

  python3 scripts/run-ci-steps.py kotlin --list
  python3 scripts/run-ci-steps.py kotlin --clean          # every run step, from a clean state
  python3 scripts/run-ci-steps.py kotlin --clean --step 11 # one step, e.g. the unit-test line
  python3 scripts/run-ci-steps.py native-packaging --step 9

--clean deletes every build directory of the checkout first (a fresh CI checkout has
none), so tests cannot depend on files left by an earlier local run. Steps that need
the hosted runner (swap, the SDK checkout) are only run when selected with --step;
their step `env:` and the job's `working-directory` are not applied.
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"


def indent(line):
    return len(line) - len(line.lstrip(" "))


def job_steps(job):
    """Return (name, command) for every step with `run:` in the job, in workflow order."""
    lines = WORKFLOW.read_text().splitlines()
    try:
        start = lines.index(f"  {job}:")
    except ValueError:
        sys.exit(f"no job {job!r} in {WORKFLOW}")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].strip() and indent(lines[i]) <= 2), len(lines))
    steps, current, i = [], None, start + 1
    while i < end:
        line = lines[i]
        stripped = line.strip()
        if stripped.startswith("- ") and indent(line) == 6:
            current = {}
            steps.append(current)
            stripped = stripped[2:]
        if current is not None and indent(line) in (6, 8) and ":" in stripped and not stripped.startswith("#"):
            key, _, value = stripped.partition(":")
            value = value.strip()
            if key == "run" and value in ("|", "|-"):
                block = []
                while i + 1 < end and (not lines[i + 1].strip() or indent(lines[i + 1]) > indent(line)):
                    i += 1
                    block.append(lines[i].strip())
                value = "\n".join(b for b in block if b)
            if key in ("name", "run"):
                current[key] = value
        i += 1
    return [(s.get("name", s["run"].splitlines()[0]), s["run"]) for s in steps if "run" in s]


def clean():
    removed = 0
    for directory, children, _ in os.walk(ROOT):
        if "build" in children:
            shutil.rmtree(Path(directory) / "build")
            removed += 1
        children[:] = [c for c in children if c not in ("build", ".git", ".gradle")]
    print(f"removed {removed} build directories", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("job")
    parser.add_argument("--list", action="store_true", help="print the numbered run steps and exit")
    parser.add_argument("--step", type=int, action="append", help="run only this step number (repeatable)")
    parser.add_argument("--clean", action="store_true", help="delete every build directory first")
    args = parser.parse_args()
    steps = job_steps(args.job)
    if args.list:
        for number, (name, command) in enumerate(steps, 1):
            print(f"{number:2} {name}" + ("" if name == command else f"\n   {command}"))
        return 0
    if args.job != "kotlin" and not args.step:
        sys.exit(f"{args.job} has steps that need the hosted runner; select them with --step (see --list)")
    selected = args.step or range(1, len(steps) + 1)
    if any(n < 1 or n > len(steps) for n in selected):
        sys.exit(f"step numbers run from 1 to {len(steps)}")
    if args.clean:
        clean()
    env = dict(os.environ, CI="true")
    for number in selected:
        name, command = steps[number - 1]
        print(f"==> step {number}: {name}", flush=True)
        status = subprocess.run(["bash", "-eo", "pipefail", "-c", command], cwd=ROOT, env=env).returncode
        print(f"<== step {number}: exit {status}", flush=True)
        if status:
            return status
    return 0


if __name__ == "__main__":
    sys.exit(main())
