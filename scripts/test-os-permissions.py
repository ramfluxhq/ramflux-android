#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Prove the manifest guard admits only allowlisted, typed, unexported services and their permissions."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('guard', Path(__file__).with_name('check-os-permissions.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

BASE = ['android.permission.INTERNET', 'android.permission.POST_NOTIFICATIONS',
        'android.permission.CAMERA', 'android.permission.RECORD_AUDIO', 'android.permission.USE_FULL_SCREEN_INTENT']
CALL = 'org.ramflux.feature.calls.CallService'
CALL_PERMISSIONS = ['android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_PHONE_CALL']


def manifest(permissions, services=''):
    uses = ''.join(f'<uses-permission android:name="{p}"/>' for p in permissions)
    return ('<manifest xmlns:android="http://schemas.android.com/apk/res/android">' + uses +
            '<application>' + services + '</application></manifest>')


def service(name=CALL, kind='phoneCall', exported='false'):
    return f'<service android:name="{name}" android:foregroundServiceType="{kind}" android:exported="{exported}"/>'


class OsPermissionGuardTest(unittest.TestCase):
    def run_guard(self, app, feature=None, entries=()):
        with tempfile.TemporaryDirectory(prefix='rf-os-permissions-') as scratch:
            root = Path(scratch)
            path = root / 'app/src/main/AndroidManifest.xml'
            path.parent.mkdir(parents=True)
            path.write_text(app)
            if feature is not None:
                other = root / 'feature/calls/impl/src/main/AndroidManifest.xml'
                other.parent.mkdir(parents=True)
                other.write_text(feature)
            allowlist = root / 'allowlist.json'
            allowlist.write_text(json.dumps({'services': list(entries)}))
            return guard.validate(root, allowlist)

    def entry(self, name=CALL, kind='phoneCall', permissions=CALL_PERMISSIONS):
        return {'name': name, 'foregroundServiceType': kind, 'permissions': permissions}

    def test_base_permissions_pass_and_extra_permission_fails(self):
        self.run_guard(manifest(BASE))
        with self.assertRaisesRegex(ValueError, 'Unapproved permission'):
            self.run_guard(manifest(BASE + ['android.permission.READ_EXTERNAL_STORAGE']))
        with self.assertRaisesRegex(ValueError, 'Missing expected'):
            self.run_guard(manifest(BASE[1:]))

    def test_service_outside_the_allowlist_fails(self):
        with self.assertRaisesRegex(ValueError, 'not in the service allowlist'):
            self.run_guard(manifest(BASE), manifest([], service()))

    def test_allowlisted_service_passes_with_its_permissions_only(self):
        found, declared = self.run_guard(manifest(BASE), manifest(CALL_PERMISSIONS, service()), [self.entry()])
        self.assertEqual({CALL}, declared)
        self.assertTrue(set(CALL_PERMISSIONS) <= found)
        with self.assertRaisesRegex(ValueError, 'need their permissions'):
            self.run_guard(manifest(BASE), manifest([], service()), [self.entry()])

    def test_service_permissions_without_the_service_fail(self):
        with self.assertRaisesRegex(ValueError, 'Unapproved permission'):
            self.run_guard(manifest(BASE + CALL_PERMISSIONS), entries=[self.entry()])

    def test_wrong_type_or_exported_service_fails(self):
        with self.assertRaisesRegex(ValueError, 'declares type'):
            self.run_guard(manifest(BASE), manifest(CALL_PERMISSIONS, service(kind='dataSync')), [self.entry()])
        with self.assertRaisesRegex(ValueError, 'exported'):
            self.run_guard(manifest(BASE), manifest(CALL_PERMISSIONS, service(exported='true')), [self.entry()])

    def test_allowlist_entries_are_exact(self):
        with self.assertRaisesRegex(ValueError, 'fully qualified'):
            self.run_guard(manifest(BASE), entries=[self.entry(name='.CallService')])
        with self.assertRaisesRegex(ValueError, 'Unknown foreground service type'):
            self.run_guard(manifest(BASE), entries=[self.entry(kind='everything')])

    def test_removal_markers_are_not_declarations(self):
        tools = 'xmlns:tools="http://schemas.android.com/tools"'
        removal = ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" ' + tools + '>'
                   '<uses-permission android:name="android.permission.WAKE_LOCK" tools:node="remove"/>'
                   '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove"/>'
                   '</manifest>')
        found, _ = self.run_guard(manifest(BASE), removal)
        self.assertNotIn('android.permission.WAKE_LOCK', found)
        self.assertNotIn('android.permission.ACCESS_NETWORK_STATE', found)
        # Any other tools:node value still declares the permission.
        merge = removal.replace('tools:node="remove"', 'tools:node="merge"')
        with self.assertRaisesRegex(ValueError, 'Unapproved permission'):
            self.run_guard(manifest(BASE), merge)
        # A removal marker never stands in for a permission the app must declare.
        missing = manifest(BASE[1:]).replace('<application>', '<uses-permission android:name="android.permission.INTERNET" '
                                             'xmlns:tools="http://schemas.android.com/tools" tools:node="remove"/><application>')
        with self.assertRaisesRegex(ValueError, 'Missing expected'):
            self.run_guard(missing)

    def test_repository_allowlist_admits_only_the_media_transfer_service(self):
        services = guard.load_allowlist(Path(__file__).with_name('os-service-allowlist.json'))
        self.assertEqual({'org.ramflux.feature.chat.MediaTransferService'}, set(services))
        kinds, permissions = services['org.ramflux.feature.chat.MediaTransferService']
        self.assertEqual({'dataSync'}, kinds)
        self.assertEqual({'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_DATA_SYNC'}, permissions)

    def test_repository_allowlist_loads(self):
        guard.load_allowlist(Path(__file__).with_name('os-service-allowlist.json'))


if __name__ == '__main__':
    unittest.main()
