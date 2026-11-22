# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Map between Android resource folders and BCP 47 tags without a list of languages.

The unqualified `values` folder holds the default locale (`unqualifiedResLocale` in
app/src/main/res/resources.properties). Every other locale is whatever `values-*`
folders exist, so adding a language is a resources-only change.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_LEGACY = re.compile(r"^values-([a-z]{2,3})(?:-r([A-Z]{2}|[0-9]{3}))?$")
_BCP47 = re.compile(r"^values-b\+([a-zA-Z0-9]+(?:\+[a-zA-Z0-9]+)*)$")


def default_locale(root=ROOT):
    properties = root / "app/src/main/res/resources.properties"
    if properties.exists():
        for line in properties.read_text().splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "unqualifiedResLocale" and value.strip():
                return value.strip()
    return "en"


def folder_locale(name):
    """BCP 47 tag of a resource folder, or None for the default and non-locale folders."""
    match = _LEGACY.match(name)
    if match:
        return match.group(1) + ("-" + match.group(2) if match.group(2) else "")
    match = _BCP47.match(name)
    if match:
        return match.group(1).replace("+", "-")
    return None


def locale_folder(tag, default):
    """Resource folder for a BCP 47 tag; the default locale uses the unqualified folder."""
    if tag == default:
        return "values"
    parts = tag.split("-")
    if len(parts) == 1:
        return "values-" + parts[0]
    if len(parts) == 2 and re.fullmatch(r"[A-Z]{2}|[0-9]{3}", parts[1]):
        return f"values-{parts[0]}-r{parts[1]}"
    return "values-b+" + "+".join(parts)


def resource_locales(res):
    """Locale tags of the qualified string folders under one res directory."""
    if not res.exists():
        return set()
    return {tag for tag in (folder_locale(p.name) for p in res.iterdir() if p.is_dir()) if tag}
