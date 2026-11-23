#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Write the Android real-run flow table (Markdown) from DIR/results.json; flows without a result are NOT RUN.

Usage: flow_table.py DIR SECONDS
"""
import json
import sys
from pathlib import Path

# Run order of the brief; ids match RealRunFlowTest.
FLOWS = [
    ("01-connect-create", "1 Connect to the node and create an account (phrase, word check, code)"),
    ("02-lock-unlock", "2 Lock by backgrounding and unlock the known account by name with the code"),
    ("03-contacts-alice", "3 Contacts with alice (add, peer accepts, safety number)"),
    ("04-direct-chat", "4 Direct chat with alice both ways: send, receive, reply, edit, erase"),
    ("05-media-m1", "5 Media M1: receive and send an image and a file"),
    ("06-groups", "6 Groups with alice and bob"),
    ("07-settings-code-backup", "7 Settings, change code, backup"),
    ("08-agents-tasks-calls-privacy", "8 Agents, tasks, calls and privacy honest states"),
]


def main():
    out, seconds = Path(sys.argv[1]), int(sys.argv[2])
    path = out / "results.json"
    results = json.loads(path.read_text()) if path.exists() else {}
    lines = ["# Android real-run flow table", "", f"Duration: {seconds // 60} min {seconds % 60} s", "",
             "| Flow | Status | Detail |", "| --- | --- | --- |"]
    known = {flow for flow, _ in FLOWS}
    for flow, title in FLOWS + [(f, f) for f in results if f not in known]:
        entry = results.get(flow, {"status": "NOT RUN", "detail": "no result recorded (suite stopped earlier)"})
        detail = entry["detail"].replace("|", "/").replace("\n", " ")
        lines.append(f"| {title} | {entry['status']} | {detail} |")
    counts = {}
    for flow, _ in FLOWS:
        status = results.get(flow, {}).get("status", "NOT RUN")
        counts[status] = counts.get(status, 0) + 1
    lines += ["", "Totals: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())), "",
              "Screenshots: screenshots/. Peer commands: peer-commands.log. Instrumentation output: instrument.log."]
    print("\n".join(lines))


if __name__ == "__main__":
    main()
