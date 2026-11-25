#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Guard the production manifest permission boundary, including transitive feature manifests.

Services are forbidden except the exact classes in os-service-allowlist.json, each with its declared
foreground service type, not exported, and only then with the permissions that entry brings. The type value
`none` is for a service that is not a foreground service and must declare no foregroundServiceType.
A uses-permission element marked tools:node="remove" takes a library permission out of the merged
manifest; it declares nothing and is not counted.
"""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'
KEY = ANDROID + 'name'
ALLOWED = {'android.permission.INTERNET', 'android.permission.POST_NOTIFICATIONS',
           'android.permission.CAMERA', 'android.permission.RECORD_AUDIO',
           'android.permission.USE_FULL_SCREEN_INTENT'}
FOREGROUND_TYPES = {'phoneCall', 'dataSync', 'mediaPlayback', 'microphone', 'camera', 'shortService',
                    'mediaProcessing', 'remoteMessaging', 'none'}


def load_allowlist(path):
    data = json.loads(Path(path).read_text())
    services = {}
    for entry in data['services']:
        name, kind, permissions = entry['name'], entry['foregroundServiceType'], entry['permissions']
        if not name or '*' in name or name.startswith('.'):
            raise ValueError(f'Allowlisted service needs a fully qualified class: {name!r}')
        kinds = set(kind.split('|'))
        if 'none' in kinds and kinds != {'none'}:
            raise ValueError(f'Foreground service type none cannot be combined with another type: {name}')
        if not kinds <= FOREGROUND_TYPES:
            raise ValueError(f'Unknown foreground service type {kind!r} for {name}')
        if name in services:
            raise ValueError(f'Duplicate allowlisted service {name}')
        services[name] = (set() if kinds == {'none'} else kinds, set(permissions))
    return services


def check_manifest(path, services):
    """Return (permissions, service names) of one source manifest; raise ValueError on a violation."""
    manifest = ET.parse(path).getroot()
    permissions = set()
    for element in manifest:
        if element.tag.startswith('uses-permission') and element.get(TOOLS + 'node') != 'remove':
            permissions.add(element.get(KEY))
    declared = set()
    for service in manifest.findall('.//service'):
        name = service.get(KEY)
        if name not in services:
            raise ValueError(f'Service {name} is not in the service allowlist: {path}')
        kinds, _ = services[name]
        actual = set((service.get(ANDROID + 'foregroundServiceType') or '').split('|')) - {''}
        if actual != kinds:
            raise ValueError(f'Service {name} declares type {sorted(actual)}, allowlist says {sorted(kinds)}: {path}')
        if service.get(ANDROID + 'exported') != 'false':
            raise ValueError(f'Service {name} must declare android:exported="false": {path}')
        declared.add(name)
    return permissions, declared


def validate(root, allowlist):
    services = load_allowlist(allowlist)
    found, declared = set(), set()
    for base in ('app', 'core', 'feature', 'push'):
        for path in sorted((Path(root) / base).glob('**/src/main/AndroidManifest.xml')):
            permissions, names = check_manifest(path, services)
            found |= permissions
            declared |= names
    brought = set().union(*(services[name][1] for name in declared)) if declared else set()
    unapproved = found - ALLOWED - brought
    if unapproved:
        raise ValueError(f'Unapproved permission {sorted(unapproved)}')
    if not ALLOWED <= found:
        raise ValueError(f'Missing expected production permission declarations: {sorted(ALLOWED - found)}')
    missing = brought - found
    if missing:
        raise ValueError(f'Declared services need their permissions: {sorted(missing)}')
    return found, declared


def validate_merged(root, services):
    for path in (Path(root) / 'app/build/intermediates').glob('merged_manifest*/debug/**/AndroidManifest.xml'):
        manifest = ET.parse(path).getroot()
        private = manifest.get('package', '') + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
        declared = {n.get(KEY): n for n in manifest.findall('permission')}
        actual = {n.get(KEY) for n in manifest if n.tag.startswith('uses-permission')}
        names = {s.get(KEY) for s in manifest.findall('.//service')}
        own = {name for name in names if name in services}
        brought = set().union(*(services[name][1] for name in own)) if own else set()
        if not actual <= ALLOWED | brought | {private}:
            raise ValueError(f'Unexpected merged permission: {sorted(actual - ALLOWED - brought - {private})}')
        if not ALLOWED <= actual:
            raise ValueError(f'Missing merged permission: {sorted(ALLOWED - actual)}')
        if private in actual and not (private in declared and declared[private].get(ANDROID + 'protectionLevel') == 'signature'):
            raise ValueError('AndroidX private receiver permission must be signature-level')
        print('MERGED_OS_PERMISSIONS_OK: approved OS permissions and AndroidX private signature receiver')


if __name__ == '__main__':
    root = Path(__file__).resolve().parent.parent
    allowlist = Path(__file__).with_name('os-service-allowlist.json')
    _, declared = validate(root, allowlist)
    print('OS_PERMISSIONS_OK: only network, notifications, full-screen call notifications, camera and dormant microphone; '
          f'no broad storage; services only from the allowlist ({len(declared)} declared)')
    validate_merged(root, load_allowlist(allowlist))
