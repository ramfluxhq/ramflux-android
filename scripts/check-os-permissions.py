#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Guard the production manifest permission boundary, including transitive feature manifests."""
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parent.parent
key = '{http://schemas.android.com/apk/res/android}name'
allowed = {'android.permission.INTERNET', 'android.permission.POST_NOTIFICATIONS',
           'android.permission.CAMERA', 'android.permission.RECORD_AUDIO'}
found = set()
for base in ('app', 'core', 'feature'):
    for path in (root / base).glob('**/src/main/AndroidManifest.xml'):
        manifest = ET.parse(path).getroot()
        for element in manifest:
            if element.tag.startswith('uses-permission'):
                value = element.get(key)
                assert value in allowed, f'Unapproved permission {value}: {path.relative_to(root)}'
                found.add(value)
        assert not manifest.findall('.//service'), f'No media foreground service in v1: {path}'
assert found == allowed, f'Missing expected production permission declarations: {allowed - found}'
print('OS_PERMISSIONS_OK: only network, notifications, camera and dormant microphone; no broad storage or media service')

# Validate dependency-contributed declarations after Gradle has produced the app manifest.
for path in (root / 'app/build/intermediates').glob('merged_manifest*/debug/**/AndroidManifest.xml'):
    manifest = ET.parse(path).getroot()
    private = manifest.get('package', '') + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
    declared = {n.get(key): n for n in manifest.findall('permission')}
    actual = {n.get(key) for n in manifest if n.tag.startswith('uses-permission')}
    assert actual <= allowed | {private}, f'Unexpected merged permission: {actual - allowed - {private}}'
    assert allowed <= actual, f'Missing merged permission: {allowed - actual}'
    if private in actual:
        assert private in declared and declared[private].get('{http://schemas.android.com/apk/res/android}protectionLevel') == 'signature'
    print('MERGED_OS_PERMISSIONS_OK: approved OS permissions and AndroidX private signature receiver')
