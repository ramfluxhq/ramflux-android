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
    ("01-connect-create", "1 Connect to the node, create an account (phrase, word check, code), completion follows networking"),
    ("02-lock-unlock", "1 Lock from settings; the locked state offers the account; unlock with the code"),
    ("03-cold-start", "1 Cold start (new app process) offers the account; unlock"),
    ("04-contacts-alice", "6 Contacts: add alice, she accepts, safety number equals hers, verify"),
    ("05-conversations", "3 Conversation list shows the direct conversation with alice"),
    ("06-direct-chat", "4 Direct chat with alice: send, live receive, receipts, typing, reply, edit, erase for everyone"),
    ("07-background", "4 Background and return keeps the session, the open chat and the draft"),
    ("08-media", "7 Media M1: receive an image and a file from alice, download; send an image to alice"),
    ("09-groups", "5 Groups: create, add alice and bob, messages both ways, the phone sends a group image"),
    ("10-settings-code", "10 Settings: devices, language, change the account code, lock and unlock with it"),
    ("11-backup", "14 Backup: create a backup job and let it finish"),
    ("12-honest-states", "8, 9, 12, 15, 2 Agents, tasks, calls, privacy and link-device screens in their honest states"),
    ("13-invitations", "5 Groups: the invitation list loads"),
    ("14-sign-out", "10 Sign out on this phone and sign back in with the code"),
    ("15-remove-local", "10 Remove the account from this phone (last step)"),
    ("16-read-receipt", "4 Read receipt: the phone reads alice's message, alice sees it read"),
    ("17-invitation-leave", "5 Invitation from a non-contact accepted, then leave the group"),
    ("18-usernames", "15 Username in settings and adding a contact by @name"),
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
    # One verdict line for the whole table: any FAIL row makes it FAIL, whatever else passed.
    for flow in results:
        if flow not in known:
            status = results[flow].get("status", "NOT RUN")
            counts[status] = counts.get(status, 0) + 1
    verdict = "FAIL" if counts.get("FAIL") else ("PASS" if set(counts) == {"PASS"} else "INCOMPLETE")
    lines += ["", "Totals: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())), "", f"Overall: {verdict}", "",
              "Screenshots: screenshots/. Peer commands: peer-commands.log. Instrumentation output: instrument.log."]
    print("\n".join(lines))


if __name__ == "__main__":
    main()
