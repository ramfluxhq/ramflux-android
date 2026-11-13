#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
import re
import sys
loads = []
for line in sys.stdin:
    if line.lstrip().startswith('LOAD '):
        match = re.search(r'align\s+2\*\*(\d+)', line)
        if not match:
            raise SystemExit('Unrecognized LOAD alignment')
        loads.append(int(match.group(1)))
if not loads or min(loads) < 14:
    raise SystemExit('ELF LOAD alignment below 16 KB or no LOAD segments')
print('PASS: every ELF LOAD segment is aligned to at least 16 KB')
