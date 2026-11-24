#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Make the flow table and the instrumentation agree for one invocation of scripts/realrun.sh.

Usage: reconcile.py DIR INSTRUMENT_FROM_LINE

Reads this invocation's part of DIR/instrument.log (the lines after INSTRUMENT_FROM_LINE) and DIR/results.json,
which the control server has stopped writing. A flow test method `fNN_name` records its result under the flow id
`NN-...`. For every flow test of this invocation:
- a flow still RUNNING (the app process died during it) becomes FAIL;
- a test the instrumentation reports as failed whose flow did not record FAIL becomes FAIL;
- a test that passed although its flow recorded FAIL is reported as a disagreement (exit 3).
Prints one line per change and the disagreements; exits 0 when both agree after the changes.
"""
import json
import re
import sys
import time
from pathlib import Path

CODES = {"0": "passed", "-1": "error", "-2": "failed", "-3": "ignored", "-4": "skipped"}


def main():
    out, start = Path(sys.argv[1]), int(sys.argv[2])
    lines = (out / "instrument.log").read_text(errors="replace").splitlines()[start:]
    tests, current, stacks = {}, {}, {}
    for line in lines:
        if line.startswith("INSTRUMENTATION_STATUS: test="):
            current["test"] = line.split("=", 1)[1]
        elif line.startswith("INSTRUMENTATION_STATUS: stack="):
            stacks[current.get("test")] = line.split("=", 1)[1]
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code = line.split(":", 1)[1].strip()
            if current.get("test"):
                # Code 1 marks the start; the last code of a test is its result.
                tests[current["test"]] = CODES.get(code, "started") if code != "1" else tests.get(current["test"], "started")
    crashed = next((l for l in lines if "Process crashed" in l or "INSTRUMENTATION_FAILED" in l), "")
    path = out / "results.json"
    results = json.loads(path.read_text()) if path.exists() else {}
    changes, disagreements = [], []
    started = set(tests)
    for test, outcome in sorted(tests.items()):
        number = re.match(r"f(\d+)_", test)
        if not number:
            continue
        flow = next((k for k in results if k.startswith(number.group(1) + "-")), None)
        entry = results.get(flow) if flow else None
        status = entry["status"] if entry else None
        reason = None
        if status == "RUNNING":
            reason = "the instrumentation ended during this flow" + (f" ({crashed.strip()})" if crashed else "")
        elif outcome in ("failed", "error", "started") and status != "FAIL":
            stack = stacks.get(test, "").strip()
            reason = f"the instrumentation reports {test} as {outcome}" + (f": {stack[:300]}" if stack else "") + \
                (f"; flow had recorded {status}: {entry['detail'][:600]}" if entry else "; the flow recorded no result")
        elif outcome == "passed" and status == "FAIL":
            disagreements.append(f"{test} passed but flow {flow} recorded FAIL")
        if reason:
            key = flow or f"{number.group(1)}-{test}"
            results[key] = {"status": "FAIL", "detail": reason[:2000], "at": time.strftime("%H:%M:%S")}
            changes.append(f"{key}: FAIL ({reason[:160]})")
    if crashed and not started:
        disagreements.append(f"the instrumentation failed before any flow started: {crashed.strip()}")
    if changes:
        path.write_text(json.dumps(results, indent=2) + "\n")
    for line in changes:
        print("realrun: reconciled " + line)
    for line in disagreements:
        print("realrun: disagreement: " + line)
    sys.exit(3 if disagreements else 0)


if __name__ == "__main__":
    main()
