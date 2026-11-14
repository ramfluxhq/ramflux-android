# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Portable dynamic linkage rules for every packaged Android ELF."""
from pathlib import PurePosixPath
import re

SYSTEM_LIBRARIES = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so'}


def validate_dynamic(dynamic, entry, packaged_names):
    path = PurePosixPath(entry)
    for needed in re.findall(r'\(NEEDED\).*Shared library: \[(.*?)\]', dynamic):
        if '/' in needed or '\\' in needed:
            raise ValueError(f'Nonportable ELF dependency: {entry}: {needed}')
        if needed not in SYSTEM_LIBRARIES and str(path.parent / needed) not in packaged_names:
            raise ValueError(f'Missing packaged ELF dependency: {entry}: {needed}')
    sonames = re.findall(r'\(SONAME\).*Library soname: \[(.*?)\]', dynamic)
    if path.name == 'libramflux_sdk.so' and sonames != [path.name]:
        raise ValueError(f'Missing or incorrect SDK SONAME: {entry}')
    if sonames and sonames != [path.name]:
        raise ValueError(f'Nonportable ELF SONAME: {entry}: {sonames}')
    for kind, value in re.findall(r'\((RPATH|RUNPATH)\).*Library (?:rpath|runpath): \[(.*?)\]', dynamic):
        if any(part not in {'$ORIGIN', '${ORIGIN}'} for part in value.split(':')):
            raise ValueError(f'Nonportable ELF {kind}: {entry}: {value}')
